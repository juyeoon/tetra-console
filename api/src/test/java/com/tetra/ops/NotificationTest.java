package com.tetra.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.tetra.ops.listener.StepFunctionsResultHandler;
import com.tetra.ops.support.IntegrationTest;

/** 상태 변화 알림 (§7.13). 운영자 알림·테넌트 알림의 category·priority와 건수. */
class NotificationTest extends IntegrationTest {

	@Autowired
	StepFunctionsResultHandler handler;

	private List<Map<String, Object>> operatorNotifications() {
		return data.jdbc().queryForList("SELECT * FROM tetra_platform.operator_notification ORDER BY notification_id");
	}

	private List<Map<String, Object>> tenantNotifications(String tenantId) {
		return data.jdbc()
			.queryForList("SELECT * FROM t_" + tenantId + ".tenant_notification ORDER BY notification_id");
	}

	private static String kind(Map<String, Object> row) {
		return row.get("category") + "/" + row.get("priority");
	}

	/** 고정 문구(라벨 + 정해진 문장). 반려 사유 · 원인은 자르지 않고 뒤에 붙으므로 그 앞까지 */
	static String fixedPart(String content) {
		return content.replaceFirst(" (반려 사유|원인): .*$", "");
	}

	/** 고정 문구는 120자 이내(이벤트명 · 테넌트명은 테스트 데이터 기준) */
	static void assertFixedPartsFit(List<String> contents) {
		assertThat(contents).isNotEmpty().allSatisfy(c -> assertThat(fixedPart(c)).hasSizeLessThanOrEqualTo(120));
	}

	private static List<String> contents(List<Map<String, Object>> rows) {
		return rows.stream().map(n -> (String) n.get("content")).toList();
	}

	@Test
	void approveLeavesOperatorAndTenantInfo() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(8101, A).name("추석 쿠폰").applied().insert();

		mvc.perform(post("/api/ops/events/{id}/approve", 8101).cookie(operatorCookie())).andExpect(status().isOk());

