package com.tetra.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import com.tetra.ops.event.EntryOpen;
import com.tetra.ops.listener.LifecycleResultHandler;
import com.tetra.ops.support.IntegrationTest;
import com.tetra.ops.support.TestData;

/** 이벤트 수명 주기 결과 리스너 (WF2-1 · WF2-2 · WF4, §8). 상태 머신 이름은 테스트 env 기준 tetra-test-…. */
class LifecycleResultHandlerTest extends IntegrationTest {

	static final String HOSTING_START = "tetra-test-hosting-start";

	static final String EVENT_OPEN = "tetra-test-event-open";

	static final String EVENT_END = "tetra-test-event-end";

	static final long EVENT_ID = 4201;

	/** 운영자 알림 라벨 [테넌트명] [id:이벤트명] · 테넌트 알림 라벨 [id:이벤트명] (이벤트명은 TestData 기본값) */
	static final String LABEL = "[A사] [4201:이벤트 4201]";

	static final String TENANT_LABEL = "[4201:이벤트 4201]";

	private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

	/** 테넌트 호스팅 시작 알림(입장 = 시작 − 입장 리드, 이벤트 시작은 TestData.FUTURE) */
	static final String TENANT_HOSTING_STARTED = "HOSTING/INFO " + TENANT_LABEL + " 이벤트 페이지가 열렸습니다. 입장은 "
			+ HH_MM.format(EntryOpen.at(TestData.FUTURE)) + "부터 시작됩니다.";

	static final String TENANT_ENTRY_OPENED = "EVENT/INFO " + TENANT_LABEL + " 이벤트 입장이 시작되었습니다. 이벤트는 "
			+ HH_MM.format(TestData.FUTURE) + "에 시작됩니다.";

	@Autowired
	LifecycleResultHandler handler;

	@BeforeEach
	void setUp() {
		data.tenant(A, "A사", "ACTIVE", "READY");
	}

	/** EventBridge "Step Functions Execution Status Change" — 실행 이름은 스케줄러가 정하므로 아무 값, input은 JSON 문자열 */
	static String message(String stateMachine, String status, long eventId, String tenantId, String error, String cause) {
		String input = "{\\\"tenant_id\\\":\\\"" + tenantId + "\\\",\\\"event_id\\\":" + eventId + ",\\\"slug\\\":\\\"lc000001\\\"}";
		return """
				{"version":"0","id":"x","detail-type":"Step Functions Execution Status Change","source":"aws.states",
				 "detail":{"executionArn":"arn:aws:states:ap-northeast-2:0:execution:%s:sched-%d","stateMachineArn":"arn:aws:states:ap-northeast-2:0:stateMachine:%s",
				           "name":"sched-%d-abc","status":"%s","input":"%s","error":%s,"cause":%s}}""".formatted(stateMachine,
				eventId, stateMachine, eventId, status, input, error == null ? "null" : "\"" + error + "\"",
				cause == null ? "null" : "\"" + cause + "\"");
	}

	private String ok(String stateMachine) {
		return message(stateMachine, "SUCCEEDED", EVENT_ID, A, null, null);
	}

	private String failed(String stateMachine, String status) {
		return message(stateMachine, status, EVENT_ID, A, "States.TaskFailed", "lambda error");
	}

	private void event(String hosting, String eventStatus) {
		data.event(EVENT_ID, A).approved(hosting, eventStatus, "lc000001").insert();
	}

	private List<String> logs(String type) {
		return data.statusLogs(A, EVENT_ID)
			.stream()
			.filter(l -> type.equals(l.get("status_type")))
			.map(l -> l.get("prev_status") + "→" + l.get("new_status"))
			.toList();
	}

	private void assertStatus(String hosting, String eventStatus) {
		assertThat(data.registry(EVENT_ID)).containsEntry("hosting_status", hosting).containsEntry("event_status", eventStatus);
		assertThat(data.eventRow(A, EVENT_ID)).containsEntry("hosting_status", hosting).containsEntry("event_status", eventStatus);
	}

	private List<String> notifications() {
		return data.jdbc()
			.queryForList("SELECT CONCAT(category, '/', priority, ' ', content) FROM tetra_platform.operator_notification"
					+ " WHERE event_id = ? ORDER BY notification_id", String.class, EVENT_ID);
	}

	private List<String> tenantNotifications() {
		return data.jdbc()
			.queryForList("SELECT CONCAT(category, '/', priority, ' ', content) FROM t_" + A + ".tenant_notification"
					+ " WHERE event_id = ? ORDER BY notification_id", String.class, EVENT_ID);
	}

