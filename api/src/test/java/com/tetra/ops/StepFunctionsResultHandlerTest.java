package com.tetra.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import com.tetra.ops.listener.StepFunctionsResultHandler;
import com.tetra.ops.support.IntegrationTest;

/** SQS 결과 리스너 처리 (§8, §12 중복 처리). 리스너 메서드는 이 핸들러를 그대로 부른다. */
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
	}

	@ParameterizedTest
	@ValueSource(strings = { "FAILED", "TIMED_OUT", "ABORTED" })
	void tenantResultNonSuccessMarksFailed(String status) {
		data.tenant(B, "B사", "INACTIVE", "PROVISIONING");

		handler.handleTenantResult(message("tenant-" + B, status, "E", "C"));

		assertThat(data.tenantRow(B)).containsEntry("provisioning_status", "FAILED");
	}

	@Test
	void tenantResultIgnoresBadNames() {
		data.tenant(B, "B사", "INACTIVE", "PROVISIONING");

		handler.handleTenantResult(message("tenant-BBBBBBBBBBBB", "SUCCEEDED", null, null));
		handler.handleTenantResult(message("approve-" + EVENT_ID, "SUCCEEDED", null, null));
		handler.handleTenantResult(message("tenant-zzzzzzzzzzzz", "SUCCEEDED", null, null));

		assertThat(data.tenantRow(B)).containsEntry("provisioning_status", "PROVISIONING");
	}

}
