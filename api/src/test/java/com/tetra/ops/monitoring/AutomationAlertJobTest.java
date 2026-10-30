package com.tetra.ops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
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
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.util.StringUtils;

import com.tetra.ops.common.time.Kst;
import com.tetra.ops.domain.platform.EventRegistryRepository;
import com.tetra.ops.domain.platform.OperatorNotification;
import com.tetra.ops.domain.platform.OperatorNotificationRepository;
import com.tetra.ops.domain.platform.TenantRepository;
import com.tetra.ops.event.EntryOpen;
import com.tetra.ops.notification.NotificationService;
import com.tetra.ops.support.IntegrationTest;
import com.tetra.ops.tenancy.TenantRouter;

/**
 * 1분 주기 자동화 확인(D5). 실제 DB(알림 · registry · 테넌트 스키마) + 목 AWS · 실행 확인 · 잠금, 시계는 고정.
 * 스케줄은 테스트 프로필에서 꺼져 있어 run()을 직접 부른다.
 */
@ExtendWith(OutputCaptureExtension.class)
class AutomationAlertJobTest extends IntegrationTest {

	static final String APPROVE_RESULT_DLQ = "tetra-test-approve-result-dlq";

	static final String TENANT_RESULT_DLQ = "tetra-test-tenant-result-dlq";

	static final String LIFECYCLE_RESULT_DLQ = "tetra-test-event-lifecycle-result-dlq";

	static final String EVENTS_DLQ = "tetra-test-wf-events-dlq";

	static final String SCHEDULER_DLQ = "tetra-test-scheduler-dlq";

	/** 2026-10-30 15:00 KST */
	static final Instant NOW = Instant.parse("2026-10-30T06:00:00Z");

	static final LocalDateTime NOW_KST = LocalDateTime.of(2026, 10, 30, 15, 0);

	private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

	@Autowired
	EventRegistryRepository registries;

	@Autowired
	TenantRepository tenants;

	@Autowired
	TenantRouter router;

	@Autowired
	TrafficEventReader reader;

	@Autowired
	OperatorNotificationRepository notificationRows;

	@Autowired
	NotificationService notifications;

	private final AwsMonitoringGateway aws = mock(AwsMonitoringGateway.class);

	private final EventExecutionService executions = mock(EventExecutionService.class);

	private final AlertLock lock = mock(AlertLock.class);

	@BeforeEach
	void lockAlwaysAcquired() {
		when(lock.runIfAcquired(any())).thenAnswer(inv -> {
			inv.<Runnable>getArgument(0).run();
			return true;
		});
	}

	private AutomationAlertJob job(Instant at, String... dlqNames) {
		return new AutomationAlertJob(lock, aws, executions, registries, tenants, router, reader, notificationRows,
				notifications, List.of(dlqNames), Duration.ofHours(24), Clock.fixed(at, Kst.ZONE));
	}

	private List<Map<String, Object>> rows() {
		return data.jdbc().queryForList("SELECT * FROM tetra_platform.operator_notification ORDER BY notification_id");
	}

	private static String kind(Map<String, Object> row) {
		return row.get("category") + "/" + row.get("priority") + " " + row.get("dedupe_key");
	}

	// ---------------------------------------------------------------- DLQ

	/** 같은 큐 · 같은 날(KST)은 1건. KST 자정을 넘기면(UTC로는 아직 같은 날) 새로 1건 */
	@Test
	void dlqOncePerQueuePerKstDay() {
		when(aws.queueDepth(APPROVE_RESULT_DLQ)).thenReturn(3);
		when(aws.queueDepth(EVENTS_DLQ)).thenReturn(0);
		AutomationAlertJob job = job(NOW, APPROVE_RESULT_DLQ, EVENTS_DLQ);

		job.run();
		job.run();
		job(Instant.parse("2026-10-30T14:59:00Z"), APPROVE_RESULT_DLQ).run(); // 23:59 KST

		assertThat(rows()).extracting(AutomationAlertJobTest::kind)
			.containsExactly("SYSTEM/URGENT DLQ:tetra-test-approve-result-dlq:2026-10-30");
		Map<String, Object> first = rows().get(0);
		assertThat(first).containsEntry("tenant_id", null).containsEntry("event_id", null).containsEntry("is_read", false);
		assertThat((String) first.get("content")).isEqualTo(
				"자동화 결과를 반영하지 못했습니다. 이벤트 등록 DLQ에 메시지 3건이 있습니다. 이벤트·테넌트 상태가 갱신되지 않았을 수 있습니다.");

		job(Instant.parse("2026-10-30T15:01:00Z"), APPROVE_RESULT_DLQ).run(); // 다음 날 00:01 KST

		assertThat(rows()).extracting(AutomationAlertJobTest::kind)
			.containsExactly("SYSTEM/URGENT DLQ:tetra-test-approve-result-dlq:2026-10-30",
					"SYSTEM/URGENT DLQ:tetra-test-approve-result-dlq:2026-10-31");
	}

