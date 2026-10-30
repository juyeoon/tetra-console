package com.tetra.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.tetra.ops.monitoring.AwsMonitoringException;
import com.tetra.ops.monitoring.AwsMonitoringGateway;
import com.tetra.ops.support.IntegrationTest;
import com.tetra.ops.support.TestData;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** OPS-08 알림센터: 목록(최신순 · 테넌트명 · 칩 · 이동 경로 · 현재 상태) · 읽음 · 모두 읽음. */
@ExtendWith(OutputCaptureExtension.class)
class NotificationCenterApiTest extends IntegrationTest {

	static final String APPROVE_DLQ = "tetra-test-approve-result-dlq";

	static final String SCHEDULER_DLQ = "tetra-test-scheduler-dlq";

	/** 테스트용 비밀번호 해시(응답 · 로그에 나오면 안 됨) */
	static final String PASSWORD_HASH = "$2a$10$notificationCenterTestHashValue0123456789abcdefghijkl";

	@MockitoBean
	AwsMonitoringGateway aws;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		// 테스트마다 OPS-05 DB 캐시 · DLQ 메시지 수를 다시 읽는다
		registry.add("tetra.monitoring.cache-ttl", () -> "PT0S");
		registry.add("tetra.monitoring.aws.cache-ttl", () -> "PT0S");
		registry.add("tetra.monitoring.aws.dlq-names", () -> APPROVE_DLQ + "," + SCHEDULER_DLQ);
	}

	private long insert(String tenantId, Long eventId, String priority, LocalDateTime occurredAt, String content,
			boolean read) {
		data.jdbc().update("""
				INSERT INTO tetra_platform.operator_notification
				    (tenant_id, event_id, category, priority, occurred_at, content, is_read)
				VALUES (?, ?, 'HOSTING', ?, ?, ?, ?)""", tenantId, eventId, priority, occurredAt, content, read);
		return data.jdbc().queryForObject("SELECT MAX(notification_id) FROM tetra_platform.operator_notification", Long.class);
	}

	private long insert(String tenantId, Long eventId, String category, String priority, String dedupeKey) {
		data.jdbc().update("""
				INSERT INTO tetra_platform.operator_notification
				    (tenant_id, event_id, category, priority, occurred_at, content, is_read, dedupe_key)
				VALUES (?, ?, ?, ?, ?, ?, 0, ?)""", tenantId, eventId, category, priority, TestData.BASE,
				category + " 알림", dedupeKey);
		return data.jdbc().queryForObject("SELECT MAX(notification_id) FROM tetra_platform.operator_notification", Long.class);
	}

	private String listBody() throws Exception {
		return mvc.perform(get("/api/ops/notifications").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	/** 목록 응답을 notificationId → 항목으로 */
	private Map<Long, JsonNode> list() throws Exception {
		Map<Long, JsonNode> items = new HashMap<>();
		JsonMapper.builder().build().readTree(listBody()).path("items")
			.forEach(n -> items.put(n.path("notificationId").asLong(), n));
		return items;
	}

	/** 칩 · 경로 · 현재 상태를 한 줄로: "EVENT /events/progress/1 HOSTING_STATUS=ENDED" (없으면 -, alert면 끝에 !) */
	private static String view(JsonNode item) {
		JsonNode state = item.path("state");
		String shown = state.isNull() || state.isMissingNode() ? "-"
				: state.path("kind").asString() + "=" + state.path("value").asString()
						+ (state.path("alert").asBoolean() ? "!" : "");
		return text(item.path("chip")) + " " + text(item.path("link")) + " " + shown;
	}

	private static String text(JsonNode node) {
		return node.isNull() || node.isMissingNode() ? "-" : node.asString();
	}

	private static List<String> fieldNames(JsonNode node) {
		List<String> names = new ArrayList<>();
		node.properties().forEach(e -> {
			names.add(e.getKey());
			names.addAll(fieldNames(e.getValue()));
		});
		node.values().forEach(child -> names.addAll(fieldNames(child)));
		return names;
	}

	private List<Boolean> readFlags() {
		return data.jdbc()
			.queryForList("SELECT is_read FROM tetra_platform.operator_notification ORDER BY notification_id", Boolean.class);
	}

	@Test
	void listsAllNewestFirstWithTenantName() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(7001, A).approved("HOSTING", "OPEN", "noc00001").insert();
		var t = TestData.BASE;
		long first = insert(A, 7001L, "URGENT", t, "배포 실패", false);
		long system = insert(null, null, "INFO", t.plusMinutes(2), "테넌트 무관 알림", true);
		// 같은 시각이면 나중에 남은 알림이 먼저
		long sameTime = insert(A, null, "WARNING", t, "같은 시각", false);

		mvc.perform(get("/api/ops/notifications").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(3))
			.andExpect(jsonPath("$.items[*].notificationId").value(Matchers.contains((int) system, (int) sameTime, (int) first)))
			.andExpect(jsonPath("$.items[0].tenantId").doesNotExist())
			.andExpect(jsonPath("$.items[0].tenantName").doesNotExist())
			.andExpect(jsonPath("$.items[0].read").value(true))
			.andExpect(jsonPath("$.items[2].tenantId").value(A))
			.andExpect(jsonPath("$.items[2].tenantName").value("A사"))
			.andExpect(jsonPath("$.items[2].eventId").value(7001))
			.andExpect(jsonPath("$.items[2].category").value("HOSTING"))
			.andExpect(jsonPath("$.items[2].priority").value("URGENT"))
			.andExpect(jsonPath("$.items[2].occurredAt").value("2026-10-01T10:00:00+09:00"))
			.andExpect(jsonPath("$.items[2].content").value("배포 실패"))
			.andExpect(jsonPath("$.items[2].read").value(false));
	}

	@Test
	void chipLinkAndCurrentStateByCategory() throws Exception {
		LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.tenant(B, "B사", "INACTIVE", "FAILED");
		data.jdbc().update("UPDATE tetra_platform.tenant SET password = ? WHERE tenant_id = ?", PASSWORD_HASH, A);
		// 7201 진행 중(OPS-05 드롭다운 안) · 7202 20일 전 종료(드롭다운 밖) · 7203 반려 · 7204 배포 실패, 오늘 시작(드롭다운 안)
		data.event(7201, A).approved("HOSTING", "OPEN", "slug7201").startAt(now.minusMinutes(10)).insert();
		data.event(7202, A).approved("ENDED", "EXPIRED", "slug7202").startAt(now.minusDays(20)).insert();
		data.event(7203, A).status("REJECTED").insert();
		data.event(7204, A).approved("DEPLOY_FAILED", "SCHEDULED", "slug7204").startAt(now.plusMinutes(90)).insert();

		Map<String, Long> ids = new HashMap<>();
		ids.put("approved", insert(A, 7201L, "APPLICATION", "INFO", null));
		ids.put("rejected", insert(A, 7203L, "APPLICATION", "INFO", null));
		ids.put("hosting live", insert(A, 7201L, "HOSTING", "INFO", null));
		ids.put("hosting failed", insert(A, 7204L, "HOSTING", "URGENT", null));
		ids.put("hosting ended", insert(A, 7202L, "HOSTING", "INFO", null));
		ids.put("event rejected", insert(A, 7203L, "EVENT", "INFO", null));
		for (String step : List.of("HOSTING_START", "PREPARE_START", "ENTRY_OPEN", "END_START", "END_DONE")) {
			ids.put("delay " + step, insert(A, 7201L, "DELAY", "URGENT", "DELAY:7201:" + step));
		}
		ids.put("private key mail", insert(A, 7204L, "MAIL", "URGENT", null));
		ids.put("password mail set", insert(A, null, "MAIL", "WARNING", null));
		ids.put("password mail unset", insert(B, null, "MAIL", "WARNING", null));
		ids.put("provisioning", insert(B, null, "PROVISIONING", "URGENT", null));
		ids.put("account", insert(B, null, "ACCOUNT", "INFO", null));
		ids.put("report", insert(null, null, "REPORT", "INFO", null));
		ids.put("unknown", insert(A, 7201L, "SLACK", "INFO", null));
		ids.put("no event", insert(null, null, "HOSTING", "INFO", null));

		Map<Long, JsonNode> items = list();
		Map<String, String> views = new HashMap<>();
		ids.forEach((name, id) -> views.put(name, view(items.get(id))));

		assertThat(views).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
				Map.entry("approved", "APPLICATION /events/applications/7201 APPLICATION_STATUS=APPROVED"),
				Map.entry("rejected", "APPLICATION /events/applications/7203 APPLICATION_STATUS=REJECTED"),
				Map.entry("hosting live", "EVENT /monitoring/traffic?eventId=7201 HOSTING_STATUS=HOSTING"),
				Map.entry("hosting failed", "EVENT /monitoring/traffic?eventId=7204 HOSTING_STATUS=DEPLOY_FAILED!"),
				Map.entry("hosting ended", "EVENT /events/progress/7202 HOSTING_STATUS=ENDED"),
				// 승인 전(반려)은 호스팅 · 이벤트 상태가 없고, 진행 현황 상세가 열리지 않는다
				Map.entry("event rejected", "EVENT /events/applications/7203 -"),
				Map.entry("delay HOSTING_START", "EVENT /monitoring/traffic?eventId=7201 HOSTING_STATUS=HOSTING"),
				Map.entry("delay PREPARE_START", "EVENT /monitoring/traffic?eventId=7201 EVENT_STATUS=OPEN"),
				Map.entry("delay ENTRY_OPEN", "EVENT /monitoring/traffic?eventId=7201 EVENT_STATUS=OPEN"),
				Map.entry("delay END_START", "EVENT /monitoring/traffic?eventId=7201 HOSTING_STATUS=HOSTING"),
				Map.entry("delay END_DONE", "EVENT /monitoring/traffic?eventId=7201 HOSTING_STATUS=HOSTING"),
				Map.entry("private key mail", "EVENT /monitoring/traffic?eventId=7204 -"),
				Map.entry("password mail set", "TENANT /tenants/" + A + " PASSWORD_SETUP=SET"),
				Map.entry("password mail unset", "TENANT /tenants/" + B + " PASSWORD_SETUP=NOT_SET"),
				Map.entry("provisioning", "TENANT /tenants/" + B + " PROVISIONING_STATUS=FAILED!"),
				Map.entry("account", "TENANT /tenants/" + B + " ACCOUNT_STATUS=INACTIVE"),
				Map.entry("report", "REPORT /logs/reports -"),
				Map.entry("unknown", "- - -"),
				Map.entry("no event", "EVENT - -")));
		// SYSTEM 행이 없으면 DLQ를 묻지 않는다
		verify(aws, never()).queueDepth(anyString());
	}

	@Test
	void responseAndLogsHaveNoPassword(CapturedOutput output) throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.jdbc().update("UPDATE tetra_platform.tenant SET password = ? WHERE tenant_id = ?", PASSWORD_HASH, A);
		insert(A, null, "MAIL", "WARNING", null);
		insert(A, null, "ACCOUNT", "INFO", null);

		String body = listBody();

		assertThat(fieldNames(JsonMapper.builder().build().readTree(body)))
			.isNotEmpty()
			.noneMatch(f -> f.toLowerCase().contains("password"));
		assertThat(body).doesNotContain(PASSWORD_HASH).doesNotContain("$2a$");
		assertThat(output.getAll()).doesNotContain(PASSWORD_HASH);
	}

	@Test
	void systemRowShowsCurrentDlqDepth() throws Exception {
		when(aws.queueDepth(APPROVE_DLQ)).thenReturn(4);
		when(aws.queueDepth(SCHEDULER_DLQ)).thenReturn(0);
		long approve = insert(null, null, "SYSTEM", "URGENT", "DLQ:" + APPROVE_DLQ + ":2026-10-29");
		long approveToday = insert(null, null, "SYSTEM", "URGENT", "DLQ:" + APPROVE_DLQ + ":2026-10-30");
		long scheduler = insert(null, null, "SYSTEM", "URGENT", "DLQ:" + SCHEDULER_DLQ + ":2026-10-30");
		// 지금 보지 않는 큐(MONITORING_DLQ_NAMES에 없음)는 묻지 않는다
		long removed = insert(null, null, "SYSTEM", "URGENT", "DLQ:tetra-test-removed-dlq:2026-10-30");

		Map<Long, JsonNode> items = list();

		assertThat(view(items.get(approve))).isEqualTo("SYSTEM /monitoring/traffic DLQ_MESSAGES=4!");
		assertThat(view(items.get(approveToday))).isEqualTo("SYSTEM /monitoring/traffic DLQ_MESSAGES=4!");
		assertThat(view(items.get(scheduler))).isEqualTo("SYSTEM /monitoring/traffic DLQ_MESSAGES=0");
		assertThat(view(items.get(removed))).isEqualTo("SYSTEM /monitoring/traffic -");
		// 같은 큐는 한 번만
		verify(aws).queueDepth(APPROVE_DLQ);
		verify(aws).queueDepth(SCHEDULER_DLQ);
		verify(aws, never()).queueDepth("tetra-test-removed-dlq");
	}

	@Test
	void dlqFailureLeavesStateNull() throws Exception {
		when(aws.queueDepth(APPROVE_DLQ)).thenThrow(new AwsMonitoringException("GetQueueAttributes 실패(AccessDenied)"));
		long system = insert(null, null, "SYSTEM", "URGENT", "DLQ:" + APPROVE_DLQ + ":2026-10-30");

		assertThat(view(list().get(system))).isEqualTo("SYSTEM /monitoring/traffic -");
	}

	@Test
	void emptyListIsItemsArray() throws Exception {
		mvc.perform(get("/api/ops/notifications").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isArray())
			.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void markReadIsIdempotentAndOnlyTouchesThatNotification() throws Exception {
		long target = insert(null, null, "INFO", TestData.BASE, "읽을 알림", false);
		insert(null, null, "INFO", TestData.BASE, "다른 알림", false);

		mvc.perform(patch("/api/ops/notifications/{id}/read", target).cookie(operatorCookie()))
			.andExpect(status().isNoContent());
		mvc.perform(patch("/api/ops/notifications/{id}/read", target).cookie(operatorCookie()))
			.andExpect(status().isNoContent());

		assertThat(readFlags()).containsExactly(true, false);
	}

	@Test
	void markReadUnknownOrMalformedId() throws Exception {
		mvc.perform(patch("/api/ops/notifications/{id}/read", 999999).cookie(operatorCookie()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
		mvc.perform(patch("/api/ops/notifications/{id}/read", "abc").cookie(operatorCookie()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
	}

	@Test
	void markAllReadUpdatesOnlyUnreadAndIsIdempotent() throws Exception {
		insert(null, null, "URGENT", TestData.BASE, "안읽음 1", false);
		insert(null, null, "INFO", TestData.BASE, "이미 읽음", true);
		insert(null, null, "WARNING", TestData.BASE, "안읽음 2", false);

		mvc.perform(patch("/api/ops/notifications/read-all").cookie(operatorCookie())).andExpect(status().isNoContent());
		assertThat(readFlags()).containsOnly(true);

		mvc.perform(patch("/api/ops/notifications/read-all").cookie(operatorCookie())).andExpect(status().isNoContent());
		assertThat(readFlags()).containsExactly(true, true, true);
	}

	@Test
	void requiresLogin() throws Exception {
		mvc.perform(get("/api/ops/notifications")).andExpect(status().isUnauthorized());
		mvc.perform(patch("/api/ops/notifications/read-all")).andExpect(status().isUnauthorized());
		mvc.perform(patch("/api/ops/notifications/{id}/read", 1)).andExpect(status().isUnauthorized());
	}

}
