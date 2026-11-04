package com.tetra.ops.monitoring;

import java.time.Duration;

import com.tetra.ops.tenancy.TenantIds;

/**
 * OPS-05 트래픽 모니터링 PromQL(명세 docs/ops05 5-1). 쿼리는 이 클래스 상수와 {@link EventQueries}로만 만든다.
 * 클러스터 CPU · 메모리 · 풀별 Ready 노드는 홈 {@link PromQueries} 상수를 그대로 쓴다.
 * 사용자 입력은 쿼리에 들어가지 않는다. 이벤트 쿼리의 tenant_id는 TenantIds.requireValid, event_id는 양의 정수(RDS 값)만.
 *
 * 라벨(2026-10-26 pf-prometheus 확인): node-exporter는 instance(IP:9100)뿐이라 node_uname_info{nodename}과 instance로 잇는다.
 * kube-state-metrics 노드 라벨은 label_tetra_io_pool만 허용돼 있다(인스턴스 타입 없음).
 */
final class TrafficQueries {

	// ---- B-1 노드 ----

	/** 노드별 CPU 사용률(0~1), nodename = kube 노드 이름 */
	static final String NODE_CPU = """
			1 - avg by (nodename) (rate(node_cpu_seconds_total{mode="idle"}[5m]) \
			* on(instance) group_left(nodename) node_uname_info)""";

	/** 노드별 메모리 사용률(0~1) */
	static final String NODE_MEMORY = """
			max by (nodename) ((1 - node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes) \
			* on(instance) group_left(nodename) node_uname_info)""";

	/**
	 * 노드 풀 라벨. 인스턴스 타입(label_node_kubernetes_io_instance_type)은 kube-state-metrics 허용 목록에 넣으면 같은 시리즈에 붙는다.
	 * TODO(미정): infra-k8s metricLabelsAllowlist nodes에 node.kubernetes.io/instance-type 추가 요청. 그전까지 type은 null
	 */
	static final String NODE_LABELS = "kube_node_labels";

	static final String NODE_READY = "kube_node_status_condition{condition=\"Ready\",status=\"true\"}";

	/** 노드 생성 시각(epoch 초) */
	static final String NODE_CREATED = "kube_node_created";

	/** 5분 넘게 Pending인 Pod 수(클러스터 전체). 0개면 결과 없음 */
	static final String PENDING_PODS_OVER_5M = """
			count((kube_pod_status_phase{phase="Pending"} == 1) \
			and on(namespace,pod) (time() - kube_pod_created > 300))""";

	// ---- B-2 애드온 ----

	private static final String ADDON_DEPLOYMENT_SELECTOR = """
			namespace=~"keda|kube-system|external-secrets|argocd",\
			deployment=~"keda-operator|keda-operator-metrics-apiserver|karpenter|aws-load-balancer-controller|\
			external-secrets|external-secrets-webhook|argocd-server|argocd-repo-server\"""";

	static final String ADDON_DEPLOYMENTS_AVAILABLE = "kube_deployment_status_replicas_available{"
			+ ADDON_DEPLOYMENT_SELECTOR + "}";

	static final String ADDON_DEPLOYMENTS_DESIRED = "kube_deployment_spec_replicas{" + ADDON_DEPLOYMENT_SELECTOR + "}";

	static final String ADDON_STATEFULSETS_READY = """
			kube_statefulset_status_replicas_ready{namespace="argocd",statefulset="argocd-application-controller"}""";

	static final String ADDON_STATEFULSETS_DESIRED = """
			kube_statefulset_replicas{namespace="argocd",statefulset="argocd-application-controller"}""";

	// ---- 요청 처리 공통(요청 수 · 5xx에서 빼는 uri는 홈과 같은 PromQueries.EXCLUDED_URI) ----

