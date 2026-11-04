package com.tetra.ops.monitoring;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.tetra.ops.common.time.Kst;
import com.tetra.ops.domain.platform.EventRegistry;
import com.tetra.ops.domain.platform.EventRegistryRepository;
import com.tetra.ops.domain.platform.Tenant;
import com.tetra.ops.domain.platform.TenantRepository;
import com.tetra.ops.event.EntryOpen;
import com.tetra.ops.monitoring.MonitoringOverview.Cluster;
import com.tetra.ops.monitoring.MonitoringOverview.EventRef;
import com.tetra.ops.monitoring.MonitoringOverview.Nodes;
import com.tetra.ops.monitoring.MonitoringOverview.PodCount;
import com.tetra.ops.monitoring.MonitoringOverview.Pods;
import com.tetra.ops.monitoring.MonitoringOverview.Point;
import com.tetra.ops.monitoring.MonitoringOverview.Series;
import com.tetra.ops.monitoring.MonitoringOverview.TenantPods;
import com.tetra.ops.monitoring.MonitoringOverview.TenantRow;
import com.tetra.ops.monitoring.MonitoringOverview.Traffic;
import com.tetra.ops.monitoring.PrometheusClient.PrometheusException;
import com.tetra.ops.monitoring.PrometheusClient.Sample;
import com.tetra.ops.tenancy.TenantIds;

/**
 * 갱신 1회: OPEN 이벤트(RDS) + Prometheus 쿼리(순차) → 응답. 캐시 · stale 처리는 MonitoringService.
 * 쿼리 하나라도 실패하면 예외 — 수치가 섞이지 않게 갱신 전체를 실패로 본다.
 *
 * 0과 null(2026-10-26 확정): 시리즈가 없으면 null이 원칙이다.
 * - 개수(노드 · Pod): kube-state-metrics 응답이 있으면(Ready 노드 쿼리에 시리즈가 있음) 빠진 그룹은 0, 응답 자체가 없으면 null.
 *   count · sum by는 0개인 그룹을 돌려주지 않기 때문(평시 app · redis 노드 0).
 * - 테넌트: 그 테넌트 요청 시리즈가 있고 5xx 시리즈만 없으면 errorRps · failed = 0. 요청 시리즈가 없으면 rps · errorRps ·
 *   completed · failed 모두 null.
 */
@Component
class MonitoringCollector {

	private static final Logger log = LoggerFactory.getLogger(MonitoringCollector.class);

	static final Duration RANGE = Duration.ofMinutes(30);

	/** = 요청량 창(PromQueries.TRAFFIC_STEP) — 그래프 점마다 그 15초 안 1초 값의 최대 */
	static final Duration STEP = PromQueries.TRAFFIC_STEP;

	private final PrometheusClient prometheus;

	private final EventRegistryRepository registries;

	private final TenantRepository tenants;

	private final Clock clock;

	MonitoringCollector(PrometheusClient prometheus, EventRegistryRepository registries, TenantRepository tenants,
			Clock clock) {
		this.prometheus = prometheus;
		this.registries = registries;
		this.tenants = tenants;
		this.clock = clock;
	}

	boolean configured() {
		return prometheus.configured();
	}

	/** 그 테넌트의 OPEN 이벤트(시작 시각 순) */
	private record OpenTenant(String tenantId, String tenantName, List<EventRegistry> events) {

		LocalDateTime since() {
			return events.stream().map(r -> EntryOpen.at(r.getStartAt())).min(Comparator.naturalOrder()).orElseThrow();
		}

		List<EventRef> eventRefs() {
			return events.stream().map(MonitoringCollector::eventRef).toList();
		}

	}

	/** Prometheus를 읽지 못했을 때(캐시도 없음): 테넌트는 DB 목록, 수치는 null */
	MonitoringOverview unavailable() {
		List<TenantRow> rows = openTenants().values()
			.stream()
			.map(t -> new TenantRow(t.tenantId(), t.tenantName(), PromQueries.namespace(t.tenantId()), t.eventRefs(),
					null, null, new TenantPods(null, null), null, null, Kst.toOffset(t.since())))
			.toList();
		return new MonitoringOverview(null, true, Cluster.UNAVAILABLE,
				new Traffic((int) RANGE.toMinutes(), (int) STEP.toSeconds(), null, List.of()), rows);
	}

