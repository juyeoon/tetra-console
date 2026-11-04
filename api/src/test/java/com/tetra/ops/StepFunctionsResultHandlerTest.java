package com.tetra.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.tetra.ops.external.MailGateway;
import com.tetra.ops.listener.StepFunctionsResultHandler;
import com.tetra.ops.support.IntegrationTest;

/** SQS 결과 리스너 처리 (§8, §12 중복 처리). 리스너 메서드는 이 핸들러를 그대로 부른다. */
@ExtendWith(OutputCaptureExtension.class)
class StepFunctionsResultHandlerTest extends IntegrationTest {

	static final long EVENT_ID = 4001;

	@Autowired
	StepFunctionsResultHandler handler;

	@BeforeEach
	void setUp() {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(EVENT_ID, A).approved("PENDING", "SCHEDULED", "k7f2q9xa").insert();
	}

	static String message(String name, String status, String error, String cause) {
		return """
				{"version":"0","id":"x","detail-type":"Step Functions Execution Status Change","source":"aws.states",
				 "detail":{"executionArn":"arn:aws:states:ap-northeast-2:0:execution:sm:%s","name":"%s","status":"%s",
				           "error":%s,"cause":%s}}""".formatted(name, name, status,
				error == null ? "null" : "\"" + error + "\"", cause == null ? "null" : "\"" + cause + "\"");
	}

	private List<Map<String, Object>> hostingLogs() {
		return data.statusLogs(A, EVENT_ID).stream().filter(l -> "HOSTING".equals(l.get("status_type"))).toList();
	}

	@Test
	void succeededMarksDeployedOnceEvenIfDeliveredTwice() {
		String body = message("approve-" + EVENT_ID, "SUCCEEDED", null, null);

		handler.handleApproveResult(body);
		handler.handleApproveResult(body);

		assertThat(data.registry(EVENT_ID)).containsEntry("hosting_status", "DEPLOYED");
		assertThat(data.eventRow(A, EVENT_ID)).containsEntry("hosting_status", "DEPLOYED");
		assertThat(hostingLogs()).hasSize(1);
		assertThat(hostingLogs().get(0)).containsEntry("prev_status", "PENDING")
			.containsEntry("new_status", "DEPLOYED")
			.containsEntry("detail", null)
			.containsEntry("changed_by", null);
	}

	@ParameterizedTest
	@ValueSource(strings = { "FAILED", "TIMED_OUT", "ABORTED" })
	void nonSuccessMarksDeployFailedWithDetail(String status) {
		String body = message("approve-" + EVENT_ID, status, "States.TaskFailed", "helm upgrade failed");

		handler.handleApproveResult(body);
		handler.handleApproveResult(body);

		assertThat(data.registry(EVENT_ID)).containsEntry("hosting_status", "DEPLOY_FAILED");
		assertThat(data.eventRow(A, EVENT_ID)).containsEntry("hosting_status", "DEPLOY_FAILED");
		assertThat(hostingLogs()).hasSize(1);
		assertThat((String) hostingLogs().get(0).get("detail"))
			.isEqualTo(status + ": States.TaskFailed / helm upgrade failed");
	}

	@Test
	void succeededAfterStartExecutionFailureCorrectsDeployFailed() {
		// StartExecution이 실패로 보여 DEPLOY_FAILED가 됐지만 실제로는 실행된 경우
		data.jdbc().update("UPDATE tetra_platform.event_registry SET hosting_status = 'DEPLOY_FAILED' WHERE event_id = ?",
				EVENT_ID);
		data.jdbc().update("UPDATE t_" + A + ".event SET hosting_status = 'DEPLOY_FAILED' WHERE event_id = ?", EVENT_ID);
		String body = message("approve-" + EVENT_ID, "SUCCEEDED", null, null);

		handler.handleApproveResult(body);
		handler.handleApproveResult(body);

		assertThat(data.registry(EVENT_ID)).containsEntry("hosting_status", "DEPLOYED");
		assertThat(data.eventRow(A, EVENT_ID)).containsEntry("hosting_status", "DEPLOYED");
		assertThat(hostingLogs()).hasSize(1);
		assertThat(hostingLogs().get(0)).containsEntry("prev_status", "DEPLOY_FAILED").containsEntry("new_status", "DEPLOYED");
	}

