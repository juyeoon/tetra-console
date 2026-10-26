package com.tetra.ops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongFunction;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.tetra.ops.common.time.Kst;
import com.tetra.ops.monitoring.AwsMonitoringGateway.LoadBalancerRef;
import com.tetra.ops.monitoring.AwsMonitoringGateway.MetricPoint;
import com.tetra.ops.monitoring.AwsMonitoringGateway.ServiceCount;
import com.tetra.ops.monitoring.AwsMonitoringGateway.TargetHealthCount;
import com.tetra.ops.support.FakePrometheus;
import com.tetra.ops.support.IntegrationTest;

/**
 * OPS-05 API 3개 — Prometheus는 HTTP 목, AWS는 게이트웨이 목, 이벤트 · 로그 · 알림은 Testcontainers DB 시드. 캐시 TTL 0(요청마다 조회).
 * 시드(지금 = now, 분 단위):
 * - 7101 A 진행 중(OPEN), 시작 now−10분 — 5xx 0.8% → 주의
 * - 7102 B 진행 중(OPEN), 시작 now−5분 — 정상
 * - 7104 A 종료(EXPIRED · ENDED), 시작 2일 전 — 결과 요약 있음
 * - 7105 B 준비 실패(OPEN_FAILED), 시작 1일 전 — URGENT 알림 + OPEN_FAILED 로그
 * - 7106 A 예정(SCHEDULED), 시작 2일 뒤(오늘 예정이 아님)
 */
class TrafficMonitoringApiTest extends IntegrationTest {

	private static final FakePrometheus prometheus = new FakePrometheus();

