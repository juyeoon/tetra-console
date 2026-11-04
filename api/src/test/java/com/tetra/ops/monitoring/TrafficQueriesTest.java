package com.tetra.ops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

/** OPS-05 이벤트 쿼리: tenant_id는 ^[a-z0-9]{12}$, event_id는 양수만. uri 정규식은 PromQL 문자열 안에서 \\{ 로 이스케이프 */
class TrafficQueriesTest {

	@Test
	void eventQueriesUseValidatedIdsOnly() {
		TrafficQueries.EventQueries q = TrafficQueries.forEvent("aaaaaaaaaaaa", 7);
		assertThat(q.ratioRps()).isEqualTo(
				"sum(rate(http_server_requests_seconds_count{event_id=\"7\",uri!~\"/actuator.*|/api/issuance/health\"}[1m]))");
		assertThat(q.apiReady()).isEqualTo("sum(kube_pod_status_ready{namespace=\"tenant-aaaaaaaaaaaa\",condition=\"true\"}"
				+ " * on(namespace,pod) group_left() kube_pod_labels{namespace=\"tenant-aaaaaaaaaaaa\","
				+ "label_tetra_io_event_id=\"7\",label_tetra_io_component=\"api\"})");
		assertThat(q.hpaMax()).isEqualTo("max(kube_horizontalpodautoscaler_spec_max_replicas"
				+ "{namespace=\"tenant-aaaaaaaaaaaa\",horizontalpodautoscaler=\"keda-hpa-issuance-7\"})");
		assertThat(q.waiting()).isEqualTo(
				"clamp_min(max(tetra_event_ticket_last{event_id=\"7\"}) - max(tetra_event_cursor{event_id=\"7\"}), 0)");
		assertThat(q.prepareSeconds()).contains("label_tetra_io_component=~\"api|redis\"");

		for (String bad : List.of("aaaaaaaaaaa", "AAAAAAAAAAAA", "aaaaaaaaaaa\"", "aaaa|.*|aaaa")) {
			assertThatThrownBy(() -> TrafficQueries.forEvent(bad, 7)).isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> TrafficQueries.forEvent("aaaaaaaaaaaa", 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> TrafficQueries.forEvent("aaaaaaaaaaaa", -1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void redisDownSkipsCleanupAndCountsOneMinute() {
		// Grafana 이벤트 인프라 · 장애 대응과 같은 식(2026-11-02). 이벤트 하나는 event_id · issuance-<id>만 바꾼다
		TrafficQueries.EventQueries q = TrafficQueries.forEvent("aaaaaaaaaaaa", 7);
		String one = "((min by (tenant_id, event_id) (redis_up{event_id=\"7\"}) == 0)"
				+ " or (min by (tenant_id, event_id) (up{job=~\".*redis.*\",event_id=\"7\"}) == 0))"
				+ " unless on(tenant_id, event_id) label_replace(label_replace("
				+ "kube_deployment_deletion_timestamp{deployment=\"issuance-7\"}"
				+ " or kube_deployment_spec_replicas{deployment=\"issuance-7\"} == 0,"
				+ " \"event_id\", \"$1\", \"deployment\", \"issuance-([0-9]+)\"), \"tenant_id\", \"$1\", \"namespace\", \"tenant-(.*)\")";
		assertThat(q.redisDown()).isEqualTo(one);
		assertThat(q.redisDownPoints()).isEqualTo("count_over_time((" + one + ")[1m:15s])");
		assertThat(TrafficQueries.REDIS_DOWN_BY_EVENT)
			.isEqualTo(one.replace("event_id=\"7\"", "event_id!=\"\"").replace("deployment=\"issuance-7\"",
					"deployment=~\"issuance-[0-9]+\""));
		assertThat(TrafficQueries.REDIS_DOWN_POINTS_BY_EVENT)
			.isEqualTo("count_over_time((" + TrafficQueries.REDIS_DOWN_BY_EVENT + ")[1m:15s])");
	}

	@Test
	void p95UriRegexIsEscapedForPromqlString() {
		String expected = "uri=~\"/api/issuance/events/\\\\{eventId\\\\}/(coupons/claim|ticket)\"";
		assertThat(TrafficQueries.forEvent("aaaaaaaaaaaa", 7).p95ByUri()).contains(expected);
		assertThat(TrafficQueries.P95_BY_EVENT).contains(expected);
	}

	@Test
	void requestRateIsGrafanaFormula() {
		// 요청량 식 X(번호표_순간RPS_조사 4절 원문, Grafana 이벤트 대시보드와 같은 식): Pod별 irate + 처음 생긴 시리즈의 첫 값 ÷ 그 Pod 수집 간격
		String req = "http_server_requests_seconds_count{event_id=\"7\",uri!~\"/actuator.*|/api/issuance/health\"}";
		String x = "sum by (pod) (irate(" + req + "[1m]) or ((" + req + " unless " + req + " offset 1m)"
				+ " / on(pod) group_left() idelta(process_uptime_seconds{event_id=\"7\"}[1m])))";
		TrafficQueries.EventQueries q = TrafficQueries.forEvent("aaaaaaaaaaaa", 7);
		// KPI = 최근 15초(캐시와 같음) 안 1초 값의 최대, 그래프 = 창이 그래프 간격
		assertThat(q.rps(TrafficQueries.RPS_INSTANT_WINDOW)).isEqualTo("max_over_time((sum(" + x + "))[15s:1s])");
		assertThat(q.rps(Duration.ofSeconds(30))).isEqualTo("max_over_time((sum(" + x + "))[30s:1s])");
		assertThat(q.rps(Duration.ofMinutes(2))).endsWith(")[2m:1s])");
		// 입장 기울기 · Redis 끊긴 점은 15초 서브쿼리 그대로(발급 앱 수집이 1초가 되어도)
		assertThat(TrafficQueries.SUBQUERY_STEP).isEqualTo("15s");
	}

	@Test
	void requestCountsExcludeHealth() {
		// 기동 웜업이 Pod마다 부르는 health는 RPS · 5xx에서 뺀다(상태 요약 판정 재료도 같은 식)
		String excluded = "uri!~\"/actuator.*|/api/issuance/health\"";
		TrafficQueries.EventQueries q = TrafficQueries.forEvent("aaaaaaaaaaaa", 7);
		assertThat(q.rps(TrafficQueries.RPS_INSTANT_WINDOW)).contains(excluded).doesNotContain("sum(rate(");
		// 5xx 비율은 rate 1m ÷ rate 1m 그대로(분모만 요청량 식으로 바꾸지 않음, 2026-11-04 확정 4)
		assertThat(q.errorRps()).isEqualTo("sum(rate(http_server_requests_seconds_count{event_id=\"7\","
				+ excluded + ",status=~\"5..\"}[1m]))");
		assertThat(q.ratioRps()).isEqualTo("sum(rate(http_server_requests_seconds_count{event_id=\"7\"," + excluded + "}[1m]))");
		assertThat(TrafficQueries.RPS_BY_EVENT)
			.isEqualTo("sum by (event_id) (rate(http_server_requests_seconds_count{" + excluded + "}[1m]))");
		assertThat(TrafficQueries.ERROR_RPS_BY_EVENT).isEqualTo(
				"sum by (event_id) (rate(http_server_requests_seconds_count{" + excluded + ",status=~\"5..\"}[1m]))");
	}

	@Test
	void p95CountsSeriesBornInWindow() {
		// 첫 요청으로 생긴 시리즈는 rate가 못 보므로 구간 시작에 없던 시리즈는 지금 값을 쓴다(이벤트 12의 첫 발급 5건)
		String bucket = "http_server_requests_seconds_bucket{event_id=\"7\","
				+ "uri=~\"/api/issuance/events/\\\\{eventId\\\\}/(coupons/claim|ticket)\"}";
		String born = "(" + bucket + " unless " + bucket + " offset 1m)";
		assertThat(TrafficQueries.forEvent("aaaaaaaaaaaa", 7).p95ByUri()).isEqualTo("histogram_quantile(0.95, sum by (le, uri) ("
				+ "(increase(" + bucket + "[1m]) unless " + born + ") or " + born + "))");
		assertThat(TrafficQueries.P95_BY_EVENT).startsWith("max by (event_id) (histogram_quantile(0.95, sum by (event_id, le, uri) (")
			.contains("unless http_server_requests_seconds_bucket{uri=~");

		// 그래프 창 = max(1분, step): step이 1분보다 크면 점 사이 요청을 건너뛰지 않게 넓힌다
		assertThat(TrafficQueries.rangeWindow(Duration.ofSeconds(15))).isEqualTo(Duration.ofMinutes(1));
		assertThat(TrafficQueries.rangeWindow(Duration.ofSeconds(75))).isEqualTo(Duration.ofSeconds(75));
		assertThat(TrafficQueries.forEvent("aaaaaaaaaaaa", 7).p95ByUri(Duration.ofSeconds(75)))
			.contains("[75s]) unless").contains(" offset 75s)");
		assertThat(TrafficQueries.forEvent("aaaaaaaaaaaa", 7).p95ByUri(Duration.ofMinutes(2))).contains("[2m]) unless");
	}

	@Test
	void admissionIsMinOfCursorAndTicketLast() {
		// 입장 = min(커서, 번호표 끝)의 기울기. 커서는 입장 허용 위치라 번호표 끝보다 앞설 수 있다(테스트 13: 21 / 6,300)
		TrafficQueries.EventQueries q = TrafficQueries.forEvent("aaaaaaaaaaaa", 7);
		String c = "max(tetra_event_cursor{event_id=\"7\"})";
		String t = "max(tetra_event_ticket_last{event_id=\"7\"})";
		assertThat(q.admittedPerSec(Duration.ofMinutes(1)))
			.isEqualTo("deriv(((" + c + " <= " + t + ") or (" + t + " < " + c + "))[1m:15s])");
		// 입장 허용(커서 전진)은 지금까지의 커서 기울기 그대로
		assertThat(q.cursorPerSec(Duration.ofMinutes(1))).isEqualTo("max(deriv(tetra_event_cursor{event_id=\"7\"}[1m]))");

		// 두 선 모두 창 = max(1분, 그래프 간격): 간격 > 1분이면 간격, 그보다 작으면 1분
		Duration step = Duration.ofSeconds(75);
		assertThat(q.admittedPerSec(TrafficQueries.rangeWindow(step))).endsWith("))[75s:15s])");
		assertThat(q.cursorPerSec(TrafficQueries.rangeWindow(step)))
			.isEqualTo("max(deriv(tetra_event_cursor{event_id=\"7\"}[75s]))");
		assertThat(q.admittedPerSec(TrafficQueries.rangeWindow(Duration.ofMinutes(2)))).endsWith("))[2m:15s])");
		assertThat(q.cursorPerSec(TrafficQueries.rangeWindow(Duration.ofSeconds(30)))).contains("[1m]))");

		// 대기 인원 0 하한(커서가 앞서면 대기 없음) — 상태 요약 판정 재료도 같은 식
		assertThat(TrafficQueries.WAITING_BY_EVENT).isEqualTo("clamp_min(max by (event_id) (tetra_event_ticket_last)"
				+ " - on(event_id) max by (event_id) (tetra_event_cursor), 0)");
	}

	@Test
	void nodeQueriesJoinNodeExporterByInstance() {
		assertThat(TrafficQueries.NODE_CPU).isEqualTo("1 - avg by (nodename) (rate(node_cpu_seconds_total{mode=\"idle\"}[5m])"
				+ " * on(instance) group_left(nodename) node_uname_info)");
		assertThat(TrafficQueries.ADDON_DEPLOYMENTS_DESIRED).isEqualTo("kube_deployment_spec_replicas{"
				+ "namespace=~\"keda|kube-system|external-secrets|argocd\",deployment=~\"keda-operator|"
				+ "keda-operator-metrics-apiserver|karpenter|aws-load-balancer-controller|external-secrets|"
				+ "external-secrets-webhook|argocd-server|argocd-repo-server\"}");
	}

}
