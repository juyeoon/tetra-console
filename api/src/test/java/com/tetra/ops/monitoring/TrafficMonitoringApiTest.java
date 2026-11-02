package com.tetra.ops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.tetra.ops.common.time.Kst;
import com.tetra.ops.event.EntryOpen;
import com.tetra.ops.monitoring.AwsMonitoringGateway.LoadBalancerRef;
import com.tetra.ops.monitoring.AwsMonitoringGateway.MetricPoint;
import com.tetra.ops.monitoring.AwsMonitoringGateway.ServiceCount;
import com.tetra.ops.monitoring.AwsMonitoringGateway.TargetHealthCount;
import com.tetra.ops.support.FakePrometheus;
import com.tetra.ops.support.IntegrationTest;

/**
 * OPS-05 API 3개 — Prometheus는 HTTP 목, AWS는 게이트웨이 목, 이벤트 · 로그 · 알림은 Testcontainers DB 시드. 캐시 TTL 0(요청마다 조회).
 * 시드(지금 = now, 분 단위):
 * - 7101 A 진행 중(OPEN), 시작 now−10분 — 5xx 1.2% → 주의(1% 이상)
 * - 7102 B 진행 중(OPEN), 시작 now−5분 — 정상
 * - 7104 A 종료(EXPIRED · ENDED), 시작 2일 전 — 결과 요약 있음
 * - 7105 B 준비 실패(OPEN_FAILED), 시작 1일 전 — URGENT 알림 + OPEN_FAILED 로그
 * - 7106 A 예정(SCHEDULED), 시작 2일 뒤(오늘 예정이 아님)
 * 호스팅이 끝난 상태(종료 처리 중 · 종료 · 종료 실패)의 Prometheus · AWS 값은 TTL 0이어도 다시 조회하지 않는다(CLOSED_TTL) —
 * 그 상태를 보는 테스트는 저마다 다른 이벤트를 쓴다.
 */
class TrafficMonitoringApiTest extends IntegrationTest {

	private static final FakePrometheus prometheus = new FakePrometheus();

	@MockitoBean
	AwsMonitoringGateway aws;

