package com.tetra.ops.monitoring;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import com.tetra.ops.support.IntegrationTest;

/**
 * PROMETHEUS_URL 미설정(테스트 기본 설정): 앱은 기동하고, 200 + 수치 null + stale.
 * 테넌트는 DB의 OPEN 목록 그대로(수치만 null).
 */
class MonitoringUnconfiguredApiTest extends IntegrationTest {

	@Test
	void startsAndRespondsWithoutPrometheus() throws Exception {
		LocalDateTime start = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES).minusMinutes(10);
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(7001, A).name("A 이벤트").approved("HOSTING", "OPEN", "slug7001").startAt(start).insert();

		mvc.perform(get("/api/ops/monitoring/overview").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.stale").value(true))
			.andExpect(jsonPath("$.asOf").value(nullValue()))
			.andExpect(jsonPath("$.cluster.cpuUsage").value(nullValue()))
			.andExpect(jsonPath("$.cluster.memoryUsage").value(nullValue()))
			.andExpect(jsonPath("$.cluster.nodes.system").value(nullValue()))
			.andExpect(jsonPath("$.cluster.pods.api.ready").value(nullValue()))
			.andExpect(jsonPath("$.traffic.totalRps").value(nullValue()))
			.andExpect(jsonPath("$.traffic.series.length()").value(0))
			.andExpect(jsonPath("$.tenants.length()").value(1))
			.andExpect(jsonPath("$.tenants[0].tenantName").value("A사"))
			.andExpect(jsonPath("$.tenants[0].events[0].name").value("A 이벤트"))
			.andExpect(jsonPath("$.tenants[0].rps").value(nullValue()))
			.andExpect(jsonPath("$.tenants[0].errorRps").value(nullValue()))
			.andExpect(jsonPath("$.tenants[0].pods.ready").value(nullValue()))
			.andExpect(jsonPath("$.tenants[0].completed").value(nullValue()))
			.andExpect(jsonPath("$.tenants[0].failed").value(nullValue()));
	}

}