	/** 운영자 · 테넌트 알림 내용만(category/priority 접두어 없이) */
	private List<String> allContents() {
		return Stream.concat(
				data.jdbc().queryForList("SELECT content FROM tetra_platform.operator_notification ORDER BY notification_id",
						String.class).stream(),
				data.jdbc().queryForList("SELECT content FROM t_" + A + ".tenant_notification ORDER BY notification_id",
						String.class).stream())
			.toList();
	}

	// ---- WF2-1 호스팅 시작 ----

	@Test
	void hostingStartSucceededOnceEvenIfDeliveredTwice() {
		event("DEPLOYED", "SCHEDULED");

		handler.handle(ok(HOSTING_START));
		handler.handle(ok(HOSTING_START));

		assertStatus("HOSTING", "SCHEDULED");
		assertThat(logs("HOSTING")).containsExactly("DEPLOYED→HOSTING");
		assertThat(notifications()).containsExactly("HOSTING/INFO " + LABEL + " 호스팅을 시작했습니다(대기방 열림).");
		// 테넌트에도 이벤트 페이지 열림 1건(입장 시각 안내)
		assertThat(tenantNotifications()).containsExactly(TENANT_HOSTING_STARTED);
	}

	@ParameterizedTest
	@ValueSource(strings = { "FAILED", "TIMED_OUT", "ABORTED" })
	void hostingStartFailureThenRerunSucceeded(String status) {
		event("DEPLOYED", "SCHEDULED");

		handler.handle(failed(HOSTING_START, status));
		assertStatus("HOSTING_FAILED", "SCHEDULED");
		assertThat((String) data.statusLogs(A, EVENT_ID).get(0).get("detail"))
			.isEqualTo(status + ": States.TaskFailed / lambda error");

		handler.handle(ok(HOSTING_START));
		// 정상이 된 뒤 늦게 온 옛 실패는 덮어쓰지 않는다
		handler.handle(failed(HOSTING_START, status));

		assertStatus("HOSTING", "SCHEDULED");
		assertThat(logs("HOSTING")).containsExactly("DEPLOYED→HOSTING_FAILED", "HOSTING_FAILED→HOSTING");
		assertThat(notifications()).containsExactly(
				"HOSTING/URGENT " + LABEL + " 호스팅 시작(대기방 열기)에 실패했습니다. 확인이 필요합니다. 원인: " + status
						+ ": States.TaskFailed / lambda error",
				"HOSTING/INFO " + LABEL + " 호스팅 시작 실패로 표시됐던 이벤트의 호스팅이 시작되었습니다.");
		// 실패는 테넌트에 남기지 않고, 복구(재실행 성공)는 처음 시작과 같은 문구로 1건
		assertThat(tenantNotifications()).containsExactly(TENANT_HOSTING_STARTED);
	}

	@Test
	void hostingStartSucceededOnDeployFailedIsIgnored() {
		event("DEPLOY_FAILED", "SCHEDULED");

		handler.handle(ok(HOSTING_START));
		handler.handle(failed(HOSTING_START, "FAILED"));

		assertStatus("DEPLOY_FAILED", "SCHEDULED");
		assertThat(data.statusLogs(A, EVENT_ID)).isEmpty();
	}

	// ---- WF2-2 이벤트 준비(입장 열기) ----

	@Test
	void eventOpenSucceededChangesOnlyEventStatus() {
		event("HOSTING", "SCHEDULED");

		handler.handle(ok(EVENT_OPEN));
		handler.handle(ok(EVENT_OPEN));

		assertStatus("HOSTING", "OPEN");
		assertThat(logs("EVENT")).containsExactly("SCHEDULED→OPEN");
		assertThat(logs("HOSTING")).isEmpty();
		assertThat(notifications()).containsExactly("EVENT/INFO " + LABEL + " 입장이 열렸습니다(쿠폰 발급은 이벤트 시작 시각부터).");
		// 테넌트에도 입장 시작 1건(이벤트 시작 시각 안내)
		assertThat(tenantNotifications()).containsExactly(TENANT_ENTRY_OPENED);
	}