	/** p95를 재는 uri: 발급 /coupons/claim, 번호표 /ticket(Spring uri 템플릿, PromQL 문자열 안에서 \\{ 로 이스케이프) */
	static final String P95_URI = "uri=~\"/api/issuance/events/\\\\{eventId\\\\}/(coupons/claim|ticket)\"";

	/** KPI · 상태 요약 p95 창. 이 동안 요청이 없으면 값 없음(화면 "–") */
	static final Duration P95_WINDOW = Duration.ofMinutes(1);

	/** 그래프 창(p95 · 입장 · 입장 허용)의 최소. 그래프 간격이 더 크면 간격을 쓴다({@link #rangeWindow}) */
	static final Duration MIN_RANGE_WINDOW = Duration.ofMinutes(1);

	/**
	 * 식(선택자가 아닌 것)의 기울기(입장) · Redis 끊긴 점 수를 구할 때 subquery 간격. 그래프 격자(15초 배수)와 맞춘 15초 점이다.
	 * 처음에는 앱 지표 수집 간격(15초)과 같게 정했다. 발급 앱 수집이 1초가 되어도 그대로 둔다(2026-11-04) —
	 * 기울기 · 점 수 판정(1분 = 4점)이 15초 점 기준이고, Redis 끊김 재료(redis-exporter PodMonitor)는 15초 수집이다
	 */
	static final String SUBQUERY_STEP = "15s";

	/** KPI '현재 RPS' 요청량 창 = Prometheus 캐시 기본값(15초). 갱신 사이 1초 값을 놓치지 않는다({@link RequestRate#peak}) */
	static final Duration RPS_INSTANT_WINDOW = Duration.ofSeconds(15);

	// ---- A 상태 요약 · 드롭다운: 이벤트별 판정 재료(모든 이벤트 한 번에, event_id별) ----

	/**
	 * 5xx 비율 분모 전용(rate 1m) — 판정 재료일 뿐 화면 숫자가 아니다. 분모 · 분자 모두 rate 1m 그대로 둔다(2026-11-04 확정 4).
	 * 화면 요청량은 요청량 식({@link RequestRate}, {@link EventQueries#rps(Duration)})
	 */
	static final String RPS_BY_EVENT = "sum by (event_id) (rate(http_server_requests_seconds_count{uri!~\"%s\"}[1m]))"
		.formatted(PromQueries.EXCLUDED_URI);

	static final String ERROR_RPS_BY_EVENT = """
			sum by (event_id) (rate(http_server_requests_seconds_count{uri!~"%s",status=~"5.."}[1m]))"""
		.formatted(PromQueries.EXCLUDED_URI);

	/** 발급 · 번호표 중 큰 p95(초). 버킷 증가량은 {@link #bucketIncrease}(구간 안에서 새로 생긴 시리즈 포함) */
	static final String P95_BY_EVENT = "max by (event_id) (histogram_quantile(0.95, sum by (event_id, le, uri) ("
			+ bucketIncrease(P95_URI, P95_WINDOW) + ")))";

	/** 최근 1분 서빙 커서 변화 횟수(커서 정지 판정). 게이지는 모든 Pod가 같은 값을 내므로 max(새 Pod는 표본이 적다) */
	static final String CURSOR_CHANGES_BY_EVENT = "max by (event_id) (changes(tetra_event_cursor[1m]))";

	/** 대기 인원 ≈ 번호표 끝 − 서빙 커서, 0 하한(커서는 입장 허용 위치라 번호표 끝보다 앞설 수 있다) */
	static final String WAITING_BY_EVENT = """
			clamp_min(max by (event_id) (tetra_event_ticket_last) - on(event_id) max by (event_id) (tetra_event_cursor), 0)""";

	static final String STOCK_BY_EVENT = "max by (event_id) (tetra_event_stock_remaining)";

	/**
	 * Redis 끊김(이벤트 정리 중 제외) — 지금 끊긴 (tenant_id, event_id)만 남는다(값 0). 식은 {@link #redisDown}
	 */
	static final String REDIS_DOWN_BY_EVENT = redisDown("!=\"\"", "=~\"issuance-[0-9]+\"");

