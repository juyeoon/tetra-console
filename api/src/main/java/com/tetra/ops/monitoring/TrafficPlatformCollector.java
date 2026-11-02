package com.tetra.ops.monitoring;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;

import org.springframework.stereotype.Component;

import com.tetra.ops.monitoring.PromValues.Grid;
import com.tetra.ops.monitoring.PrometheusClient.Sample;
import com.tetra.ops.monitoring.PrometheusClient.Series;
import com.tetra.ops.monitoring.TrafficPlatformResponse.HistoryPoint;
import com.tetra.ops.monitoring.TrafficRules.AddonMember;
import com.tetra.ops.monitoring.TrafficRules.EventSignals;

/**
 * OPS-05 Prometheus 갱신 1회(A · B-1 · B-2 애드온): 클러스터 · 노드 · 애드온 · 진행 중 이벤트 판정 재료(event_id별 한 번에).
 * 쿼리는 {@link PromBatch}로 4개씩 병렬. Prometheus 자체 문제면 갱신 전체 실패, 쿼리 하나의 오류면 그 항목만 null.
 * 캐시 · stale은 TrafficMonitoringService.
 */
@Component
class TrafficPlatformCollector {

	static final Duration HISTORY_RANGE = Duration.ofHours(1);

	static final Duration HISTORY_STEP = Duration.ofSeconds(60);

	/** 핵심 애드온(명세 5-1). Prometheus 자신은 API stale로 판단 */
	static final List<AddonMember> ADDONS = List.of(new AddonMember("KEDA", "keda", "keda-operator", null, null),
			new AddonMember("KEDA", "keda", "keda-operator-metrics-apiserver", null, null),
			new AddonMember("Karpenter", "kube-system", "karpenter", null, null),
			new AddonMember("LBC", "kube-system", "aws-load-balancer-controller", null, null),
			new AddonMember("External Secrets", "external-secrets", "external-secrets", null, null),
			new AddonMember("External Secrets", "external-secrets", "external-secrets-webhook", null, null),
			new AddonMember("ArgoCD", "argocd", "argocd-server", null, null),
			new AddonMember("ArgoCD", "argocd", "argocd-repo-server", null, null),
			new AddonMember("ArgoCD", "argocd", "argocd-application-controller", null, null));

	static final String STATEFULSET_ADDON = "argocd-application-controller";

	/** createdAt은 epoch 초 → Instant. type은 kube-state-metrics 라벨 허용 전에는 null */
	record NodeMetric(String name, String pool, String type, boolean ready, Double cpu, Double memory,
			Instant createdAt) {
	}

	/**
	 * at = 평가 시각(Prometheus를 못 읽었으면 null). kubeStateMetrics = 노드 라벨 시리즈가 있음(개수 0/null 규칙).
	 * 쿼리 오류로 빈 항목: readyByPool · addons는 null, 이벤트 재료는 EventSignals.complete = false.
	 * noSeries = 시리즈가 하나도 없는 이벤트의 재료(Ready Pod는 kube-state-metrics 응답이 있으면 0)
	 */
	record Metrics(Instant at, boolean kubeStateMetrics, Double cpu, Double memory, List<HistoryPoint> history,
			Map<String, Integer> readyByPool, List<NodeMetric> nodes, List<AddonMember> addons, Integer pendingOver5m,
			Map<Long, EventSignals> events, EventSignals noSeries) {

		static Metrics unavailable() {
			return new Metrics(null, false, null, null, List.of(), Map.of(), List.of(), List.of(), null, Map.of(), null);
		}

		boolean available() {
			return at != null && kubeStateMetrics;
		}

		/** 진행 중 이벤트의 판정 재료. Prometheus를 못 읽었으면 null */
		EventSignals signals(Long eventId) {
			if (at == null) {
				return null;
			}
			return events.getOrDefault(eventId, noSeries);
		}

	}

	private final PrometheusClient prometheus;

	private final Clock clock;

	private final FailureStreaks streaks = new FailureStreaks();

	TrafficPlatformCollector(PrometheusClient prometheus, Clock clock) {
		this.prometheus = prometheus;
		this.clock = clock;
	}

	boolean configured() {
		return prometheus.configured();
	}

