package com.tetra.ops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tetra.ops.config.TetraProperties;
import com.tetra.ops.monitoring.AwsMonitoringGateway.ExecutionHistory;
import com.tetra.ops.monitoring.AwsMonitoringGateway.ExecutionItem;
import com.tetra.ops.monitoring.EventExecutionService.EventKey;
import com.tetra.ops.monitoring.EventExecutionService.Owner;
import com.tetra.ops.monitoring.EventExecutionsResponse.Execution;
import com.tetra.ops.monitoring.EventExecutionsResponse.Flow;
import com.tetra.ops.monitoring.MonitoringSource.StaleReason;
import com.tetra.ops.monitoring.SfnHistoriesTest.Builder;

import tools.jackson.databind.json.JsonMapper;

/**
 * 이벤트 → 실행 찾기와 캐시 3단(목록 · Describe · 기록). 게이트웨이는 목. RefreshCache TTL은 nanoTime 기준이라
 * 캐시를 보는 테스트는 TTL 0(매번 갱신) 서비스와 긴 TTL 서비스를 나눠 쓴다.
 */
class EventExecutionServiceTest {

	private static final String TENANT = "a1b2c3d4e5f6";

	private static final String OTHER_TENANT = "zzzzzzzzzzzz";

	private static final String PREFIX = "arn:aws:states:ap-northeast-2:000000000000:";

	private static final String REGISTER = PREFIX + "stateMachine:tetra-test-event-register";

	private static final String HOSTING_START = PREFIX + "stateMachine:tetra-test-hosting-start";

	private static final String EVENT_OPEN = PREFIX + "stateMachine:tetra-test-event-open";

	private static final String EVENT_END = PREFIX + "stateMachine:tetra-test-event-end";

	private static final TetraProperties.Sfn SFN = new TetraProperties.Sfn(REGISTER,
			PREFIX + "stateMachine:tetra-test-tenant-provision", new TetraProperties.Lifecycle(
					"tetra-test-hosting-start", "tetra-test-event-open", "tetra-test-event-end"));

	/** 이벤트 13: 승인 14:18 KST, 호스팅 종료 15:15 KST(2026-10-28) */
	private static final EventKey EVENT_13 = new EventKey(13, TENANT, LocalDateTime.of(2026, 10, 28, 14, 18),
			LocalDateTime.of(2026, 10, 28, 15, 15));

	private static final EventKey EVENT_12 = new EventKey(12, TENANT, LocalDateTime.of(2026, 10, 28, 9, 21),
			LocalDateTime.of(2026, 10, 28, 10, 25));

	private final AwsMonitoringGateway aws = mock(AwsMonitoringGateway.class);

	private final Clock clock = Clock.fixed(Instant.parse("2026-10-28T07:30:00Z"), ZoneOffset.UTC);

	private EventExecutionService service;

