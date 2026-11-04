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

	/**
	 * 실시간 트래픽 그래프 간격 = 요청량 창({@link RequestRate#peak}). 같아야 1초 값이 빠짐없이 어느 한 점에 들어간다.
	 * instant(지금 값)도 같은 창 — 홈 캐시(15초)와 같아 갱신 사이 값을 놓치지 않는다
	 */
	static final Duration TRAFFIC_STEP = Duration.ofSeconds(15);

	/** 테넌트별 초당 요청 수(요청량 식 {@link RequestRate}). range(최근 30분)와 instant(테넌트 별 처리 현황 rps) 둘 다 */
	static final String RPS_BY_TENANT = RequestRate.peak(
			"sum by (tenant_id) (" + RequestRate.byPod("uri!~\"" + EXCLUDED_URI + "\"", "", "pod, tenant_id") + ")",
			TRAFFIC_STEP);

	static final String RPS_TOTAL = RequestRate
		.peak("sum(" + RequestRate.byPod("uri!~\"" + EXCLUDED_URI + "\"", "", "pod") + ")", TRAFFIC_STEP);

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
	 * 구간 안에서 처음 생긴 시리즈는 첫 값부터 센다({@link RequestRate#increaseWithBorn}) — increase만 쓰면 이벤트 20의
	 * 번호표 몰림(13:20에 생긴 시리즈의 첫 값 약 1.1만 건)이 빠졌다(2026-11-04)
	 */
	private static final String REQUESTS_SERIES = "http_server_requests_seconds_count{tenant_id=\"%s\",uri!~\"%s\"}";

	static final Duration MIN_RANGE = Duration.ofSeconds(60);

	static final Duration MAX_RANGE = Duration.ofDays(7);

	private PromQueries() {
	}

	/**
	 * 테넌트별 5xx 초당 요청 수 — 홈 테넌트 흐름의 완료 · 실패 점 나누기(errorRps ÷ rps, 화면 효과뿐)라 {@link #RPS_BY_TENANT}와
	 * 같은 요청량 식(분모 · 분자가 같은 구간). 판정에는 쓰지 않는다. tenantIds = 검증한 tenant_id
	 */
	static String errorRpsByTenant(Collection<String> tenantIds) {
		if (tenantIds.isEmpty()) {
			throw new IllegalArgumentException("tenant_id가 없습니다.");
		}
		String alternatives = tenantIds.stream().map(TenantIds::requireValid).sorted().collect(Collectors.joining("|"));
		String tenants = "tenant_id=~\"" + alternatives + "\"";
		return RequestRate.peak("sum by (tenant_id) ("
				+ RequestRate.byPod(tenants + ",uri!~\"" + EXCLUDED_URI + "\",status=~\"5..\"", tenants, "pod, tenant_id") + ")",
				TRAFFIC_STEP);
	}

	/** range는 60초 ~ 7일로 자른다 */
	static String requestsByStatusSince(String tenantId, Duration range) {
		long seconds = Math.clamp(range.toSeconds(), MIN_RANGE.toSeconds(), MAX_RANGE.toSeconds());
		String series = REQUESTS_SERIES.formatted(TenantIds.requireValid(tenantId), EXCLUDED_URI);
		return "sum by (status) (" + RequestRate.increaseWithBorn(series, seconds + "s") + ")";
	}

	static String namespace(String tenantId) {
		return "tenant-" + TenantIds.requireValid(tenantId);
	}

}