	/** @param deadlineNanos System.nanoTime() 기준 갱신 상한. 넘으면 다음 쿼리를 하지 않고 실패 */
	MonitoringOverview collect(long deadlineNanos) {
		Instant now = clock.instant();
		Run run = new Run(now, deadlineNanos);
		Map<String, OpenTenant> open = openTenants();

		// 클러스터
		Double cpu = round(single(run.instant(PromQueries.CPU_USAGE)), 4);
		Double memory = round(single(run.instant(PromQueries.MEMORY_USAGE)), 4);
		Map<String, Double> nodes = byLabel(run.instant(PromQueries.READY_NODES_BY_POOL), "label_tetra_io_pool");
		boolean kubeStateMetrics = !nodes.isEmpty();
		Map<String, Double> readyPods = byLabel(run.instant(PromQueries.READY_PODS_BY_COMPONENT),
				"label_tetra_io_component");
		Map<String, Double> activePods = byLabel(run.instant(PromQueries.ACTIVE_PODS_BY_COMPONENT),
				"label_tetra_io_component");
		Cluster cluster = new Cluster(cpu, memory,
				new Nodes(count(nodes, "app", kubeStateMetrics), count(nodes, "redis", kubeStateMetrics),
						count(nodes, "system", kubeStateMetrics)),
				new Pods(new PodCount(count(readyPods, "api", kubeStateMetrics), count(activePods, "api", kubeStateMetrics)),
						new PodCount(count(readyPods, "redis", kubeStateMetrics),
								count(activePods, "redis", kubeStateMetrics))));

		// 실시간 트래픽: 시각을 step 배수로 맞춰 모든 시리즈가 같은 격자를 쓰게 한다
		long stepMillis = STEP.toMillis();
		Instant end = Instant.ofEpochMilli(now.toEpochMilli() / stepMillis * stepMillis);
		Instant start = end.minus(RANGE);
		List<PrometheusClient.Series> ranged = run.range(PromQueries.RPS_BY_TENANT, start, end);
		Double totalRps = single(run.instant(PromQueries.RPS_TOTAL));
		List<Series> series = series(ranged, start, end, open);
		Traffic traffic = new Traffic((int) RANGE.toMinutes(), (int) STEP.toSeconds(),
				totalRps == null ? 0.0 : round(totalRps, 1), series);

		return new MonitoringOverview(Kst.toOffset(LocalDateTime.ofInstant(now, Kst.ZONE)), false, cluster, traffic,
				tenantRows(run, open, kubeStateMetrics, now));
	}

	private List<TenantRow> tenantRows(Run run, Map<String, OpenTenant> open, boolean kubeStateMetrics, Instant now) {
		if (open.isEmpty()) {
			return List.of();
		}
		Map<String, Double> rps = byLabel(run.instant(PromQueries.RPS_BY_TENANT), "tenant_id");
		Map<String, Double> errorRps = byLabel(run.instant(PromQueries.errorRpsByTenant(open.keySet())), "tenant_id");
		Map<String, Double> readyByNamespace = byLabel(run.instant(PromQueries.API_READY_PODS_BY_NAMESPACE), "namespace");
		Map<String, Double> maxByNamespace = byLabel(run.instant(PromQueries.HPA_MAX_BY_NAMESPACE), "namespace");
		LocalDateTime nowKst = LocalDateTime.ofInstant(now, Kst.ZONE);

		List<TenantRow> rows = new ArrayList<>();
		for (OpenTenant t : open.values()) {
			String namespace = PromQueries.namespace(t.tenantId());
			LocalDateTime since = t.since();
			// 테넌트당 1회 호출. 범위(since)가 테넌트마다 달라 합칠 수 없음.
			// 동시 이벤트 상한(현재 3)을 올리면 or로 이은 단일 쿼리(설계 A안)로 바꾼다.
			List<Sample> byStatus = run
				.instant(PromQueries.requestsByStatusSince(t.tenantId(), Duration.between(since, nowKst)));
			Long completed = null;
			Long failed = null;
			if (!byStatus.isEmpty()) {
				double ok = 0;
				double error = 0;
				for (Sample s : byStatus) {
					double v = s.value() == null ? 0 : s.value();
					if (s.labels().getOrDefault("status", "").startsWith("5")) {
						error += v;
					}
					else {
						ok += v;
					}
				}
				completed = Math.round(ok);
				failed = Math.round(error);
			}
			Double tenantRps = round(rps.get(t.tenantId()), 1);
			Double tenantErrorRps = tenantRps == null ? null : round(errorRps.getOrDefault(t.tenantId(), 0.0), 1);
			rows.add(new TenantRow(t.tenantId(), t.tenantName(), namespace, t.eventRefs(), tenantRps, tenantErrorRps,
					new TenantPods(count(readyByNamespace, namespace, kubeStateMetrics), toInt(maxByNamespace.get(namespace))),
					completed, failed, Kst.toOffset(since)));
		}
		return rows;
	}