	/** 종류는 이름 접미사, 결과 반영 실패의 결과 종류는 그 앞 이름. 문구는 결정 문구 그대로(120자 이내) */
	@Test
	void dlqKindsAndWording() {
		for (String queue : List.of(TENANT_RESULT_DLQ, APPROVE_RESULT_DLQ, LIFECYCLE_RESULT_DLQ, EVENTS_DLQ, SCHEDULER_DLQ)) {
			when(aws.queueDepth(queue)).thenReturn(2);
		}

		job(NOW, TENANT_RESULT_DLQ, APPROVE_RESULT_DLQ, LIFECYCLE_RESULT_DLQ, EVENTS_DLQ, SCHEDULER_DLQ).run();

		assertThat(rows()).extracting(r -> (String) r.get("content"))
			.containsExactly(
					"자동화 결과를 반영하지 못했습니다. 테넌트 환경 준비 DLQ에 메시지 2건이 있습니다. 이벤트·테넌트 상태가 갱신되지 않았을 수 있습니다.",
					"자동화 결과를 반영하지 못했습니다. 이벤트 등록 DLQ에 메시지 2건이 있습니다. 이벤트·테넌트 상태가 갱신되지 않았을 수 있습니다.",
					"자동화 결과를 반영하지 못했습니다. 이벤트 수명 주기 DLQ에 메시지 2건이 있습니다. 이벤트·테넌트 상태가 갱신되지 않았을 수 있습니다.",
					"자동화 실행 결과가 Console에 전달되지 않았습니다. 결과 전달 DLQ에 메시지 2건이 있습니다.",
					"예약된 자동화 실행이 시작되지 않았습니다. 스케줄러 DLQ에 메시지 2건이 있습니다.")
			.allSatisfy(c -> assertThat(c).hasSizeLessThanOrEqualTo(120));
		assertThat(rows()).allSatisfy(r -> assertThat(kind(r)).startsWith("SYSTEM/URGENT DLQ:"));
	}

	/** 이름 규칙에 맞지 않는 큐는 기동 때 WARN 한 줄만, 조회 · 알림 없음 */
	@Test
	void unknownQueueNameIsIgnoredWithOneWarning(CapturedOutput output) {
		AutomationAlertJob job = job(NOW, "tetra-test-something-dlq", SCHEDULER_DLQ);

		job.run();
		job.run();

		verify(aws, never()).queueDepth("tetra-test-something-dlq");
		verify(aws, times(2)).queueDepth(SCHEDULER_DLQ);
		assertThat(rows()).isEmpty();
		assertThat(StringUtils.countOccurrencesOf(output.getAll(), "tetra-test-something-dlq는 DLQ 이름 규칙")).isEqualTo(1);
	}

	/** AWS 실패: 이번 회차 DLQ 확인을 건너뛰고 WARN 한 줄(남은 큐도 부르지 않는다). 정상 회차는 로그가 없다 */
	@Test
	void awsFailureSkipsDlqWithOneWarning(CapturedOutput output) {
		when(aws.queueDepth(APPROVE_RESULT_DLQ)).thenThrow(new AwsMonitoringException("GetQueueAttributes HTTP 403"));
		when(aws.queueDepth(SCHEDULER_DLQ)).thenReturn(5);

		job(NOW, APPROVE_RESULT_DLQ, SCHEDULER_DLQ).run();

		verify(aws, never()).queueDepth(SCHEDULER_DLQ);
		assertThat(rows()).isEmpty();
		assertThat(StringUtils.countOccurrencesOf(output.getAll(), "자동화 확인")).isEqualTo(1);
		assertThat(output.getAll()).contains("DLQ 메시지 수를 읽지 못해 이번 회차 DLQ 확인을 건너뜁니다(GetQueueAttributes HTTP 403)");
	}