	@MockitoBean
	AwsMonitoringGateway aws;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("tetra.monitoring.prometheus-url", prometheus::url);
		registry.add("tetra.monitoring.cache-ttl", () -> "PT0S");
		registry.add("tetra.monitoring.aws.cache-ttl", () -> "PT0S");
		registry.add("tetra.monitoring.aws.issuance-alb-name", () -> "tetra-dev-issuance");
		registry.add("tetra.monitoring.aws.rds-id", () -> "tetra-dev-mysql");
		registry.add("tetra.monitoring.aws.ecs-cluster", () -> "tetra-dev");
		registry.add("tetra.monitoring.aws.ecs-services", () -> "console,portal");
		registry.add("tetra.monitoring.aws.console-tg-arn", () -> "arn:console");
		registry.add("tetra.monitoring.aws.portal-tg-arn", () -> "arn:portal");
		registry.add("tetra.monitoring.aws.dlq-names", () -> "tetra-dev-approve-result-dlq,tetra-dev-scheduler-dlq");
	}

	@AfterAll
	static void stop() {
		prometheus.close();
	}

	private LocalDateTime now;

	@BeforeEach
	void seed() {
		prometheus.reset();
		now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.tenant(B, "B사", "ACTIVE", "READY");

		live(7101, A, "A 쿠폰 이벤트", now.minusMinutes(10));
		live(7102, B, "B 타임세일", now.minusMinutes(5));

		LocalDateTime endedStart = now.minusDays(2);
		data.event(7104, A).name("A 지난 이벤트").approved("ENDED", "EXPIRED", "slug7104").startAt(endedStart).insert();
		approvedLogs(A, 7104, endedStart);
		data.statusLog(A, 7104, "EVENT", "SCHEDULED", "OPEN", endedStart.minusMinutes(60), null);
		data.statusLog(A, 7104, "HOSTING", "HOSTING", "ENDED", endedStart.plusHours(5), null);
		data.statusLog(A, 7104, "EVENT", "OPEN", "EXPIRED", endedStart.plusHours(5), null);
		data.jdbc().update("""
				INSERT INTO t_%s.event_result_summary (event_id, tenant_id, session_count, ticket_count, peak_concurrent,
				    metrics_finalized_at, total_stock, success_count, soldout_count, history_generated_at)
				VALUES (7104, ?, 100, 90, 80, ?, 10000, 9999, 1, ?)""".formatted(A), A, endedStart.plusHours(5),
				endedStart.plusHours(5).plusMinutes(6));

		LocalDateTime failedStart = now.minusDays(1);
		data.event(7105, B).name("B 신규 가입 쿠폰").approved("HOSTING", "OPEN_FAILED", "slug7105").startAt(failedStart)
			.insert();
		approvedLogs(B, 7105, failedStart);
		data.statusLog(B, 7105, "EVENT", "SCHEDULED", "OPEN_FAILED", failedStart.minusMinutes(59), "동결 확인 시간 초과");
		notification(B, 7105L, "EVENT", failedStart.minusMinutes(59),
				"[B사] [B 신규 가입 쿠폰] 이벤트 준비에 실패했습니다. 원인: 동결 확인 시간 초과");
		notification(null, null, "MAIL", now.minusDays(3), "개인키 메일 발송에 실패했습니다.");

		data.event(7106, A).name("A 다음 이벤트").approved("DEPLOYED", "SCHEDULED", "slug7106").startAt(now.plusDays(2))
			.insert();

		when(aws.metricData(anyList(), any(), any())).thenReturn(Map.of("rds_cpu",
				List.of(new MetricPoint(Instant.now().minusSeconds(120).toEpochMilli(), 48.04)), "dlq1",
				List.of(new MetricPoint(Instant.now().minusSeconds(120).toEpochMilli(), 2))));
		when(aws.dbInstanceStatus("tetra-dev-mysql")).thenReturn("available");
		when(aws.services(eq("tetra-dev"), anyList()))
			.thenReturn(List.of(new ServiceCount("console", 1, 1), new ServiceCount("portal", 1, 1)));
		when(aws.targetHealth(anyString())).thenReturn(new TargetHealthCount(1, 1));
		when(aws.findLoadBalancer("tetra-dev-issuance")).thenReturn(Optional.of(new LoadBalancerRef("arn:alb", "app/x/1")));
		when(aws.targetGroups("arn:alb")).thenReturn(List.of());
	}

	private void live(long eventId, String tenantId, String name, LocalDateTime start) {
		data.event(eventId, tenantId).name(name).approved("HOSTING", "OPEN", "slug" + eventId).startAt(start).insert();
		approvedLogs(tenantId, eventId, start);
		data.statusLog(tenantId, eventId, "EVENT", "SCHEDULED", "OPEN", start.minusMinutes(60).plusSeconds(20), null);
	}

	private void approvedLogs(String tenantId, long eventId, LocalDateTime start) {
		data.statusLog(tenantId, eventId, "APPLICATION", "APPLIED", "APPROVED", start.minusDays(1), null, 1L);
		data.statusLog(tenantId, eventId, "HOSTING", "DEPLOYED", "HOSTING", start.minusHours(2), null);
	}

	private void notification(String tenantId, Long eventId, String category, LocalDateTime at, String content) {
		data.jdbc().update("""
				INSERT INTO tetra_platform.operator_notification (tenant_id, event_id, category, priority, occurred_at, content, is_read)
				VALUES (?, ?, ?, 'URGENT', ?, ?, 0)""", tenantId, eventId, category, at, content);
	}

	private static String iso(LocalDateTime value) {
		return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Kst.toOffset(value));
	}

	// ---------------------------------------------------------------- Prometheus 목 응답

	private static String vector(String value) {
		return "[{\"metric\":{},\"value\":[1,\"" + value + "\"]}]";
	}

	/** range 요청 격자에 값을 채운 시리즈 하나. value(i) = i번째 점 */
	private static String matrix(FakePrometheus.Request r, String metric, LongFunction<String> value) {
		long start = Long.parseLong(r.params().get("start"));
		long end = Long.parseLong(r.params().get("end"));
		long step = Long.parseLong(r.params().get("step").replace("s", ""));
		StringBuilder points = new StringBuilder();
		long i = 0;
		for (long t = start; t <= end; t += step, i++) {
			points.append(points.isEmpty() ? "" : ",").append("[").append(t).append(",\"").append(value.apply(i)).append("\"]");
		}
		return "[{\"metric\":" + metric + ",\"values\":[" + points + "]}]";
	}

	private static boolean isRange(FakePrometheus.Request r) {
		return r.path().endsWith("query_range");
	}

	/** instant는 vector, range는 같은 값으로 채운 시리즈 */
	private void both(String query, String value) {
		prometheus.respond(query, r -> isRange(r) ? matrix(r, "{}", i -> value) : vector(value));
	}

	private void respondPlatform() {
		prometheus.respond(PromQueries.CPU_USAGE, r -> isRange(r) ? matrix(r, "{}", i -> "0.4") : vector("0.4612"))
			.respond(PromQueries.MEMORY_USAGE, r -> isRange(r) ? matrix(r, "{}", i -> "0.5") : vector("0.5123"))
			.respond(PromQueries.READY_NODES_BY_POOL, """
					[{"metric":{"label_tetra_io_pool":"system"},"value":[1,"2"]},
					 {"metric":{"label_tetra_io_pool":"app"},"value":[1,"1"]}]""")
			.respond(TrafficQueries.NODE_LABELS, """
					[{"metric":{"node":"n1","label_tetra_io_pool":"system"},"value":[1,"1"]},
					 {"metric":{"node":"n2","label_tetra_io_pool":"system"},"value":[1,"1"]},
					 {"metric":{"node":"n3","label_tetra_io_pool":"app"},"value":[1,"1"]}]""")
			.respond(TrafficQueries.NODE_READY, """
					[{"metric":{"node":"n1"},"value":[1,"1"]},{"metric":{"node":"n2"},"value":[1,"1"]},
					 {"metric":{"node":"n3"},"value":[1,"1"]}]""")
			.respond(TrafficQueries.NODE_CREATED, """
					[{"metric":{"node":"n1"},"value":[1,"1700000000"]},{"metric":{"node":"n2"},"value":[1,"1700000100"]},
					 {"metric":{"node":"n3"},"value":[1,"%d"]}]""".formatted(Instant.now().minusSeconds(300).getEpochSecond()))
			.respond(TrafficQueries.NODE_CPU, """
					[{"metric":{"nodename":"n1"},"value":[1,"0.29"]},{"metric":{"nodename":"n2"},"value":[1,"0.31"]},
					 {"metric":{"nodename":"n3"},"value":[1,"0.83"]}]""")
			.respond(TrafficQueries.NODE_MEMORY, """
					[{"metric":{"nodename":"n1"},"value":[1,"0.46"]},{"metric":{"nodename":"n2"},"value":[1,"0.48"]},
					 {"metric":{"nodename":"n3"},"value":[1,"0.7"]}]""")
			.respond(TrafficQueries.ADDON_DEPLOYMENTS_AVAILABLE, addonDeployments("0"))
			.respond(TrafficQueries.ADDON_DEPLOYMENTS_DESIRED, addonDeployments("1"))
			.respond(TrafficQueries.ADDON_STATEFULSETS_READY,
					"[{\"metric\":{\"namespace\":\"argocd\",\"statefulset\":\"argocd-application-controller\"},\"value\":[1,\"1\"]}]")
			.respond(TrafficQueries.ADDON_STATEFULSETS_DESIRED,
					"[{\"metric\":{\"namespace\":\"argocd\",\"statefulset\":\"argocd-application-controller\"},\"value\":[1,\"1\"]}]")
			.respond(TrafficQueries.RPS_BY_EVENT, """
					[{"metric":{"event_id":"7101"},"value":[1,"1000"]},{"metric":{"event_id":"7102"},"value":[1,"500"]}]""")
			.respond(TrafficQueries.ERROR_RPS_BY_EVENT, "[{\"metric\":{\"event_id\":\"7101\"},\"value\":[1,\"8\"]}]")
			.respond(TrafficQueries.P95_BY_EVENT, """
					[{"metric":{"event_id":"7101"},"value":[1,"0.18"]},{"metric":{"event_id":"7102"},"value":[1,"0.1"]}]""")
			.respond(TrafficQueries.API_READY_BY_EVENT,
					"[{\"metric\":{\"label_tetra_io_event_id\":\"7101\"},\"value\":[1,\"6\"]}]")
			.respond(TrafficQueries.HPA_MAX_BY_HPA,
					"[{\"metric\":{\"horizontalpodautoscaler\":\"keda-hpa-issuance-7101\"},\"value\":[1,\"12\"]}]");
	}

	/** keda-operator만 value, 나머지는 1 */
	private static String addonDeployments(String kedaOperator) {
		StringBuilder out = new StringBuilder("[");
		String[][] all = { { "keda", "keda-operator" }, { "keda", "keda-operator-metrics-apiserver" },
				{ "kube-system", "karpenter" }, { "kube-system", "aws-load-balancer-controller" },
				{ "external-secrets", "external-secrets" }, { "external-secrets", "external-secrets-webhook" },
				{ "argocd", "argocd-server" }, { "argocd", "argocd-repo-server" } };
		for (String[] d : all) {
			String v = "keda-operator".equals(d[1]) ? kedaOperator : "1";
			out.append(out.length() > 1 ? "," : "")
				.append("{\"metric\":{\"namespace\":\"%s\",\"deployment\":\"%s\"},\"value\":[1,\"%s\"]}".formatted(d[0], d[1], v));
		}
		return out.append("]").toString();
	}

	// ---------------------------------------------------------------- platform

	@Test
	void platform() throws Exception {
		respondPlatform();
		mvc.perform(get("/api/ops/monitoring/platform").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.prometheus.stale").value(false))
			.andExpect(jsonPath("$.prometheus.asOf").isString())
			.andExpect(jsonPath("$.aws.stale").value(false))
			// A. 상태 요약
			.andExpect(jsonPath("$.summary[*].area").value(contains("CLUSTER", "EVENT", "AUTOMATION", "PLATFORM")))
			.andExpect(jsonPath("$.summary[0].level").value("DANGER"))
			.andExpect(jsonPath("$.summary[0].reason").value("KEDA 가용 0"))
			.andExpect(jsonPath("$.summary[1].level").value("WARN"))
			.andExpect(jsonPath("$.summary[1].reason").value("7101번 5xx 0.8%"))
			.andExpect(jsonPath("$.summary[1].openCount").value(2))
			.andExpect(jsonPath("$.summary[1].eventId").value(7101))
			// 24시간 안 실패 없음(준비 실패는 1일 전 − 59분), DLQ 하나에 2건
			.andExpect(jsonPath("$.summary[2].level").value("DANGER"))
			.andExpect(jsonPath("$.summary[2].reason").value("DLQ tetra-dev-scheduler-dlq"))
			.andExpect(jsonPath("$.summary[3].level").value("OK"))
			.andExpect(jsonPath("$.summary[3].reason").value("RDS 가동 · Portal·Console 2/2"))
			// B-1 노드
			.andExpect(jsonPath("$.nodes.cpu").value(0.4612))
			.andExpect(jsonPath("$.nodes.history.stepSeconds").value(60))
			.andExpect(jsonPath("$.nodes.history.points.length()").value(61))
			.andExpect(jsonPath("$.nodes.history.points[0][1]").value(0.4))
			.andExpect(jsonPath("$.nodes.pools[*].pool").value(contains("system", "app", "redis")))
			.andExpect(jsonPath("$.nodes.pools[0].count").value(2))
			.andExpect(jsonPath("$.nodes.pools[0].cpu.avg").value(0.3))
			.andExpect(jsonPath("$.nodes.pools[0].cpu.max").value(0.31))
			.andExpect(jsonPath("$.nodes.pools[1].count").value(1))
			.andExpect(jsonPath("$.nodes.pools[1].addedRecently").value(1))
			.andExpect(jsonPath("$.nodes.pools[1].nodes[0].name").value("n3"))
			.andExpect(jsonPath("$.nodes.pools[1].nodes[0].recent").value(true))
			.andExpect(jsonPath("$.nodes.pools[1].nodes[0].type").value(nullValue()))
			.andExpect(jsonPath("$.nodes.pools[2].count").value(0))
			.andExpect(jsonPath("$.nodes.pools[2].nodes.length()").value(0))
			// B-2 플랫폼
			.andExpect(jsonPath("$.platform.addons.healthy").value(5))
			.andExpect(jsonPath("$.platform.addons.total").value(6))
			.andExpect(jsonPath("$.platform.addons.groups[0].name").value("KEDA"))
			.andExpect(jsonPath("$.platform.addons.groups[0].healthy").value(false))
			.andExpect(jsonPath("$.platform.addons.groups[0].problems[0].name").value("keda-operator"))
			.andExpect(jsonPath("$.platform.addons.groups[0].problems[0].ready").value(0))
			.andExpect(jsonPath("$.platform.addons.groups[3].name").value("Prometheus"))
			.andExpect(jsonPath("$.platform.addons.groups[3].healthy").value(true))
			.andExpect(jsonPath("$.platform.rds.status").value("available"))
			.andExpect(jsonPath("$.platform.rds.cpuPercent").value(48.0))
			.andExpect(jsonPath("$.platform.services[1].name").value("portal"))
			.andExpect(jsonPath("$.platform.services[1].healthyTargets").value(1))
			.andExpect(jsonPath("$.platform.services[1].fiveXx").value(nullValue()))
			// B-3 다가오는 이벤트
			.andExpect(jsonPath("$.upcoming.length()").value(1))
			.andExpect(jsonPath("$.upcoming[0].eventId").value(7106))
			.andExpect(jsonPath("$.upcoming[0].prepareAt").value(iso(now.plusDays(2).minusMinutes(90))))
			.andExpect(jsonPath("$.upcoming[0].endAt").value(iso(now.plusDays(2).plusHours(5))))
			// E. 실패: 알림 + OPEN_FAILED 로그는 한 건(로그의 단계 · 사유), 메일 알림은 내용 그대로
			.andExpect(jsonPath("$.failures.length()").value(2))
			.andExpect(jsonPath("$.failures[0].eventId").value(7105))
			.andExpect(jsonPath("$.failures[0].tenantName").value("B사"))
			.andExpect(jsonPath("$.failures[0].eventName").value("B 신규 가입 쿠폰"))
			.andExpect(jsonPath("$.failures[0].step").value("PREPARE"))
			.andExpect(jsonPath("$.failures[0].reason").value("동결 확인 시간 초과"))
			.andExpect(jsonPath("$.failures[1].step").value("MAIL"))
			.andExpect(jsonPath("$.failures[1].eventId").value(nullValue()));
	}

	@Test
	void prometheusFailureKeepsOtherSourcesAndMarksStale() throws Exception {
		respondPlatform();
		mvc.perform(get("/api/ops/monitoring/platform").cookie(operatorCookie()))
			.andExpect(jsonPath("$.prometheus.stale").value(false));
		prometheus.failWith(503);
		mvc.perform(get("/api/ops/monitoring/platform").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.prometheus.stale").value(true))
			.andExpect(jsonPath("$.prometheus.asOf").isString())
			.andExpect(jsonPath("$.nodes.cpu").value(0.4612))
			.andExpect(jsonPath("$.platform.addons.groups[3].healthy").value(false))
			.andExpect(jsonPath("$.aws.stale").value(false))
			.andExpect(jsonPath("$.summary[3].level").value("OK"));
	}

	@Test
	void queryErrorBlanksOnlyThatItemAndJudgesUnknown() throws Exception {
		respondPlatform();
		prometheus.respond(TrafficQueries.ADDON_DEPLOYMENTS_AVAILABLE, addonDeployments("1"))
			.failQuery(PromQueries.READY_NODES_BY_POOL, 422)
			.failQuery(TrafficQueries.ERROR_RPS_BY_EVENT, 400);
		mvc.perform(get("/api/ops/monitoring/platform").cookie(operatorCookie()))
			.andExpect(status().isOk())
			// 서버 문제가 아니라 갱신은 성공(stale 아님), 나머지는 새 값
			.andExpect(jsonPath("$.prometheus.stale").value(false))
			.andExpect(jsonPath("$.nodes.cpu").value(0.4612))
			// 풀별 Ready 쿼리 오류 → 개수 null(0으로 바꾸지 않음), system 노드 수를 모르니 클러스터 UNKNOWN
			.andExpect(jsonPath("$.nodes.pools[0].count").value(nullValue()))
			.andExpect(jsonPath("$.summary[0].level").value("UNKNOWN"))
			// 5xx 쿼리 오류 → 보이는 위험이 없으니 이벤트 UNKNOWN(5xx 0으로 보지 않음)
			.andExpect(jsonPath("$.summary[1].level").value("UNKNOWN"))
			.andExpect(jsonPath("$.summary[1].reason").value("데이터 없음"))
			.andExpect(jsonPath("$.platform.addons.healthy").value(6));
	}

	@Test
	void eventQueryErrorBlanksOnlyThatItem() throws Exception {
		respondLiveEvent();
		prometheus.failQuery(TrafficQueries.forEvent(A, 7101).errorRps(), 400);
		mvc.perform(get("/api/ops/monitoring/events/7101").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.prometheus.stale").value(false))
			.andExpect(jsonPath("$.kpi.live.rps").value(1131.2))
			.andExpect(jsonPath("$.kpi.live.errorRatio").value(nullValue()))
			.andExpect(jsonPath("$.kpi.live.podsReady").value(8))
			.andExpect(jsonPath("$.kpi.level").value("UNKNOWN"))
			.andExpect(jsonPath("$.charts.traffic.rps[0]").value(1131.24))
			.andExpect(jsonPath("$.charts.errors.app").value(everyItem(nullValue())));
	}

	@Test
	void eventPrometheusDownIsStaleWithoutValue() throws Exception {
		// 이 테스트만의 이벤트(캐시는 컨텍스트 공유라 다른 테스트가 채운 7101의 마지막 값이 stale로 나온다)
		live(7108, A, "A 새 이벤트", now.minusMinutes(3));
		prometheus.failWith(503);
		mvc.perform(get("/api/ops/monitoring/events/7108").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.prometheus.stale").value(true))
			.andExpect(jsonPath("$.prometheus.asOf").value(nullValue()))
			.andExpect(jsonPath("$.kpi.level").value("UNKNOWN"))
			.andExpect(jsonPath("$.charts").value(nullValue()));
	}

	// ---------------------------------------------------------------- events

	@Test
	void eventsListAndDefault() throws Exception {
		respondPlatform();
		mvc.perform(get("/api/ops/monitoring/events").cookie(operatorCookie()))
			.andExpect(status().isOk())
			// 진행 중: 주의(7101)가 정상(7102, 더 늦게 시작)보다 앞
			.andExpect(jsonPath("$.defaultEventId").value(7101))
			.andExpect(jsonPath("$.items[*].eventId").value(contains(7101, 7102, 7105, 7104)))
			.andExpect(jsonPath("$.items[0].group").value("OPEN"))
			.andExpect(jsonPath("$.items[0].level").value("WARN"))
			.andExpect(jsonPath("$.items[0].phase").value("LIVE"))
			.andExpect(jsonPath("$.items[1].level").value("OK"))
			// 최근 종료는 늦은 순: 준비 실패(1일 전 입장 예정)가 종료(2일 전 시작 + 5시간 hosting_end)보다 앞
			.andExpect(jsonPath("$.items[2].group").value("ENDED"))
			.andExpect(jsonPath("$.items[2].phase").value("PREPARE_FAILED"))
			.andExpect(jsonPath("$.items[2].level").value(nullValue()))
			.andExpect(jsonPath("$.items[3].phase").value("ENDED"))
			.andExpect(jsonPath("$.items[3].endAt").value(iso(now.minusDays(2).plusHours(5))));
	}

	@Test
	void defaultFallsBackToTodayThenEnded() throws Exception {
		data.jdbc().update("UPDATE tetra_platform.event_registry SET event_status = 'EXPIRED', hosting_status = 'ENDED'"
				+ " WHERE event_id IN (7101, 7102)");
		LocalDateTime todayStart = now.plusMinutes(90);
		assumeTrue(todayStart.toLocalDate().equals(now.toLocalDate()));
		data.event(7107, A).name("A 오늘 이벤트").approved("HOSTING", "SCHEDULED", "slug7107").startAt(todayStart).insert();
		mvc.perform(get("/api/ops/monitoring/events").cookie(operatorCookie()))
			.andExpect(jsonPath("$.defaultEventId").value(7107))
			.andExpect(jsonPath("$.items[0].group").value("TODAY"))
			.andExpect(jsonPath("$.items[0].phase").value("SCHEDULED"));
	}

	@Test
	void noEventsMeansNoDefault() throws Exception {
		data.reset();
		mvc.perform(get("/api/ops/monitoring/events").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.defaultEventId").value(nullValue()))
			.andExpect(jsonPath("$.items.length()").value(0));
	}

	// ---------------------------------------------------------------- event detail

	private void respondLiveEvent() {
		TrafficQueries.EventQueries q = TrafficQueries.forEvent(A, 7101);
		both(TrafficQueries.KUBE_STATE_METRICS_UP, "3");
		both(q.rps(), "1131.24");
		both(q.errorRps(), "9.05");
		prometheus.respond(q.p95ByUri(), r -> isRange(r)
				? matrix(r, "{\"uri\":\"/api/issuance/events/{eventId}/coupons/claim\"}", i -> "0.18")
				: """
						[{"metric":{"uri":"/api/issuance/events/{eventId}/coupons/claim"},"value":[1,"0.18"]},
						 {"metric":{"uri":"/api/issuance/events/{eventId}/ticket"},"value":[1,"0.12"]}]""");
		both(q.cursorChanges(), "3");
		both(q.waiting(), "2400");
		both(q.stock(), "3100");
		both(q.apiReady(), "8");
		both(q.hpaMin(), "6");
		both(q.hpaMax(), "12");
		both(q.apiCpu(), "3.2");
		both(q.apiCpuRequest(), "4");
		both(q.apiMemory(), "4100000000");
		both(q.apiMemoryRequest(), "8000000000");
		both(q.redisMemoryUsed(), "570000000");
		both(q.redisMemoryMax(), "1500000000");
		both(q.redisOps(), "42000");
		both(q.redisClients(), "96");
		both(q.redisUp(), "1");
		both(q.redisExporterUp(), "1");
		both(q.redisRestarts(), "0");
		both(q.prepareSeconds(), "200.4");
	}

	@Test
	void liveEvent() throws Exception {
		respondLiveEvent();
		mvc.perform(get("/api/ops/monitoring/events/7101").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.prometheus.stale").value(false))
			.andExpect(jsonPath("$.event.phase").value("LIVE"))
			.andExpect(jsonPath("$.event.tenantName").value("A사"))
			.andExpect(jsonPath("$.event.entryAt").value(iso(now.minusMinutes(70))))
			.andExpect(jsonPath("$.event.endAt").value(iso(now.minusMinutes(10).plusHours(5))))
			// 진행 단계
			.andExpect(jsonPath("$.steps[*].key")
				.value(contains("APPROVE", "HOSTING_START", "PREPARE", "ENTRY", "START", "END", "AGGREGATE")))
			.andExpect(jsonPath("$.steps[*].state")
				.value(contains("DONE", "DONE", "DONE", "DONE", "ACTIVE", "WAITING", "WAITING")))
			.andExpect(jsonPath("$.steps[2].plannedAt").value(iso(now.minusMinutes(100))))
			.andExpect(jsonPath("$.steps[2].actualAt").value(iso(now.minusMinutes(70).plusSeconds(20))))
			.andExpect(jsonPath("$.steps[2].durationSeconds").value(200))
			.andExpect(jsonPath("$.steps[4].actualAt").value(iso(now.minusMinutes(10))))
			// KPI
			.andExpect(jsonPath("$.kpi.level").value("WARN"))
			.andExpect(jsonPath("$.kpi.reason").value("5xx 0.8%"))
			.andExpect(jsonPath("$.kpi.warn").value(contains("errorRatio")))
			.andExpect(jsonPath("$.kpi.live.rps").value(1131.2))
			.andExpect(jsonPath("$.kpi.live.errorRatio").value(0.008))
			.andExpect(jsonPath("$.kpi.live.p95Ms").value(180))
			.andExpect(jsonPath("$.kpi.live.p95TicketMs").value(120))
			.andExpect(jsonPath("$.kpi.live.podsReady").value(8))
			.andExpect(jsonPath("$.kpi.live.podsMax").value(12))
			.andExpect(jsonPath("$.kpi.live.podsPending").value(0))
			.andExpect(jsonPath("$.kpi.live.waiting").value(2400))
			.andExpect(jsonPath("$.kpi.live.stockRemaining").value(3100))
			.andExpect(jsonPath("$.kpi.live.stockTotal").value(10000))
			.andExpect(jsonPath("$.kpi.live.redisMemoryRatio").value(0.38))
			.andExpect(jsonPath("$.kpi.live.redisUp").value(true))
			.andExpect(jsonPath("$.kpi.summary").value(nullValue()))
			// 범위 · 그래프: 입장 −10분 ~ 지금(80분 → 30초 간격)
			.andExpect(jsonPath("$.range.key").value("entry"))
			.andExpect(jsonPath("$.range.options").value(contains("entry", "15m", "1h")))
			.andExpect(jsonPath("$.range.stepSeconds").value(30))
			.andExpect(jsonPath("$.charts.traffic.rps[0]").value(1131.24))
			.andExpect(jsonPath("$.charts.errors.app[0]").value(0.008))
			.andExpect(jsonPath("$.charts.latency.issuance[0]").value(180.0))
			.andExpect(jsonPath("$.charts.latency.ticket").value(everyItem(nullValue())))
			.andExpect(jsonPath("$.charts.pods.pending").value(everyItem(is(0.0))))
			.andExpect(jsonPath("$.charts.pods.max").value(12))
			.andExpect(jsonPath("$.charts.rds.cpu[0][1]").value(48.0));
		assertThat(prometheus.requests()).extracting(FakePrometheus.Request::query)
			.doesNotContain(TrafficQueries.forEvent(B, 7106).rps());
	}

	@Test
	void liveEventRangeOption() throws Exception {
		respondLiveEvent();
		mvc.perform(get("/api/ops/monitoring/events/7101").param("range", "15m").cookie(operatorCookie()))
			.andExpect(jsonPath("$.range.key").value("15m"))
			.andExpect(jsonPath("$.range.stepSeconds").value(15))
			.andExpect(jsonPath("$.charts.timestamps.length()").value(61));
		mvc.perform(get("/api/ops/monitoring/events/7101").param("range", "2h").cookie(operatorCookie()))
			.andExpect(status().isBadRequest());
	}

	@Test
	void endedEventSummary() throws Exception {
		TrafficQueries.EventQueries q = TrafficQueries.forEvent(A, 7104);
		both(TrafficQueries.KUBE_STATE_METRICS_UP, "3");
		prometheus.respond(q.rps(), r -> matrix(r, "{}", i -> i == 10 ? "1820" : "100"));
		both(q.errorRps(), "0.3");
		prometheus.respond(q.stock(), r -> matrix(r, "{}", i -> Long.toString(Math.max(0, 10000 - i * 100))));
		both(q.hpaMax(), "12");
		prometheus.respond(q.apiReady(), r -> matrix(r, "{}", i -> i < 5 ? "6" : "10"));
		mvc.perform(get("/api/ops/monitoring/events/7104").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.event.phase").value("ENDED"))
			.andExpect(jsonPath("$.steps[*].state")
				.value(contains("DONE", "DONE", "DONE", "DONE", "DONE", "DONE", "DONE")))
			.andExpect(jsonPath("$.steps[5].actualAt").value(iso(now.minusDays(2).plusHours(5))))
			.andExpect(jsonPath("$.kpi.live").value(nullValue()))
			.andExpect(jsonPath("$.kpi.summary.peakRps.value").value(1820.0))
			.andExpect(jsonPath("$.kpi.summary.errorRatioAvg").value(closeTo(0.00281, 0.00001)))
			.andExpect(jsonPath("$.kpi.summary.podsMax").value(10))
			.andExpect(jsonPath("$.kpi.summary.podsLimit").value(12))
			// 발급은 결과 요약 success_count 우선
			.andExpect(jsonPath("$.kpi.summary.issued").value(9999))
			.andExpect(jsonPath("$.kpi.summary.stockTotal").value(10000))
			.andExpect(jsonPath("$.kpi.summary.soldOutAt").isString())
			.andExpect(jsonPath("$.range.key").value("full"))
			.andExpect(jsonPath("$.range.options").value(contains("full", "start")))
			.andExpect(jsonPath("$.range.from").value(iso(now.minusDays(2).minusMinutes(70))))
			.andExpect(jsonPath("$.range.to").value(iso(now.minusDays(2).plusHours(5).plusMinutes(10))));
		mvc.perform(get("/api/ops/monitoring/events/7104").param("range", "start").cookie(operatorCookie()))
			.andExpect(jsonPath("$.range.key").value("start"))
			.andExpect(jsonPath("$.range.stepSeconds").value(15))
			// 최종값은 범위와 상관없이 전체 구간
			.andExpect(jsonPath("$.kpi.summary.peakRps.value").value(1820.0));
	}

	@Test
	void prepareFailedAndScheduledSkipPrometheus() throws Exception {
		mvc.perform(get("/api/ops/monitoring/events/7105").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.event.phase").value("PREPARE_FAILED"))
			.andExpect(jsonPath("$.steps[*].state")
				.value(contains("DONE", "DONE", "FAILED", "SKIPPED", "SKIPPED", "SKIPPED", "SKIPPED")))
			.andExpect(jsonPath("$.steps[2].reason").value("동결 확인 시간 초과"))
			.andExpect(jsonPath("$.kpi").value(nullValue()))
			.andExpect(jsonPath("$.range").value(nullValue()))
			.andExpect(jsonPath("$.charts").value(nullValue()));
		mvc.perform(get("/api/ops/monitoring/events/7106").cookie(operatorCookie()))
			.andExpect(jsonPath("$.event.phase").value("SCHEDULED"))
			.andExpect(jsonPath("$.steps[*].state")
				.value(contains("DONE", "WAITING", "WAITING", "WAITING", "WAITING", "WAITING", "WAITING")))
			.andExpect(jsonPath("$.kpi").value(nullValue()))
			.andExpect(jsonPath("$.charts").value(nullValue()));
		assertThat(prometheus.requests()).isEmpty();
	}

	@Test
	void unknownEventAndLogin() throws Exception {
		mvc.perform(get("/api/ops/monitoring/events/9999").cookie(operatorCookie()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
		mvc.perform(get("/api/ops/monitoring/platform")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/ops/monitoring/events")).andExpect(status().isUnauthorized());
	}

	@Test
	void eventOfTenantNotReady() throws Exception {
		data.jdbc().update("UPDATE tetra_platform.tenant SET provisioning_status = 'FAILED' WHERE tenant_id = ?", B);
		mvc.perform(get("/api/ops/monitoring/events/7102").cookie(operatorCookie()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TENANT_NOT_READY"));
		// 목록은 READY가 아닌 테넌트도 registry 값으로 보여 준다(종료 시각만 null)
		mvc.perform(get("/api/ops/monitoring/events").cookie(operatorCookie()))
			.andExpect(jsonPath("$.items[*].eventId").value(hasItem(7102)));
	}

}