	@BeforeEach
	void stubs() {
		when(aws.executions(eq(REGISTER), any(), anyInt())).thenReturn(List.of(
				item(REGISTER, "approve-13-r1", "SUCCEEDED", "2026-10-28T05:40:00Z", 0),
				item(REGISTER, "approve-130", "SUCCEEDED", "2026-10-28T05:30:00Z", 0),
				item(REGISTER, "approve-13", "FAILED", "2026-10-28T05:18:38Z", 0),
				item(REGISTER, "approve-12", "SUCCEEDED", "2026-10-28T00:21:58Z", 0)));
		when(aws.executions(eq(HOSTING_START), any(), anyInt())).thenReturn(List.of(
				item(HOSTING_START, "uuid-a", "SUCCEEDED", "2026-10-28T05:35:15Z", 0),
				item(HOSTING_START, "uuid-b", "SUCCEEDED", "2026-10-28T00:35:10Z", 0),
				// 이벤트 13 승인(05:18Z)보다 앞 — 13이면 Describe하지 않는다
				item(HOSTING_START, "uuid-old", "SUCCEEDED", "2026-10-27T07:45:10Z", 0)));
		when(aws.executions(eq(EVENT_OPEN), any(), anyInt())).thenReturn(List.of(
				item(EVENT_OPEN, "uuid-e", "SUCCEEDED", "2026-10-28T05:45:37Z", 0),
				item(EVENT_OPEN, "uuid-d", "SUCCEEDED", "2026-10-28T05:45:30Z", 0)));
		when(aws.executions(eq(EVENT_END), any(), anyInt())).thenReturn(List.of(
				// 호스팅 종료 + 1일 뒤 — 찾지 않는다
				item(EVENT_END, "uuid-late", "SUCCEEDED", "2026-10-30T07:00:00Z", 0)));
		when(aws.executionInput(arn(HOSTING_START, "uuid-a")))
			.thenReturn("{\"tenant_id\":\"" + TENANT + "\",\"event_id\":13,\"slug\":\"slug0013\"}");
		when(aws.executionInput(arn(HOSTING_START, "uuid-b")))
			.thenReturn("{\"tenant_id\":\"" + TENANT + "\",\"event_id\":12,\"slug\":\"slug0012\"}");
		when(aws.executionInput(arn(EVENT_OPEN, "uuid-e"))).thenReturn("{\"tenant_id\":\"" + TENANT
				+ "\",\"event_id\":\"13\",\"slug\":\"slug0013\",\"entry_open_at\":\"2026-10-28T14:55:00+09:00\"}");
		// 다른 테넌트의 같은 event_id — 리스너처럼 뺀다
		when(aws.executionInput(arn(EVENT_OPEN, "uuid-d")))
			.thenReturn("{\"tenant_id\":\"" + OTHER_TENANT + "\",\"event_id\":13,\"slug\":\"slug9999\"}");
		when(aws.executionHistory(anyString(), anyInt())).thenAnswer(inv -> succeeded());
		when(aws.executionHistory(eq(arn(EVENT_OPEN, "uuid-e")), anyInt()))
			.thenReturn(new ExecutionHistory(SfnHistoriesTest.load("event-open-13"), false));
		when(aws.executionHistory(eq(arn(REGISTER, "approve-13")), anyInt()))
			.thenReturn(new ExecutionHistory(SfnHistoriesTest.load("approve-9"), false));
	}

	@AfterEach
	void shutdown() {
		if (service != null) {
			service.shutdown();
		}
	}

	@Test
	void findsExecutionsOfEventByNameAndInput() {
		service = service(Duration.ofMinutes(5));
		EventExecutionsResponse response = service.respond(EVENT_13);

		assertThat(response.aws().stale()).isFalse();
		assertThat(response.executions()).extracting(Execution::flow, Execution::workflow, Execution::name,
				Execution::attempt, Execution::status)
			.containsExactly(tuple(Flow.DEPLOY, "WF1-2", "approve-13", 1, "FAILED"),
					tuple(Flow.DEPLOY, "WF1-2", "approve-13-r1", 2, "SUCCEEDED"),
					tuple(Flow.HOSTING_START, "WF2-1", "uuid-a", 1, "SUCCEEDED"),
					tuple(Flow.PREPARE, "WF2-2", "uuid-e", 1, "SUCCEEDED"));
		// WF1-2는 이름으로만, 시간 창 밖은 Describe하지 않는다
		verify(aws, never()).executionInput(arn(REGISTER, "approve-13"));
		verify(aws, never()).executionInput(arn(HOSTING_START, "uuid-old"));
		verify(aws, never()).executionInput(arn(EVENT_END, "uuid-late"));

		Execution failed = response.executions().get(0);
		assertThat(failed.failure().state()).isEqualTo("IsEventAppReady");
		assertThat(failed.failure().error()).isEqualTo("EventAppSyncTimedOut");
		assertThat(failed.stoppedAt()).isNotNull();
		Execution prepare = response.executions().get(3);
		assertThat(prepare.failure()).isNull();
		assertThat(prepare.durationSeconds()).isEqualTo(587L);
		assertThat(prepare.states()).anySatisfy(s -> {
			assertThat(s.names()).containsExactly("WaitEventReady", "CheckEventApp", "IsEventAppReady");
			assertThat(s.count()).isEqualTo(6);
		});
	}

