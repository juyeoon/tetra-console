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
		assertThat(q.rps()).isEqualTo(
				"sum(rate(http_server_requests_seconds_count{event_id=\"7\",uri!~\"/actuator.*|/api/issuance/health\"}[1m]))");
		assertThat(q.apiReady()).isEqualTo("sum(kube_pod_status_ready{namespace=\"tenant-aaaaaaaaaaaa\",condition=\"true\"}"
				+ " * on(namespace,pod) group_left() kube_pod_labels{namespace=\"tenant-aaaaaaaaaaaa\","
				+ "label_tetra_io_event_id=\"7\",label_tetra_io_component=\"api\"})");
		assertThat(q.hpaMax()).isEqualTo("max(kube_horizontalpodautoscaler_spec_max_replicas"
				+ "{namespace=\"tenant-aaaaaaaaaaaa\",horizontalpodautoscaler=\"keda-hpa-issuance-7\"})");
		assertThat(q.waiting()).isEqualTo(
				"max(tetra_event_ticket_last{event_id=\"7\"}) - max(tetra_event_cursor{event_id=\"7\"})");
		assertThat(q.prepareSeconds()).contains("label_tetra_io_component=~\"api|redis\"");

		for (String bad : List.of("aaaaaaaaaaa", "AAAAAAAAAAAA", "aaaaaaaaaaa\"", "aaaa|.*|aaaa")) {
			assertThatThrownBy(() -> TrafficQueries.forEvent(bad, 7)).isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> TrafficQueries.forEvent("aaaaaaaaaaaa", 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> TrafficQueries.forEvent("aaaaaaaaaaaa", -1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void p95UriRegexIsEscapedForPromqlString() {
		String expected = "uri=~\"/api/issuance/events/\\\\{eventId\\\\}/(coupons/claim|ticket)\"";
		assertThat(TrafficQueries.forEvent("aaaaaaaaaaaa", 7).p95ByUri()).contains(expected);
		assertThat(TrafficQueries.P95_BY_EVENT).contains(expected);
	}

	@Test
	void requestCountsExcludeHealth() {
		// 기동 웜업이 Pod마다 부르는 health는 RPS · 5xx에서 뺀다(상태 요약 판정 재료도 같은 식)
		String excluded = "uri!~\"/actuator.*|/api/issuance/health\"";
		TrafficQueries.EventQueries q = TrafficQueries.forEvent("aaaaaaaaaaaa", 7);
		assertThat(q.errorRps()).isEqualTo("sum(rate(http_server_requests_seconds_count{event_id=\"7\","
				+ excluded + ",status=~\"5..\"}[1m]))");
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
		assertThat(TrafficQueries.p95RangeWindow(Duration.ofSeconds(15))).isEqualTo(Duration.ofMinutes(1));
		assertThat(TrafficQueries.p95RangeWindow(Duration.ofSeconds(75))).isEqualTo(Duration.ofSeconds(75));
		assertThat(TrafficQueries.forEvent("aaaaaaaaaaaa", 7).p95ByUri(Duration.ofSeconds(75)))
			.contains("[75s]) unless").contains(" offset 75s)");
		assertThat(TrafficQueries.forEvent("aaaaaaaaaaaa", 7).p95ByUri(Duration.ofMinutes(2))).contains("[2m]) unless");
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
