package com.tetra.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.tetra.ops.external.MailGateway;
import com.tetra.ops.support.IntegrationTest;
import com.tetra.ops.support.TestData;

/** WF1-2 승인 (§7.3, §12 중복 처리·CHECK 제약·외부 호출 실패). */
@ExtendWith(OutputCaptureExtension.class)
class ApproveApiTest extends IntegrationTest {

	static final long EVENT_ID = 1043;

	@BeforeEach
	void setUp() {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(EVENT_ID, A).name("추석맞이 쿠폰").applied().insert();
		data.statusLog(A, EVENT_ID, "APPLICATION", null, "APPLYING", TestData.BASE, null);
		data.statusLog(A, EVENT_ID, "APPLICATION", "APPLYING", "APPLIED", TestData.BASE, null);
	}

	@Test
	void approveCommitsRegistryEventKeyAndThreeLogs(CapturedOutput output) throws Exception {
		mvc.perform(post("/api/ops/events/{id}/approve", EVENT_ID).cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.eventId").value(1043))
			.andExpect(jsonPath("$.applicationStatus").value("APPROVED"))
			.andExpect(jsonPath("$.hostingStatus").value("PENDING"))
			.andExpect(jsonPath("$.eventStatus").value("SCHEDULED"))
			.andExpect(jsonPath("$.privateKeyMailSent").value(true));

		Map<String, Object> registry = data.registry(EVENT_ID);
		assertThat(registry).containsEntry("application_status", "APPROVED")
			.containsEntry("hosting_status", "PENDING")
			.containsEntry("event_status", "SCHEDULED");
		assertThat(registry.get("processed_at")).isNotNull();

		// chk_event_approved를 통과한 행 (DB가 CHECK로 막지 않았고 값도 맞음)
		Map<String, Object> event = data.eventRow(A, EVENT_ID);
		assertThat(event).containsEntry("application_status", "APPROVED")
			.containsEntry("hosting_status", "PENDING")
			.containsEntry("event_status", "SCHEDULED");
		assertThat(((Number) event.get("processed_by")).longValue()).isEqualTo(TestData.OPERATOR_ID);
		assertThat((String) event.get("slug")).matches("^[a-z0-9]{8}$");

		List<Map<String, Object>> keys = data.authKeys(A, EVENT_ID);
		assertThat(keys).hasSize(1);
		assertThat(keys.get(0)).containsEntry("status", "ACTIVE");
		assertThat(((Number) keys.get(0).get("key_version")).intValue()).isEqualTo(1);
		assertThat((String) keys.get(0).get("public_key")).startsWith("-----BEGIN PUBLIC KEY-----");
		assertThat(keys.get(0).get("expires_at")).isEqualTo(event.get("hosting_end_at"));

		List<Map<String, Object>> logs = data.statusLogs(A, EVENT_ID);
		assertThat(logs).hasSize(2 + 3);
		assertThat(logs.subList(2, 5)).extracting(l -> l.get("status_type") + ":" + l.get("prev_status") + "->"
				+ l.get("new_status") + ":" + l.get("changed_by"))
			.containsExactly("APPLICATION:APPLIED->APPROVED:1", "HOSTING:null->PENDING:null",
					"EVENT:null->SCHEDULED:null");

		// 같은 트랜잭션에서 테넌트 알림 1건
		List<Map<String, Object>> notifications = data.jdbc()
			.queryForList("SELECT * FROM t_" + A + ".tenant_notification WHERE event_id = ?", EVENT_ID);
		assertThat(notifications).hasSize(1);
		assertThat(notifications.get(0)).containsEntry("tenant_id", A).containsEntry("is_read", false);
		assertThat((String) notifications.get(0).get("content")).contains("추석맞이 쿠폰", "승인");

		// 커밋 후 Step Functions: 실행 이름 approve-{event_id}, input에 slug 포함
		ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
		verify(stepFunctions).startExecution(eq("arn:aws:states:ap-northeast-2:000000000000:stateMachine:approve"),
				eq("approve-1043"), input.capture());
		assertThat(input.getValue()).contains("\"tenant_id\":\"" + A + "\"", "\"event_id\":1043",
				"\"slug\":\"" + event.get("slug") + "\"", "\"template_file_path\":\"apps/coupon/\"", "+09:00");
		// layout_config는 문자열이 아니라 JSON 객체로 들어간다 (event.json layout으로 그대로 전달)
		var layout = tools.jackson.databind.json.JsonMapper.builder().build().readTree(input.getValue()).path("layout_config");
		assertThat(layout.isObject()).isTrue();
		assertThat(layout.path("theme").asString()).isEqualTo("WHITE");
		assertThat(layout.path("issueButton").path("v").asString()).isEqualTo("BOTTOM");

		// 커밋 후 SES: 개인키(PKCS#8 PEM)를 tenant.email로
		ArgumentCaptor<MailGateway.Mail> mailCaptor = ArgumentCaptor.forClass(MailGateway.Mail.class);
		verify(mail).send(mailCaptor.capture());
		MailGateway.Mail sent = mailCaptor.getValue();
		assertThat(sent.to()).isEqualTo(data.tenantRow(A).get("email"));
		assertThat(sent.textBody()).contains("-----BEGIN PRIVATE KEY-----", "키 버전(kid): 1");

		// 개인키·JWT가 로그에 나오지 않는다
		String privateKeyBody = sent.textBody()
			.substring(sent.textBody().indexOf("-----BEGIN PRIVATE KEY-----") + 28)
			.substring(0, 40);
		assertThat(output.getAll()).doesNotContain("BEGIN PRIVATE KEY").doesNotContain(privateKeyBody);
		assertThat(sent.toString()).doesNotContain("PRIVATE KEY");
	}

	@Test
	void secondApproveIs409AndChangesNothing() throws Exception {
		mvc.perform(post("/api/ops/events/{id}/approve", EVENT_ID).cookie(operatorCookie())).andExpect(status().isOk());

		mvc.perform(post("/api/ops/events/{id}/approve", EVENT_ID).cookie(operatorCookie()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("APPLICATION_NOT_APPLIED"));

		assertThat(data.authKeys(A, EVENT_ID)).hasSize(1);
		assertThat(data.statusLogs(A, EVENT_ID)).hasSize(5);
	}

	@Test
	void startExecutionFailureKeepsApprovalAndMarksDeployFailed() throws Exception {
		when(stepFunctions.startExecution(anyString(), anyString(), anyString())).thenReturn(false);

		mvc.perform(post("/api/ops/events/{id}/approve", EVENT_ID).cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.applicationStatus").value("APPROVED"))
			.andExpect(jsonPath("$.hostingStatus").value("DEPLOY_FAILED"))
			.andExpect(jsonPath("$.privateKeyMailSent").value(true));

		assertThat(data.registry(EVENT_ID)).containsEntry("application_status", "APPROVED")
			.containsEntry("hosting_status", "DEPLOY_FAILED");
		assertThat(data.eventRow(A, EVENT_ID)).containsEntry("hosting_status", "DEPLOY_FAILED");
		Map<String, Object> last = data.statusLogs(A, EVENT_ID).getLast();
		assertThat(last).containsEntry("status_type", "HOSTING")
			.containsEntry("prev_status", "PENDING")
			.containsEntry("new_status", "DEPLOY_FAILED")
			.containsEntry("detail", "StartExecution 실패");
		assertThat(data.statusLogs(A, EVENT_ID)).hasSize(2 + 3 + 1);
	}

	@Test
	void mailFailureKeepsApprovalAndReportsFlag() throws Exception {
		when(mail.send(any())).thenReturn(false);

		mvc.perform(post("/api/ops/events/{id}/approve", EVENT_ID).cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.applicationStatus").value("APPROVED"))
			.andExpect(jsonPath("$.hostingStatus").value("PENDING"))
			.andExpect(jsonPath("$.privateKeyMailSent").value(false));

		assertThat(data.registry(EVENT_ID)).containsEntry("application_status", "APPROVED");
	}

	@Test
	void notAppliedIs409AndCallsNothing() throws Exception {
		data.event(2000, A).status("APPLYING").insert();

		mvc.perform(post("/api/ops/events/{id}/approve", 2000).cookie(operatorCookie()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("APPLICATION_NOT_APPLIED"));
		verify(stepFunctions, never()).startExecution(anyString(), anyString(), anyString());
		verify(mail, never()).send(any());
	}

	@Test
	void hostingPeriodEndedIs409AndChangesNothing() throws Exception {
		// 호스팅 종료가 이미 지난 신청 (start = 어제, hosting_end = start + 5시간)
		data.event(2100, A).applied().startAt(java.time.LocalDateTime.now().minusDays(1).withNano(0)).insert();

		mvc.perform(post("/api/ops/events/{id}/approve", 2100).cookie(operatorCookie()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("HOSTING_PERIOD_ENDED"));

		assertThat(data.registry(2100)).containsEntry("application_status", "APPLIED");
		assertThat(data.eventRow(A, 2100)).containsEntry("application_status", "APPLIED");
		assertThat(data.authKeys(A, 2100)).isEmpty();
		assertThat(data.statusLogs(A, 2100)).isEmpty();
		verify(stepFunctions, never()).startExecution(anyString(), anyString(), anyString());
		verify(mail, never()).send(any());
	}

	@Test
	void unknownEventIs404() throws Exception {
		mvc.perform(post("/api/ops/events/{id}/approve", 999999).cookie(operatorCookie()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
	}

	@Test
	void requiresAuthentication() throws Exception {
		mvc.perform(post("/api/ops/events/{id}/approve", EVENT_ID))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		assertThat(data.registry(EVENT_ID)).containsEntry("application_status", "APPLIED");
	}

}