	@Test
	void listIsSharedAcrossEvents() {
		service = service(Duration.ofMinutes(5));
		service.respond(EVENT_13);
		EventExecutionsResponse twelve = service.respond(EVENT_12);

		assertThat(twelve.executions()).extracting(Execution::flow, Execution::name)
			.containsExactly(tuple(Flow.DEPLOY, "approve-12"), tuple(Flow.HOSTING_START, "uuid-b"));
		for (String machine : List.of(REGISTER, HOSTING_START, EVENT_OPEN, EVENT_END)) {
			verify(aws, times(1)).executions(eq(machine), any(), anyInt());
		}
	}

	@Test
	void describeIsKeptAndFinishedHistoryIsCached() {
		service = service(Duration.ZERO);
		service.respond(EVENT_13);
		service.respond(EVENT_13);

		// 목록은 두 번 받았지만 Describe · 끝난 실행 기록은 한 번
		verify(aws, times(2)).executions(eq(HOSTING_START), any(), anyInt());
		verify(aws, times(1)).executionInput(arn(HOSTING_START, "uuid-a"));
		verify(aws, times(1)).executionInput(arn(EVENT_OPEN, "uuid-d"));
		verify(aws, times(1)).executionHistory(eq(arn(EVENT_OPEN, "uuid-e")), anyInt());
	}

	@Test
	void runningHistoryIsFetchedEveryTimeAndRedriveChangesKey() {
		when(aws.executions(eq(EVENT_END), any(), anyInt()))
			.thenReturn(List.of(item(EVENT_END, "uuid-f", "RUNNING", "2026-10-28T06:15:22Z", 0)));
		when(aws.executionInput(arn(EVENT_END, "uuid-f")))
			.thenReturn("{\"tenant_id\":\"" + TENANT + "\",\"event_id\":13,\"slug\":\"slug0013\"}");
		service = service(Duration.ZERO);

		Execution running = service.respond(EVENT_13).executions().getLast();
		assertThat(running.flow()).isEqualTo(Flow.END);
		service.respond(EVENT_13);
		verify(aws, times(2)).executionHistory(eq(arn(EVENT_END, "uuid-f")), anyInt());

		// 끝난 실행을 재실행(redrive)하면 status · redriveCount가 바뀌어 다시 받는다
		when(aws.executions(eq(HOSTING_START), any(), anyInt()))
			.thenReturn(List.of(item(HOSTING_START, "uuid-a", "SUCCEEDED", "2026-10-28T05:35:15Z", 1)));
		service.respond(EVENT_13);
		verify(aws, times(2)).executionHistory(eq(arn(HOSTING_START, "uuid-a")), anyInt());
	}

	@Test
	void runningExecutionShowsElapsedTime() {
		when(aws.executions(eq(EVENT_END), any(), anyInt()))
			.thenReturn(List.of(item(EVENT_END, "uuid-f", "RUNNING", "2026-10-28T07:20:00Z", 0)));
		when(aws.executionInput(arn(EVENT_END, "uuid-f")))
			.thenReturn("{\"tenant_id\":\"" + TENANT + "\",\"event_id\":13,\"slug\":\"slug0013\"}");
		Builder h = new Builder();
		h.add("TaskStateEntered", "CloseEvent", h.started(), 0);
		when(aws.executionHistory(eq(arn(EVENT_END, "uuid-f")), anyInt())).thenReturn(h.history(false));
		service = service(Duration.ZERO);

		Execution running = service.respond(EVENT_13).executions().getLast();
		assertThat(running.status()).isEqualTo("RUNNING");
		assertThat(running.stoppedAt()).isNull();
		// 지금(07:30Z) − 시작(07:20Z)
		assertThat(running.durationSeconds()).isEqualTo(600L);
		assertThat(running.states().getFirst().endedAt()).isNull();
	}