	@Test
	void failureOnDeployFailedIsIgnored() {
		data.jdbc().update("UPDATE tetra_platform.event_registry SET hosting_status = 'DEPLOY_FAILED' WHERE event_id = ?",
				EVENT_ID);
		data.jdbc().update("UPDATE t_" + A + ".event SET hosting_status = 'DEPLOY_FAILED' WHERE event_id = ?", EVENT_ID);

		handler.handleApproveResult(message("approve-" + EVENT_ID, "FAILED", "E", "C"));

		assertThat(data.registry(EVENT_ID)).containsEntry("hosting_status", "DEPLOY_FAILED");
		assertThat(hostingLogs()).isEmpty();
	}

	@Test
	void longCauseIsTruncatedTo500() {
		handler.handleApproveResult(message("approve-" + EVENT_ID, "FAILED", "E", "x".repeat(800)));
		assertThat((String) hostingLogs().get(0).get("detail")).hasSize(500);
	}

	@Test
	void malformedOrForeignMessagesAreAcknowledgedWithoutChanges() {
		handler.handleApproveResult("not json");
		handler.handleApproveResult("{\"detail\":{}}");
		handler.handleApproveResult(message("tenant-" + A, "SUCCEEDED", null, null));
		handler.handleApproveResult(message("approve-abc", "SUCCEEDED", null, null));
		handler.handleApproveResult(message("approve-" + EVENT_ID, "RUNNING", null, null));
		handler.handleApproveResult(message("approve-999999", "SUCCEEDED", null, null));
		// 재실행 접미어는 -r 뒤에 숫자가 있어야 한다
		handler.handleApproveResult(message("approve-" + EVENT_ID + "-r", "SUCCEEDED", null, null));
		handler.handleApproveResult(message("approve-" + EVENT_ID + "-rx", "SUCCEEDED", null, null));
		handler.handleApproveResult(message("approve-" + EVENT_ID + "-r1-r2", "SUCCEEDED", null, null));

		assertThat(data.registry(EVENT_ID)).containsEntry("hosting_status", "PENDING");
		assertThat(hostingLogs()).isEmpty();
	}

	@Test
	void unexpectedErrorPropagatesAndRollsBackSoMessageIsRedelivered() {
		// registry는 PENDING인데 event 행이 없다 → event UPDATE 0행 → 예외 → registry도 롤백
		data.event(4002, A).approved("PENDING", "SCHEDULED", "zzzz9999").registryOnly().insert();

		assertThatThrownBy(() -> handler.handleApproveResult(message("approve-4002", "SUCCEEDED", null, null)))
			.isInstanceOf(IllegalStateException.class);
		assertThat(data.registry(4002)).containsEntry("hosting_status", "PENDING");
	}

	@Test
	void tenantResultUpdatesProvisioningStatusOnce() {
		data.tenant(B, "B사", "INACTIVE", "PROVISIONING");
		String body = message("tenant-" + B, "SUCCEEDED", null, null);

		handler.handleTenantResult(body);
		handler.handleTenantResult(body);
		// 늦게 온 다른 결과도 PROVISIONING이 아니므로 무시
		handler.handleTenantResult(message("tenant-" + B, "FAILED", "E", "C"));

		assertThat(data.tenantRow(B)).containsEntry("provisioning_status", "READY");
		assertThat(data.tenantStatusLogs(B)).isEmpty();
		// 같은 메시지가 두 번 와도 READY로 실제로 바뀐 한 번만 메일
		verify(mail, times(1)).send(any());
	}

	@ParameterizedTest
	@ValueSource(strings = { "FAILED", "TIMED_OUT", "ABORTED" })
	void tenantResultNonSuccessMarksFailed(String status) {
		data.tenant(B, "B사", "INACTIVE", "PROVISIONING");

		handler.handleTenantResult(message("tenant-" + B, status, "E", "C"));

		assertThat(data.tenantRow(B)).containsEntry("provisioning_status", "FAILED");
		verify(mail, never()).send(any());
	}