	/** 다른 태스크가 잠금을 잡고 있으면 아무것도 하지 않는다 */
	@Test
	void skipsWhenLockIsHeldElsewhere() {
		doReturn(false).when(lock).runIfAcquired(any());

		job(NOW, SCHEDULER_DLQ).run();

		verifyNoInteractions(aws, executions);
		assertThat(rows()).isEmpty();
	}

	/** 다른 태스크와 같은 키를 동시에 넣어 1062가 나면 false(그 알림만 건너뜀). 다른 무결성 오류는 그대로 던진다 */
	@Test
	void duplicateKeyRaceIsNotRecorded() {
		String key = NotificationService.dlqKey(SCHEDULER_DLQ, NOW_KST.toLocalDate());
		notificationRows.save(OperatorNotification.unread(null, null, "SYSTEM", "URGENT", NOW_KST, "먼저 남은 알림", key));
		AutomationAlertJob job = job(NOW);

		// 존재 확인과 INSERT 사이에 다른 태스크가 넣은 경우를 흉내 낸다(확인 없이 바로 저장)
		assertThat(job.record(() -> {
			notificationRows.save(OperatorNotification.unread(null, null, "SYSTEM", "URGENT", NOW_KST, "경합", key));
			return true;
		})).isFalse();
		assertThat(rows()).hasSize(1);
		// 존재 확인으로 걸러지면 1062 없이 false
		assertThat(job.record(() -> notifications.dlqDetected(DlqQueue.classify(SCHEDULER_DLQ).orElseThrow(), 1,
				NOW_KST.toLocalDate()))).isFalse();
		assertThat(rows()).hasSize(1);

		assertThatThrownBy(() -> job.record(() -> {
			notificationRows.save(OperatorNotification.unread("zzzzzzzzzzzz", null, "SYSTEM", "URGENT", NOW_KST, "없는 테넌트",
					"DLQ:other:2026-10-30"));
			return true;
		})).isInstanceOf(DataIntegrityViolationException.class);
	}

	// ---------------------------------------------------------------- 단계 지연

