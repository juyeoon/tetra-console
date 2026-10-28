package com.tetra.ops.monitoring;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.tetra.ops.config.TetraProperties;

import jakarta.annotation.PreDestroy;

/**
 * 홈 모니터링 응답 캐시. 운영자 화면 전체용이라 키는 하나(테넌트별 데이터 아님).
 * 캐시 규칙(TTL 기본 15초 · single-flight · 갱신 상한 기본 8초 · 실패 시 마지막 값 stale, 없으면 수치 null + stale ·
 * 실패 뒤 TTL 동안 재조회 안 함)은 {@link RefreshCache}.
 */
@Service
public class MonitoringService {

	private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);

	private final MonitoringCollector collector;

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	private final RefreshCache<MonitoringOverview> cache;

	public MonitoringService(MonitoringCollector collector, TetraProperties properties) {
		this.collector = collector;
		TetraProperties.Monitoring monitoring = properties.monitoring();
		this.cache = new RefreshCache<>("홈 모니터링 · Prometheus", monitoring.cacheTtl(), monitoring.refreshTimeout(),
				executor, collector::collect, (last, reason) -> last.asStale(), reason -> collector.unavailable());
		if (!collector.configured()) {
			log.warn("PROMETHEUS_URL이 비어 있어 홈 모니터링 카드는 값 없이(stale) 응답합니다");
		}
	}

	public MonitoringOverview overview() {
		if (!collector.configured()) {
			return collector.unavailable();
		}
		return cache.get();
	}

	@PreDestroy
	void shutdown() {
		executor.shutdownNow();
	}

}
