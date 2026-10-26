package com.tetra.ops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.tetra.ops.config.TetraProperties;
import com.tetra.ops.monitoring.MonitoringOverview.Cluster;
import com.tetra.ops.monitoring.MonitoringOverview.Traffic;
import com.tetra.ops.monitoring.PrometheusClient.PrometheusException;

/** 캐시(TTL) · single-flight · 실패 시 stale · 갱신 상한 · 미설정. */
class MonitoringServiceTest {

	private final MonitoringCollector collector = mock(MonitoringCollector.class);

	private final List<MonitoringService> services = new ArrayList<>();

	private static final MonitoringOverview FRESH = new MonitoringOverview(
			OffsetDateTime.parse("2026-10-26T15:00:00+09:00"), false,
			new Cluster(0.42, 0.61, new MonitoringOverview.Nodes(0, 0, 2), null), new Traffic(30, 15, 0.0, List.of()),
			List.of());

	private static final MonitoringOverview UNAVAILABLE = new MonitoringOverview(null, true, Cluster.UNAVAILABLE,
			new Traffic(30, 15, null, List.of()), List.of());

	@AfterEach
	void shutdown() {
		services.forEach(MonitoringService::shutdown);
	}

	private MonitoringService service(Duration ttl, Duration refreshTimeout) {
		when(collector.configured()).thenReturn(true);
		when(collector.unavailable()).thenReturn(UNAVAILABLE);
		var monitoring = new TetraProperties.Monitoring("http://prometheus:9090", Duration.ofSeconds(2),
				Duration.ofSeconds(3), ttl, refreshTimeout, null);
		var properties = new TetraProperties("test", null, null, null, null, null, null, null, null, monitoring, null, null,
				null, "tetraops.cloud");
		MonitoringService service = new MonitoringService(collector, properties);
		services.add(service);
		return service;
	}

	@Test
	void cachesWithinTtlAndRefreshesAfter() throws Exception {
		when(collector.collect(anyLong())).thenReturn(FRESH);
		MonitoringService service = service(Duration.ofMillis(300), Duration.ofSeconds(8));

		assertThat(service.overview()).isSameAs(FRESH);
		assertThat(service.overview()).isSameAs(FRESH);
		verify(collector, times(1)).collect(anyLong());

		Thread.sleep(400);
		assertThat(service.overview()).isSameAs(FRESH);
		verify(collector, times(2)).collect(anyLong());
	}

	@Test
	void concurrentRequestsShareOneRefresh() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		when(collector.collect(anyLong())).thenAnswer(invocation -> {
			release.await(5, TimeUnit.SECONDS);
			return FRESH;
		});
		MonitoringService service = service(Duration.ofSeconds(15), Duration.ofSeconds(8));

		ExecutorService pool = Executors.newFixedThreadPool(10);
		try {
			List<Future<MonitoringOverview>> results = new ArrayList<>();
			for (int i = 0; i < 10; i++) {
				results.add(pool.submit(service::overview));
			}
			Thread.sleep(200); // 10개가 모두 진행 중인 갱신을 기다리게
			release.countDown();
			for (Future<MonitoringOverview> result : results) {
				assertThat(result.get(5, TimeUnit.SECONDS)).isSameAs(FRESH);
			}
		}
		finally {
			pool.shutdownNow();
		}
		verify(collector, times(1)).collect(anyLong());
	}

	@Test
	void failureReturnsLastValueAsStale() throws Exception {
		when(collector.collect(anyLong())).thenReturn(FRESH).thenThrow(new PrometheusException("HTTP 503"));
		MonitoringService service = service(Duration.ofMillis(100), Duration.ofSeconds(8));

		assertThat(service.overview().stale()).isFalse();
		Thread.sleep(150);

		MonitoringOverview stale = service.overview();
		assertThat(stale.stale()).isTrue();
		assertThat(stale.asOf()).isEqualTo(FRESH.asOf());
		assertThat(stale.cluster()).isEqualTo(FRESH.cluster());
	}

	@Test
	void failureWithoutCacheReturnsUnavailableAndDoesNotRetryWithinTtl() {
		when(collector.collect(anyLong())).thenThrow(new PrometheusException("HTTP 503"));
		MonitoringService service = service(Duration.ofSeconds(15), Duration.ofSeconds(8));

		assertThat(service.overview()).isSameAs(UNAVAILABLE);
		assertThat(service.overview()).isSameAs(UNAVAILABLE);
		verify(collector, times(1)).collect(anyLong());
	}

	@Test
	void refreshOverTimeoutIsFailure() {
		when(collector.collect(anyLong())).thenAnswer(invocation -> {
			Thread.sleep(3000);
			return FRESH;
		});
		MonitoringService service = service(Duration.ofSeconds(15), Duration.ofMillis(300));

		long started = System.nanoTime();
		MonitoringOverview result = service.overview();

		assertThat(result).isSameAs(UNAVAILABLE);
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
	}

	@Test
	void unconfiguredNeverCollects() {
		MonitoringService service = service(Duration.ofSeconds(15), Duration.ofSeconds(8));
		when(collector.configured()).thenReturn(false);

		assertThat(service.overview()).isSameAs(UNAVAILABLE);
		verify(collector, never()).collect(anyLong());
	}

}