	@Test
	void eventOpenFailureKeepsHostingThenRerunOpens() {
		// 호스팅 시작이 실패했어도 이벤트 준비는 따로 돈다
		event("HOSTING_FAILED", "SCHEDULED");

		handler.handle(failed(EVENT_OPEN, "FAILED"));
		assertStatus("HOSTING_FAILED", "OPEN_FAILED");

		handler.handle(ok(EVENT_OPEN));
		handler.handle(failed(EVENT_OPEN, "TIMED_OUT"));

		assertStatus("HOSTING_FAILED", "OPEN");
		assertThat(logs("EVENT")).containsExactly("SCHEDULED→OPEN_FAILED", "OPEN_FAILED→OPEN");
		assertThat(notifications()).extracting(n -> n.substring(0, n.indexOf(' ')))
			.containsExactly("EVENT/URGENT", "EVENT/INFO");
		assertThat(notifications().get(0)).contains("이벤트 준비(입장 열기)에 실패했습니다", "원인: FAILED: States.TaskFailed / lambda error");
		assertThat(notifications().get(1)).contains("이벤트 준비 실패로 표시됐던 이벤트의 입장이 열렸습니다.");
		// 준비 실패는 테넌트에 남기지 않고, 복구는 입장 시작과 같은 문구로 1건
		assertThat(tenantNotifications()).containsExactly(TENANT_ENTRY_OPENED);
	}

	// ---- WF4 이벤트 종료 ----

	@Test
	void eventEndSucceededEndsBothLogsTwoNotifiesOnceAndDeactivatesIdleTenant() {
		event("HOSTING", "OPEN");

		handler.handle(ok(EVENT_END));
		handler.handle(ok(EVENT_END));

		assertStatus("ENDED", "EXPIRED");
		assertThat(logs("HOSTING")).containsExactly("HOSTING→ENDED");
		assertThat(logs("EVENT")).containsExactly("OPEN→EXPIRED");
		assertThat(notifications()).containsExactly(
				"HOSTING/INFO " + LABEL + " 호스팅이 종료되었습니다. 이벤트 앱(발급 서버·Redis)과 엣지 라우팅 키를 정리했습니다.",
				"ACCOUNT/INFO [A사] 진행 중인 이벤트가 없어 계정이 비활성으로 전환되었습니다.");
		// 종료(HOSTING → ENDED) 1건 + 발급 이력 처음 저장 때 집계 완료 1건(두 번 받아도 한 번씩, §7.13)
		assertThat(tenantNotifications()).containsExactly(
				"HOSTING/INFO " + TENANT_LABEL + " 이벤트가 종료되었습니다. 결과 집계가 끝나면 다시 알려 드립니다.",
				"EVENT/INFO " + TENANT_LABEL
						+ " 이벤트 결과 집계가 완료되었습니다. 결과 화면에서 발급 내역을 확인하고 CSV를 내려받을 수 있습니다.");
		// 남은 이벤트가 없으면 계정 비활성(사유 배지 [종료])
		assertThat(data.tenantRow(A)).containsEntry("account_status", "INACTIVE");
		List<Map<String, Object>> tenantLogs = data.tenantStatusLogs(A);
		assertThat(tenantLogs).hasSize(1);
		assertThat(tenantLogs.get(0)).containsEntry("prev_status", "ACTIVE")
			.containsEntry("new_status", "INACTIVE")
			.containsEntry("related_event_id", EVENT_ID);
	}

	@Test
	void eventEndKeepsTenantActiveWhenOtherEventRemains() {
		event("HOSTING", "OPEN");
		data.event(4202, A).approved("PENDING", "SCHEDULED", "lc000002").insert();

		handler.handle(ok(EVENT_END));

		assertStatus("ENDED", "EXPIRED");
		assertThat(data.tenantRow(A)).containsEntry("account_status", "ACTIVE");
		assertThat(data.tenantStatusLogs(A)).isEmpty();
	}

	/** 입장을 못 연 이벤트(OPEN_FAILED) · 호스팅 실패 이벤트도 종료 성공이면 정리된 것으로 본다 */
	@ParameterizedTest
	@CsvSource({ "HOSTING_FAILED, OPEN_FAILED", "DEPLOY_FAILED, SCHEDULED", "END_FAILED, OPEN" })
	void eventEndSucceededFromAnyNotEndedState(String hosting, String eventStatus) {
		event(hosting, eventStatus);

		handler.handle(ok(EVENT_END));

		assertStatus("ENDED", "EXPIRED");
		assertThat(logs("HOSTING")).containsExactly(hosting + "→ENDED");
		assertThat(logs("EVENT")).containsExactly(eventStatus + "→EXPIRED");
	}