	/** 최근 1분 동안 Redis가 끊긴 15초 점 수(1~4). 끊긴 점이 없으면 시리즈 없음 */
	static final String REDIS_DOWN_POINTS_BY_EVENT = downPoints(REDIS_DOWN_BY_EVENT);

	static final String API_READY_BY_EVENT = """
			sum by (label_tetra_io_event_id) (kube_pod_labels{label_tetra_io_component="api"} \
			* on(namespace,pod) group_left() kube_pod_status_ready{condition="true"})""";

	/** KEDA가 만든 HPA(keda-hpa-issuance-<eventId>)의 최대 Pod 수 */
	static final String HPA_MAX_BY_HPA = """
			kube_horizontalpodautoscaler_spec_max_replicas{horizontalpodautoscaler=~"keda-hpa-issuance-[0-9]+"}""";

	static final String HPA_NAME_PREFIX = "keda-hpa-issuance-";

	/** kube-state-metrics 응답 여부(0/null 규칙). 노드는 항상 있다 */
	static final String KUBE_STATE_METRICS_UP = "count(kube_node_labels)";

	private TrafficQueries() {
	}

	static EventQueries forEvent(String tenantId, long eventId) {
		return new EventQueries(tenantId, eventId);
	}

	/**
	 * 구간(window) 동안 늘어난 http_server_requests_seconds_bucket. 구간 시작에 없던 시리즈는 increase 대신 지금 값(0부터 늘어난 양)을 쓴다.
	 * Micrometer 타이머 시리즈는 그 uri의 첫 요청 때 생기는데, rate · increase는 시리즈가 생길 때 이미 붙어 있던 값을 세지 못한다.
	 * 그래서 첫 요청들이 한 수집 간격(당시 15초) 안에 몰리면 증가 0 → histogram_quantile NaN → p95가 비었다
	 * (이벤트 12: 10:00~10:01 발급 5건은 안 그려지고 10:05 발급 10건은 그려짐, 2026-10-28). KEDA가 늘린 새 Pod의 첫 요청도 같다.
	 * 식은 {@link RequestRate#increaseWithBorn}(홈 누적 요청 수와 같은 모양)
	 */
	static String bucketIncrease(String selector, Duration window) {
		return RequestRate.increaseWithBorn("http_server_requests_seconds_bucket{" + selector + "}", promDuration(window));
	}

	/**
	 * Redis 끊김: redis_up 또는 redis-exporter up(PodMonitor relabel로 tenant_id · event_id)이 0인 (tenant_id, event_id).
	 * 이벤트 정리 중(그 이벤트의 issuance-<id> Deployment에 삭제 표시가 있거나 spec 0)은 뺀다 — 종료(WF4) 때 exporter가 먼저 사라지며
	 * 낸 up 0 한 점(이벤트 14, 2026-10-29 15:06:52)이 위험으로 보이던 것. Grafana 이벤트 인프라 · 장애 대응(infra-k8s
	 * event_common REDIS_DOWN)과 같은 식이다(2026-11-02).
	 * @param event event_id 비교(목록 != "", 이벤트 하나 = "<id>")
	 * @param deployment issuance Deployment 비교(목록 =~ "issuance-[0-9]+", 이벤트 하나 = "issuance-<id>")
	 */
	static String redisDown(String event, String deployment) {
		return """
				((min by (tenant_id, event_id) (redis_up{event_id%1$s}) == 0) \
				or (min by (tenant_id, event_id) (up{job=~".*redis.*",event_id%1$s}) == 0)) \
				unless on(tenant_id, event_id) label_replace(label_replace(\
				kube_deployment_deletion_timestamp{deployment%2$s} or kube_deployment_spec_replicas{deployment%2$s} == 0, \
				"event_id", "$1", "deployment", "issuance-([0-9]+)"), "tenant_id", "$1", "namespace", "tenant-(.*)")"""
			.formatted(event, deployment);
	}