		// 승인 = 로그 3줄이지만 알림은 운영자 1건 + 테넌트 1건. 라벨은 [테넌트명] [id:이벤트명] / [id:이벤트명]
		assertThat(operatorNotifications()).extracting(NotificationTest::kind).containsExactly("APPLICATION/INFO");
		assertThat((String) operatorNotifications().get(0).get("content"))
			.isEqualTo("[A사] [8101:추석 쿠폰] 이벤트 신청을 승인했습니다.");
		assertThat(((Number) operatorNotifications().get(0).get("event_id")).longValue()).isEqualTo(8101);
		assertThat(operatorNotifications().get(0)).containsEntry("tenant_id", A)
			.containsEntry("is_read", false)
			.containsEntry("dedupe_key", null);
		assertThat(tenantNotifications(A)).extracting(NotificationTest::kind).containsExactly("APPLICATION/INFO");
		assertThat((String) tenantNotifications(A).get(0).get("content")).isEqualTo("[8101:추석 쿠폰] 이벤트 신청이 승인되었습니다.");
		assertFixedPartsFit(contents(operatorNotifications()));
		assertFixedPartsFit(contents(tenantNotifications(A)));
	}

	@Test
	void startExecutionFailureAndMailFailureAreUrgent() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(8102, A).applied().insert();
		when(stepFunctions.startExecution(anyString(), anyString(), anyString())).thenReturn(false);
		when(mail.send(any())).thenReturn(false);

		mvc.perform(post("/api/ops/events/{id}/approve", 8102).cookie(operatorCookie())).andExpect(status().isOk());

		assertThat(operatorNotifications()).extracting(NotificationTest::kind)
			.containsExactly("APPLICATION/INFO", "HOSTING/URGENT", "MAIL/URGENT");
		assertThat((String) operatorNotifications().get(1).get("content")).contains("배포에 실패", "StartExecution 실패");
		assertThat((String) operatorNotifications().get(2).get("content")).contains("키 재발급");
		// 개인키 자체는 알림에 들어가지 않는다
		assertThat(operatorNotifications()).allSatisfy(n -> assertThat((String) n.get("content")).doesNotContain("PRIVATE KEY"));
		// 배포 실패·메일 실패는 테넌트 알림을 남기지 않는다
		assertThat(tenantNotifications(A)).extracting(NotificationTest::kind).containsExactly("APPLICATION/INFO");
		assertFixedPartsFit(contents(operatorNotifications()));
	}

	/** 반려: 운영자는 처리 완료라 INFO, 테넌트는 WARNING. 반려 사유는 자르지 않고 붙인다 */
	@Test
	void rejectLeavesOperatorInfoTenantWarningAndDeactivationInfo() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(8103, A).applied().insert();

		mvc.perform(post("/api/ops/events/{id}/reject", 8103).cookie(operatorCookie())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"배너 규격 위반\"}")).andExpect(status().isOk());

		assertThat(operatorNotifications()).extracting(NotificationTest::kind)
			.containsExactly("APPLICATION/INFO", "ACCOUNT/INFO");
		assertThat((String) operatorNotifications().get(0).get("content"))
			.isEqualTo("[A사] [8103:이벤트 8103] 이벤트 신청을 반려했습니다. 반려 사유: 배너 규격 위반");
		assertThat((String) operatorNotifications().get(1).get("content"))
			.isEqualTo("[A사] 진행 중인 이벤트가 없어 계정이 비활성으로 전환되었습니다.");
		assertThat(tenantNotifications(A)).extracting(NotificationTest::kind).containsExactly("APPLICATION/WARNING");
		assertThat((String) tenantNotifications(A).get(0).get("content"))
			.isEqualTo("[8103:이벤트 8103] 이벤트 신청이 반려되었습니다. 반려 사유: 배너 규격 위반");
		assertFixedPartsFit(contents(operatorNotifications()));
		assertFixedPartsFit(contents(tenantNotifications(A)));
	}

	@Test
	void longRejectReasonIsKeptWhole() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(8107, A).applied().insert();
		String reason = "가".repeat(500);

		mvc.perform(post("/api/ops/events/{id}/reject", 8107).cookie(operatorCookie())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"" + reason + "\"}")).andExpect(status().isOk());

		// 120자는 고정 문구 기준이고, 반려 사유는 자르지 않는다(1000자 상한 안)
		assertThat((String) operatorNotifications().get(0).get("content")).endsWith("반려 사유: " + reason);
		assertThat((String) tenantNotifications(A).get(0).get("content")).endsWith("반려 사유: " + reason);
	}

	@Test
	void deployResultsNotifyOperatorOnceEach() {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(8104, A).approved("PENDING", "SCHEDULED", "noti0001").insert();
		data.event(8105, A).approved("PENDING", "SCHEDULED", "noti0002").insert();
		data.event(8106, A).approved("DEPLOY_FAILED", "SCHEDULED", "noti0003").insert();

		String ok = StepFunctionsResultHandlerTest.message("approve-8104", "SUCCEEDED", null, null);
		handler.handleApproveResult(ok);
		handler.handleApproveResult(ok); // 중복 메시지 → 알림도 1건
		handler.handleApproveResult(StepFunctionsResultHandlerTest.message("approve-8105", "TIMED_OUT", "E", "C"));
		handler.handleApproveResult(StepFunctionsResultHandlerTest.message("approve-8106", "SUCCEEDED", null, null));

		assertThat(operatorNotifications()).extracting(NotificationTest::kind)
			.containsExactly("HOSTING/INFO", "HOSTING/URGENT", "HOSTING/INFO");
		assertThat((String) operatorNotifications().get(1).get("content")).contains("TIMED_OUT: E / C");
		assertThat((String) operatorNotifications().get(2).get("content")).contains("배포 실패로 표시됐던");
		assertThat((String) operatorNotifications().get(0).get("content"))
			.isEqualTo("[A사] [8104:이벤트 8104] 이벤트 배포가 완료되었습니다.");
		assertThat(tenantNotifications(A)).isEmpty();
		assertFixedPartsFit(contents(operatorNotifications()));
	}

	@Test
	void tenantRegistrationProvisioningAndMailFailure() throws Exception {
		when(mail.send(any())).thenReturn(false);

		String body = mvc.perform(post("/api/ops/tenants").cookie(operatorCookie())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"name":"E사","email":"e@e-corp.com","rootDomain":"e-corp.com","contactName":"박민호"}"""))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String tenantId = body.replaceAll(".*\"tenantId\":\"([a-z0-9]{12})\".*", "$1");

		// 등록 때는 메일을 보내지 않는다 — 등록 알림만
		assertThat(operatorNotifications()).extracting(NotificationTest::kind).containsExactly("ACCOUNT/INFO");

		// 환경 준비 성공(READY) 뒤 메일 발송 — 실패하면 환경 준비 완료 다음에 메일 실패 알림. 중복 메시지는 무시
		String ready = StepFunctionsResultHandlerTest.message("tenant-" + tenantId, "SUCCEEDED", null, null);
		handler.handleTenantResult(ready);
		handler.handleTenantResult(ready);

		// 비밀번호 설정 메일 실패는 주의(WARNING), 테넌트 ID 대신 [테넌트명]
		assertThat(operatorNotifications()).extracting(NotificationTest::kind)
			.containsExactly("ACCOUNT/INFO", "PROVISIONING/INFO", "MAIL/WARNING");
		assertThat((String) operatorNotifications().get(2).get("content"))
			.isEqualTo("[E사] 비밀번호 설정 메일 발송에 실패했습니다. 담당자 이메일 주소와 SES 상태를 확인해 주세요.");
		assertThat(operatorNotifications().get(2)).containsEntry("tenant_id", tenantId).containsEntry("event_id", null);
		assertThat(operatorNotifications()).allSatisfy(n -> assertThat((String) n.get("content")).doesNotContain("password/setup"));
		assertFixedPartsFit(contents(operatorNotifications()));
	}

	@Test
	void provisioningFailuresAreUrgent() throws Exception {
		when(stepFunctions.startExecution(anyString(), anyString(), anyString())).thenReturn(false);

		mvc.perform(post("/api/ops/tenants").cookie(operatorCookie())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"name":"F사","email":"f@f-corp.com","rootDomain":"f-corp.com","contactName":"최담당"}"""))
			.andExpect(status().isCreated());
		data.tenant(B, "B사", "INACTIVE", "PROVISIONING");
		handler.handleTenantResult(StepFunctionsResultHandlerTest.message("tenant-" + B, "FAILED", "E", "C"));

		assertThat(operatorNotifications()).extracting(NotificationTest::kind)
			.containsExactly("ACCOUNT/INFO", "PROVISIONING/URGENT", "PROVISIONING/URGENT");
		assertThat((String) operatorNotifications().get(1).get("content")).contains("StartExecution 실패");
		assertThat((String) operatorNotifications().get(2).get("content")).contains("[B사]", "FAILED: E / C");
		assertFixedPartsFit(contents(operatorNotifications()));
	}

}