	@Test
	void tenantResultIgnoresBadNames() {
		data.tenant(B, "B사", "INACTIVE", "PROVISIONING");

		handler.handleTenantResult(message("tenant-BBBBBBBBBBBB", "SUCCEEDED", null, null));
		handler.handleTenantResult(message("approve-" + EVENT_ID, "SUCCEEDED", null, null));
		handler.handleTenantResult(message("tenant-zzzzzzzzzzzz", "SUCCEEDED", null, null));

		assertThat(data.tenantRow(B)).containsEntry("provisioning_status", "PROVISIONING");
	}

	// ---- 수동 재실행 (실행 이름 끝 -rN, ops-workflows 계약) ----

	@Test
	void approveRerunNameIsAcceptedAndCorrectsDeployFailed() {
		data.event(12, A).approved("DEPLOY_FAILED", "SCHEDULED", "rerun012").insert();

		handler.handleApproveResult(message("approve-12-r1", "SUCCEEDED", null, null));

		assertThat(data.registry(12)).containsEntry("hosting_status", "DEPLOYED");
		assertThat(data.eventRow(A, 12)).containsEntry("hosting_status", "DEPLOYED");
		List<Map<String, Object>> logs = data.statusLogs(A, 12)
			.stream()
			.filter(l -> "HOSTING".equals(l.get("status_type")))
			.toList();
		assertThat(logs).hasSize(1);
		assertThat(logs.get(0)).containsEntry("prev_status", "DEPLOY_FAILED").containsEntry("new_status", "DEPLOYED");
	}

	/** dev 2-14: approve-3이 시간 초과로 DEPLOY_FAILED → 판정 수정 후 approve-3-r1 재실행 성공이 DEPLOYED로 바로잡는다 */
	@Test
	void timedOutThenRerunSucceededIsDeployedWithTwoLogs() {
		data.event(3, A).approved("PENDING", "SCHEDULED", "rerun003").insert();

		handler.handleApproveResult(message("approve-3", "TIMED_OUT", "States.Timeout", "ArgoCD sync wait timed out"));
		assertThat(data.registry(3)).containsEntry("hosting_status", "DEPLOY_FAILED");

		String rerun = message("approve-3-r1", "SUCCEEDED", null, null);
		handler.handleApproveResult(rerun);
		handler.handleApproveResult(rerun);

		assertThat(data.registry(3)).containsEntry("hosting_status", "DEPLOYED");
		assertThat(data.eventRow(A, 3)).containsEntry("hosting_status", "DEPLOYED");
		List<Map<String, Object>> logs = data.statusLogs(A, 3)
			.stream()
			.filter(l -> "HOSTING".equals(l.get("status_type")))
			.toList();
		assertThat(logs).extracting(l -> l.get("prev_status") + "→" + l.get("new_status"))
			.containsExactly("PENDING→DEPLOY_FAILED", "DEPLOY_FAILED→DEPLOYED");
	}

	/** 이미 배포 완료(DEPLOYED) · 그 뒤 상태(HOSTING)면 늦게 온 옛 결과(실패 · 성공 · 재실행)는 무시 */
	@ParameterizedTest
	@ValueSource(strings = { "DEPLOYED", "HOSTING" })
	void lateResultsDoNotOverwriteDeployedOrLater(String current) {
		data.event(5, A).approved(current, "SCHEDULED", "late0005").insert();

		handler.handleApproveResult(message("approve-5", "FAILED", "E", "C"));
		handler.handleApproveResult(message("approve-5", "TIMED_OUT", "E", "C"));
		handler.handleApproveResult(message("approve-5-r1", "SUCCEEDED", null, null));

		assertThat(data.registry(5)).containsEntry("hosting_status", current);
		assertThat(data.eventRow(A, 5)).containsEntry("hosting_status", current);
		assertThat(data.statusLogs(A, 5).stream().filter(l -> "HOSTING".equals(l.get("status_type")))).isEmpty();
	}

	@Test
	void approveRerunWithPendingEventMarksDeployed() {
		handler.handleApproveResult(message("approve-" + EVENT_ID + "-r2", "SUCCEEDED", null, null));

		assertThat(data.registry(EVENT_ID)).containsEntry("hosting_status", "DEPLOYED");
	}