	/** 쿼리에 나온 시리즈 = 지금 호스팅 중인 이벤트(필터 없음). 이름은 DB, 이벤트는 그 테넌트의 OPEN 이벤트 */
	private List<Series> series(List<PrometheusClient.Series> ranged, Instant start, Instant end,
			Map<String, OpenTenant> open) {
		List<Long> grid = new ArrayList<>();
		for (long t = start.toEpochMilli(); t <= end.toEpochMilli(); t += STEP.toMillis()) {
			grid.add(t);
		}
		Map<String, Map<Long, Double>> byTenant = new LinkedHashMap<>();
		for (PrometheusClient.Series s : ranged) {
			String tenantId = s.labels().get("tenant_id");
			if (!TenantIds.isValid(tenantId)) {
				continue; // tenant_id 라벨이 없거나 형식이 아닌 요청은 합계(totalRps)에만 들어간다
			}
			Map<Long, Double> values = new HashMap<>();
			s.points().forEach(p -> values.put(p.epochMillis(), round(p.value(), 1)));
			byTenant.put(tenantId, values);
		}
		Map<String, String> names = tenants.findAllById(byTenant.keySet())
			.stream()
			.collect(Collectors.toMap(Tenant::getTenantId, Tenant::getName));
		return byTenant.entrySet()
			.stream()
			.map(e -> new Series(e.getKey(), names.get(e.getKey()),
					open.containsKey(e.getKey()) ? open.get(e.getKey()).eventRefs() : List.of(),
					grid.stream().map(t -> new Point(t, e.getValue().get(t))).toList()))
			// 폴링마다 색이 바뀌지 않게 이름 → tenant_id 순
			.sorted(Comparator.comparing((Series s) -> s.tenantName() == null ? "￿" : s.tenantName())
				.thenComparing(Series::tenantId))
			.toList();
	}

	/** OPEN 이벤트를 테넌트별로 묶는다(한 테넌트에 OPEN이 둘이면 한 행) */
	private Map<String, OpenTenant> openTenants() {
		Map<String, OpenTenant> open = new LinkedHashMap<>();
		for (EventRegistryRepository.WithTenantName row : registries.findOpenWithTenantName()) {
			String tenantId = row.registry().getTenantId();
			if (!TenantIds.isValid(tenantId)) {
				log.warn("event_registry의 tenant_id 형식이 아니어서 모니터링에서 뺍니다 (event_id {})", row.registry().getEventId());
				continue;
			}
			open.computeIfAbsent(tenantId, id -> new OpenTenant(id, row.tenantName(), new ArrayList<>()))
				.events()
				.add(row.registry());
		}
		return open;
	}

	private static EventRef eventRef(EventRegistry r) {
		return new EventRef(r.getEventId(), r.getName(), Kst.toOffset(r.getStartAt()));
	}

	/** 갱신 1회의 평가 시각과 상한. 쿼리마다 남은 시간을 확인한다 */
	private final class Run {

		private final Instant at;

		private final long deadlineNanos;

		Run(Instant at, long deadlineNanos) {
			this.at = at;
			this.deadlineNanos = deadlineNanos;
		}

		List<Sample> instant(String promql) {
			checkDeadline();
			return prometheus.query(promql, at);
		}

		List<PrometheusClient.Series> range(String promql, Instant start, Instant end) {
			checkDeadline();
			return prometheus.queryRange(promql, start, end, STEP);
		}

		private void checkDeadline() {
			if (System.nanoTime() - deadlineNanos > 0) {
				throw new PrometheusException("갱신 시간 초과");
			}
		}

	}

	private static Double single(List<Sample> samples) {
		return samples.isEmpty() ? null : samples.get(0).value();
	}

	private static Map<String, Double> byLabel(List<Sample> samples, String label) {
		Map<String, Double> values = new HashMap<>();
		for (Sample s : samples) {
			String key = s.labels().get(label);
			if (key != null && s.value() != null) {
				values.merge(key, s.value(), Double::sum);
			}
		}
		return values;
	}

	private static Integer count(Map<String, Double> values, String key, boolean missingIsZero) {
		Double v = values.get(key);
		if (v == null) {
			return missingIsZero ? 0 : null;
		}
		return toInt(v);
	}

	private static Integer toInt(Double v) {
		return v == null ? null : (int) Math.round(v);
	}

	private static Double round(Double v, int digits) {
		if (v == null) {
			return null;
		}
		double scale = Math.pow(10, digits);
		return Math.round(v * scale) / scale;
	}

}
