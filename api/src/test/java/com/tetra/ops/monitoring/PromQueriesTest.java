package com.tetra.ops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

/** PromQL에 들어가는 tenant_id는 ^[a-z0-9]{12}$만, 범위는 60초 ~ 7일. */
class PromQueriesTest {

	@Test
	void errorRpsJoinsOnlyValidTenantIds() {
		// 홈 점 나누기(errorRps ÷ rps, 화면 효과뿐)라 rps와 같은 요청량 식. 수집 간격은 같은 테넌트 Pod만 본다
		String tenants = "tenant_id=~\"aaaaaaaaaaaa|bbbbbbbbbbbb\"";
		String req = "http_server_requests_seconds_count{" + tenants
				+ ",uri!~\"/actuator.*|/api/issuance/health\",status=~\"5..\"}";
		assertThat(PromQueries.errorRpsByTenant(List.of("bbbbbbbbbbbb", "aaaaaaaaaaaa")))
			.isEqualTo("max_over_time((sum by (tenant_id) (sum by (pod, tenant_id) (irate(" + req + "[1m]) or ((" + req
					+ " unless " + req + " offset 1m) / on(pod) group_left() idelta(process_uptime_seconds{" + tenants
					+ "}[1m])))))[15s:1s])");

		for (String bad : List.of("aaaaaaaaaaa", "AAAAAAAAAAAA", "aaaaaaaaaaa\"", "aaaa|.*|aaaa", "aaaaaaaaaaaa\n")) {
			assertThatThrownBy(() -> PromQueries.errorRpsByTenant(List.of("bbbbbbbbbbbb", bad)))
				.isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> PromQueries.errorRpsByTenant(List.of())).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void requestsSinceValidatesTenantAndClampsRange() {
		// 구간 안에서 처음 생긴 시리즈는 첫 값부터(increase는 이벤트 20의 몰림 약 1.1만 건을 빠뜨렸다)
		String series = "http_server_requests_seconds_count{tenant_id=\"aaaaaaaaaaaa\","
				+ "uri!~\"/actuator.*|/api/issuance/health\"}";
		String born = "(" + series + " unless " + series + " offset 3900s)";
		assertThat(PromQueries.requestsByStatusSince("aaaaaaaaaaaa", Duration.ofMinutes(65))).isEqualTo(
				"sum by (status) ((increase(" + series + "[3900s]) unless " + born + ") or " + born + ")");
		assertThat(PromQueries.requestsByStatusSince("aaaaaaaaaaaa", Duration.ofSeconds(-30))).contains("[60s])")
			.endsWith(" offset 60s))");
		assertThat(PromQueries.requestsByStatusSince("aaaaaaaaaaaa", Duration.ofDays(30))).contains("[604800s])")
			.endsWith(" offset 604800s))");
		assertThatThrownBy(() -> PromQueries.requestsByStatusSince("a\"}or vector(1", Duration.ofMinutes(5)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void requestQueriesExcludeActuatorAndHealthLikeEventDetail() {
		// 요청량 식(이벤트 상세 rps와 같은 X), 창 = 그래프 간격 15초. 수집 간격은 모든 발급 앱 Pod에서(on(pod))
		String req = "http_server_requests_seconds_count{uri!~\"/actuator.*|/api/issuance/health\"}";
		String tail = " or ((" + req + " unless " + req + " offset 1m) / on(pod) group_left() "
				+ "idelta(process_uptime_seconds[1m]))";
		assertThat(PromQueries.RPS_BY_TENANT).isEqualTo(
				"max_over_time((sum by (tenant_id) (sum by (pod, tenant_id) (irate(" + req + "[1m])" + tail + ")))[15s:1s])");
		assertThat(PromQueries.RPS_TOTAL)
			.isEqualTo("max_over_time((sum(sum by (pod) (irate(" + req + "[1m])" + tail + ")))[15s:1s])");
		assertThat(MonitoringCollector.STEP).isEqualTo(PromQueries.TRAFFIC_STEP);
		// 홈 · 이벤트 상세가 같은 필터
		assertThat(TrafficQueries.RPS_BY_EVENT).contains("uri!~\"" + PromQueries.EXCLUDED_URI + "\"");
	}

	@Test
	void namespaceRequiresValidTenantId() {
		assertThat(PromQueries.namespace("aaaaaaaaaaaa")).isEqualTo("tenant-aaaaaaaaaaaa");
		assertThatThrownBy(() -> PromQueries.namespace(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void textBlocksAreSingleLine() {
		for (String q : List.of(PromQueries.READY_NODES_BY_POOL, PromQueries.READY_PODS_BY_COMPONENT,
				PromQueries.ACTIVE_PODS_BY_COMPONENT, PromQueries.RPS_BY_TENANT, PromQueries.RPS_TOTAL,
				PromQueries.API_READY_PODS_BY_NAMESPACE, PromQueries.HPA_MAX_BY_NAMESPACE)) {
			assertThat(q).doesNotContain("\n").doesNotStartWith(" ").doesNotEndWith(" ");
		}
		assertThat(PromQueries.READY_NODES_BY_POOL).isEqualTo("count by (label_tetra_io_pool) (kube_node_labels * on(node) "
				+ "group_left() (kube_node_status_condition{condition=\"Ready\",status=\"true\"} == 1))");
	}

}
