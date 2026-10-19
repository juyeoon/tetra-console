package com.tetra.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.tetra.ops.support.IntegrationTest;

/** WF1-3 반려 (§7.4, §12 CHECK 제약·테넌트 비활성). */
class RejectApiTest extends IntegrationTest {

	static final long EVENT_ID = 2001;

	@BeforeEach
	void setUp() {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(EVENT_ID, A).applied().insert();
	}

	private org.springframework.test.web.servlet.ResultActions reject(long eventId, String json) throws Exception {
		return mvc.perform(post("/api/ops/events/{id}/reject", eventId).cookie(operatorCookie())
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	@Test
	void rejectWithoutRemainingEventsDeactivatesTenant() throws Exception {
		reject(EVENT_ID, "{\"reason\":\"배너 이미지가 정책에 맞지 않습니다.\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.applicationStatus").value("REJECTED"));

		assertThat(data.registry(EVENT_ID)).containsEntry("application_status", "REJECTED");
		assertThat(data.registry(EVENT_ID).get("processed_at")).isNotNull();
		// chk_event_reject를 통과한 행
		Map<String, Object> event = data.eventRow(A, EVENT_ID);
		assertThat(event).containsEntry("application_status", "REJECTED")
			.containsEntry("reject_reason", "배너 이미지가 정책에 맞지 않습니다.");
		assertThat(event.get("hosting_status")).isNull();
		assertThat(((Number) event.get("processed_by")).longValue()).isEqualTo(1L);

		List<Map<String, Object>> logs = data.statusLogs(A, EVENT_ID);
		assertThat(logs).hasSize(1);
		assertThat(logs.get(0)).containsEntry("status_type", "APPLICATION")
			.containsEntry("prev_status", "APPLIED")
			.containsEntry("new_status", "REJECTED")
			.containsEntry("detail", "배너 이미지가 정책에 맞지 않습니다.");
		assertThat(((Number) logs.get(0).get("changed_by")).longValue()).isEqualTo(1L);

		// 테넌트 알림 1건 (같은 트랜잭션)
		List<Map<String, Object>> notifications = notifications();
		assertThat(notifications).hasSize(1);
		assertThat(notifications.get(0)).containsEntry("tenant_id", A).containsEntry("is_read", false);
		assertThat(((Number) notifications.get(0).get("event_id")).longValue()).isEqualTo(EVENT_ID);
		assertThat((String) notifications.get(0).get("content")).contains("이벤트 2001", "배너 이미지가 정책에 맞지 않습니다.");

		assertThat(data.tenantRow(A)).containsEntry("account_status", "INACTIVE");
		List<Map<String, Object>> tenantLogs = data.tenantStatusLogs(A);
		assertThat(tenantLogs).hasSize(1);
		assertThat(tenantLogs.get(0)).containsEntry("prev_status", "ACTIVE").containsEntry("new_status", "INACTIVE");
		assertThat(((Number) tenantLogs.get(0).get("related_event_id")).longValue()).isEqualTo(EVENT_ID);
	}

	@Test
	void rejectWithAnotherAppliedEventKeepsTenantActive() throws Exception {
		data.event(2002, A).applied().insert();

		reject(EVENT_ID, "{\"reason\":\"사유\"}").andExpect(status().isOk());

		assertThat(data.tenantRow(A)).containsEntry("account_status", "ACTIVE");
		assertThat(data.tenantStatusLogs(A)).isEmpty();
	}

	@Test
	void rejectWithOpenApprovedEventKeepsTenantActive() throws Exception {
		data.event(2003, A).approved("HOSTING", "OPEN", "abcd1234").insert();

		reject(EVENT_ID, "{\"reason\":\"사유\"}").andExpect(status().isOk());

		assertThat(data.tenantRow(A)).containsEntry("account_status", "ACTIVE");
	}

	@Test
	void expiredOpenFailedOrRejectedEventsDoNotKeepTenantActive() throws Exception {
		data.event(2004, A).approved("ENDED", "EXPIRED", "abcd1234").insert();
		// 화면설계서 OPS-06 기준: 이벤트 준비 실패(OPEN_FAILED)는 남은 이벤트가 아니다
		data.event(2008, A).approved("HOSTING_FAILED", "OPEN_FAILED", "abcd5678").insert();
		data.event(2005, A).status("REJECTED").insert();
		data.event(2006, A).status("APPLY_FAILED").insert();
		// 다른 테넌트의 진행 중 이벤트는 판정에 들어가지 않는다
		data.tenant(B, "B사", "ACTIVE", "READY");
		data.event(2007, B).applied().insert();

		reject(EVENT_ID, "{\"reason\":\"사유\"}").andExpect(status().isOk());

		assertThat(data.tenantRow(A)).containsEntry("account_status", "INACTIVE");
		assertThat(data.tenantRow(B)).containsEntry("account_status", "ACTIVE");
	}

	@Test
	void scheduledApprovedEventKeepsTenantActiveEvenIfDeployFailed() throws Exception {
		data.event(2009, A).approved("DEPLOY_FAILED", "SCHEDULED", "abcd9999").insert();

		reject(EVENT_ID, "{\"reason\":\"사유\"}").andExpect(status().isOk());

		assertThat(data.tenantRow(A)).containsEntry("account_status", "ACTIVE");
	}

	@Test
	void alreadyInactiveTenantGetsNoExtraLog() throws Exception {
		data.jdbc().update("UPDATE tetra_platform.tenant SET account_status = 'INACTIVE' WHERE tenant_id = ?", A);

		reject(EVENT_ID, "{\"reason\":\"사유\"}").andExpect(status().isOk());

		assertThat(data.tenantStatusLogs(A)).isEmpty();
	}

	@Test
	void blankOrTooLongReasonIs400() throws Exception {
		reject(EVENT_ID, "{\"reason\":\"   \"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
			.andExpect(jsonPath("$.fields.reason").exists());
		reject(EVENT_ID, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.reason").exists());
		reject(EVENT_ID, "{\"reason\":\"" + "가".repeat(501) + "\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.reason").exists());
		reject(EVENT_ID, "{\"reason\":\"" + "가".repeat(500) + "\"}").andExpect(status().isOk());
	}

	@Test
	void secondRejectIs409() throws Exception {
		reject(EVENT_ID, "{\"reason\":\"사유\"}").andExpect(status().isOk());
		reject(EVENT_ID, "{\"reason\":\"사유\"}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("APPLICATION_NOT_APPLIED"));
		assertThat(data.statusLogs(A, EVENT_ID)).hasSize(1);
		assertThat(notifications()).hasSize(1);
	}

	@Test
	void longestNameAndReasonFitNotificationContent() throws Exception {
		data.event(2010, A).name("이".repeat(100)).applied().insert();
		reject(2010, "{\"reason\":\"" + "가".repeat(500) + "\"}").andExpect(status().isOk());
		assertThat(data.count("SELECT COUNT(*) FROM t_" + A + ".tenant_notification WHERE event_id = 2010")).isEqualTo(1);
	}

	private List<Map<String, Object>> notifications() {
		return data.jdbc().queryForList("SELECT * FROM t_" + A + ".tenant_notification WHERE event_id = ?", EVENT_ID);
	}

}