	@Test
	void awsFailureGivesLastValueAsStaleOrNothing() {
		service = service(Duration.ZERO);
		EventExecutionsResponse ok = service.respond(EVENT_13);
		when(aws.executions(eq(REGISTER), any(), anyInt()))
			.thenThrow(new AwsMonitoringException("ListExecutions AccessDeniedException"));

		// 목록 하나가 실패하면 그 목록의 마지막 값으로 만들고 stale
		EventExecutionsResponse stale = service.respond(EVENT_13);
		assertThat(stale.aws().stale()).isTrue();
		assertThat(stale.aws().staleReason()).isEqualTo(StaleReason.SOURCE);
		assertThat(stale.aws().asOf()).isEqualTo(ok.aws().asOf());
		assertThat(stale.executions()).hasSameSizeAs(ok.executions());

		// 한 번도 성공 못 함: asOf null + 빈 목록
		EventExecutionService fresh = service(Duration.ZERO);
		try {
			EventExecutionsResponse none = fresh.respond(EVENT_13);
			assertThat(none.aws().asOf()).isNull();
			assertThat(none.aws().stale()).isTrue();
			assertThat(none.aws().staleReason()).isEqualTo(StaleReason.SOURCE);
			assertThat(none.executions()).isEmpty();
		}
		finally {
			fresh.shutdown();
		}
	}

	@Test
	void stateMachinesFromApproveArn() {
		assertThat(EventExecutionService.stateMachines(SFN)).containsExactlyInAnyOrderEntriesOf(Map.of(Flow.DEPLOY,
				REGISTER, Flow.HOSTING_START, HOSTING_START, Flow.PREPARE, EVENT_OPEN, Flow.END, EVENT_END));

		var broken = new TetraProperties.Sfn("local-approve", "local-tenant", SFN.lifecycle());
		assertThat(EventExecutionService.stateMachines(broken)).isEmpty();
		service = new EventExecutionService(aws, null, null, JsonMapper.builder().build(), broken, Duration.ZERO,
				Duration.ZERO, Duration.ofSeconds(5), clock);
		EventExecutionsResponse response = service.respond(EVENT_13);
		assertThat(response.aws().stale()).isTrue();
		assertThat(response.executions()).isEmpty();
		verifyNoInteractions(aws);
	}

	@Test
	void ownerReadsOnlyEventIdAndTenant() {
		service = service(Duration.ZERO);
		assertThat(service.parseOwner("{\"tenant_id\":\"" + TENANT + "\",\"event_id\":13}"))
			.isEqualTo(new Owner(13L, TENANT));
		assertThat(service.parseOwner("{\"event_id\":\"42\"}")).isEqualTo(new Owner(42L, null));
		assertThat(service.parseOwner("{\"tenant_id\":\"" + TENANT + "\",\"event_id\":\"abc\"}"))
			.isEqualTo(new Owner(null, null));
		assertThat(service.parseOwner("not json")).isEqualTo(new Owner(null, null));
		assertThat(service.parseOwner(null)).isEqualTo(new Owner(null, null));
	}

	// ---------------------------------------------------------------- 도우미

	private EventExecutionService service(Duration ttl) {
		return new EventExecutionService(aws, null, null, JsonMapper.builder().build(), SFN, ttl, ttl,
				Duration.ofSeconds(5), clock);
	}

	private static ExecutionItem item(String stateMachine, String name, String status, String start, int redrives) {
		Instant started = Instant.parse(start);
		return new ExecutionItem(arn(stateMachine, name), name, status, started,
				"RUNNING".equals(status) ? null : started.plusSeconds(60), redrives);
	}

	/** stateMachine:{name} → execution:{name}:{실행 이름} */
	private static String arn(String stateMachine, String name) {
		return stateMachine.replace(":stateMachine:", ":execution:") + ":" + name;
	}

	private static ExecutionHistory succeeded() {
		Builder h = new Builder();
		long p = h.task("OpenHosting", h.started(), 0, 2, 1);
		h.add("ExecutionSucceeded", null, p, 60);
		return h.history(false);
	}

}
