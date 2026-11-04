package com.tetra.ops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.tetra.ops.common.time.Kst;
import com.tetra.ops.support.FakePrometheus;
import com.tetra.ops.support.IntegrationTest;

/** GET /api/ops/monitoring/overview — Prometheus는 HTTP 목, OPEN 이벤트는 Testcontainers DB 시드. 캐시 TTL 0(요청마다 조회). */
class MonitoringApiTest extends IntegrationTest {

	private static final FakePrometheus prometheus = new FakePrometheus();

	private static final String UNKNOWN = "cccccccccccc";

	@DynamicPropertySource
	static void prometheusUrl(DynamicPropertyRegistry registry) {
		registry.add("tetra.monitoring.prometheus-url", prometheus::url);
		registry.add("tetra.monitoring.cache-ttl", () -> "PT0S");
	}

	@AfterAll
	static void stop() {
		prometheus.close();
	}

	@BeforeEach
	void resetPrometheus() {
		prometheus.reset();
	}

	private static String iso(LocalDateTime value) {
		return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Kst.toOffset(value));
	}

	/** range 요청의 start · end · step으로 격자 시각을 만들어 값을 채운다 */
	private static String rangeSeries(FakePrometheus.Request request) {
		long start = Long.parseLong(request.params().get("start"));
		long end = Long.parseLong(request.params().get("end"));
		StringBuilder a = new StringBuilder();
		for (long t = start + 15; t <= end; t += 15) { // 첫 시각은 비워 둔다 → null
			a.append(a.isEmpty() ? "" : ",").append("[").append(t).append(",\"700\"]");
		}
		return """
				[{"metric":{"tenant_id":"%s"},"values":[%s]},
				 {"metric":{"tenant_id":"%s"},"values":[[%d,"3"]]},
				 {"metric":{},"values":[[%d,"9"]]}]""".formatted(A, a, UNKNOWN, end, end);
	}

	private void seedOpenEvents(LocalDateTime firstStart) {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.tenant(B, "B사", "ACTIVE", "READY");
		data.event(7001, A).name("A 첫 이벤트").approved("HOSTING", "OPEN", "slug7001").startAt(firstStart).insert();
		data.event(7002, A).name("A 둘째 이벤트").approved("HOSTING", "OPEN", "slug7002").startAt(firstStart.plusMinutes(20)).insert();
		data.event(7003, A).approved("HOSTING", "SCHEDULED", "slug7003").startAt(firstStart.plusHours(5)).insert();
		data.event(7004, B).name("B 이벤트").approved("HOSTING", "OPEN", "slug7004").startAt(firstStart.plusMinutes(20)).insert();
	}

	private void respondAll() {
		prometheus.respond(PromQueries.CPU_USAGE, "[{\"metric\":{},\"value\":[1,\"0.4213\"]}]")
			.respond(PromQueries.MEMORY_USAGE, "[{\"metric\":{},\"value\":[1,\"NaN\"]}]")
			.respond(PromQueries.READY_NODES_BY_POOL, """
					[{"metric":{"label_tetra_io_pool":"system"},"value":[1,"2"]},
					 {"metric":{"label_tetra_io_pool":"app"},"value":[1,"3"]}]""")
			.respond(PromQueries.READY_PODS_BY_COMPONENT, "[{\"metric\":{\"label_tetra_io_component\":\"api\"},\"value\":[1,\"5\"]}]")
			.respond(PromQueries.ACTIVE_PODS_BY_COMPONENT, """
					[{"metric":{"label_tetra_io_component":"api"},"value":[1,"6"]},
					 {"metric":{"label_tetra_io_component":"redis"},"value":[1,"1"]}]""")
			.respond(PromQueries.RPS_BY_TENANT, request -> request.path().endsWith("query_range") ? rangeSeries(request)
					: "[{\"metric\":{\"tenant_id\":\"" + A + "\"},\"value\":[1,\"706.14\"]}]")
			.respond(PromQueries.RPS_TOTAL, "[{\"metric\":{},\"value\":[1,\"1131.24\"]}]")
			.respond(PromQueries.errorRpsByTenant(List.of(A, B)), "[{\"metric\":{\"tenant_id\":\"" + A + "\"},\"value\":[1,\"7.06\"]}]")
			.respond(PromQueries.API_READY_PODS_BY_NAMESPACE, "[{\"metric\":{\"namespace\":\"tenant-" + A + "\"},\"value\":[1,\"6\"]}]")
			.respond(PromQueries.HPA_MAX_BY_NAMESPACE, "[{\"metric\":{\"namespace\":\"tenant-" + A + "\"},\"value\":[1,\"12\"]}]")
			.respondStartingWith(requestsPrefix(A), """
					[{"metric":{"status":"200"},"value":[1,"50.4"]},{"metric":{"status":"404"},"value":[1,"8.6"]},
					 {"metric":{"status":"500"},"value":[1,"5.2"]},{"metric":{"status":"503"},"value":[1,"2.6"]}]""");
	}

	/** 범위(초)만 다른 누적 쿼리의 앞부분 */
	private static String requestsPrefix(String tenantId) {
		String query = PromQueries.requestsByStatusSince(tenantId, Duration.ZERO);
		return query.substring(0, query.lastIndexOf('[') + 1);
	}

	/** 누적 쿼리의 범위(초) — increase 창 [Ns]와 처음 생긴 시리즈를 가르는 offset Ns가 같다 */
	private long rangeSecondsOf(String tenantId) {
		Matcher m = Pattern.compile("\\[(\\d+)s]\\) unless .* offset \\1s\\)\\)$")
			.matcher(prometheus.requests()
				.stream()
				.map(FakePrometheus.Request::query)
				.filter(q -> q.startsWith(requestsPrefix(tenantId)))
				.findFirst()
				.orElseThrow());
		assertThat(m.find()).isTrue();
		return Long.parseLong(m.group(1));
	}

	@Test
	void overviewFromPrometheusAndOpenEvents() throws Exception {
		LocalDateTime firstStart = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES).minusMinutes(30);
		seedOpenEvents(firstStart);
		respondAll();

		mvc.perform(get("/api/ops/monitoring/overview").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.stale").value(false))
			.andExpect(jsonPath("$.asOf").isString())
			// 클러스터: NaN → null, 빠진 풀 · component는 0
			.andExpect(jsonPath("$.cluster.cpuUsage").value(0.4213))
			.andExpect(jsonPath("$.cluster.memoryUsage").value(nullValue()))
			.andExpect(jsonPath("$.cluster.nodes.app").value(3))
			.andExpect(jsonPath("$.cluster.nodes.redis").value(0))
			.andExpect(jsonPath("$.cluster.nodes.system").value(2))
			.andExpect(jsonPath("$.cluster.pods.api.ready").value(5))
			.andExpect(jsonPath("$.cluster.pods.api.total").value(6))
			.andExpect(jsonPath("$.cluster.pods.redis.ready").value(0))
			.andExpect(jsonPath("$.cluster.pods.redis.total").value(1))
			// 트래픽: tenant_id 없는 시리즈는 빼고, DB에 없는 테넌트는 이름 null(뒤로)
			.andExpect(jsonPath("$.traffic.rangeMinutes").value(30))
			.andExpect(jsonPath("$.traffic.stepSeconds").value(15))
			.andExpect(jsonPath("$.traffic.totalRps").value(1131.2))
			.andExpect(jsonPath("$.traffic.series.length()").value(2))
			.andExpect(jsonPath("$.traffic.series[0].tenantId").value(A))
			.andExpect(jsonPath("$.traffic.series[0].tenantName").value("A사"))
			.andExpect(jsonPath("$.traffic.series[0].events[*].eventId").value(org.hamcrest.Matchers.contains(7001, 7002)))
			.andExpect(jsonPath("$.traffic.series[0].events[0].name").value("A 첫 이벤트"))
			.andExpect(jsonPath("$.traffic.series[0].events[0].startAt").value(iso(firstStart)))
			.andExpect(jsonPath("$.traffic.series[0].points.length()").value(121))
			.andExpect(jsonPath("$.traffic.series[0].points[0][1]").value(nullValue()))
			.andExpect(jsonPath("$.traffic.series[0].points[1][1]").value(700.0))
			.andExpect(jsonPath("$.traffic.series[1].tenantId").value(UNKNOWN))
			.andExpect(jsonPath("$.traffic.series[1].tenantName").value(nullValue()))
			.andExpect(jsonPath("$.traffic.series[1].events.length()").value(0))
			.andExpect(jsonPath("$.traffic.series[1].points[120][1]").value(3.0))
			// 테넌트: OPEN 이벤트 2개인 A는 한 행, SCHEDULED는 빠짐
			.andExpect(jsonPath("$.tenants.length()").value(2))
			.andExpect(jsonPath("$.tenants[0].tenantId").value(A))
			.andExpect(jsonPath("$.tenants[0].tenantName").value("A사"))
			.andExpect(jsonPath("$.tenants[0].namespace").value("tenant-" + A))
			.andExpect(jsonPath("$.tenants[0].events[*].eventId").value(org.hamcrest.Matchers.contains(7001, 7002)))
			.andExpect(jsonPath("$.tenants[0].rps").value(706.1))
			.andExpect(jsonPath("$.tenants[0].errorRps").value(7.1))
			.andExpect(jsonPath("$.tenants[0].pods.ready").value(6))
			.andExpect(jsonPath("$.tenants[0].pods.max").value(12))
			.andExpect(jsonPath("$.tenants[0].completed").value(59))
			.andExpect(jsonPath("$.tenants[0].failed").value(8))
			// TEST-LEAD: 서비스 값 60
			// .andExpect(jsonPath("$.tenants[0].since").value(iso(firstStart.minusMinutes(60))))
			.andExpect(jsonPath("$.tenants[0].since").value(iso(firstStart.minusMinutes(15)))) // TEST-LEAD
			// B: 요청 시리즈 없음 → rps · errorRps · 누적 null, HPA 없음 → max null, Ready Pod는 0
			.andExpect(jsonPath("$.tenants[1].tenantId").value(B))
			.andExpect(jsonPath("$.tenants[1].rps").value(nullValue()))
			.andExpect(jsonPath("$.tenants[1].errorRps").value(nullValue()))
			.andExpect(jsonPath("$.tenants[1].pods.ready").value(0))
			.andExpect(jsonPath("$.tenants[1].pods.max").value(nullValue()))
			.andExpect(jsonPath("$.tenants[1].completed").value(nullValue()))
			.andExpect(jsonPath("$.tenants[1].failed").value(nullValue()));

		// TEST-LEAD: 서비스 값 60분 · 5400 · 4200
		// // 누적 범위 = 지금 − (가장 이른 OPEN 시작 − 60분): A 90분, B 70분
		// assertThat(rangeSecondsOf(A)).isBetween(5400L - 5, 5400L + 70);
		// assertThat(rangeSecondsOf(B)).isBetween(4200L - 5, 4200L + 70);
		// 누적 범위 = 지금 − (가장 이른 OPEN 시작 − 15분): A 45분, B 25분 // TEST-LEAD
		assertThat(rangeSecondsOf(A)).isBetween(2700L - 5, 2700L + 70); // TEST-LEAD
		assertThat(rangeSecondsOf(B)).isBetween(1500L - 5, 1500L + 70); // TEST-LEAD
		// 시각 격자: start · end가 15초 배수
		FakePrometheus.Request range = prometheus.requests()
			.stream()
			.filter(r -> r.path().endsWith("query_range"))
			.findFirst()
			.orElseThrow();
		assertThat(Long.parseLong(range.params().get("end")) % 15).isZero();
		assertThat(Long.parseLong(range.params().get("end")) - Long.parseLong(range.params().get("start"))).isEqualTo(1800);
	}

	@Test
	void noOpenEventsAndNoTraffic() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(7003, A).approved("HOSTING", "SCHEDULED", "slug7003").insert();
		prometheus.respond(PromQueries.READY_NODES_BY_POOL, "[{\"metric\":{\"label_tetra_io_pool\":\"system\"},\"value\":[1,\"2\"]}]");

		mvc.perform(get("/api/ops/monitoring/overview").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.stale").value(false))
			.andExpect(jsonPath("$.cluster.cpuUsage").value(nullValue()))
			.andExpect(jsonPath("$.cluster.nodes.app").value(0))
			.andExpect(jsonPath("$.cluster.nodes.system").value(2))
			.andExpect(jsonPath("$.cluster.pods.api.ready").value(0))
			.andExpect(jsonPath("$.traffic.totalRps").value(0.0))
			.andExpect(jsonPath("$.traffic.series.length()").value(0))
			.andExpect(jsonPath("$.tenants.length()").value(0));

		// OPEN 테넌트가 없으면 테넌트 쿼리를 하지 않는다
		assertThat(prometheus.requests()).extracting(FakePrometheus.Request::query)
			.doesNotContain(PromQueries.API_READY_PODS_BY_NAMESPACE, PromQueries.HPA_MAX_BY_NAMESPACE);
	}

	@Test
	void prometheusFailureReturnsLastValueAsStale() throws Exception {
		seedOpenEvents(LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES).minusMinutes(30));
		respondAll();
		String asOf = com.jayway.jsonpath.JsonPath.read(mvc.perform(get("/api/ops/monitoring/overview").cookie(operatorCookie()))
			.andExpect(jsonPath("$.stale").value(false))
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.asOf");

		prometheus.failWith(503);

		mvc.perform(get("/api/ops/monitoring/overview").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.stale").value(true))
			.andExpect(jsonPath("$.asOf").value(asOf))
			.andExpect(jsonPath("$.cluster.cpuUsage").value(0.4213))
			.andExpect(jsonPath("$.tenants[0].completed").value(59));
	}

	@Test
	void requiresLogin() throws Exception {
		mvc.perform(get("/api/ops/monitoring/overview")).andExpect(status().isUnauthorized());
	}

}