	@Test
	void tenantRerunSucceededTurnsFailedIntoReady() {
		data.tenant(B, "B사", "INACTIVE", "FAILED");

		handler.handleTenantResult(message("tenant-" + B + "-r1", "SUCCEEDED", null, null));
		handler.handleTenantResult(message("tenant-" + B + "-r1", "SUCCEEDED", null, null));

		assertThat(data.tenantRow(B)).containsEntry("provisioning_status", "READY");
		assertThat(data.count("SELECT COUNT(*) FROM tetra_platform.operator_notification WHERE tenant_id = ?", B))
			.isEqualTo(1);
		// 수동 재실행으로 FAILED → READY가 되면 그때 한 번 메일. 같은 -r1이 또 오면 보내지 않는다
		verify(mail, times(1)).send(any());
	}

	@ParameterizedTest
	@ValueSource(strings = { "FAILED", "TIMED_OUT", "ABORTED" })
	void tenantRerunFailureKeepsFailed(String status) {
		data.tenant(B, "B사", "INACTIVE", "FAILED");

		handler.handleTenantResult(message("tenant-" + B + "-r2", status, "E", "C"));

		assertThat(data.tenantRow(B)).containsEntry("provisioning_status", "FAILED");
		verify(mail, never()).send(any());
		assertThat(data.count("SELECT COUNT(*) FROM tetra_platform.operator_notification WHERE tenant_id = ?", B))
			.isZero();
	}

	@Test
	void readyTenantIsNotChangedByLateResults() {
		data.tenant(B, "B사", "INACTIVE", "READY");

		handler.handleTenantResult(message("tenant-" + B + "-r1", "FAILED", "E", "C"));
		handler.handleTenantResult(message("tenant-" + B, "SUCCEEDED", null, null));

		assertThat(data.tenantRow(B)).containsEntry("provisioning_status", "READY");
		// 이미 READY면 메일을 다시 보내지 않는다
		verify(mail, never()).send(any());
	}

	// ---- 비밀번호 설정 메일: 환경 준비 성공(READY) 뒤 결과 리스너에서 ----

	@Test
	void readySendsSignedPasswordSetupMailAfterCommit(CapturedOutput output) throws Exception {
		data.tenant(B, "B사", "INACTIVE", "PROVISIONING");
		// 등록 뒤 담당자 이메일이 바뀌었으면 바뀐 주소로 보낸다(발송 때 테넌트를 다시 읽는다)
		data.jdbc().update("UPDATE tetra_platform.tenant SET email = 'new.owner@b.example.com' WHERE tenant_id = ?", B);
		// 메일을 보낼 때 이미 READY가 커밋돼 있어야 한다(상태 커밋 → 발송)
		AtomicReference<Object> statusAtSend = new AtomicReference<>();
		when(mail.send(any())).thenAnswer(invocation -> {
			statusAtSend.set(data.tenantRow(B).get("provisioning_status"));
			return true;
		});

		handler.handleTenantResult(message("tenant-" + B, "SUCCEEDED", null, null));

		assertThat(statusAtSend).hasValue("READY");
		ArgumentCaptor<MailGateway.Mail> captor = ArgumentCaptor.forClass(MailGateway.Mail.class);
		verify(mail).send(captor.capture());
		MailGateway.Mail sent = captor.getValue();
		assertThat(sent.to()).isEqualTo("new.owner@b.example.com");
		assertThat(sent.subject()).isEqualTo("[Tetra] 테넌트 계정이 등록되었습니다");
		assertThat(sent.textBody()).contains("안녕하세요, B사 담당자님.", "Tetra에 B사 테넌트 계정이 등록되었습니다.", "24시간");

		// 링크: {portal}/password/setup#t=&exp=&sig= (프래그먼트 — 서버로 가지 않음, 2026-11-05), sig = base64url(HMAC-SHA256(secret,
		// t + exp + "")). 만료는 발송 시점 + 24시간
		String link = sent.textBody().replaceAll("(?s).*(https://portal\\.example\\.test/password/setup#\\S+).*", "$1");
		assertThat(URI.create(link).getRawQuery()).isNull();
		Map<String, String> query = fragment(link);
		assertThat(query.get("t")).isEqualTo(B);
		long exp = Long.parseLong(query.get("exp"));
		assertThat(exp - System.currentTimeMillis() / 1000).isBetween(24 * 3600L - 60, 24 * 3600L + 60);
		assertThat(query.get("sig")).isEqualTo(hmac("test-hmac-secret", B + exp));

		// 링크는 로그 · 알림에 남지 않는다
		assertThat(output.getAll()).doesNotContain(query.get("sig")).doesNotContain("password/setup");
		assertThat(sent.toString()).doesNotContain("sig=");
		assertThat(data.count("SELECT COUNT(*) FROM tetra_platform.operator_notification WHERE tenant_id = ? AND category = 'MAIL'", B))
			.isZero();
	}