	/** 실제 수집 그대로, 한 테스트만 결과 가공 실패를 흉내 낸다 */
	@MockitoSpyBean
	TrafficEventCollector eventCollector;

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
			.respond(TrafficQueries.ERROR_RPS_BY_EVENT, "[{\"metric\":{\"event_id\":\"7101\"},\"value\":[1,\"12\"]}]")
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
			// 'Grafana에서 자세히' 링크 주소 — GRAFANA_URL이 없으면 pf-grafana 포트 포워딩 주소
			.andExpect(jsonPath("$.grafanaUrl").value("http://localhost:13000"))
			// A. 상태 요약
			.andExpect(jsonPath("$.summary[*].area").value(contains("CLUSTER", "EVENT", "AUTOMATION", "PLATFORM")))
			.andExpect(jsonPath("$.summary[0].level").value("DANGER"))
			.andExpect(jsonPath("$.summary[0].reason").value("KEDA 가용 0"))
			.andExpect(jsonPath("$.summary[1].level").value("WARN"))
			.andExpect(jsonPath("$.summary[1].reason").value("7101번 5xx 1.2%"))
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
			// TEST-LEAD: 서비스 값 90
			// .andExpect(jsonPath("$.upcoming[0].prepareAt").value(iso(now.plusDays(2).minusMinutes(90))))
			.andExpect(jsonPath("$.upcoming[0].prepareAt").value(iso(now.plusDays(2).minusMinutes(15)))) // TEST-LEAD
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
	void upcomingSkipsFailedEvents() throws Exception {
		// 배포 · 호스팅 시작 실패는 이벤트가 SCHEDULED로 남아도 열리지 않는다 — 다가오는 이벤트에서 빼고 드롭다운 · 실패 목록이 보여 준다
		data.event(7124, A).name("A 배포 실패").approved("DEPLOY_FAILED", "SCHEDULED", "slug7124").startAt(now.plusDays(1))
			.insert();
		data.event(7125, B).name("B 호스팅 실패").approved("HOSTING_FAILED", "SCHEDULED", "slug7125").startAt(now.plusDays(1))
			.insert();
		respondPlatform();
		mvc.perform(get("/api/ops/monitoring/platform").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.upcoming[*].eventId").value(contains(7106)));
	}

	/** D6: 단계 지연(DELAY) · 시스템(SYSTEM) 긴급 알림은 E 목록에는 보이고, 자동화 칸 "24시간 실패" 수에는 넣지 않는다 */
	@Test
	void delayAndSystemAlertsAreListedButNotCountedAsFailures() throws Exception {
		notification(A, 7101L, "DELAY", now.minusMinutes(30),
				"[A사] [7101:A 쿠폰 이벤트] 입장 시각(10:00)이 5분 지났지만 입장이 열리지 않았습니다.");
		notification(null, null, "SYSTEM", now.minusMinutes(20),
				"예약된 자동화 실행이 시작되지 않았습니다. 스케줄러 DLQ에 메시지 2건이 있습니다.");
		respondPlatform();
		mvc.perform(get("/api/ops/monitoring/platform").cookie(operatorCookie()))
			.andExpect(status().isOk())
			// 24시간 안 알림 2건이 있어도 실패 수("24시간 실패 N")에는 없다 — DLQ · 지연은 자동화 칸이 따로 판정한다
			.andExpect(jsonPath("$.summary[2].level").value("DANGER"))
			.andExpect(jsonPath("$.summary[2].reason").value("DLQ tetra-dev-scheduler-dlq"))
			.andExpect(jsonPath("$.failures.length()").value(4))
			.andExpect(jsonPath("$.failures[0].step").value("SYSTEM"))
			.andExpect(jsonPath("$.failures[0].eventId").value(nullValue()))
			.andExpect(jsonPath("$.failures[1].step").value("DELAY"))
			.andExpect(jsonPath("$.failures[1].eventId").value(7101))
			.andExpect(jsonPath("$.failures[1].eventName").value("A 쿠폰 이벤트"));
	}

	@Test
	void prometheusFailureKeepsOtherSourcesAndMarksStale() throws Exception {
		respondPlatform();
		mvc.perform(get("/api/ops/monitoring/platform").cookie(operatorCookie()))
			.andExpect(jsonPath("$.prometheus.stale").value(false))
			.andExpect(jsonPath("$.prometheus.staleReason").value(nullValue()));
		prometheus.failWith(503);
		mvc.perform(get("/api/ops/monitoring/platform").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.prometheus.stale").value(true))
			.andExpect(jsonPath("$.prometheus.staleReason").value("SOURCE"))
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
			// 5xx = Prometheus 자체 장애(화면: "Prometheus 응답이 늦어 그래프를 받지 못했어요")
			.andExpect(jsonPath("$.prometheus.staleReason").value("SOURCE"))
			.andExpect(jsonPath("$.prometheus.asOf").value(nullValue()))
			.andExpect(jsonPath("$.kpi.level").value("UNKNOWN"))
			.andExpect(jsonPath("$.charts").value(nullValue()));
	}

	@Test
	void chartsSectionFailureKeepsKpiAndIsNotStale() throws Exception {
		// 갱신은 성공했는데 그래프 구역만 가공에 실패: 그래프만 비고 KPI는 그대로, stale 아님(화면 "그래프를 만들지 못했어요")
		live(7120, A, "A 그래프 가공 실패", now.minusMinutes(3));
		respondPlatform();
		doAnswer(inv -> {
			TrafficEventCollector.Metrics m = (TrafficEventCollector.Metrics) inv.callRealMethod();
			return new TrafficEventCollector.Metrics(m.at(), null, m.full(), m.live(), m.signals(), m.prepareSeconds());
		}).when(eventCollector).collect(argThat(req -> req != null && req.eventId() == 7120), anyLong(), any());
		mvc.perform(get("/api/ops/monitoring/events/7120").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.event.phase").value("LIVE"))
			.andExpect(jsonPath("$.prometheus.stale").value(false))
			.andExpect(jsonPath("$.prometheus.staleReason").value(nullValue()))
			.andExpect(jsonPath("$.charts").value(nullValue()))
			.andExpect(jsonPath("$.kpi.live").isNotEmpty());
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
	void scheduledButEndedEventListedOnce() throws Exception {
		// 오늘 시작, DB는 SCHEDULED · DEPLOYED(호스팅 시작 기록 없음), hosting_end_at 지남 → 오늘 예정 · 최근 종료 둘 다에 걸리던 이벤트
		LocalDateTime start = now.minusMinutes(30);
		assumeTrue(start.toLocalDate().equals(now.toLocalDate()));
		data.event(7109, A).name("A 호스팅 안 됨").approved("DEPLOYED", "SCHEDULED", "slug7109").startAt(start).insert();
		data.jdbc().update("UPDATE t_%s.event SET end_at = ?, hosting_end_at = ? WHERE event_id = 7109".formatted(A),
				now.minusMinutes(10), now.minusMinutes(1));
		respondPlatform();
		mvc.perform(get("/api/ops/monitoring/events").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.defaultEventId").value(7101))
			.andExpect(jsonPath("$.items[?(@.eventId == 7109)]").value(hasSize(1)))
			// 최근 종료(늦은 순)의 맨 앞: hosting_end_at now−1분
			.andExpect(jsonPath("$.items[*].eventId").value(contains(7101, 7102, 7109, 7105, 7104)))
			.andExpect(jsonPath("$.items[2].group").value("ENDED"))
			// 호스팅 종료는 지났고 WF4 결과 · 집계가 없음 = 종료 처리 중(2026-10-28 상태 정리)
			.andExpect(jsonPath("$.items[2].phase").value("ENDING"));
	}

	@Test
	void failedEventsInList() throws Exception {
		LocalDateTime todayStart = now.plusMinutes(60);
		LocalDateTime pastStart = now.minusMinutes(30);
		assumeTrue(todayStart.toLocalDate().equals(now.toLocalDate()) && pastStart.toLocalDate().equals(now.toLocalDate()));
		// 배포 실패: 시각과 상관없이 최근 종료, 기준 · 표시 시각은 실패 로그
		data.event(7115, A).name("A 배포 실패").approved("DEPLOY_FAILED", "SCHEDULED", "slug7115").startAt(now.plusDays(3))
			.insert();
		data.statusLog(A, 7115, "HOSTING", "PENDING", "DEPLOY_FAILED", now.minusMinutes(2), "ECR 이미지 없음");
		// 호스팅 실패, hosting_end_at 전: 시각대로(오늘 예정)
		data.event(7116, A).name("A 호스팅 실패").approved("HOSTING_FAILED", "SCHEDULED", "slug7116").startAt(todayStart)
			.insert();
		// 호스팅 실패, hosting_end_at 지남: 최근 종료
		data.event(7117, A).name("A 호스팅 실패 지남").approved("HOSTING_FAILED", "SCHEDULED", "slug7117")
			.startAt(pastStart)
			.insert();
		data.jdbc().update("UPDATE t_%s.event SET end_at = ?, hosting_end_at = ? WHERE event_id = 7117".formatted(A),
				now.minusMinutes(10), now.minusMinutes(1));
		respondPlatform();
		mvc.perform(get("/api/ops/monitoring/events").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].eventId").value(contains(7101, 7102, 7116, 7117, 7115, 7105, 7104)))
			.andExpect(jsonPath("$.items[2].group").value("TODAY"))
			.andExpect(jsonPath("$.items[2].phase").value("HOSTING_FAILED"))
			.andExpect(jsonPath("$.items[3].group").value("ENDED"))
			.andExpect(jsonPath("$.items[3].phase").value("HOSTING_FAILED"))
			.andExpect(jsonPath("$.items[4].group").value("ENDED"))
			.andExpect(jsonPath("$.items[4].phase").value("DEPLOY_FAILED"))
			.andExpect(jsonPath("$.items[4].failedAt").value(iso(now.minusMinutes(2))))
			// 준비 실패도 실패 시각을 준다(표시는 입장 예정 시각 그대로)
			.andExpect(jsonPath("$.items[5].failedAt").value(iso(now.minusDays(1).minusMinutes(59))))
			.andExpect(jsonPath("$.items[6].failedAt").value(nullValue()));
	}

	@Test
	void noEventsMeansNoDefault() throws Exception {
		data.reset();
		mvc.perform(get("/api/ops/monitoring/events").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.defaultEventId").value(nullValue()))
			.andExpect(jsonPath("$.items.length()").value(0));
		// 상태 요약 이벤트 칸: 진행 중 묶음은 입장 중도 포함
		mvc.perform(get("/api/ops/monitoring/platform").cookie(operatorCookie()))
			.andExpect(jsonPath("$.summary[1].area").value("EVENT"))
			.andExpect(jsonPath("$.summary[1].openCount").value(0))
			.andExpect(jsonPath("$.summary[1].reason").value("진행 중(입장 중 · 마무리 포함) 이벤트 없음"));
	}

	// ---------------------------------------------------------------- event detail

	private void respondLiveEvent() {
		respondEvent(A, 7101);
	}

	/** 진행 중 이벤트 값 한 벌(7101과 같은 값) */
	private void respondEvent(String tenantId, long eventId) {
		TrafficQueries.EventQueries q = TrafficQueries.forEvent(tenantId, eventId);
		both(TrafficQueries.KUBE_STATE_METRICS_UP, "3");
		both(q.rps(), "1131.24");
		both(q.errorRps(), "13.57");
		prometheus.respond(q.p95ByUri(), r -> isRange(r)
				? matrix(r, "{\"uri\":\"/api/issuance/events/{eventId}/coupons/claim\"}", i -> "0.18")
				: """
						[{"metric":{"uri":"/api/issuance/events/{eventId}/coupons/claim"},"value":[1,"0.18"]},
						 {"metric":{"uri":"/api/issuance/events/{eventId}/ticket"},"value":[1,"0.12"]}]""");
		both(q.cursorChanges(), "3");
		// 15초 간격 그래프라 입장 · 입장 허용 창은 1분
		both(q.admittedPerSec(TrafficQueries.MIN_RANGE_WINDOW), "0.17");
		both(q.cursorPerSec(TrafficQueries.MIN_RANGE_WINDOW), "54");
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
		// Redis 연결됨 = 끊김(redisDown · redisDownPoints) 시리즈 없음
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
			// TEST-LEAD: 서비스 값 70(시작 now−10 − 60)
			// .andExpect(jsonPath("$.event.entryAt").value(iso(now.minusMinutes(70))))
			.andExpect(jsonPath("$.event.entryAt").value(iso(now.minusMinutes(15)))) // TEST-LEAD
			.andExpect(jsonPath("$.event.endAt").value(iso(now.minusMinutes(10).plusHours(5))))
			// 진행 단계
			.andExpect(jsonPath("$.steps[*].key")
				.value(contains("APPROVE", "DEPLOY", "HOSTING_START", "PREPARE", "ENTRY", "START", "END", "AGGREGATE")))
			.andExpect(jsonPath("$.steps[*].state")
				.value(contains("DONE", "DONE", "DONE", "DONE", "DONE", "ACTIVE", "WAITING", "WAITING")))
			// 배포: DEPLOYED 이후라 완료, → DEPLOYED 로그가 없어 시각 없음. 호스팅 시작: HOSTING 로그 시각
			.andExpect(jsonPath("$.steps[1].actualAt").value(nullValue()))
			.andExpect(jsonPath("$.steps[2].actualAt").value(iso(now.minusMinutes(10).minusHours(2))))
			// TEST-LEAD: 서비스 값 100(시작 now−10 − 90)
			// .andExpect(jsonPath("$.steps[3].plannedAt").value(iso(now.minusMinutes(100))))
			.andExpect(jsonPath("$.steps[3].plannedAt").value(iso(now.minusMinutes(25)))) // TEST-LEAD
			.andExpect(jsonPath("$.steps[3].actualAt").value(iso(now.minusMinutes(70).plusSeconds(20))))
			.andExpect(jsonPath("$.steps[3].durationSeconds").value(200))
			.andExpect(jsonPath("$.steps[5].actualAt").value(iso(now.minusMinutes(10))))
			// KPI
			.andExpect(jsonPath("$.kpi.level").value("WARN"))
			.andExpect(jsonPath("$.kpi.reason").value("5xx 1.2%"))
			.andExpect(jsonPath("$.kpi.warn").value(contains("errorRatio")))
			.andExpect(jsonPath("$.kpi.live.rps").value(1131.2))
			.andExpect(jsonPath("$.kpi.live.errorRatio").value(0.012))
			.andExpect(jsonPath("$.kpi.live.p95Ms").value(180))
			.andExpect(jsonPath("$.kpi.live.p95TicketMs").value(120))
			.andExpect(jsonPath("$.kpi.live.podsReady").value(8))
			.andExpect(jsonPath("$.kpi.live.podsMax").value(12))
			.andExpect(jsonPath("$.kpi.live.podsPending").value(0))
			.andExpect(jsonPath("$.kpi.live.waiting").value(2400))
			.andExpect(jsonPath("$.kpi.live.stockRemaining").value(3100))
			.andExpect(jsonPath("$.kpi.live.stockTotal").value(10000))
			.andExpect(jsonPath("$.kpi.live.redisMemoryRatio").value(0.38))
			.andExpect(jsonPath("$.kpi.live.redisConnection").value("UP"))
			.andExpect(jsonPath("$.kpi.live.redisRestarts").value(0))
			.andExpect(jsonPath("$.kpi.summary").value(nullValue()))
			// TEST-LEAD: 서비스 값 80분 → 30초
			// // 범위 · 그래프: 입장 −10분 ~ 지금(80분 → 30초 간격)
			// 범위 · 그래프: 입장 −10분 ~ 지금(25분 → 15초 간격) // TEST-LEAD
			.andExpect(jsonPath("$.range.key").value("entry"))
			.andExpect(jsonPath("$.range.options").value(contains("entry", "15m", "1h")))
			// TEST-LEAD: 서비스 값 30
			// .andExpect(jsonPath("$.range.stepSeconds").value(30))
			.andExpect(jsonPath("$.range.stepSeconds").value(15)) // TEST-LEAD
			.andExpect(jsonPath("$.charts.traffic.rps[0]").value(1131.24))
			// 트래픽 그래프는 RPS만, 입장 · 입장 허용은 입장 그래프
			.andExpect(jsonPath("$.charts.traffic.cursorPerSec").doesNotExist())
			.andExpect(jsonPath("$.charts.admission.admittedPerSec[0]").value(0.17))
			.andExpect(jsonPath("$.charts.admission.cursorPerSec[0]").value(54.0))
			.andExpect(jsonPath("$.charts.errors.app[0]").value(0.012))
			.andExpect(jsonPath("$.charts.latency.issuance[0]").value(180.0))
			.andExpect(jsonPath("$.charts.latency.ticket").value(everyItem(nullValue())))
			.andExpect(jsonPath("$.charts.pods.pending").value(everyItem(is(0.0))))
			.andExpect(jsonPath("$.charts.pods.max").value(12))
			.andExpect(jsonPath("$.charts.rds.cpu[0][1]").value(48.0));
		assertThat(prometheus.requests()).extracting(FakePrometheus.Request::query)
			.doesNotContain(TrafficQueries.forEvent(B, 7106).rps());
	}

	@Test
	void redisDownWithinOneMinuteIsCheckingThenDanger() throws Exception {
		respondLiveEvent();
		TrafficQueries.EventQueries q = TrafficQueries.forEvent(A, 7101);
		// 지금 끊김, 1분 안(15초 점 2개) → 주의(5xx 1.2% 주의보다 먼저) · Redis 칸 '확인 중'
		prometheus.respond(q.redisDown(), vector("0")).respond(q.redisDownPoints(), vector("2"));
		mvc.perform(get("/api/ops/monitoring/events/7101").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.kpi.level").value("WARN"))
			.andExpect(jsonPath("$.kpi.reason").value("Redis 연결 확인 중"))
			.andExpect(jsonPath("$.kpi.warn").value(contains("errorRatio", "redisConnection")))
			.andExpect(jsonPath("$.kpi.live.redisConnection").value("CHECKING"));
		// 1분 내내(4점) → 위험 · '끊김'
		prometheus.respond(q.redisDownPoints(), vector("4"));
		mvc.perform(get("/api/ops/monitoring/events/7101").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.kpi.level").value("DANGER"))
			.andExpect(jsonPath("$.kpi.reason").value("Redis 연결 끊김"))
			.andExpect(jsonPath("$.kpi.live.redisConnection").value("DOWN"));
	}

	@Test
	void redisDownInStatusSummary() throws Exception {
		respondPlatform();
		String down = "[{\"metric\":{\"tenant_id\":\"%s\",\"event_id\":\"7102\"},\"value\":[1,\"%s\"]}]";
		prometheus.respond(TrafficQueries.REDIS_DOWN_BY_EVENT, down.formatted(B, "0"))
			.respond(TrafficQueries.REDIS_DOWN_POINTS_BY_EVENT, down.formatted(B, "4"));
		mvc.perform(get("/api/ops/monitoring/platform").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.summary[1].level").value("DANGER"))
			.andExpect(jsonPath("$.summary[1].reason").value("7102번 Redis 연결 끊김"));
		// 1분 안: 7101(5xx 주의)과 같은 주의 — 시작이 늦은 7102가 먼저
		prometheus.respond(TrafficQueries.REDIS_DOWN_POINTS_BY_EVENT, down.formatted(B, "1"));
		mvc.perform(get("/api/ops/monitoring/platform").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.summary[1].level").value("WARN"))
			.andExpect(jsonPath("$.summary[1].reason").value("7102번 Redis 연결 확인 중"));
	}

	@Test
	void entryOpenBeforeStart() throws Exception {
		// 입장은 열렸고(OPEN 로그) 시작은 3분 뒤: 입장이 지금 단계, 시작은 예정
		LocalDateTime start = now.plusMinutes(3);
		data.event(7118, A).name("A 입장 중").approved("HOSTING", "OPEN", "slug7118").startAt(start).insert();
		approvedLogs(A, 7118, start);
		LocalDateTime openedAt = EntryOpen.at(start).plusSeconds(20);
		data.statusLog(A, 7118, "EVENT", "SCHEDULED", "OPEN", openedAt, null);
		respondPlatform();
		mvc.perform(get("/api/ops/monitoring/events/7118").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.event.phase").value("ENTRY_OPEN"))
			.andExpect(jsonPath("$.steps[*].state")
				.value(contains("DONE", "DONE", "DONE", "DONE", "ACTIVE", "WAITING", "WAITING", "WAITING")))
			// 준비 · 입장 모두 OPEN 로그 시각(따로 준비 완료 시각이 없음)
			.andExpect(jsonPath("$.steps[3].actualAt").value(iso(openedAt)))
			.andExpect(jsonPath("$.steps[4].key").value("ENTRY"))
			.andExpect(jsonPath("$.steps[4].actualAt").value(iso(openedAt)))
			.andExpect(jsonPath("$.steps[5].key").value("START"))
			.andExpect(jsonPath("$.steps[5].plannedAt").value(iso(start)))
			.andExpect(jsonPath("$.steps[5].actualAt").value(nullValue()))
			// 그래프 · KPI는 진행 중과 같은 모양(Prometheus 조회)
			.andExpect(jsonPath("$.range.key").value("entry"))
			.andExpect(jsonPath("$.kpi").isNotEmpty())
			// 발급 · 번호표 요청이 아직 없어 p95가 둘 다 비어도 갱신은 성공(2026-10-28 NPE로 전체가 stale · 그래프 없음이던 것)
			.andExpect(jsonPath("$.prometheus.stale").value(false))
			.andExpect(jsonPath("$.prometheus.staleReason").value(nullValue()))
			.andExpect(jsonPath("$.charts.timestamps").isNotEmpty())
			.andExpect(jsonPath("$.kpi.live").isNotEmpty())
			.andExpect(jsonPath("$.kpi.live.p95Ms").value(nullValue()));
		// 목록: DB 상태 OPEN이라 진행 중 묶음, phase는 입장 중
		mvc.perform(get("/api/ops/monitoring/events").cookie(operatorCookie()))
			.andExpect(jsonPath("$.items[?(@.eventId == 7118)].group").value(contains("OPEN")))
			.andExpect(jsonPath("$.items[?(@.eventId == 7118)].phase").value(contains("ENTRY_OPEN")));
	}

	@Test
	void preEntryDrawsChartsWithoutP95() throws Exception {
		// 입장 3분 전(입장 직전): 요청 처리 · p95 순간값은 조회하지 않는다 — 그래도 갱신은 성공(2026-10-28 NPE로 늘 stale이던 상태)
		LocalDateTime start = now.plus(EntryOpen.ENTRY_OPEN_LEAD).plusMinutes(3);
		data.event(7119, A).name("A 입장 직전").approved("HOSTING", "SCHEDULED", "slug7119").startAt(start).insert();
		approvedLogs(A, 7119, start);
		respondPlatform();
		// redis-exporter 지표는 없고 재시작(kube-state-metrics)만 0 → 재시작도 비운다(메모리 · 연결처럼 "–")
		both(TrafficQueries.forEvent(A, 7119).redisRestarts(), "0");
		mvc.perform(get("/api/ops/monitoring/events/7119").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.event.phase").value("PRE_ENTRY"))
			.andExpect(jsonPath("$.prometheus.stale").value(false))
			.andExpect(jsonPath("$.prometheus.staleReason").value(nullValue()))
			.andExpect(jsonPath("$.charts.timestamps").isNotEmpty())
			.andExpect(jsonPath("$.kpi.live").isNotEmpty())
			.andExpect(jsonPath("$.kpi.live.rps").value(nullValue()))
			.andExpect(jsonPath("$.kpi.live.p95Ms").value(nullValue()))
			.andExpect(jsonPath("$.kpi.live.redisMemoryRatio").value(nullValue()))
			.andExpect(jsonPath("$.kpi.live.redisRestarts").value(nullValue()))
			// 준비가 진행 중(계획 시각 지남, OPEN 전), 입장은 예정 — 진행 중은 하나뿐
			.andExpect(jsonPath("$.steps[3].state").value("ACTIVE"))
			.andExpect(jsonPath("$.steps[4].state").value("WAITING"));
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
		// 전체 구간(입장 −10분 ~ 호스팅 종료 +10분, 5시간 넘음 ÷ 300점)은 간격이 1분보다 크다 → p95 · 입장 · 입장 허용 창 = 간격
		LocalDateTime start = now.minusDays(2);
		Duration step = TrafficWindow.step(
				Duration.between(EntryOpen.at(start).minusMinutes(10), start.plusHours(5).plusMinutes(10)),
				Duration.ofSeconds(15));
		assertThat(step).isGreaterThan(Duration.ofMinutes(1));
		assertThat(TrafficQueries.rangeWindow(step)).isEqualTo(step);
		both(q.admittedPerSec(step), "0.17");
		both(q.cursorPerSec(step), "54");
		both(TrafficQueries.KUBE_STATE_METRICS_UP, "3");
		// 0번 점(입장 −10분)은 입장 전 웜업 — 최종값에 넣지 않는다
		prometheus.respond(q.rps(), r -> matrix(r, "{}", i -> i == 10 ? "1820" : i == 0 ? "99999" : "100"));
		both(q.errorRps(), "0.3");
		both(q.waiting(), "0");
		prometheus.respond(q.stock(), r -> matrix(r, "{}", i -> Long.toString(Math.max(0, 10000 - i * 100))));
		both(q.hpaMax(), "12");
		prometheus.respond(q.apiReady(), r -> matrix(r, "{}", i -> i < 5 ? "6" : "10"));
		mvc.perform(get("/api/ops/monitoring/events/7104").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.event.phase").value("ENDED"))
			.andExpect(jsonPath("$.steps[*].state")
				.value(contains("DONE", "DONE", "DONE", "DONE", "DONE", "DONE", "DONE", "DONE")))
			.andExpect(jsonPath("$.steps[6].actualAt").value(iso(now.minusDays(2).plusHours(5))))
			// 이벤트 진행: 시작 ~ end_at(시작 +3시간)
			.andExpect(jsonPath("$.steps[5].actualAt").value(iso(now.minusDays(2))))
			.andExpect(jsonPath("$.steps[5].endAt").value(iso(now.minusDays(2).plusHours(3))))
			.andExpect(jsonPath("$.event.eventEndAt").value(iso(now.minusDays(2).plusHours(3))))
			.andExpect(jsonPath("$.kpi.live").value(nullValue()))
			.andExpect(jsonPath("$.kpi.summary.peakRps.value").value(1820.0))
			.andExpect(jsonPath("$.kpi.summary.peakRps.at").isString())
			// 평균도 입장 ~ 이벤트 종료(end_at = 시작 +3시간)만: 0.3 × n ÷ (100 × (n − 1) + 1820)
			.andExpect(jsonPath("$.kpi.summary.errorRatioAvg").value(closeTo(0.0027, 0.00005)))
			// 대기 인원 최대 0 → 시각 없음
			.andExpect(jsonPath("$.kpi.summary.peakWaiting.value").value(0.0))
			.andExpect(jsonPath("$.kpi.summary.peakWaiting.at").value(nullValue()))
			.andExpect(jsonPath("$.kpi.summary.podsMax").value(10))
			.andExpect(jsonPath("$.kpi.summary.podsLimit").value(12))
			// 발급은 결과 요약 success_count 우선
			.andExpect(jsonPath("$.kpi.summary.issued").value(9999))
			.andExpect(jsonPath("$.kpi.summary.stockTotal").value(10000))
			.andExpect(jsonPath("$.kpi.summary.soldOutAt").isString())
			.andExpect(jsonPath("$.range.key").value("full"))
			.andExpect(jsonPath("$.range.options").value(contains("full", "start")))
			// TEST-LEAD: 서비스 값 70(입장 −60 · −10)
			// .andExpect(jsonPath("$.range.from").value(iso(now.minusDays(2).minusMinutes(70))))
			.andExpect(jsonPath("$.range.from").value(iso(now.minusDays(2).minusMinutes(15)))) // TEST-LEAD
			.andExpect(jsonPath("$.range.to").value(iso(now.minusDays(2).plusHours(5).plusMinutes(10))))
			.andExpect(jsonPath("$.range.stepSeconds").value((int) step.toSeconds()))
			.andExpect(jsonPath("$.charts.admission.admittedPerSec[0]").value(0.17))
			.andExpect(jsonPath("$.charts.admission.cursorPerSec[0]").value(54.0));
		assertThat(prometheus.requests()).extracting(FakePrometheus.Request::query)
			.contains(q.admittedPerSec(step), q.cursorPerSec(step), q.p95ByUri(step));
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
				.value(contains("DONE", "DONE", "DONE", "FAILED", "SKIPPED", "SKIPPED", "SKIPPED", "SKIPPED")))
			.andExpect(jsonPath("$.steps[3].reason").value("동결 확인 시간 초과"))
			.andExpect(jsonPath("$.kpi").value(nullValue()))
			.andExpect(jsonPath("$.range").value(nullValue()))
			.andExpect(jsonPath("$.charts").value(nullValue()));
		mvc.perform(get("/api/ops/monitoring/events/7106").cookie(operatorCookie()))
			.andExpect(jsonPath("$.event.phase").value("SCHEDULED"))
			.andExpect(jsonPath("$.steps[*].state")
				.value(contains("DONE", "DONE", "WAITING", "WAITING", "WAITING", "WAITING", "WAITING", "WAITING")))
			.andExpect(jsonPath("$.kpi").value(nullValue()))
			.andExpect(jsonPath("$.charts").value(nullValue()));
		assertThat(prometheus.requests()).isEmpty();
	}

	@Test
	void deployStepByHostingStatus() throws Exception {
		LocalDateTime start = now.plusDays(1);
		// 배포 중(PENDING): 예정 상태여도 배포 칸이 지금 단계
		data.event(7110, A).name("A 배포 중").approved("PENDING", "SCHEDULED", "slug7110").startAt(start).insert();
		mvc.perform(get("/api/ops/monitoring/events/7110").cookie(operatorCookie()))
			.andExpect(jsonPath("$.event.phase").value("SCHEDULED"))
			.andExpect(jsonPath("$.steps[*].state")
				.value(contains("DONE", "ACTIVE", "WAITING", "WAITING", "WAITING", "WAITING", "WAITING", "WAITING")));

		// 배포 완료: 시각은 마지막 → DEPLOYED 로그(재시도 DEPLOY_FAILED → DEPLOYED)
		data.event(7111, A).name("A 배포 완료").approved("DEPLOYED", "SCHEDULED", "slug7111").startAt(start).insert();
		data.statusLog(A, 7111, "HOSTING", "PENDING", "DEPLOY_FAILED", now.minusMinutes(20), "시간 초과");
		data.statusLog(A, 7111, "HOSTING", "DEPLOY_FAILED", "DEPLOYED", now.minusMinutes(19), null);
		mvc.perform(get("/api/ops/monitoring/events/7111").cookie(operatorCookie()))
			.andExpect(jsonPath("$.steps[1].state").value("DONE"))
			.andExpect(jsonPath("$.steps[1].actualAt").value(iso(now.minusMinutes(19))));

		// 배포 실패: 빨강 + 사유, 뒤 단계 전부 진행 안 됨(승인은 완료)
		data.event(7112, A).name("A 배포 실패").approved("DEPLOY_FAILED", "SCHEDULED", "slug7112").startAt(start).insert();
		data.statusLog(A, 7112, "HOSTING", "PENDING", "DEPLOY_FAILED", now.minusMinutes(5), "ECR 이미지 없음");
		mvc.perform(get("/api/ops/monitoring/events/7112").cookie(operatorCookie()))
			.andExpect(jsonPath("$.event.phase").value("DEPLOY_FAILED"))
			.andExpect(jsonPath("$.steps[*].state")
				.value(contains("DONE", "FAILED", "SKIPPED", "SKIPPED", "SKIPPED", "SKIPPED", "SKIPPED", "SKIPPED")))
			.andExpect(jsonPath("$.steps[1].actualAt").value(iso(now.minusMinutes(5))))
			.andExpect(jsonPath("$.steps[1].reason").value("ECR 이미지 없음"))
			.andExpect(jsonPath("$.kpi").value(nullValue()))
			.andExpect(jsonPath("$.charts").value(nullValue()));
		assertThat(prometheus.requests()).isEmpty();
	}

	@Test
	void hostingStartByHostingStatus() throws Exception {
		LocalDateTime start = now.plusDays(1);
		// 호스팅 중인데 → HOSTING 로그가 없음: 상태로 완료, 시각 없음
		data.event(7113, A).name("A 로그 누락").approved("HOSTING", "SCHEDULED", "slug7113").startAt(start).insert();
		mvc.perform(get("/api/ops/monitoring/events/7113").cookie(operatorCookie()))
			.andExpect(jsonPath("$.steps[2].key").value("HOSTING_START"))
			.andExpect(jsonPath("$.steps[2].state").value("DONE"))
			.andExpect(jsonPath("$.steps[2].actualAt").value(nullValue()));

		// 호스팅 실패: 준비 · 입장 · 시작 · 집계 진행 안 됨, 종료는 자기 기록대로(아직 없으면 진행 안 됨)
		data.event(7114, A).name("A 호스팅 실패").approved("HOSTING_FAILED", "SCHEDULED", "slug7114").startAt(start).insert();
		data.statusLog(A, 7114, "HOSTING", "DEPLOYED", "HOSTING_FAILED", now.minusMinutes(3), "Ingress 생성 실패");
		mvc.perform(get("/api/ops/monitoring/events/7114").cookie(operatorCookie()))
			.andExpect(jsonPath("$.event.phase").value("HOSTING_FAILED"))
			.andExpect(jsonPath("$.steps[*].state")
				.value(contains("DONE", "DONE", "FAILED", "SKIPPED", "SKIPPED", "SKIPPED", "SKIPPED", "SKIPPED")))
			.andExpect(jsonPath("$.steps[2].reason").value("Ingress 생성 실패"))
			.andExpect(jsonPath("$.charts").value(nullValue()));

		data.statusLog(A, 7114, "HOSTING", "HOSTING_FAILED", "ENDED", now.minusMinutes(1), null);
		mvc.perform(get("/api/ops/monitoring/events/7114").cookie(operatorCookie()))
			.andExpect(jsonPath("$.steps[6].key").value("END"))
			.andExpect(jsonPath("$.steps[6].state").value("DONE"));
	}

	/** 시작 · 종료 시각을 바꾼 진행 중 이벤트(DB OPEN · HOSTING) */
	private void liveWithEnds(long eventId, LocalDateTime start, LocalDateTime endAt, LocalDateTime hostingEndAt) {
		live(eventId, A, "A " + eventId, start);
		data.jdbc().update("UPDATE t_%s.event SET end_at = ?, hosting_end_at = ? WHERE event_id = ?".formatted(A), endAt,
				hostingEndAt, eventId);
	}

	/** WF4 결과: registry · 테넌트 event 상태 + 상태 로그 */
	private void hostingResult(long eventId, String hostingStatus, String eventStatus, LocalDateTime at, String detail) {
		data.jdbc().update("UPDATE tetra_platform.event_registry SET hosting_status = ?, event_status = ? WHERE event_id = ?",
				hostingStatus, eventStatus, eventId);
		data.jdbc().update("UPDATE t_%s.event SET hosting_status = ?, event_status = ? WHERE event_id = ?".formatted(A),
				hostingStatus, eventStatus, eventId);
		data.statusLog(A, eventId, "HOSTING", "HOSTING", hostingStatus, at, detail);
	}

	@Test
	void wrapUpKeepsLiveValuesAndCountsAsOpen() throws Exception {
		// 이벤트 종료(end_at) 2분 지남, 호스팅 종료는 8분 뒤: 마무리 — KPI · 그래프는 진행 중처럼, 이벤트 종료 점선
		liveWithEnds(7121, now.minusMinutes(20), now.minusMinutes(2), now.plusMinutes(8));
		respondPlatform();
		respondEvent(A, 7121);
		// 커서가 멈췄지만 이벤트가 끝나 정상 — 진행 중이면 "커서 1분 정지"(위험)
		both(TrafficQueries.forEvent(A, 7121).cursorChanges(), "0");
		mvc.perform(get("/api/ops/monitoring/events/7121").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.event.phase").value("WRAP_UP"))
			.andExpect(jsonPath("$.event.eventEndAt").value(iso(now.minusMinutes(2))))
			.andExpect(jsonPath("$.kpi.live.rps").value(1131.2))
			.andExpect(jsonPath("$.kpi.level").value("WARN"))
			.andExpect(jsonPath("$.kpi.reason").value("5xx 1.2%"))
			.andExpect(jsonPath("$.range.key").value("entry"))
			.andExpect(jsonPath("$.range.options").value(contains("entry", "15m", "1h")))
			.andExpect(jsonPath("$.charts.timestamps").isNotEmpty())
			// 이벤트 진행은 끝(시작 ~ end_at), 호스팅 종료는 예정 — 기다리는 구간이라 진행 중 단계 없음
			.andExpect(jsonPath("$.steps[*].state")
				.value(contains("DONE", "DONE", "DONE", "DONE", "DONE", "DONE", "WAITING", "WAITING")))
			.andExpect(jsonPath("$.steps[5].actualAt").value(iso(now.minusMinutes(20))))
			.andExpect(jsonPath("$.steps[5].endAt").value(iso(now.minusMinutes(2))))
			.andExpect(jsonPath("$.steps[6].plannedAt").value(iso(now.plusMinutes(8))));
		// 상태 요약 · 목록: 진행 중 묶음에 센다(7101 · 7102 · 7121)
		mvc.perform(get("/api/ops/monitoring/platform").cookie(operatorCookie()))
			.andExpect(jsonPath("$.summary[1].openCount").value(3));
		mvc.perform(get("/api/ops/monitoring/events").cookie(operatorCookie()))
			.andExpect(jsonPath("$.items[?(@.eventId == 7121)].group").value(contains("OPEN")))
			.andExpect(jsonPath("$.items[?(@.eventId == 7121)].phase").value(contains("WRAP_UP")));
	}

	@Test
	void afterHostingEndFreezesMetricsAndRefreshesStepsOnly() throws Exception {
		// 호스팅 종료 1분 지남, DB는 아직 OPEN · HOSTING(WF4 결과 전): 종료 처리 중
		LocalDateTime start = now.minusMinutes(40);
		LocalDateTime endAt = now.minusMinutes(15);
		LocalDateTime hostingEnd = now.minusMinutes(1);
		liveWithEnds(7122, start, endAt, hostingEnd);
		respondPlatform();
		respondEvent(A, 7122);
		mvc.perform(get("/api/ops/monitoring/events/7122").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.event.phase").value("ENDING"))
			// 호스팅 종료(WF4) 진행 중 = "종료 처리 중", 집계는 아직
			.andExpect(jsonPath("$.steps[*].state")
				.value(contains("DONE", "DONE", "DONE", "DONE", "DONE", "DONE", "ACTIVE", "WAITING")))
			// 최종값 · 범위 고정: 입장 −10분 ~ 호스팅 종료 +10분(아직 미래여도 지금으로 자르지 않음)
			.andExpect(jsonPath("$.kpi.live").value(nullValue()))
			.andExpect(jsonPath("$.kpi.summary.peakRps.value").value(1131.24))
			.andExpect(jsonPath("$.range.key").value("full"))
			.andExpect(jsonPath("$.range.options").value(contains("full", "start")))
			.andExpect(jsonPath("$.range.from").value(iso(EntryOpen.at(start).minusMinutes(10))))
			.andExpect(jsonPath("$.range.to").value(iso(hostingEnd.plusMinutes(10))));

		// 상태 요약 · 목록: DB가 아직 OPEN이어도 호스팅 종료 뒤는 진행 중에서 빼고 최근 종료로(이벤트 12의 "진행 중 1")
		mvc.perform(get("/api/ops/monitoring/platform").cookie(operatorCookie()))
			.andExpect(jsonPath("$.summary[1].openCount").value(2));
		mvc.perform(get("/api/ops/monitoring/events").cookie(operatorCookie()))
			.andExpect(jsonPath("$.items[?(@.eventId == 7122)].group").value(contains("ENDED")))
			.andExpect(jsonPath("$.items[?(@.eventId == 7122)].phase").value(contains("ENDING")))
			.andExpect(jsonPath("$.items[?(@.eventId == 7122)].endAt").value(contains(iso(hostingEnd))));
		int queried = prometheus.requests().size();
		clearInvocations(aws);

		// 다시 불러도(15초 갱신) Prometheus · CloudWatch는 다시 조회하지 않고 진행 단계(DB)만 새로: WF4 성공 → 집계 중
		hostingResult(7122, "ENDED", "EXPIRED", now, null);
		mvc.perform(get("/api/ops/monitoring/events/7122").cookie(operatorCookie()))
			.andExpect(jsonPath("$.event.phase").value("ENDING"))
			.andExpect(jsonPath("$.steps[*].state")
				.value(contains("DONE", "DONE", "DONE", "DONE", "DONE", "DONE", "DONE", "ACTIVE")))
			.andExpect(jsonPath("$.steps[6].actualAt").value(iso(now)))
			.andExpect(jsonPath("$.kpi.summary.peakRps.value").value(1131.24));
		assertThat(prometheus.requests()).hasSize(queried);
		verify(aws, never()).metricData(anyList(), any(), any());

		// 집계 완료 = 종료(진행 단계 모두 끝)
		data.jdbc().update("""
				INSERT INTO t_%s.event_result_summary (event_id, tenant_id, session_count, ticket_count, peak_concurrent,
				    metrics_finalized_at, total_stock, success_count, soldout_count, history_generated_at)
				VALUES (7122, ?, 10, 10, 5, ?, 10000, 15, 0, ?)""".formatted(A), A, now, now);
		mvc.perform(get("/api/ops/monitoring/events/7122").cookie(operatorCookie()))
			.andExpect(jsonPath("$.event.phase").value("ENDED"))
			.andExpect(jsonPath("$.steps[*].state")
				.value(contains("DONE", "DONE", "DONE", "DONE", "DONE", "DONE", "DONE", "DONE")))
			.andExpect(jsonPath("$.kpi.summary.issued").value(15));
	}

	@Test
	void hostingEndFailureIsFinalWithoutAggregation() throws Exception {
		LocalDateTime hostingEnd = now.minusMinutes(5);
		liveWithEnds(7123, now.minusMinutes(40), now.minusMinutes(15), hostingEnd);
		hostingResult(7123, "END_FAILED", "OPEN", now.minusMinutes(3), "FAILED: States.TaskFailed / "
				+ "{\"errorMessage\":\"namespace delete timed out\",\"stackTrace\":[\"at x\"],\"token\":\"abc123\"}");
		respondPlatform();
		respondEvent(A, 7123);
		mvc.perform(get("/api/ops/monitoring/events/7123").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.event.phase").value("END_FAILED"))
			.andExpect(jsonPath("$.steps[*].state")
				.value(contains("DONE", "DONE", "DONE", "DONE", "DONE", "DONE", "FAILED", "SKIPPED")))
			// 실패 사유는 JSON 원문 대신 요약(비밀값 가림)
			.andExpect(jsonPath("$.steps[6].reason").value("FAILED: States.TaskFailed · namespace delete timed out"))
			.andExpect(jsonPath("$.kpi.summary").isNotEmpty())
			.andExpect(jsonPath("$.range.key").value("full"));
		mvc.perform(get("/api/ops/monitoring/platform").cookie(operatorCookie()))
			.andExpect(jsonPath("$.failures[0].eventId").value(7123))
			.andExpect(jsonPath("$.failures[0].step").value("END"))
			.andExpect(jsonPath("$.failures[0].reason").value("FAILED: States.TaskFailed · namespace delete timed out"));
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
