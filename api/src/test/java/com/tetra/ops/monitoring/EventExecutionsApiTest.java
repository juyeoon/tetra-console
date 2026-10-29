package com.tetra.ops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.tetra.ops.monitoring.AwsMonitoringGateway.ExecutionHistory;
import com.tetra.ops.monitoring.AwsMonitoringGateway.ExecutionItem;
import com.tetra.ops.support.IntegrationTest;

/**
 * GET /api/ops/monitoring/events/{eventId}/executions — AWS는 게이트웨이 목, 이벤트는 Testcontainers 시드. 캐시 TTL 0.
 * 실행 기록은 dev 기록 픽스처(approve-9 실패 · 이벤트 13 WF2-2 성공). 상태 머신은 application-test.yml의 승인 ARN과 tetra-test-… 이름.
 */
class EventExecutionsApiTest extends IntegrationTest {

	private static final String PREFIX = "arn:aws:states:ap-northeast-2:000000000000:";

	private static final String REGISTER = PREFIX + "stateMachine:approve";

	private static final String HOSTING_START = PREFIX + "stateMachine:tetra-test-hosting-start";

	/** 실행 입력에만 있는 값 — 응답에 나오면 안 된다 */
	private static final String INPUT_MARKER = "INPUT-ONLY-7201";