	/**
	 * 최근 {@link TrafficRules#REDIS_DOWN_FOR}(1분) 동안 끊긴 점 수 — {@link #SUBQUERY_STEP}(15초) 간격 subquery.
	 * 범위는 왼쪽이 열려 있어(Prometheus 3) 1분 창의 점은 최대 4개
	 */
	static String downPoints(String redisDown) {
		return "count_over_time((" + redisDown + ")[" + promDuration(TrafficRules.REDIS_DOWN_FOR) + ":" + SUBQUERY_STEP
				+ "])";
	}

	/** 그래프 창 = max(1분, step) — p95 · 입장 · 입장 허용. step이 1분보다 크면 점 사이 요청 · 변화를 건너뛰지 않게 넓힌다 */
	static Duration rangeWindow(Duration step) {
		return step.compareTo(MIN_RANGE_WINDOW) > 0 ? step : MIN_RANGE_WINDOW;
	}

	/** PromQL 기간: 60초 배수는 "Nm", 아니면 "Ns" */
	static String promDuration(Duration d) {
		long seconds = d.toSeconds();
		return seconds % 60 == 0 ? (seconds / 60) + "m" : seconds + "s";
	}

	/**
	 * 이벤트 하나의 쿼리(C·D). tenant-<t> 네임스페이스의 Pod는 kube_pod_labels{label_tetra_io_event_id}로 이 이벤트만 거른다.
	 * 앱 · Redis 지표는 event_id 라벨(Micrometer 태그 · PodMonitor relabel).
	 */
	static final class EventQueries {

		private final String ns;

		private final String e;

		private EventQueries(String tenantId, long eventId) {
			if (eventId <= 0) {
				throw new IllegalArgumentException("event_id가 양수가 아닙니다.");
			}
			this.ns = PromQueries.namespace(TenantIds.requireValid(tenantId));
			this.e = Long.toString(eventId);
		}

		private String podsOf(String component) {
			return "kube_pod_labels{namespace=\"%s\",label_tetra_io_event_id=\"%s\",label_tetra_io_component=%s}"
				.formatted(ns, e, component);
		}

		private String api() {
			return podsOf("\"api\"");
		}

		private String redis() {
			return podsOf("\"redis\"");
		}

		// ---- 요청 처리 ----

		/**
		 * 초당 요청(액추에이터 · health 제외, {@link PromQueries#EXCLUDED_URI}) — 요청량 식({@link RequestRate}) 창 window 안 1초 값의 최대.
		 * KPI는 {@link TrafficQueries#RPS_INSTANT_WINDOW}, 그래프는 그래프 간격(같아야 1초 값이 빠짐없이 어느 한 점에 들어간다)
		 */
		String rps(Duration window) {
			String selector = "event_id=\"%s\",uri!~\"%s\"".formatted(e, PromQueries.EXCLUDED_URI);
			return RequestRate.peak("sum(" + RequestRate.byPod(selector, "event_id=\"" + e + "\"", "pod") + ")", window);
		}

		/** 5xx 비율 분모 전용(rate 1m) — 분자 {@link #errorRps()}와 같은 창. 화면 요청량이 아니다(2026-11-04 확정 4) */
		String ratioRps() {
			return "sum(rate(http_server_requests_seconds_count{event_id=\"%s\",uri!~\"%s\"}[1m]))"
				.formatted(e, PromQueries.EXCLUDED_URI);
		}

		/** 5xx 초당 요청(rate 1m) — 5xx 비율 분자 */
		String errorRps() {
			return "sum(rate(http_server_requests_seconds_count{event_id=\"%s\",uri!~\"%s\",status=~\"5..\"}[1m]))"
				.formatted(e, PromQueries.EXCLUDED_URI);
		}

