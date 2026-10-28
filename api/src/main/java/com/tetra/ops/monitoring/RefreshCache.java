package com.tetra.ops.monitoring;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tetra.ops.monitoring.MonitoringSource.StaleReason;
import com.tetra.ops.monitoring.PrometheusClient.PrometheusException;

/**
 * 조회 결과 하나를 캐시한다(홈 모니터링 · OPS-05 공통, 2026-10-26 홈 MonitoringService에서 뽑음).
 * - TTL 안에는 마지막 값을 그대로 준다. Pod마다 따로 두는 조회 캐시다 — Pod끼리 값이 몇 초 달라도 되고 공유 상태가 아니다(CLAUDE.md §10).
 * - single-flight: 같은 시점의 요청은 진행 중인 갱신 하나를 같이 기다린다(조회는 한 번).
 * - 갱신 1회 전체 상한을 넘거나 실패하면 마지막 값을 stale로(asStale), 없으면 unavailable. 둘 다 원인을 받는다 —
 *   출처 장애(Prometheus 서버 문제 · AWS 오류 · 갱신 상한 초과)면 SOURCE, 그 밖(코드 · 가공 오류)이면 INTERNAL.
 *   실패한 뒤 TTL 동안은 다시 조회하지 않는다(외부 장애 때 요청마다 상한까지 기다리지 않게).
 * - 실패 로그는 갱신마다 WARN 한 줄(원인 · 연속 횟수, 쿼리 원문 · 응답 본문 · 자격 증명 없음).
 *   예상 밖 예외(Prometheus · AWS 오류 · 시간 초과가 아닌 것)는 연속 실패 동안 처음 한 번만 스택 트레이스를 붙인다(2026-10-28).
 */
final class RefreshCache<T> {

	private static final Logger log = LoggerFactory.getLogger(RefreshCache.class);

	/** 갱신 1회. deadlineNanos = System.nanoTime() 기준 상한 */
	@FunctionalInterface
	interface Loader<T> {

		T load(long deadlineNanos);

	}

	private final String label;

	private final long ttlNanos;

	private final Duration refreshTimeout;

	private final ExecutorService executor;

	private final Loader<T> loader;

	private final BiFunction<T, StaleReason, T> asStale;

	private final Function<StaleReason, T> unavailable;

	private final Object lock = new Object();

	// ---- lock으로 보호 ----

	private CompletableFuture<T> inFlight;

	private T last;

	private long lastSuccessNanos;

	private boolean failedRecently;

	private long lastFailureNanos;

	private StaleReason lastFailureReason;

	/** 마지막 성공 뒤 연속 실패 횟수 */
	private int failureStreak;

	/** 이번 연속 실패에서 스택 트레이스를 이미 남김 */
	private boolean stackLogged;

	private long lastUsedNanos = System.nanoTime();

	/**
	 * @param label 로그용 이름(예: "홈 모니터링 · Prometheus")
	 * @param asStale 마지막 값을 stale로 표시한 값(마지막 값, 원인)
	 * @param unavailable 캐시가 없을 때 줄 값(원인). 던지면 그 예외가 호출한 쪽으로 간다
	 */
	RefreshCache(String label, Duration ttl, Duration refreshTimeout, ExecutorService executor, Loader<T> loader,
			BiFunction<T, StaleReason, T> asStale, Function<StaleReason, T> unavailable) {
		this.label = label;
		this.ttlNanos = ttl.toNanos();
		this.refreshTimeout = refreshTimeout;
		this.executor = executor;
		this.loader = loader;
		this.asStale = asStale;
		this.unavailable = unavailable;
	}

	T get() {
		CompletableFuture<T> refresh;
		synchronized (lock) {
			long now = System.nanoTime();
			lastUsedNanos = now;
			if (last != null && now - lastSuccessNanos < ttlNanos) {
				return last;
			}
			if (inFlight != null) {
				refresh = inFlight;
			}
			else {
				if (failedRecently && now - lastFailureNanos < ttlNanos) {
					return fallbackLocked(lastFailureReason);
				}
				long deadline = now + refreshTimeout.toNanos();
				CompletableFuture<T> started = CompletableFuture.supplyAsync(() -> loader.load(deadline), executor)
					.orTimeout(refreshTimeout.toMillis(), TimeUnit.MILLISECONDS);
				inFlight = started;
				// 이미 끝났으면 이 스레드에서 바로 finish가 돈다(같은 lock, 재진입)
				started.whenComplete((result, error) -> finish(started, result, error));
				refresh = started;
			}
		}
		try {
			return refresh.join();
		}
		catch (CompletionException ex) {
			// finish보다 먼저 깨어날 수 있어 원인은 받은 예외에서 바로 정한다
			StaleReason reason = staleReason(unwrap(ex));
			synchronized (lock) {
				return fallbackLocked(reason);
			}
		}
	}

	/** 마지막으로 get()을 부른 시각(System.nanoTime) — 키별 캐시 정리용 */
	long lastUsedNanos() {
		synchronized (lock) {
			return lastUsedNanos;
		}
	}

	private void finish(CompletableFuture<T> refresh, T result, Throwable error) {
		Throwable cause = error == null ? null : unwrap(error);
		int streak;
		boolean withStack;
		boolean hasLast;
		synchronized (lock) {
			if (inFlight == refresh) {
				inFlight = null;
			}
			if (error == null) {
				last = result;
				lastSuccessNanos = System.nanoTime();
				failedRecently = false;
				failureStreak = 0;
				stackLogged = false;
				return;
			}
			failedRecently = true;
			lastFailureNanos = System.nanoTime();
			lastFailureReason = staleReason(cause);
			streak = ++failureStreak;
			withStack = !expected(cause) && !stackLogged;
			stackLogged |= withStack;
			hasLast = last != null;
		}
		String why = streak > 1 ? reason(cause) + " · 연속 " + streak + "번째" : reason(cause);
		String outcome = hasLast ? "마지막 값을 stale로 응답합니다" : "마지막 값이 없어 확인 불가로 응답합니다";
		if (withStack) {
			log.warn("{} 조회 실패({}) — {}", label, why, outcome, cause);
		}
		else {
			log.warn("{} 조회 실패({}) — {}", label, why, outcome);
		}
	}

	private T fallbackLocked(StaleReason reason) {
		// 캐시가 없으면 unavailable(홈은 DB만 읽음 — lock 안이지만 짧고, 이때는 외부를 부르지 않는다)
		return last != null ? asStale.apply(last, reason) : unavailable.apply(reason);
	}

	private static Throwable unwrap(Throwable error) {
		return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
	}

	/** 메시지를 다듬어 둔 예상된 실패 — 한 줄만(스택 없음) */
	private static boolean expected(Throwable cause) {
		return cause instanceof TimeoutException || cause instanceof PrometheusException
				|| cause instanceof AwsMonitoringException;
	}

	private static StaleReason staleReason(Throwable cause) {
		boolean source = cause instanceof TimeoutException || cause instanceof AwsMonitoringException
				|| cause instanceof PrometheusException p && p.serverFailure();
		return source ? StaleReason.SOURCE : StaleReason.INTERNAL;
	}

	private String reason(Throwable cause) {
		if (cause instanceof TimeoutException) {
			return "갱신 " + refreshTimeout.toMillis() + "ms 초과";
		}
		if (cause instanceof PrometheusException || cause instanceof AwsMonitoringException) {
			return cause.getMessage();
		}
		return cause.getClass().getSimpleName();
	}

}