	Metrics collect(long deadlineNanos, ExecutorService executor) {
		Instant now = clock.instant();
		Grid grid = Grid.of(now.minus(HISTORY_RANGE), now, HISTORY_STEP);
		PromBatch.Results r = new PromBatch(prometheus, executor, deadlineNanos, "플랫폼", streaks)
			.instant("cpu", PromQueries.CPU_USAGE, now)
			.instant("memory", PromQueries.MEMORY_USAGE, now)
			.range("cpuHistory", PromQueries.CPU_USAGE, grid.start(), grid.end(), grid.step())
			.range("memoryHistory", PromQueries.MEMORY_USAGE, grid.start(), grid.end(), grid.step())
			.instant("readyByPool", PromQueries.READY_NODES_BY_POOL, now)
			.instant("nodeCpu", TrafficQueries.NODE_CPU, now)
			.instant("nodeMemory", TrafficQueries.NODE_MEMORY, now)
			.instant("nodeLabels", TrafficQueries.NODE_LABELS, now)
			.instant("nodeReady", TrafficQueries.NODE_READY, now)
			.instant("nodeCreated", TrafficQueries.NODE_CREATED, now)
			.instant("pending", TrafficQueries.PENDING_PODS_OVER_5M, now)
			.instant("addonAvailable", TrafficQueries.ADDON_DEPLOYMENTS_AVAILABLE, now)
			.instant("addonDesired", TrafficQueries.ADDON_DEPLOYMENTS_DESIRED, now)
			.instant("stsReady", TrafficQueries.ADDON_STATEFULSETS_READY, now)
			.instant("stsDesired", TrafficQueries.ADDON_STATEFULSETS_DESIRED, now)
			.instant("rps", TrafficQueries.RPS_BY_EVENT, now)
			.instant("errorRps", TrafficQueries.ERROR_RPS_BY_EVENT, now)
			.instant("p95", TrafficQueries.P95_BY_EVENT, now)
			.instant("cursorChanges", TrafficQueries.CURSOR_CHANGES_BY_EVENT, now)
			.instant("waiting", TrafficQueries.WAITING_BY_EVENT, now)
			.instant("stock", TrafficQueries.STOCK_BY_EVENT, now)
			.instant("redisDown", TrafficQueries.REDIS_DOWN_BY_EVENT, now)
			.instant("redisDownPoints", TrafficQueries.REDIS_DOWN_POINTS_BY_EVENT, now)
			.instant("apiReady", TrafficQueries.API_READY_BY_EVENT, now)
			.instant("hpaMax", TrafficQueries.HPA_MAX_BY_HPA, now)
			.execute();

		List<NodeMetric> nodes = nodes(r);
		boolean ksm = !r.instant("nodeLabels").isEmpty();
		Map<String, Integer> readyByPool = null;
		if (r.ok("readyByPool")) {
			Map<String, Integer> pools = new LinkedHashMap<>();
			PromValues.byLabel(r.instant("readyByPool"), "label_tetra_io_pool")
				.forEach((pool, v) -> pools.put(pool, PromValues.toInt(v)));
			readyByPool = pools;
		}
		Double pending = PromValues.single(r.instant("pending"));
		boolean addonsOk = r.allOk("addonAvailable", "addonDesired", "stsReady", "stsDesired");
		boolean eventsOk = r.allOk("nodeLabels", "rps", "errorRps", "p95", "cursorChanges", "waiting", "stock",
				"redisDown", "redisDownPoints", "apiReady", "hpaMax");

		return new Metrics(now, ksm, PromValues.round(PromValues.single(r.instant("cpu")), 4),
				PromValues.round(PromValues.single(r.instant("memory")), 4), history(r, grid), readyByPool, nodes,
				addonsOk ? addons(r) : null, ksm && r.ok("pending") ? PromValues.count(pending, true) : null, events(r),
				new EventSignals(null, null, null, null, null, null, null, ksm && r.ok("apiReady") ? 0 : null, null,
						eventsOk));
	}

	private static List<HistoryPoint> history(PromBatch.Results r, Grid grid) {
		List<Long> times = grid.timestamps();
		List<Double> cpu = PromValues.onGrid(r.range("cpuHistory"), times, false);
		List<Double> memory = PromValues.onGrid(r.range("memoryHistory"), times, false);
		List<HistoryPoint> points = new ArrayList<>(times.size());
		for (int i = 0; i < times.size(); i++) {
			points.add(new HistoryPoint(times.get(i), PromValues.round(cpu.get(i), 4), PromValues.round(memory.get(i), 4)));
		}
		return points;
	}

	/** 노드 목록 = kube-state-metrics 노드(라벨). CPU · 메모리는 node-exporter(nodename) */
	private static List<NodeMetric> nodes(PromBatch.Results r) {
		Map<String, Double> cpu = PromValues.byLabel(r.instant("nodeCpu"), "nodename");
		Map<String, Double> memory = PromValues.byLabel(r.instant("nodeMemory"), "nodename");
		Map<String, Double> ready = PromValues.byLabel(r.instant("nodeReady"), "node");
		Map<String, Double> created = PromValues.byLabel(r.instant("nodeCreated"), "node");
		Map<String, Sample> labels = new TreeMap<>();
		for (Sample s : r.instant("nodeLabels")) {
			String node = s.labels().get("node");
			if (node != null) {
				labels.put(node, s);
			}
		}
		List<NodeMetric> nodes = new ArrayList<>();
		labels.forEach((node, s) -> {
			Double createdAt = created.get(node);
			nodes.add(new NodeMetric(node, s.labels().get("label_tetra_io_pool"),
					s.labels().get("label_node_kubernetes_io_instance_type"),
					ready.getOrDefault(node, 0.0) == 1.0, PromValues.round(cpu.get(node), 4),
					PromValues.round(memory.get(node), 4),
					createdAt == null ? null : Instant.ofEpochSecond(Math.round(createdAt))));
		});
		return nodes;
	}