	/**
	 * 지연 5종이 이벤트 · 단계마다 1건(다음 회차는 0건). 이미 알림을 남긴 단계는 Step Functions를 다시 묻지 않는다.
	 * 시각은 TestData 간격(호스팅 시작 = 시작 − 2시간, 호스팅 종료 = 시작 + 5시간)과 EventPhase · EntryOpen 상수(TEST-LEAD)로 맞춘다.
	 */
	@Test
	void delaysOncePerEventAndStep() {
		data.tenant(A, "A사", "ACTIVE", "READY");
		// 호스팅 시작 = 지금 − 11분, DEPLOYED 그대로
		LocalDateTime start7301 = NOW_KST.plusHours(2).minusMinutes(11);
		data.event(7301, A).approved("DEPLOYED", "SCHEDULED", "dly07301").startAt(start7301).insert();
		// 준비 스케줄 = 지금 − 11분, WF2-2 실행 없음
		LocalDateTime start7302 = NOW_KST.plus(EventPhase.PREPARE_LEAD).minusMinutes(11);
		data.event(7302, A).approved("HOSTING", "SCHEDULED", "dly07302").startAt(start7302).insert();
		// 입장 = 지금 − 6분, 아직 SCHEDULED(준비는 시작함)
		LocalDateTime start7303 = NOW_KST.plus(EntryOpen.ENTRY_OPEN_LEAD).minusMinutes(6);
		data.event(7303, A).approved("HOSTING", "SCHEDULED", "dly07303").startAt(start7303).insert();
		// 호스팅 종료 = 지금 − 11분, WF4 실행 없음
		data.event(7304, A).approved("HOSTING", "OPEN", "dly07304").startAt(NOW_KST.minusHours(5).minusMinutes(11)).insert();
		// 호스팅 시작 실패 · 준비 실패여도 WF4는 돈다: 호스팅 종료 = 지금 − 61분, WF4는 시작했지만 끝나지 않음
		data.event(7305, A).approved("HOSTING_FAILED", "OPEN_FAILED", "dly07305")
			.startAt(NOW_KST.minusHours(6).minusMinutes(1))
			.insert();
		// 배포 실패: 모든 예정이 지났어도 지연 아님(실패 알림이 따로 있고 WF4 스케줄도 없다)
		data.event(7306, A).approved("DEPLOY_FAILED", "SCHEDULED", "dly07306")
			.startAt(NOW_KST.minusHours(6).minusMinutes(1))
			.insert();
		when(executions.startedFor(eq(DelayStep.PREPARE_START), argThat(t -> t != null && t.eventId() == 7303L)))
			.thenReturn(true);
		when(executions.startedFor(eq(DelayStep.END_START), argThat(t -> t != null && t.eventId() == 7305L)))
			.thenReturn(true);
		AutomationAlertJob job = job(NOW);

		job.run();
		job.run();

		assertThat(rows()).extracting(AutomationAlertJobTest::kind)
			.containsExactlyInAnyOrder("DELAY/URGENT DELAY:7301:HOSTING_START", "DELAY/URGENT DELAY:7302:PREPARE_START",
					"DELAY/URGENT DELAY:7303:ENTRY_OPEN", "DELAY/WARNING DELAY:7304:END_START",
					"DELAY/WARNING DELAY:7305:END_DONE");
		Map<Long, String> contents = new java.util.HashMap<>();
		rows().forEach(r -> contents.put(((Number) r.get("event_id")).longValue(), (String) r.get("content")));
		assertThat(contents.get(7301L))
			.isEqualTo("[A사] [7301:이벤트 7301] 호스팅 시작이 예정 시각(14:49)보다 10분 넘게 늦어지고 있습니다.");
		assertThat(contents.get(7302L)).isEqualTo("[A사] [7302:이벤트 7302] 이벤트 준비가 예정 시각(14:49)보다 10분 넘게 시작되지 않았습니다. 입장("
				+ HH_MM.format(EntryOpen.at(start7302)) + ")이 늦어질 수 있습니다.");
		assertThat(contents.get(7303L))
			.isEqualTo("[A사] [7303:이벤트 7303] 입장 시각(14:54)이 5분 지났지만 입장이 열리지 않았습니다.");
		assertThat(contents.get(7304L))
			.isEqualTo("[A사] [7304:이벤트 7304] 호스팅 종료가 예정 시각(14:49)보다 10분 넘게 시작되지 않았습니다.");
		assertThat(contents.get(7305L))
			.isEqualTo("[A사] [7305:이벤트 7305] 호스팅 종료 예정 시각(13:59)에서 60분이 지나도 종료가 끝나지 않았습니다.");
		assertThat(contents.values()).allSatisfy(c -> assertThat(c).hasSizeLessThanOrEqualTo(120));
		assertThat(rows()).allSatisfy(r -> assertThat(r).containsEntry("tenant_id", A));

		// 알림을 남긴 단계는 다음 회차에 Step Functions를 묻지 않는다(남기지 않은 7303 준비 시작 · 7305 종료 시작은 매번 묻는다)
		verify(executions, times(1)).startedFor(eq(DelayStep.PREPARE_START), argThat(t -> t.eventId() == 7302L));
		verify(executions, times(1)).startedFor(eq(DelayStep.END_START), argThat(t -> t.eventId() == 7304L));
		verify(executions, times(2)).startedFor(eq(DelayStep.PREPARE_START), argThat(t -> t.eventId() == 7303L));
		verify(executions, times(2)).startedFor(eq(DelayStep.END_START), argThat(t -> t.eventId() == 7305L));
	}

	/** Step Functions를 못 읽으면 그 회차의 준비 시작 · 종료 시작만 건너뛰고(WARN 한 줄) 나머지 지연은 그대로 본다 */
	@Test
	void executionLookupFailureSkipsOnlyThoseSteps(CapturedOutput output) {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(7311, A).approved("HOSTING", "SCHEDULED", "dly07311")
			.startAt(NOW_KST.plus(EntryOpen.ENTRY_OPEN_LEAD).minusMinutes(6))
			.insert();
		when(executions.startedFor(any(), any())).thenThrow(new AwsMonitoringException("ListExecutions 실패(WF2-2)"));

		job(NOW).run();

		assertThat(rows()).extracting(AutomationAlertJobTest::kind).containsExactly("DELAY/URGENT DELAY:7311:ENTRY_OPEN");
		assertThat(StringUtils.countOccurrencesOf(output.getAll(), "Step Functions 실행을 읽지 못해")).isEqualTo(1);
	}

}