	@Test
	void mailFailureKeepsReadyNotifiesOperatorAndDoesNotThrow() {
		data.tenant(B, "B사", "INACTIVE", "PROVISIONING");
		when(mail.send(any())).thenReturn(false);
		String body = message("tenant-" + B, "SUCCEEDED", null, null);

		// 예외 없이 끝나야 리스너가 ack한다(메시지 재처리 없음). 재전달되어도 이미 READY라 다시 보내지 않는다
		handler.handleTenantResult(body);
		handler.handleTenantResult(body);

		assertThat(data.tenantRow(B)).containsEntry("provisioning_status", "READY");
		verify(mail, times(1)).send(any());
		assertThat(mailFailureNotifications(B, "B사")).isEqualTo(1);
	}

	@Test
	void unexpectedMailErrorIsCaughtAndNotified() {
		data.tenant(B, "B사", "INACTIVE", "PROVISIONING");
		when(mail.send(any())).thenThrow(new IllegalStateException("boom"));

		handler.handleTenantResult(message("tenant-" + B, "SUCCEEDED", null, null));

		assertThat(data.tenantRow(B)).containsEntry("provisioning_status", "READY");
		assertThat(mailFailureNotifications(B, "B사")).isEqualTo(1);
	}

	/** 비밀번호 설정 메일 실패 알림: 주의(WARNING), [테넌트명]으로 시작(테넌트 ID는 문구에 넣지 않는다) */
	private long mailFailureNotifications(String tenantId, String tenantName) {
		return data.count("SELECT COUNT(*) FROM tetra_platform.operator_notification WHERE tenant_id = ?"
				+ " AND category = 'MAIL' AND priority = 'WARNING' AND content = ?", tenantId,
				"[" + tenantName + "] 비밀번호 설정 메일 발송에 실패했습니다. 담당자 이메일 주소와 SES 상태를 확인해 주세요.");
	}

	// ---- 스키마가 없으면 READY로 바꾸지 않는다 ----

	/** PROVISIONING → FAILED로 실제로 바뀔 때만 URGENT 1건. 이미 FAILED면 상태 · 알림 그대로. 중복 수신도 한 번 */
	@ParameterizedTest
	@CsvSource({ "PROVISIONING, 1", "FAILED, 0" })
	void successWithoutTenantSchemaIsNotMadeReady(String current, long expectedNotifications) {
		String noSchema = "cccccccccccc";
		data.tenant(noSchema, "C사", "INACTIVE", current);
		String body = message("tenant-" + noSchema + "-r1", "SUCCEEDED", null, null);

		handler.handleTenantResult(body);
		handler.handleTenantResult(body);

		assertThat(data.tenantRow(noSchema)).containsEntry("provisioning_status", "FAILED");
		verify(mail, never()).send(any());
		assertThat(data.count("SELECT COUNT(*) FROM tetra_platform.operator_notification WHERE tenant_id = ?"
				+ " AND category = 'PROVISIONING' AND priority = 'URGENT' AND content LIKE ?", noSchema,
				"[C사] %테넌트 스키마(t_" + noSchema + ")가 없어%READY로 반영하지 않았습니다%")).isEqualTo(expectedNotifications);
		assertThat(data.count("SELECT COUNT(*) FROM tetra_platform.operator_notification WHERE tenant_id = ?", noSchema))
			.isEqualTo(expectedNotifications);
	}

	private static Map<String, String> fragment(String url) {
		return List.of(URI.create(url).getRawFragment().split("&"))
			.stream()
			.map(p -> p.split("=", 2))
			.collect(Collectors.toMap(p -> p[0], p -> p[1]));
	}

	private static String hmac(String secret, String message) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
	}

}
