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
 * PROMETHEUS_URL · MONITORING_* AWS 미설정(테스트 기본 설정): 앱은 기동하고 200. Prometheus · AWS는 stale + 수치 null,
 * 클러스터 · 플랫폼 칸은 UNKNOWN, DB에서 오는 값(이벤트 목록 · 진행 단계)은 그대로.
 */
class TrafficMonitoringUnconfiguredApiTest extends IntegrationTest {

	@Test
	void respondsWithoutPrometheusAndAws() throws Exception {
		LocalDateTime start = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES).minusMinutes(10);
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(7201, A).name("A 이벤트").approved("HOSTING", "OPEN", "slug7201").startAt(start).insert();

		mvc.perform(get("/api/ops/monitoring/platform").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.prometheus.stale").value(true))
			.andExpect(jsonPath("$.prometheus.asOf").value(nullValue()))
			// 미설정도 출처 장애(화면: Prometheus 응답 문구)
			.andExpect(jsonPath("$.prometheus.staleReason").value("SOURCE"))
			.andExpect(jsonPath("$.aws.stale").value(true))
			.andExpect(jsonPath("$.aws.staleReason").value("SOURCE"))
			.andExpect(jsonPath("$.summary[0].level").value("UNKNOWN"))
			.andExpect(jsonPath("$.summary[1].level").value("UNKNOWN"))
			.andExpect(jsonPath("$.summary[1].openCount").value(1))
			.andExpect(jsonPath("$.summary[2].level").value("OK"))
			.andExpect(jsonPath("$.summary[3].level").value("UNKNOWN"))
			.andExpect(jsonPath("$.nodes.cpu").value(nullValue()))
			.andExpect(jsonPath("$.nodes.pools[0].count").value(nullValue()))
			.andExpect(jsonPath("$.platform.addons.healthy").value(nullValue()))
			.andExpect(jsonPath("$.platform.rds").value(nullValue()))
			.andExpect(jsonPath("$.failures.length()").value(0));

		mvc.perform(get("/api/ops/monitoring/events").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.defaultEventId").value(7201))
			.andExpect(jsonPath("$.items[0].level").value("UNKNOWN"));

		mvc.perform(get("/api/ops/monitoring/events/7201").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.prometheus.stale").value(true))
			.andExpect(jsonPath("$.prometheus.staleReason").value("SOURCE"))
			.andExpect(jsonPath("$.event.phase").value("LIVE"))
			.andExpect(jsonPath("$.kpi.level").value("UNKNOWN"))
			.andExpect(jsonPath("$.kpi.live").value(nullValue()))
			.andExpect(jsonPath("$.range.key").value("entry"))
			.andExpect(jsonPath("$.charts").value(nullValue()));
	}

}