	@Test
	void eventEndFailureKeepsEventThenRerunEnds() {
		event("HOSTING", "OPEN");

		handler.handle(failed(EVENT_END, "FAILED"));
		assertStatus("END_FAILED", "OPEN");
		// 이벤트가 진행 중으로 남아 계정은 그대로
		assertThat(data.tenantRow(A)).containsEntry("account_status", "ACTIVE");
		assertThat(notifications().get(0)).startsWith("HOSTING/URGENT").contains("이벤트 종료 처리에 실패했습니다");

		handler.handle(ok(EVENT_END));
		handler.handle(failed(EVENT_END, "FAILED"));

		assertStatus("ENDED", "EXPIRED");
		assertThat(logs("HOSTING")).containsExactly("HOSTING→END_FAILED", "END_FAILED→ENDED");
		assertThat(logs("EVENT")).containsExactly("OPEN→EXPIRED");
		assertThat(data.tenantRow(A)).containsEntry("account_status", "INACTIVE");
	}

	@ParameterizedTest
	@ValueSource(strings = { "HOSTING_FAILED", "DEPLOYED" })
	void eventEndFailureFromOtherStatesMarksEndFailed(String hosting) {
		event(hosting, "SCHEDULED");

		handler.handle(failed(EVENT_END, "ABORTED"));

		assertStatus("END_FAILED", "SCHEDULED");
		assertThat(logs("EVENT")).isEmpty();
	}

	// ---- 문구 길이 ----

	/** 수명 주기 알림의 고정 문구(라벨 + 정해진 문장)는 120자 이내 — 원인은 자르지 않고 뒤에 붙는다 */
	@Test
	void lifecycleFixedWordingFitsIn120Chars() {
		event("DEPLOYED", "SCHEDULED");

		handler.handle(failed(HOSTING_START, "FAILED"));
		handler.handle(ok(HOSTING_START));
		handler.handle(failed(EVENT_OPEN, "FAILED"));
		handler.handle(ok(EVENT_OPEN));
		handler.handle(failed(EVENT_END, "FAILED"));
		handler.handle(ok(EVENT_END));

		// 운영자: 실패 3 · 복구 2 · 종료 · 비활성, 테넌트: 호스팅 시작 · 입장 시작 · 종료 · 집계 완료
		assertThat(allContents()).hasSize(11);
		assertThat(tenantNotifications()).hasSize(4);
		NotificationTest.assertFixedPartsFit(allContents());
	}

	// ---- 형식 오류 · 다른 이벤트: WARN 후 ack(변경 없음) ----

	@Test
	void malformedOrForeignMessagesAreAcknowledgedWithoutChanges() {
		event("DEPLOYED", "SCHEDULED");

		handler.handle("not json");
		handler.handle("{\"detail\":{}}");
		// 모르는 상태 머신(다른 env 접두사 · 승인 상태 머신)
		handler.handle(message("tetra-prod-hosting-start", "SUCCEEDED", EVENT_ID, A, null, null));
		handler.handle(message("tetra-test-event-register", "SUCCEEDED", EVENT_ID, A, null, null));
		// 없는 이벤트, 다른 테넌트
		handler.handle(message(HOSTING_START, "SUCCEEDED", 999999, A, null, null));
		handler.handle(message(HOSTING_START, "SUCCEEDED", EVENT_ID, B, null, null));
		// input 없음 · event_id 형식 오류
		handler.handle("""
				{"detail":{"stateMachineArn":"arn:aws:states:ap-northeast-2:0:stateMachine:tetra-test-hosting-start",
				           "name":"sched-1","status":"SUCCEEDED"}}""");
		handler.handle(ok(HOSTING_START).replace("\\\"event_id\\\":4201", "\\\"event_id\\\":\\\"abc\\\""));
		// 처리 대상이 아닌 상태
		handler.handle(message(HOSTING_START, "RUNNING", EVENT_ID, A, null, null));

		assertStatus("DEPLOYED", "SCHEDULED");
		assertThat(data.statusLogs(A, EVENT_ID)).isEmpty();
	}

	@Test
	void versionedStateMachineArnAndStringEventIdAreAccepted() {
		event("DEPLOYED", "SCHEDULED");
		String versioned = ok(HOSTING_START)
			.replace("stateMachine:" + HOSTING_START + "\"", "stateMachine:" + HOSTING_START + ":3\"")
			.replace("\\\"event_id\\\":4201", "\\\"event_id\\\":\\\"4201\\\"");

		handler.handle(versioned);

		assertStatus("HOSTING", "SCHEDULED");
	}

}