	private static List<AddonMember> addons(PromBatch.Results r) {
		Map<String, Double> available = new HashMap<>();
		Map<String, Double> desired = new HashMap<>();
		collect(r.instant("addonAvailable"), "deployment", available);
		collect(r.instant("addonDesired"), "deployment", desired);
		collect(r.instant("stsReady"), "statefulset", available);
		collect(r.instant("stsDesired"), "statefulset", desired);
		return ADDONS.stream()
			.map(a -> new AddonMember(a.group(), a.namespace(), a.name(),
					PromValues.toInt(available.get(a.namespace() + "/" + a.name())),
					PromValues.toInt(desired.get(a.namespace() + "/" + a.name()))))
			.toList();
	}

	private static void collect(List<Sample> samples, String nameLabel, Map<String, Double> into) {
		for (Sample s : samples) {
			String ns = s.labels().get("namespace");
			String name = s.labels().get(nameLabel);
			if (ns != null && name != null && s.value() != null) {
				into.put(ns + "/" + name, s.value());
			}
		}
	}

	/** event_id별 판정 재료. HPA는 이름(keda-hpa-issuance-<id>)에서 id를 얻는다. 대기 인원 0 하한은 쿼리(clamp_min)에 있고 여기서는 안전장치 */
	private static Map<Long, EventSignals> events(PromBatch.Results r) {
		Map<Long, Double> rps = byEvent(r.instant("rps"), "event_id");
		Map<Long, Double> errorRps = byEvent(r.instant("errorRps"), "event_id");
		Map<Long, Double> p95 = byEvent(r.instant("p95"), "event_id");
		Map<Long, Double> cursor = byEvent(r.instant("cursorChanges"), "event_id");
		Map<Long, Double> waiting = byEvent(r.instant("waiting"), "event_id");
		Map<Long, Double> stock = byEvent(r.instant("stock"), "event_id");
		Map<Long, Double> redisDown = byEvent(r.instant("redisDown"), "event_id");
		Map<Long, Double> redisDownPoints = byEvent(r.instant("redisDownPoints"), "event_id");
		Map<Long, Double> apiReady = byEvent(r.instant("apiReady"), "label_tetra_io_event_id");
		Map<Long, Double> hpaMax = new HashMap<>();
		for (Sample s : r.instant("hpaMax")) {
			String hpa = s.labels().getOrDefault("horizontalpodautoscaler", "");
			Long id = hpa.startsWith(TrafficQueries.HPA_NAME_PREFIX)
					? PromValues.parseId(hpa.substring(TrafficQueries.HPA_NAME_PREFIX.length())) : null;
			if (id != null && s.value() != null) {
				hpaMax.merge(id, s.value(), Math::max);
			}
		}
		boolean ksm = !r.instant("nodeLabels").isEmpty();
		boolean errorOk = r.ok("errorRps");
		boolean complete = r.allOk("nodeLabels", "rps", "errorRps", "p95", "cursorChanges", "waiting", "stock",
				"redisDown", "redisDownPoints", "apiReady", "hpaMax");
		boolean readyZero = ksm && r.ok("apiReady");
		Map<Long, EventSignals> events = new HashMap<>();
		for (Map<Long, Double> m : List.of(rps, errorRps, p95, cursor, waiting, stock, redisDown, redisDownPoints,
				apiReady, hpaMax)) {
			for (Long id : m.keySet()) {
				events.computeIfAbsent(id,
						k -> new EventSignals(errorOk ? rps.get(k) : null, errorRps.get(k), p95.get(k), cursor.get(k),
								waiting.get(k) == null ? null : Math.max(0, waiting.get(k)), stock.get(k),
								TrafficRules.redisConnection(redisDown.containsKey(k), redisDownPoints.get(k)),
								PromValues.count(apiReady.get(k), readyZero), PromValues.toInt(hpaMax.get(k)), complete));
			}
		}
		return events;
	}

	private static Map<Long, Double> byEvent(List<Sample> samples, String label) {
		Map<Long, Double> values = new HashMap<>();
		for (Sample s : samples) {
			Long id = PromValues.parseId(s.labels().get(label));
			if (id != null && s.value() != null) {
				values.merge(id, s.value(), Double::sum);
			}
		}
		return values;
	}

}
