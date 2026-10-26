package com.tetra.ops.monitoring;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.tetra.ops.config.TetraProperties;
import com.tetra.ops.monitoring.PrometheusClient.PrometheusException;

import jakarta.annotation.PreDestroy;

/**
 * 홈 모니터링 응답 캐시.
 * - TTL(기본 15초) 안에는 마지막 값을 그대로 준다. 이 캐시는 Pod마다 따로 두는 조회 캐시다 — Pod끼리 값이 몇 초 달라도 되고,
 *   공유 상태가 아니라서 Pod 여러 개가 떠도 문제없다(CLAUDE.md §10). 운영자 화면 전체용이라 키는 하나(테넌트별 데이터 아님).
 * - single-flight: 같은 시점의 요청은 진행 중인 갱신 하나를 같이 기다린다(Prometheus 조회는 한 번).
 * - 갱신 1회 전체 상한(기본 8초)을 넘거나 쿼리가 실패하면 마지막 값을 stale로, 없으면 수치 null + stale.
 *   실패한 뒤 TTL 동안은 다시 조회하지 않는다(Prometheus 장애 때 요청마다 상한까지 기다리지 않게).
 * - 실패 로그는 갱신마다 WARN 한 줄(원인만, 쿼리 원문 · 응답 본문 없음).
 */
@Service
public class MonitoringService {

	private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);

	private final MonitoringCollector collector;

	private final long ttlNanos;

	private final Duration refreshTimeout;

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	private final Object lock = new Object();

	// ---- lock으로 보호 ----

	private CompletableFuture<MonitoringOverview> inFlight;

	private MonitoringOverview last;

	private long lastSuccessNanos;

	private boolean failedRecently;

	private long lastFailureNanos;

	public MonitoringService(MonitoringCollector collector, TetraProperties properties) {
		this.collector = collector;
		this.ttlNanos = properties.monitoring().cacheTtl().toNanos();
		this.refreshTimeout = properties.monitoring().refreshTimeout();
		if (!collector.configured()) {
			log.warn("PROMETHEUS_URL이 비어 있어 홈 모니터링 카드는 값 없이(stale) 응답합니다");
		}
	}

	public MonitoringOverview overview() {
		if (!collector.configured()) {
			return collector.unavailable();
		}
		CompletableFuture<MonitoringOverview> refresh;
		synchronized (lock) {
			long now = System.nanoTime();
			if (last != null && now - lastSuccessNanos < ttlNanos) {
				return last;
			}
			if (inFlight != null) {
				refresh = inFlight;
			}
			else {
				if (failedRecently && now - lastFailureNanos < ttlNanos) {
					return fallbackLocked();
				}
				long deadline = now + refreshTimeout.toNanos();
				CompletableFuture<MonitoringOverview> started = CompletableFuture
					.supplyAsync(() -> collector.collect(deadline), executor)
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
			return fallback();
		}
	}

	private void finish(CompletableFuture<MonitoringOverview> refresh, MonitoringOverview result, Throwable error) {
		synchronized (lock) {
			if (inFlight == refresh) {
				inFlight = null;
			}
			if (error == null) {
				last = result;
				lastSuccessNanos = System.nanoTime();
				failedRecently = false;
				return;
			}
			failedRecently = true;
			lastFailureNanos = System.nanoTime();
		}
		log.warn("Prometheus 조회 실패({}) — 홈 모니터링은 마지막 값을 stale로 응답합니다", reason(error));
	}

	private MonitoringOverview fallback() {
		synchronized (lock) {
			return fallbackLocked();
		}
	}

	private MonitoringOverview fallbackLocked() {
		// 캐시가 없으면 DB만 읽는다(lock 안이지만 짧고, 이때는 Prometheus를 부르지 않는다)
		return last != null ? last.asStale() : collector.unavailable();
	}

	private String reason(Throwable error) {
		Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
		if (cause instanceof TimeoutException) {
			return "갱신 " + refreshTimeout.toMillis() + "ms 초과";
		}
		if (cause instanceof PrometheusException) {
			return cause.getMessage();
		}
		return cause.getClass().getSimpleName();
	}

	@PreDestroy
	void shutdown() {
		executor.shutdownNow();
	}

}
