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
		assertThat(PromQueries.errorRpsByTenant(List.of("bbbbbbbbbbbb", "aaaaaaaaaaaa"))).isEqualTo(
				"sum by (tenant_id) (rate(http_server_requests_seconds_count{tenant_id=~\"aaaaaaaaaaaa|bbbbbbbbbbbb\","
						+ "uri!~\"/actuator.*\",status=~\"5..\"}[1m]))");

		for (String bad : List.of("aaaaaaaaaaa", "AAAAAAAAAAAA", "aaaaaaaaaaa\"", "aaaa|.*|aaaa", "aaaaaaaaaaaa\n")) {
			assertThatThrownBy(() -> PromQueries.errorRpsByTenant(List.of("bbbbbbbbbbbb", bad)))
				.isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> PromQueries.errorRpsByTenant(List.of())).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void requestsSinceValidatesTenantAndClampsRange() {
		assertThat(PromQueries.requestsByStatusSince("aaaaaaaaaaaa", Duration.ofMinutes(65))).isEqualTo(
				"sum by (status) (increase(http_server_requests_seconds_count{tenant_id=\"aaaaaaaaaaaa\","
						+ "uri!~\"/actuator.*\"}[3900s]))");
		assertThat(PromQueries.requestsByStatusSince("aaaaaaaaaaaa", Duration.ofSeconds(-30))).endsWith("[60s]))");
		assertThat(PromQueries.requestsByStatusSince("aaaaaaaaaaaa", Duration.ofDays(30))).endsWith("[604800s]))");
		assertThatThrownBy(() -> PromQueries.requestsByStatusSince("a\"}or vector(1", Duration.ofMinutes(5)))
			.isInstanceOf(IllegalArgumentException.class);
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
