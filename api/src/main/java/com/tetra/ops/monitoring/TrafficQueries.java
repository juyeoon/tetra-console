package com.tetra.ops.monitoring;

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

	// ---- A 상태 요약 · 드롭다운: 이벤트별 판정 재료(모든 이벤트 한 번에, event_id별) ----

	static final String RPS_BY_EVENT = """
			sum by (event_id) (rate(http_server_requests_seconds_count{uri!~"/actuator.*"}[1m]))""";

	static final String ERROR_RPS_BY_EVENT = """
			sum by (event_id) (rate(http_server_requests_seconds_count{uri!~"/actuator.*",status=~"5.."}[1m]))""";

	/** 발급 · 번호표 중 큰 p95(초) */
	static final String P95_BY_EVENT = """
			max by (event_id) (histogram_quantile(0.95, sum by (event_id, le, uri) \
			(rate(http_server_requests_seconds_bucket{uri=~"/api/issuance/events/\\\\{eventId\\\\}/(coupons/claim|ticket)"}[1m]))))""";

	/** 최근 1분 커서 변화 횟수. 게이지는 모든 Pod가 같은 값을 내므로 max(새 Pod는 표본이 적다) */
	static final String CURSOR_CHANGES_BY_EVENT = "max by (event_id) (changes(tetra_event_cursor[1m]))";

	/** 대기 인원 ≈ 마지막 번호표 − 서빙 커서 */
	static final String WAITING_BY_EVENT = """
			max by (event_id) (tetra_event_ticket_last) - on(event_id) max by (event_id) (tetra_event_cursor)""";

	static final String STOCK_BY_EVENT = "max by (event_id) (tetra_event_stock_remaining)";

	static final String REDIS_UP_BY_EVENT = "min by (event_id) (redis_up)";

	/** redis-exporter 스크레이프 상태(PodMonitor relabel로 event_id) */
	static final String REDIS_EXPORTER_UP_BY_EVENT = "min by (event_id) (up{job=~\".*redis.*\"})";

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

		String rps() {
			return "sum(rate(http_server_requests_seconds_count{event_id=\"%s\",uri!~\"/actuator.*\"}[1m]))".formatted(e);
		}

		String errorRps() {
			return "sum(rate(http_server_requests_seconds_count{event_id=\"%s\",uri!~\"/actuator.*\",status=~\"5..\"}[1m]))"
				.formatted(e);
		}

		/** uri별 p95(초). 발급 /coupons/claim, 번호표 /ticket */
		String p95ByUri() {
			return """
					histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket\
					{event_id="%s",uri=~"/api/issuance/events/\\\\{eventId\\\\}/(coupons/claim|ticket)"}[1m])))"""
				.formatted(e);
		}

		// ---- 진행(issuance-api EventStateGauges, 모든 Pod가 같은 값 → max) ----

		/** 커서 도달/초 — 게이지라 rate가 아니라 deriv */
		String cursorPerSec() {
			return "max(deriv(tetra_event_cursor{event_id=\"%s\"}[1m]))".formatted(e);
		}

		String cursorChanges() {
			return "max(changes(tetra_event_cursor{event_id=\"%s\"}[1m]))".formatted(e);
		}

		String waiting() {
			return "max(tetra_event_ticket_last{event_id=\"%s\"}) - max(tetra_event_cursor{event_id=\"%s\"})".formatted(e, e);
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

		String redisUp() {
			return "min(redis_up{event_id=\"%s\"})".formatted(e);
		}

		String redisExporterUp() {
			return "min(up{job=~\".*redis.*\",event_id=\"%s\"})".formatted(e);
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