	@MockitoBean
	AwsMonitoringGateway aws;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("tetra.monitoring.cache-ttl", () -> "PT0S");
		registry.add("tetra.monitoring.aws.sfn-list-ttl", () -> "PT0S");
	}

	@BeforeEach
	void seed() {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.tenant(B, "B사", "ACTIVE", "READY");
		// 승인 2026-10-01 11:00 KST(TestData 기본), 호스팅 종료 = 시작 + 5시간
		data.event(7201, A).name("A 쿠폰 이벤트").approved("DEPLOYED", "SCHEDULED", "slug7201").insert();
		data.event(7202, A).name("A 승인 전 이벤트").insert();
		data.event(7203, B).name("B 이벤트").approved("DEPLOYED", "SCHEDULED", "slug7203").insert();

		Instant approved = Instant.parse("2026-10-01T02:00:00Z");
		when(aws.executions(anyString(), any(), anyInt())).thenReturn(List.of());
		when(aws.executions(eq(REGISTER), any(), anyInt()))
			.thenReturn(List.of(item(REGISTER, "approve-7201", "FAILED", approved.plusSeconds(5))));
		when(aws.executions(eq(HOSTING_START), any(), anyInt()))
			.thenReturn(List.of(item(HOSTING_START, "3f2c9a1e-7b4d-4e8a-9c1f-2a6b8d0e4f13", "SUCCEEDED",
					approved.plusSeconds(3600))));
		when(aws.executionInput(anyString())).thenReturn("{\"tenant_id\":\"" + A
				+ "\",\"event_id\":7201,\"slug\":\"slug7201\",\"endpoint_url\":\"https://shop.example.com/" + INPUT_MARKER
				+ "\"}");
		when(aws.executionHistory(eq(arn(REGISTER, "approve-7201")), anyInt()))
			.thenReturn(new ExecutionHistory(SfnHistoriesTest.load("approve-9"), false));
		when(aws.executionHistory(eq(arn(HOSTING_START, "3f2c9a1e-7b4d-4e8a-9c1f-2a6b8d0e4f13")), anyInt()))
			.thenReturn(new ExecutionHistory(SfnHistoriesTest.load("event-open-13"), false));
	}

	@Test
	void executionsWithStatesAndFailure() throws Exception {
		mvc.perform(get("/api/ops/monitoring/events/7201/executions").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.eventId").value(7201))
			.andExpect(jsonPath("$.aws.stale").value(false))
			.andExpect(jsonPath("$.executions", hasSize(2)))
			.andExpect(jsonPath("$.executions[0].flow").value("DEPLOY"))
			.andExpect(jsonPath("$.executions[0].workflow").value("WF1-2"))
			.andExpect(jsonPath("$.executions[0].name").value("approve-7201"))
			.andExpect(jsonPath("$.executions[0].attempt").value(1))
			.andExpect(jsonPath("$.executions[0].status").value("FAILED"))
			.andExpect(jsonPath("$.executions[0].failure.state").value("IsEventAppReady"))
			.andExpect(jsonPath("$.executions[0].failure.error").value("EventAppSyncTimedOut"))
			.andExpect(jsonPath("$.executions[0].states[5].kind").value("LOOP"))
			.andExpect(jsonPath("$.executions[0].states[5].names",
					contains("WaitEventAppSync", "CheckEventAppStatus", "IsEventAppReady")))
			.andExpect(jsonPath("$.executions[0].states[5].count").value(20))
			.andExpect(jsonPath("$.executions[0].states[5].status").value("FAILED"))
			.andExpect(jsonPath("$.executions[1].flow").value("HOSTING_START"))
			.andExpect(jsonPath("$.executions[1].workflow").value("WF2-1"))
			.andExpect(jsonPath("$.executions[1].failure").doesNotExist())
			.andExpect(jsonPath("$.executions[1].states[2].kind").value("LOOP"))
			.andExpect(jsonPath("$.executions[1].states[2].count").value(6))
			.andExpect(jsonPath("$.executions[1].states[2].durationSeconds").isNumber());
	}

	@Test
	void responseHasNoInputOutputOrArn() throws Exception {
		String body = mvc.perform(get("/api/ops/monitoring/events/7201/executions").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(body).contains("approve-7201", "IsEventAppReady");
		assertThat(body).doesNotContain("\"input\"", "\"output\"", "parameters", "Arn", "arn:", "roleArn",
				"000000000000", INPUT_MARKER, "endpoint_url");
	}

	@Test
	void otherTenantInputIsNotMatched() throws Exception {
		// B 이벤트 7203과 event_id가 같아도 input의 tenant_id가 A면 B 이벤트 실행으로 보지 않는다(리스너와 같은 규칙)
		String other = "9b8a7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d";
		when(aws.executions(eq(HOSTING_START), any(), anyInt()))
			.thenReturn(List.of(item(HOSTING_START, other, "SUCCEEDED", Instant.parse("2026-10-01T03:00:00Z"))));
		when(aws.executionInput(arn(HOSTING_START, other)))
			.thenReturn("{\"tenant_id\":\"" + A + "\",\"event_id\":7203,\"slug\":\"slug7203\"}");

		mvc.perform(get("/api/ops/monitoring/events/7203/executions").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.executions", hasSize(0)));
		verify(aws).executionInput(arn(HOSTING_START, other));
	}

	@Test
	void notApprovedEventSkipsStepFunctions() throws Exception {
		mvc.perform(get("/api/ops/monitoring/events/7202/executions").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.aws.stale").value(false))
			.andExpect(jsonPath("$.executions", hasSize(0)));
		verify(aws, never()).executions(anyString(), any(), anyInt());
	}

	@Test
	void awsFailureIsStale() throws Exception {
		when(aws.executions(eq(REGISTER), any(), anyInt()))
			.thenThrow(new AwsMonitoringException("ListExecutions AccessDeniedException"));
		mvc.perform(get("/api/ops/monitoring/events/7201/executions").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.aws.stale").value(true))
			.andExpect(jsonPath("$.aws.staleReason").value("SOURCE"));
	}

	@Test
	void unknownEventTenantNotReadyAndLogin() throws Exception {
		mvc.perform(get("/api/ops/monitoring/events/9999/executions").cookie(operatorCookie()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
		data.jdbc().update("UPDATE tetra_platform.tenant SET provisioning_status = 'FAILED' WHERE tenant_id = ?", B);
		mvc.perform(get("/api/ops/monitoring/events/7203/executions").cookie(operatorCookie()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TENANT_NOT_READY"));
		mvc.perform(get("/api/ops/monitoring/events/7201/executions")).andExpect(status().isUnauthorized());
	}

	private static ExecutionItem item(String stateMachine, String name, String status, Instant started) {
		return new ExecutionItem(arn(stateMachine, name), name, status, started, started.plusSeconds(600), 0);
	}

	private static String arn(String stateMachine, String name) {
		return stateMachine.replace(":stateMachine:", ":execution:") + ":" + name;
	}

}