		/** uri별 p95(초), 최근 1분(KPI 순간값). 발급 /coupons/claim, 번호표 /ticket */
		String p95ByUri() {
			return p95ByUri(P95_WINDOW);
		}

		/** uri별 p95(초), 창 window(그래프는 {@link TrafficQueries#rangeWindow}) */
		String p95ByUri(Duration window) {
			return "histogram_quantile(0.95, sum by (le, uri) ("
					+ bucketIncrease("event_id=\"" + e + "\"," + P95_URI, window) + "))";
		}

		// ---- 진행(issuance-api EventStateGauges, 모든 Pod가 같은 값 → max) ----

		private String cursor() {
			return "max(tetra_event_cursor{event_id=\"%s\"})".formatted(e);
		}

		private String ticketLast() {
			return "max(tetra_event_ticket_last{event_id=\"%s\"})".formatted(e);
		}

		/**
		 * 입장이 허용된 번호표 수 = min(서빙 커서, 번호표 끝). 커서는 입장 허용 위치라 번호표 끝보다 앞설 수 있다
		 * (테스트 13: 번호표 끝 21, 커서 6,300, 2026-10-28). PromQL에는 두 벡터의 min이 없어 비교 필터로 작은 쪽을 남긴다 —
		 * 둘 중 하나라도 없거나 NaN이면 결과 없음(번호표 끝만으로 채우지 않는다)
		 */
		private String admitted() {
			return "(" + cursor() + " <= " + ticketLast() + ") or (" + ticketLast() + " < " + cursor() + ")";
		}

		/**
		 * 입장(명/초, 추정) — 입장이 허용된 번호표 수의 window 기울기. 번호표를 받은 뒤 떠난 사람도 들어간다.
		 * 식이라 subquery로 점을 만든다({@link TrafficQueries#SUBQUERY_STEP}). 그래프 창은 {@link TrafficQueries#rangeWindow}
		 */
		String admittedPerSec(Duration window) {
			return "deriv((" + admitted() + ")[" + promDuration(window) + ":" + SUBQUERY_STEP + "])";
		}

		/**
		 * 입장 허용(커서 전진, 번호/초) — 서빙 커서 tetra_event_cursor의 window 기울기. 요청 수가 아니다.
		 * 커서는 폴링 요청이 올 때 창마다 step씩 오르는 입장 허용 위치라(issuance-api CursorStore) 번호표 끝보다 앞설 수 있다 —
		 * 실제 입장은 {@link #admittedPerSec}. 게이지라 rate가 아니라 deriv
		 */
		String cursorPerSec(Duration window) {
			return "max(deriv(tetra_event_cursor{event_id=\"%s\"}[%s]))".formatted(e, promDuration(window));
		}

		String cursorChanges() {
			return "max(changes(tetra_event_cursor{event_id=\"%s\"}[1m]))".formatted(e);
		}

		/** 대기 인원 ≈ 번호표 끝 − 서빙 커서, 0 하한(커서가 앞서면 대기 없음). 가공에서도 0 하한을 한 번 더 건다 */
		String waiting() {
			return "clamp_min(" + ticketLast() + " - " + cursor() + ", 0)";
		}

		String stock() {
			return "max(tetra_event_stock_remaining{event_id=\"%s\"})".formatted(e);
		}

		// ---- 앱 자원(api Pod) ----

		String apiReady() {
			return "sum(kube_pod_status_ready{namespace=\"%s\",condition=\"true\"} * on(namespace,pod) group_left() %s)"
				.formatted(ns, api());
		}

		String apiPending() {
			return "sum(kube_pod_status_phase{namespace=\"%s\",phase=\"Pending\"} * on(namespace,pod) group_left() %s)"
				.formatted(ns, api());
		}

		private String hpa(String metric) {
			return "max(%s{namespace=\"%s\",horizontalpodautoscaler=\"%s%s\"})".formatted(metric, ns, HPA_NAME_PREFIX, e);
		}

