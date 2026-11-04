package com.tetra.ops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.util.StringUtils;

import com.tetra.ops.monitoring.PrometheusClient.PrometheusException;

/**
 * 갱신 실패 원인(staleReason)과 WARN. 출처 장애(Prometheus 서버 문제 · 시간 초과)는 SOURCE, 그 밖은 INTERNAL.
 * 예상 밖 예외는 연속 실패의 처음 한 번만 스택 트레이스, 이후는 한 줄에 연속 횟수. TTL 0 = get()마다 갱신.
 */
@ExtendWith(OutputCaptureExtension.class)
class RefreshCacheTest {

	/** 다음 갱신이 할 일 */
	private final AtomicReference<Supplier<String>> next = new AtomicReference<>(() -> "v");

	private RefreshCache<String> cache(ExecutorService executor, Duration timeout) {
		return new RefreshCache<>("시험 캐시", Duration.ZERO, timeout, executor, deadline -> next.get().get(),
				(last, reason) -> "stale:" + last + ":" + reason, reason -> "none:" + reason);
	}

	/** 갱신을 get()을 부른 스레드에서 바로 돌린다 — 로그 · 마지막 값이 get() 안에서 끝나 순서가 정해진다 */
	private RefreshCache<String> direct() {
		return cache(new DirectExecutor(), Duration.ofSeconds(5));
	}

	private static Supplier<String> failing(RuntimeException ex) {
		return () -> {
			throw ex;
		};
	}

	@Test
	void unexpectedErrorIsInternalAndLogsStackOncePerStreak(CapturedOutput output) {
		RefreshCache<String> cache = direct();
		next.set(failing(new NullPointerException("p95 없음")));
		assertThat(cache.get()).isEqualTo("none:INTERNAL");
		assertThat(cache.get()).isEqualTo("none:INTERNAL");
		assertThat(output.getOut())
			.contains("시험 캐시 조회 실패(NullPointerException) — 마지막 값이 없어 확인 불가로 응답합니다")
			.contains("시험 캐시 조회 실패(NullPointerException · 연속 2번째) — 마지막 값이 없어 확인 불가로 응답합니다");
		assertThat(StringUtils.countOccurrencesOf(output.getOut(), "java.lang.NullPointerException: p95 없음")).isEqualTo(1);

		// 성공하면 처음부터 다시: 다음 실패에 스택 트레이스, 마지막 값은 stale로
		next.set(() -> "v1");
		assertThat(cache.get()).isEqualTo("v1");
		next.set(failing(new NullPointerException("p95 없음")));
		assertThat(cache.get()).isEqualTo("stale:v1:INTERNAL");
		assertThat(output.getOut()).contains("시험 캐시 조회 실패(NullPointerException) — 마지막 값을 stale로 응답합니다");
		assertThat(StringUtils.countOccurrencesOf(output.getOut(), "java.lang.NullPointerException: p95 없음")).isEqualTo(2);
	}

	@Test
	void prometheusServerFailureIsSourceInOneLine(CapturedOutput output) {
		RefreshCache<String> cache = direct();
		next.set(failing(new PrometheusException("HTTP 503", true)));
		assertThat(cache.get()).isEqualTo("none:SOURCE");
		next.set(() -> "v1");
		cache.get();
		next.set(failing(new PrometheusException("HTTP 503", true)));
		assertThat(cache.get()).isEqualTo("stale:v1:SOURCE");
		// 쿼리만의 오류(4xx)가 갱신까지 올라오면(홈) 출처 장애가 아님 — 메시지는 다듬어져 있어 역시 한 줄
		next.set(failing(new PrometheusException("HTTP 400", false)));
		assertThat(cache.get()).isEqualTo("stale:v1:INTERNAL");
		assertThat(output.getOut()).contains("시험 캐시 조회 실패(HTTP 503) — 마지막 값이 없어 확인 불가로 응답합니다")
			.contains("시험 캐시 조회 실패(HTTP 400 · 연속 2번째) — 마지막 값을 stale로 응답합니다")
			.doesNotContain("PrometheusException");
	}

	@Test
	void awsAccessDeniedBeforeFirstSuccessIsSourceWithoutValue(CapturedOutput output) {
		// 발급 ALB LCU 예약: 권한(infra-aws)이 반영되기 전 배포 → 값 없음(화면 '확인 불가'), 원인 SOURCE, 한 줄(스택 없음)
		RefreshCache<String> cache = direct();
		next.set(failing(new AwsMonitoringException("DescribeCapacityReservation AccessDenied")));
		assertThat(cache.get()).isEqualTo("none:SOURCE");
		assertThat(output.getOut())
			.contains("시험 캐시 조회 실패(DescribeCapacityReservation AccessDenied) — 마지막 값이 없어 확인 불가로 응답합니다")
			.doesNotContain("AwsMonitoringException");
		// 권한이 반영되면 다음 갱신부터 값
		next.set(() -> "100");
		assertThat(cache.get()).isEqualTo("100");
	}

	@Test
	void refreshTimeoutIsSource() {
		ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
		try {
			RefreshCache<String> cache = cache(executor, Duration.ofMillis(100));
			next.set(() -> {
				try {
					Thread.sleep(2000);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				return "late";
			});
			assertThat(cache.get()).isEqualTo("none:SOURCE");
		}
		finally {
			executor.shutdownNow();
		}
	}

	private static final class DirectExecutor extends AbstractExecutorService {

		private volatile boolean shutdown;

		@Override
		public void execute(Runnable command) {
			command.run();
		}

		@Override
		public void shutdown() {
			shutdown = true;
		}

		@Override
		public List<Runnable> shutdownNow() {
			shutdown = true;
			return List.of();
		}

		@Override
		public boolean isShutdown() {
			return shutdown;
		}

		@Override
		public boolean isTerminated() {
			return shutdown;
		}

		@Override
		public boolean awaitTermination(long timeout, TimeUnit unit) {
			return true;
		}

	}

}
