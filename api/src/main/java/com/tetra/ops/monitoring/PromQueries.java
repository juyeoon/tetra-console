package com.tetra.ops.monitoring;

import java.time.Duration;
import java.util.Collection;
import java.util.stream.Collectors;

import com.tetra.ops.tenancy.TenantIds;

/**
 * 홈 모니터링이 쓰는 PromQL 전부(10-24 · 10-26 회의 확정). 쿼리는 이 클래스 상수로만 둔다.
 * 사용자 입력은 쿼리에 들어가지 않는다. tenant_id는 RDS 값이라도 TenantIds.requireValid(^[a-z0-9]{12}$)를 통과한 것만 넣는다.
 * 요청 수 지표 http_server_requests_seconds_count는 발급 API 것(모든 지표에 tenant_id · event_id 라벨).
 * KEDA 식에서 health를 더 뺀다({@link #EXCLUDED_URI}, 이벤트 상세 {@link TrafficQueries}와 같은 필터).
 */
final class PromQueries {

	/**
	 * 요청 수 · 5xx에서 빼는 uri(홈 · 이벤트 상세 공통): 액추에이터와 발급 API health(사용자 요청이 아니라 상태 확인).
	 * 기동 웜업 요청은 issuance-api가 지표에서 뺀다(I3) — Console은 따로 거르지 않는다
	 */
	static final String EXCLUDED_URI = "/actuator.*|/api/issuance/health";

	// ---- 클러스터 사용량 (클러스터 전체, 실사용률) ----

	static final String CPU_USAGE = "1 - avg(rate(node_cpu_seconds_total{mode=\"idle\"}[5m]))";

	static final String MEMORY_USAGE = "1 - sum(node_memory_MemAvailable_bytes) / sum(node_memory_MemTotal_bytes)";

	/** Ready 노드 수, 풀(label_tetra_io_pool = app | redis | system)별 */
	static final String READY_NODES_BY_POOL = """
			count by (label_tetra_io_pool) (kube_node_labels * on(node) group_left() \
			(kube_node_status_condition{condition="Ready",status="true"} == 1))""";

	/** Ready Pod 수, component(api | redis)별 */
	static final String READY_PODS_BY_COMPONENT = """
			sum by (label_tetra_io_component) (kube_pod_labels{label_tetra_io_component=~"api|redis"} \
			* on(namespace,pod) group_left() kube_pod_status_ready{condition="true"})""";

	/** 전체 Pod 수(phase Pending | Running), component별. phase 시리즈가 Pod마다 여럿이라 먼저 Pod별로 합친다 */
	static final String ACTIVE_PODS_BY_COMPONENT = """
			sum by (label_tetra_io_component) (kube_pod_labels{label_tetra_io_component=~"api|redis"} \
			* on(namespace,pod) group_left() sum by (namespace,pod) (kube_pod_status_phase{phase=~"Pending|Running"}))""";

	// ---- 실시간 트래픽 · 테넌트 RPS ----

	/** 테넌트별 초당 요청 수. range(최근 30분)와 instant(테넌트 별 처리 현황 rps) 둘 다 */
	static final String RPS_BY_TENANT = """
			sum by (tenant_id) (rate(http_server_requests_seconds_count{uri!~"%s"}[1m]))""".formatted(EXCLUDED_URI);

	static final String RPS_TOTAL = """
			sum(rate(http_server_requests_seconds_count{uri!~"%s"}[1m]))""".formatted(EXCLUDED_URI);

	/** 테넌트별 5xx 초당 요청 수. %s = 검증한 tenant_id를 |로 이은 것, {@link #EXCLUDED_URI} */
	private static final String ERROR_RPS_BY_TENANT = """
			sum by (tenant_id) (rate(http_server_requests_seconds_count{tenant_id=~"%s",uri!~"%s",status=~"5.."}[1m]))""";

	// ---- 테넌트 별 처리 현황: 네임스페이스 tenant-<tenant_id> ----

	/** component=api Ready Pod 수, 네임스페이스별 */
	static final String API_READY_PODS_BY_NAMESPACE = """
			sum by (namespace) (kube_pod_labels{label_tetra_io_component="api",namespace=~"tenant-.*"} \
			* on(namespace,pod) group_left() kube_pod_status_ready{condition="true"})""";

	/**
	 * HPA 최대 Pod 수, 네임스페이스별 합.
	 * 네임스페이스에 api 말고 다른 HPA가 생기면 그 값도 더해진다(지금은 KEDA가 만든 api HPA뿐).
	 */
	static final String HPA_MAX_BY_NAMESPACE = """
			sum by (namespace) (kube_horizontalpodautoscaler_spec_max_replicas{namespace=~"tenant-.*"})""";

	/**
	 * 입장 열림부터 누적 요청 수, status별. 5xx는 실패, 나머지는 처리 완료로 나눈다.
	 * 앱(발급 API)까지 온 요청만 센다 — ALB가 앞에서 낸 5xx는 잡히지 않는다.
	 * %s = 검증한 tenant_id · {@link #EXCLUDED_URI}, %d = 범위(초)
	 */
	private static final String REQUESTS_BY_STATUS_SINCE = """
			sum by (status) (increase(http_server_requests_seconds_count{tenant_id="%s",uri!~"%s"}[%ds]))""";

	static final Duration MIN_RANGE = Duration.ofSeconds(60);

	static final Duration MAX_RANGE = Duration.ofDays(7);

	private PromQueries() {
	}

	static String errorRpsByTenant(Collection<String> tenantIds) {
		if (tenantIds.isEmpty()) {
			throw new IllegalArgumentException("tenant_id가 없습니다.");
		}
		String alternatives = tenantIds.stream().map(TenantIds::requireValid).sorted().collect(Collectors.joining("|"));
		return ERROR_RPS_BY_TENANT.formatted(alternatives, EXCLUDED_URI);
	}

	/** range는 60초 ~ 7일로 자른다 */
	static String requestsByStatusSince(String tenantId, Duration range) {
		long seconds = Math.clamp(range.toSeconds(), MIN_RANGE.toSeconds(), MAX_RANGE.toSeconds());
		return REQUESTS_BY_STATUS_SINCE.formatted(TenantIds.requireValid(tenantId), EXCLUDED_URI, seconds);
	}

	static String namespace(String tenantId) {
		return "tenant-" + TenantIds.requireValid(tenantId);
	}

}