		String hpaMin() {
			return hpa("kube_horizontalpodautoscaler_spec_min_replicas");
		}

		String hpaMax() {
			return hpa("kube_horizontalpodautoscaler_spec_max_replicas");
		}

		/** api Pod CPU 합계(코어) */
		String apiCpu() {
			return """
					sum(sum by (namespace,pod) (rate(container_cpu_usage_seconds_total\
					{namespace="%s",container!="",container!="POD"}[1m])) * on(namespace,pod) group_left() %s)"""
				.formatted(ns, api());
		}

		String apiMemory() {
			return """
					sum(sum by (namespace,pod) (container_memory_working_set_bytes\
					{namespace="%s",container!="",container!="POD"}) * on(namespace,pod) group_left() %s)"""
				.formatted(ns, api());
		}

		private String apiRequests(String resource) {
			return "sum(kube_pod_container_resource_requests{namespace=\"%s\",resource=\"%s\"} * on(namespace,pod) group_left() %s)"
				.formatted(ns, resource, api());
		}

		String apiCpuRequest() {
			return apiRequests("cpu");
		}

		String apiMemoryRequest() {
			return apiRequests("memory");
		}

		// ---- Redis(maxmemory 1536mb, noeviction) ----

		String redisMemoryUsed() {
			return "max(redis_memory_used_bytes{event_id=\"%s\"})".formatted(e);
		}

		String redisMemoryMax() {
			return "max(redis_memory_max_bytes{event_id=\"%s\"})".formatted(e);
		}

		String redisMemoryRatio() {
			return redisMemoryUsed() + " / " + redisMemoryMax();
		}

		String redisOps() {
			return "sum(rate(redis_commands_processed_total{event_id=\"%s\"}[1m]))".formatted(e);
		}

		String redisClients() {
			return "max(redis_connected_clients{event_id=\"%s\"})".formatted(e);
		}

		/** Redis 끊김(이벤트 정리 중 제외) — 지금 끊겼으면 시리즈 하나({@link TrafficQueries#redisDown}) */
		String redisDown() {
			return TrafficQueries.redisDown("=\"%s\"".formatted(e), "=\"issuance-%s\"".formatted(e));
		}

		/** 최근 1분 동안 Redis가 끊긴 15초 점 수(1~4). 끊긴 점이 없으면 시리즈 없음 */
		String redisDownPoints() {
			return downPoints(redisDown());
		}

		String redisRestarts() {
			return """
					sum(kube_pod_container_status_restarts_total{namespace="%s",container="redis"} \
					* on(namespace,pod) group_left() %s)""".formatted(ns, redis());
		}

		/** Redis CPU 사용률(0~1) = 사용 코어 ÷ limit 코어 */
		String redisCpuRatio() {
			return """
					sum(sum by (namespace,pod) (rate(container_cpu_usage_seconds_total{namespace="%s",container="redis"}[1m])) \
					* on(namespace,pod) group_left() %s) / sum(kube_pod_container_resource_limits\
					{namespace="%s",resource="cpu",container="redis"} * on(namespace,pod) group_left() %s)"""
				.formatted(ns, redis(), ns, redis());
		}

		// ---- 준비(WF2-2)에 걸린 시간 ----

		/**
		 * api · Redis Pod 중 가장 이른 시작부터 가장 늦은 Ready까지(초). 입장(OPEN 로그) 시각에 평가한다 —
		 * 그 뒤 KEDA가 늘린 Pod는 들어가지 않고, 종료된 이벤트도 보존 기간(7일) 안이면 계산된다.
		 */
		String prepareSeconds() {
			String pods = podsOf("~\"api|redis\"");
			return "max(kube_pod_status_ready_time{namespace=\"%s\"} * on(namespace,pod) group_left() %s)"
				.formatted(ns, pods) + " - "
					+ "min(kube_pod_start_time{namespace=\"%s\"} * on(namespace,pod) group_left() %s)".formatted(ns, pods);
		}

	}

}
