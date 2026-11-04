package com.tetra.ops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.tetra.ops.common.time.Kst;
import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.HostingStatus;
import com.tetra.ops.event.EntryOpen;
import com.tetra.ops.monitoring.TrafficEventResponse.RedisConnection;
import com.tetra.ops.monitoring.TrafficRules.AddonMember;
import com.tetra.ops.monitoring.TrafficRules.EventSignals;
import com.tetra.ops.monitoring.TrafficRules.Judgment;

/** 명세 2절 상태 판정 경계값 · 상태(4절) · 그래프 범위 */
class TrafficRulesTest {

	private static EventSignals signals(Double rps, Double errorRps, Double p95) {
		return new EventSignals(rps, errorRps, p95, 3.0, 100.0, 50.0, RedisConnection.UP, 6, 12);
	}

	/** 다른 재료는 정상, Redis 연결만 redis */
	private static EventSignals redis(RedisConnection redis) {
		return new EventSignals(1000.0, 0.0, 0.1, 3.0, 100.0, 50.0, redis, 6, 12);
	}

	private static Judgment event(EventSignals s) {
		return TrafficRules.event(s, true);
	}

	@Test
	void eventThresholds() {
		assertThat(event(signals(1000.0, 0.0, 0.18))).isEqualTo(new Judgment(MonitoringLevel.OK, "정상"));
		// 5xx: 1% 주의, 2% 위험(경계 포함, 설계 D-2)
		assertThat(event(signals(1000.0, 9.9, 0.18)).level()).isEqualTo(MonitoringLevel.OK);
		assertThat(event(signals(1000.0, 10.0, 0.18))).isEqualTo(new Judgment(MonitoringLevel.WARN, "5xx 1.0%"));
		assertThat(event(signals(1000.0, 19.9, 0.18)).level()).isEqualTo(MonitoringLevel.WARN);
		assertThat(event(signals(1000.0, 20.0, 0.18))).isEqualTo(new Judgment(MonitoringLevel.DANGER, "5xx 2.0%"));
		// p95: 500ms 주의, 2초 위험
		assertThat(event(signals(1000.0, 0.0, 0.5))).isEqualTo(new Judgment(MonitoringLevel.WARN, "p95 500ms"));
		assertThat(event(signals(1000.0, 0.0, 2.3))).isEqualTo(new Judgment(MonitoringLevel.DANGER, "p95 2.3초"));
		// 요청 시리즈가 있고 5xx만 없으면 0
		assertThat(event(signals(1000.0, null, 0.1)).level()).isEqualTo(MonitoringLevel.OK);
		// Prometheus를 못 읽음
		assertThat(event(null)).isEqualTo(Judgment.NO_DATA);
	}

	@Test
	void incompleteSignalsAreUnknownUnlessDangerVisible() {
		// 쿼리 일부 오류: 주의 · 정상은 판단하지 않고 UNKNOWN
		EventSignals warnButIncomplete = new EventSignals(1000.0, 5.0, 0.18, 3.0, 100.0, 50.0, RedisConnection.UP, 6, 12,
				false);
		assertThat(event(warnButIncomplete)).isEqualTo(Judgment.NO_DATA);
		assertThat(TrafficRules.preEntry(warnButIncomplete)).isEqualTo(Judgment.NO_DATA);
		// 보이는 위험은 그대로 위험
		EventSignals dangerIncomplete = new EventSignals(1000.0, 30.0, 0.18, 3.0, 100.0, 50.0, null, 6, 12, false);
		assertThat(event(dangerIncomplete).level()).isEqualTo(MonitoringLevel.DANGER);
		EventSignals redisDownIncomplete = new EventSignals(null, null, null, null, null, null, RedisConnection.DOWN,
				null, null, false);
		assertThat(TrafficRules.preEntry(redisDownIncomplete).level()).isEqualTo(MonitoringLevel.DANGER);
		// Redis 확인 중(주의)은 다른 주의처럼 UNKNOWN
		EventSignals redisCheckingIncomplete = new EventSignals(null, null, null, null, null, null,
				RedisConnection.CHECKING, null, null, false);
		assertThat(TrafficRules.preEntry(redisCheckingIncomplete)).isEqualTo(Judgment.NO_DATA);
	}

	@Test
	void redisConnectionByDownPoints() {
		// 정리 중(issuance Deployment 삭제 표시 · spec 0)은 쿼리에서 빠져 끊긴 점이 없다
		assertThat(TrafficRules.redisConnection(false, null)).isEqualTo(RedisConnection.UP);
		// 지금 끊김, 1분 안(15초 점 1~3개). subquery 점이 아직 없어도 지금 끊겼으면 확인 중
		assertThat(TrafficRules.redisConnection(true, null)).isEqualTo(RedisConnection.CHECKING);
		assertThat(TrafficRules.redisConnection(true, 1.0)).isEqualTo(RedisConnection.CHECKING);
		assertThat(TrafficRules.redisConnection(true, 3.0)).isEqualTo(RedisConnection.CHECKING);
		// 1분 내내(4점) 끊김. 막 다시 붙었어도 지난 1분이 4점이면 끊김(Grafana 화면 값 count_over_time >= 4와 같다)
		assertThat(TrafficRules.redisConnection(true, 4.0)).isEqualTo(RedisConnection.DOWN);
		assertThat(TrafficRules.redisConnection(false, 4.0)).isEqualTo(RedisConnection.DOWN);
		// 지난 1분에 끊긴 점이 있었지만 지금은 붙음
		assertThat(TrafficRules.redisConnection(false, 2.0)).isEqualTo(RedisConnection.UP);
	}

	@Test
	void exporterBlipWhileCleaningUpIsOk() {
		// 이벤트 14 종료(2026-10-29 15:07:00): exporter up 0 한 점은 Deployment 삭제 표시(15:06:34) 뒤라 끊김 쿼리가 비어 있다
		EventSignals cleaning = redis(TrafficRules.redisConnection(false, null));
		assertThat(event(cleaning)).isEqualTo(new Judgment(MonitoringLevel.OK, "정상"));
		assertThat(TrafficRules.preEntry(cleaning)).isEqualTo(new Judgment(MonitoringLevel.OK, "정상"));
	}

	@Test
	void redisDownWithinOneMinuteIsWarn() {
		Judgment checking = new Judgment(MonitoringLevel.WARN, "Redis 연결 확인 중");
		assertThat(event(redis(RedisConnection.CHECKING))).isEqualTo(checking);
		assertThat(TrafficRules.preEntry(redis(RedisConnection.CHECKING))).isEqualTo(checking);
		// 주의 중에서는 Redis가 먼저, 위험은 그대로 위험
		assertThat(event(new EventSignals(1000.0, 10.0, 0.6, 3.0, 100.0, 50.0, RedisConnection.CHECKING, 12, 12)))
			.isEqualTo(checking);
		assertThat(event(new EventSignals(1000.0, 30.0, 0.1, 3.0, 100.0, 50.0, RedisConnection.CHECKING, 6, 12)))
			.isEqualTo(new Judgment(MonitoringLevel.DANGER, "5xx 3.0%"));
	}

	@Test
	void redisDownForOneMinuteIsDanger() {
		Judgment down = new Judgment(MonitoringLevel.DANGER, "Redis 연결 끊김");
		assertThat(event(redis(RedisConnection.DOWN))).isEqualTo(down);
		assertThat(TrafficRules.preEntry(redis(RedisConnection.DOWN))).isEqualTo(down);
		// 다른 위험보다 먼저
		assertThat(event(new EventSignals(1000.0, 30.0, 2.3, 0.0, 100.0, 50.0, RedisConnection.DOWN, 6, 12)))
			.isEqualTo(down);
	}

	@Test
	void cursorStallAndPodsAtMax() {
		EventSignals stalled = new EventSignals(1000.0, 0.0, 0.1, 0.0, 100.0, 50.0, RedisConnection.UP, 6, 12);
		assertThat(event(stalled)).isEqualTo(new Judgment(MonitoringLevel.DANGER, "커서 1분 정지"));
		// 시작 전에는 커서가 안 움직이는 게 정상, 대기 · 재고가 없으면 정지가 아님
		// (커서가 번호표 끝보다 앞서면 대기 0 — 테스트 13: 번호표 끝 21, 커서 6,300)
		assertThat(TrafficRules.event(stalled, false).level()).isEqualTo(MonitoringLevel.OK);
		assertThat(event(new EventSignals(1000.0, 0.0, 0.1, 0.0, 0.0, 50.0, RedisConnection.UP, 6, 12)).level())
			.isEqualTo(MonitoringLevel.OK);
		assertThat(event(new EventSignals(1000.0, 0.0, 0.1, 0.0, 100.0, 0.0, RedisConnection.UP, 6, 12)).level())
			.isEqualTo(MonitoringLevel.OK);

		EventSignals atMax = new EventSignals(1000.0, 0.0, 0.1, 3.0, 100.0, 50.0, RedisConnection.UP, 12, 12);
		assertThat(event(atMax)).isEqualTo(new Judgment(MonitoringLevel.WARN, "Pod 최대 12/12"));
		// 입장 직전은 Redis · Pod만
		EventSignals slowBeforeEntry = new EventSignals(null, null, 3.0, null, null, 50.0, RedisConnection.UP, 6, 12);
		assertThat(TrafficRules.preEntry(slowBeforeEntry).level()).isEqualTo(MonitoringLevel.OK);
	}

	@Test
	void cluster() {
		List<AddonMember> ok = List.of(new AddonMember("KEDA", "keda", "keda-operator", 1, 1));
		assertThat(TrafficRules.cluster(true, 2, 16, ok, 0)).isEqualTo(new Judgment(MonitoringLevel.OK, "노드 16 Ready"));
		assertThat(TrafficRules.cluster(true, 1, 16, ok, 0))
			.isEqualTo(new Judgment(MonitoringLevel.DANGER, "system 노드 Ready 1"));
		assertThat(TrafficRules.cluster(true, 2, 16, List.of(new AddonMember("KEDA", "keda", "keda-operator", 0, 1)), 0))
			.isEqualTo(new Judgment(MonitoringLevel.DANGER, "KEDA 가용 0"));
		// 시리즈 없음(배포 안 됨)도 가용 0
		assertThat(TrafficRules.cluster(true, 2, 16, List.of(new AddonMember("LBC", "kube-system", "x", null, null)), 0)
			.level()).isEqualTo(MonitoringLevel.DANGER);
		assertThat(TrafficRules.cluster(true, 2, 16, ok, 3))
			.isEqualTo(new Judgment(MonitoringLevel.WARN, "Pending Pod 3개 5분 이상"));
		assertThat(TrafficRules.cluster(false, null, 0, List.of(), null)).isEqualTo(Judgment.NO_DATA);
		// 쿼리 오류로 빈 재료(null): 보이는 위험이 없으면 UNKNOWN, 있으면 위험
		assertThat(TrafficRules.cluster(true, null, 16, ok, 0)).isEqualTo(Judgment.NO_DATA);
		assertThat(TrafficRules.cluster(true, 2, 16, null, 0)).isEqualTo(Judgment.NO_DATA);
		assertThat(TrafficRules.cluster(true, 2, 16, ok, null)).isEqualTo(Judgment.NO_DATA);
		assertThat(TrafficRules.cluster(true, 1, 16, null, null).level()).isEqualTo(MonitoringLevel.DANGER);
	}

	// ---------------------------------------------------------------- 자동화 · 단계 지연 5종

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 15, 0);

	private static final Duration WINDOW = Duration.ofHours(24);

	private static final Duration ONE_SECOND = Duration.ofSeconds(1);

	/** 실행 확인: 실행 없음 · 있음 · 모름 */
	private static final TrafficRules.ExecutionCheck NONE = (step, t) -> false;

	private static final TrafficRules.ExecutionCheck STARTED = (step, t) -> true;

	private static final TrafficRules.ExecutionCheck UNKNOWN = (step, t) -> null;

	private static TrafficRules.DelayTarget target(long id, LocalDateTime hostingStartAt, LocalDateTime startAt,
			LocalDateTime hostingEndAt, EventStatus eventStatus, HostingStatus hostingStatus) {
		return new TrafficRules.DelayTarget(id, "aaaaaaaaaaaa", NOW.minusDays(2), hostingStartAt, startAt, hostingEndAt,
				eventStatus, hostingStatus);
	}

	/** 시작 시각으로 준비 스케줄 · 입장을 정한다(EventPhase · EntryOpen 상수 — 지금은 TEST-LEAD 값). 호스팅 종료는 먼 뒤 */
	private static TrafficRules.DelayTarget byStart(long id, LocalDateTime startAt, EventStatus eventStatus,
			HostingStatus hostingStatus) {
		return target(id, startAt.minusHours(3), startAt, startAt.plusDays(2), eventStatus, hostingStatus);
	}

	/** 호스팅 종료만 정한다(시작 · 호스팅 시작은 하루 넘게 전이라 앞 3종은 기간 밖) */
	private static TrafficRules.DelayTarget byEnd(long id, LocalDateTime hostingEndAt, EventStatus eventStatus,
			HostingStatus hostingStatus) {
		LocalDateTime start = hostingEndAt.minusDays(2);
		return target(id, start.minusHours(2), start, hostingEndAt, eventStatus, hostingStatus);
	}

	private static List<String> delays(TrafficRules.ExecutionCheck executions, TrafficRules.DelayTarget... targets) {
		return TrafficRules.delays(List.of(targets), NOW, WINDOW, executions)
			.stream()
			.map(d -> d.target().eventId() + ":" + d.step() + ":" + d.minutes())
			.toList();
	}

	private static TrafficRules.Delay delay(long id, DelayStep step, long minutes) {
		return new TrafficRules.Delay(byStart(id, NOW, EventStatus.SCHEDULED, HostingStatus.HOSTING), step,
				NOW.minusMinutes(minutes), minutes);
	}

	@Test
	void automationFailuresDlqAndDelays() {
		var failure = new TrafficCatalog.FailureItem(NOW.minusHours(2), 5L, "aaaaaaaaaaaa", "C사", "신규 가입 쿠폰",
				FailureStep.PREPARE, "동결 확인 시간 초과");
		Map<String, Integer> emptyDlq = new LinkedHashMap<>();
		emptyDlq.put("tetra-dev-approve-result-dlq", 0);
		emptyDlq.put("tetra-dev-scheduler-dlq", 0);
		assertThat(TrafficRules.automation(List.of(), emptyDlq, List.of()))
			.isEqualTo(new Judgment(MonitoringLevel.OK, "최근 24시간 실패 0"));
		assertThat(TrafficRules.automation(List.of(failure), null, List.of()))
			.isEqualTo(new Judgment(MonitoringLevel.DANGER, "24시간 실패 1 · 5번 준비"));
		Map<String, Integer> dlq = new LinkedHashMap<>(emptyDlq);
		dlq.put("tetra-dev-scheduler-dlq", 2);
		dlq.put("tetra-dev-wf-events-dlq", 1);
		assertThat(TrafficRules.automation(List.of(), dlq, List.of()))
			.isEqualTo(new Judgment(MonitoringLevel.DANGER, "DLQ tetra-dev-scheduler-dlq, tetra-dev-wf-events-dlq"));

		// 주의 2종(종료 시작 · 종료 완료)만이면 주의, 긴급 3종이 하나라도 있으면 위험(주의 지연은 사유에 넣지 않는다)
		assertThat(TrafficRules.automation(List.of(), null,
				List.of(delay(8, DelayStep.END_START, 11), delay(9, DelayStep.END_DONE, 61))))
			.isEqualTo(new Judgment(MonitoringLevel.WARN, "8번 종료 시작 11분 지연 외 1"));
		assertThat(TrafficRules.automation(List.of(), null,
				List.of(delay(7, DelayStep.ENTRY_OPEN, 12), delay(8, DelayStep.END_START, 11))))
			.isEqualTo(new Judgment(MonitoringLevel.DANGER, "7번 입장 12분 지연"));
		assertThat(TrafficRules.automation(List.of(failure), null,
				List.of(delay(7, DelayStep.HOSTING_START, 15), delay(6, DelayStep.PREPARE_START, 12))))
			.isEqualTo(new Judgment(MonitoringLevel.DANGER, "24시간 실패 1 · 5번 준비 · 7번 호스팅 시작 15분 지연 외 1"));
	}

	/** 호스팅 시작: hosting_start_at + 10분 초과(경계 미포함) ~ + 24시간(경계 포함), PENDING · DEPLOYED만 */
	@Test
	void hostingStartBoundariesAndWindow() {
		LocalDateTime start = NOW.plusHours(3);
		assertThat(delays(NONE, target(1, NOW.minusMinutes(10), start, start.plusHours(5), EventStatus.SCHEDULED,
				HostingStatus.DEPLOYED))).isEmpty();
		assertThat(delays(NONE, target(1, NOW.minusMinutes(10).minus(ONE_SECOND), start, start.plusHours(5),
				EventStatus.SCHEDULED, HostingStatus.DEPLOYED))).containsExactly("1:HOSTING_START:10");
		assertThat(delays(NONE, target(2, NOW.minusMinutes(15), start, start.plusHours(5), EventStatus.SCHEDULED,
				HostingStatus.PENDING))).containsExactly("2:HOSTING_START:15");
		// 호스팅이 이미 시작됐으면 아님
		assertThat(delays(NONE, target(3, NOW.minusMinutes(15), start, start.plusHours(5), EventStatus.SCHEDULED,
				HostingStatus.HOSTING))).isEmpty();
		// 24시간 상한: 경계 포함, 넘으면 보지 않는다(첫 배포 때 옛 이벤트 알림이 몰리지 않게)
		assertThat(delays(NONE, target(4, NOW.minusHours(24), NOW.plusHours(1), NOW.plusHours(6), EventStatus.OPEN,
				HostingStatus.DEPLOYED))).containsExactly("4:HOSTING_START:1440");
		assertThat(delays(NONE, target(4, NOW.minusHours(24).minus(ONE_SECOND), NOW.plusHours(1), NOW.plusHours(6),
				EventStatus.OPEN, HostingStatus.DEPLOYED))).isEmpty();
	}

	/** 준비 시작: 준비 스케줄(EventPhase.prepareAt) + 10분 초과, SCHEDULED이고 WF2-2 실행이 없다고 확인될 때만 */
	@Test
	void prepareStartNeedsMissingExecution() {
		LocalDateTime lateStart = NOW.plus(EventPhase.PREPARE_LEAD).minusMinutes(10).minus(ONE_SECOND);
		LocalDateTime onTimeStart = NOW.plus(EventPhase.PREPARE_LEAD).minusMinutes(10);
		var late = byStart(5, lateStart, EventStatus.SCHEDULED, HostingStatus.HOSTING);
		assertThat(delays(NONE, late)).containsExactly("5:PREPARE_START:10");
		// 실행이 있거나 모르면(조회 실패) 지연으로 보지 않는다
		assertThat(delays(STARTED, late)).isEmpty();
		assertThat(delays(UNKNOWN, late)).isEmpty();
		// 경계(정확히 + 10분)는 아님. 판정 시각 전이면 실행 확인도 부르지 않는다
		List<DelayStep> asked = new ArrayList<>();
		assertThat(delays((step, t) -> {
			asked.add(step);
			return false;
		}, byStart(5, onTimeStart, EventStatus.SCHEDULED, HostingStatus.HOSTING))).isEmpty();
		assertThat(asked).isEmpty();
		// 이미 입장이 열렸거나(OPEN) 준비가 실패했으면(OPEN_FAILED) 아님
		assertThat(delays(NONE, byStart(5, lateStart, EventStatus.OPEN, HostingStatus.HOSTING))).isEmpty();
		assertThat(delays(NONE, byStart(5, lateStart, EventStatus.OPEN_FAILED, HostingStatus.HOSTING))).isEmpty();
	}

	/** 입장: 입장(EntryOpen.at) + 5분 초과까지 OPEN이 아님. 준비 실패(OPEN_FAILED)는 실패 알림이 있어 아님 */
	@Test
	void entryOpenBoundaries() {
		LocalDateTime onTime = NOW.plus(EntryOpen.ENTRY_OPEN_LEAD).minusMinutes(5);
		LocalDateTime late = onTime.minus(ONE_SECOND);
		// 준비 스케줄은 더 앞이라 준비 시작도 판정 대상 — 실행이 있다고 두어 입장만 본다
		assertThat(delays(STARTED, byStart(6, onTime, EventStatus.SCHEDULED, HostingStatus.HOSTING))).isEmpty();
		assertThat(delays(STARTED, byStart(6, late, EventStatus.SCHEDULED, HostingStatus.HOSTING)))
			.containsExactly("6:ENTRY_OPEN:5");
		assertThat(delays(STARTED, byStart(6, late, EventStatus.OPEN, HostingStatus.HOSTING))).isEmpty();
		assertThat(delays(STARTED, byStart(6, late, EventStatus.OPEN_FAILED, HostingStatus.HOSTING))).isEmpty();
		// 실행이 없으면 준비 시작과 입장이 함께 늦다(단계마다 따로)
		assertThat(delays(NONE, byStart(6, late, EventStatus.SCHEDULED, HostingStatus.HOSTING))).containsExactly(
				"6:PREPARE_START:" + (EventPhase.PREPARE_LEAD.minus(EntryOpen.ENTRY_OPEN_LEAD).toMinutes() + 5),
				"6:ENTRY_OPEN:5");
	}

	/** 배포 · 호스팅 시작 실패는 앞 3종(호스팅 시작 · 준비 시작 · 입장)에서 뺀다. 여러 단계가 같이 늦을 수 있다 */
	@Test
	void hostingFailuresSkipFrontSteps() {
		// 호스팅 시작 · 준비 · 입장이 모두 지난 이벤트
		LocalDateTime start = NOW.plus(EntryOpen.ENTRY_OPEN_LEAD).minusMinutes(20);
		var pending = target(7, start.minusHours(2), start, start.plusDays(2), EventStatus.SCHEDULED, HostingStatus.PENDING);
		assertThat(delays(NONE, pending)).extracting(s -> s.split(":")[1])
			.containsExactly("HOSTING_START", "PREPARE_START", "ENTRY_OPEN");
		for (HostingStatus failed : List.of(HostingStatus.DEPLOY_FAILED, HostingStatus.HOSTING_FAILED)) {
			assertThat(delays(NONE, target(7, start.minusHours(2), start, start.plusDays(2), EventStatus.SCHEDULED, failed)))
				.as(failed.name())
				.isEmpty();
		}
	}

	/**
	 * 종료 시작 · 종료 완료: hosting_end_at + 10분 · + 60분. WF4가 도는 호스팅 상태(DEPLOYED · HOSTING · HOSTING_FAILED)만 —
	 * DEPLOY_FAILED는 event-end 스케줄이 없고, ENDED · END_FAILED는 끝났거나 실패 알림이 있다. 준비 실패(OPEN_FAILED)여도 본다.
	 */
	@Test
	void endStepsOnlyWhereWf4Runs() {
		LocalDateTime startLate = NOW.minusMinutes(10).minus(ONE_SECOND);
		assertThat(delays(NONE, byEnd(8, NOW.minusMinutes(10), EventStatus.OPEN, HostingStatus.HOSTING))).isEmpty();
		assertThat(delays(NONE, byEnd(8, startLate, EventStatus.OPEN, HostingStatus.HOSTING)))
			.containsExactly("8:END_START:10");
		// WF4 실행이 있으면 종료 시작은 아님 — 끝나지 않은 채 60분이 지나면 종료 완료
		assertThat(delays(STARTED, byEnd(8, startLate, EventStatus.OPEN, HostingStatus.HOSTING))).isEmpty();
		LocalDateTime doneLate = NOW.minusMinutes(60).minus(ONE_SECOND);
		assertThat(delays(STARTED, byEnd(9, NOW.minusMinutes(60), EventStatus.OPEN, HostingStatus.HOSTING))).isEmpty();
		assertThat(delays(STARTED, byEnd(9, doneLate, EventStatus.OPEN, HostingStatus.HOSTING)))
			.containsExactly("9:END_DONE:60");
		assertThat(delays(NONE, byEnd(9, doneLate, EventStatus.OPEN, HostingStatus.HOSTING)))
			.containsExactly("9:END_START:60", "9:END_DONE:60");
		// 호스팅 시작 실패 · 준비 실패여도 WF4는 돈다
		assertThat(delays(NONE, byEnd(10, startLate, EventStatus.SCHEDULED, HostingStatus.HOSTING_FAILED)))
			.containsExactly("10:END_START:10");
		assertThat(delays(NONE, byEnd(11, startLate, EventStatus.OPEN_FAILED, HostingStatus.DEPLOYED)))
			.containsExactly("11:END_START:10");
		for (HostingStatus none : List.of(HostingStatus.DEPLOY_FAILED, HostingStatus.ENDED, HostingStatus.END_FAILED,
				HostingStatus.PENDING)) {
			assertThat(delays(NONE, byEnd(12, doneLate, EventStatus.OPEN, none))).as(none.name()).isEmpty();
		}
		// hosting_end_at을 못 읽었으면(테넌트 스키마) 종료 2종을 보지 않는다
		assertThat(delays(NONE, target(13, NOW.minusDays(3), NOW.minusDays(2), null, EventStatus.OPEN,
				HostingStatus.HOSTING))).isEmpty();
		// 24시간 상한
		assertThat(delays(NONE, byEnd(14, NOW.minusHours(24).minus(ONE_SECOND), EventStatus.OPEN, HostingStatus.HOSTING)))
			.isEmpty();
	}

	@Test
	void platform() {
		assertThat(TrafficRules.platform(true, "available", 48.0, 1, 2, 2))
			.isEqualTo(new Judgment(MonitoringLevel.OK, "RDS 가동 · Portal·Console 2/2"));
		assertThat(TrafficRules.platform(true, "modifying", 48.0, 1, 2, 2))
			.isEqualTo(new Judgment(MonitoringLevel.DANGER, "RDS modifying"));
		assertThat(TrafficRules.platform(true, "available", 48.0, 0, 1, 2))
			.isEqualTo(new Judgment(MonitoringLevel.DANGER, "Portal 정상 대상 0"));
		assertThat(TrafficRules.platform(true, "available", 80.0, 1, 2, 2))
			.isEqualTo(new Judgment(MonitoringLevel.WARN, "RDS CPU 80%"));
		assertThat(TrafficRules.platform(false, null, null, null, 0, 0)).isEqualTo(Judgment.NO_DATA);
	}

	@Test
	void kpiWarnings() {
		assertThat(TrafficRules.kpiWarnings(0.012, 0.18, 8, 12, 0.38, 0.0, RedisConnection.UP))
			.containsExactly("errorRatio");
		assertThat(TrafficRules.kpiWarnings(0.009, 0.18, 8, 12, 0.38, 0.0, RedisConnection.UP)).isEmpty();
		assertThat(TrafficRules.kpiWarnings(0.0, 0.6, 12, 12, 0.85, 1.0, RedisConnection.DOWN))
			.containsExactly("p95", "pods", "redisMemory", "redisRestarts", "redisConnection");
		assertThat(TrafficRules.kpiWarnings(0.0, 0.18, 8, 12, 0.38, 0.0, RedisConnection.CHECKING))
			.containsExactly("redisConnection");
		assertThat(TrafficRules.kpiWarnings(null, null, null, null, null, null, null)).isEmpty();
	}

	@Test
	void phaseByTimeAndStatus() {
		LocalDateTime start = LocalDateTime.of(2026, 10, 26, 15, 0);
		LocalDateTime end = start.plusMinutes(40);
		// TEST-LEAD: 서비스 값 71
		// assertThat(EventPhase.of(start, end, EventStatus.SCHEDULED, HostingStatus.HOSTING, start.minusMinutes(71)))
		assertThat(EventPhase.of(start, end, EventStatus.SCHEDULED, HostingStatus.HOSTING, start.minusMinutes(26))) // TEST-LEAD
			.isEqualTo(EventPhase.SCHEDULED);
		// TEST-LEAD: 서비스 값 70
		// assertThat(EventPhase.of(start, end, EventStatus.SCHEDULED, HostingStatus.HOSTING, start.minusMinutes(70)))
		assertThat(EventPhase.of(start, end, EventStatus.SCHEDULED, HostingStatus.HOSTING, start.minusMinutes(25))) // TEST-LEAD
			.isEqualTo(EventPhase.PRE_ENTRY);
		// 입장 ~ 시작 전은 입장 중, 시작부터 진행 중
		// TEST-LEAD: 서비스 값 60
		// assertThat(EventPhase.of(start, end, EventStatus.OPEN, HostingStatus.HOSTING, start.minusMinutes(60)))
		assertThat(EventPhase.of(start, end, EventStatus.OPEN, HostingStatus.HOSTING, start.minusMinutes(15))) // TEST-LEAD
			.isEqualTo(EventPhase.ENTRY_OPEN);
		assertThat(EventPhase.of(start, end, EventStatus.OPEN, HostingStatus.HOSTING, start.minusSeconds(1)))
			.isEqualTo(EventPhase.ENTRY_OPEN);
		assertThat(EventPhase.of(start, end, EventStatus.OPEN, HostingStatus.HOSTING, start)).isEqualTo(EventPhase.LIVE);
		assertThat(EventPhase.ENTRY_OPEN.entryOpened()).isTrue();
		assertThat(EventPhase.ENTRY_OPEN.hasMetrics()).isTrue();
		assertThat(EventPhase.PRE_ENTRY.entryOpened()).isFalse();
		// end_at · 집계를 모르면(5인자) 마무리 없이 호스팅 종료 뒤 종료 처리 중
		assertThat(EventPhase.of(start, end, EventStatus.OPEN, HostingStatus.HOSTING, end)).isEqualTo(EventPhase.ENDING);
		assertThat(EventPhase.of(start, end, EventStatus.EXPIRED, HostingStatus.ENDED, start)).isEqualTo(EventPhase.ENDING);
		assertThat(EventPhase.of(start, end, EventStatus.OPEN_FAILED, HostingStatus.HOSTING, start))
			.isEqualTo(EventPhase.PREPARE_FAILED);
	}

	@Test
	void phaseBoundariesWithEventEndAndAggregation() {
		LocalDateTime start = LocalDateTime.of(2026, 10, 28, 10, 0);
		LocalDateTime entry = EntryOpen.at(start);
		LocalDateTime endAt = start.plusMinutes(15);
		LocalDateTime hostingEnd = start.plusMinutes(25);
		// 입장 · 시작 · 이벤트 종료 · 호스팅 종료 · 집계 완료 경계(시각은 그 순간부터 다음 상태)
		assertThat(phase(start, endAt, hostingEnd, EventStatus.SCHEDULED, HostingStatus.HOSTING, false, entry.minusSeconds(1)))
			.isEqualTo(EventPhase.PRE_ENTRY);
		assertThat(phase(start, endAt, hostingEnd, EventStatus.OPEN, HostingStatus.HOSTING, false, entry))
			.isEqualTo(EventPhase.ENTRY_OPEN);
		assertThat(phase(start, endAt, hostingEnd, EventStatus.OPEN, HostingStatus.HOSTING, false, start))
			.isEqualTo(EventPhase.LIVE);
		assertThat(phase(start, endAt, hostingEnd, EventStatus.OPEN, HostingStatus.HOSTING, false, endAt.minusSeconds(1)))
			.isEqualTo(EventPhase.LIVE);
		assertThat(phase(start, endAt, hostingEnd, EventStatus.OPEN, HostingStatus.HOSTING, false, endAt))
			.isEqualTo(EventPhase.WRAP_UP);
		assertThat(phase(start, endAt, hostingEnd, EventStatus.OPEN, HostingStatus.HOSTING, false, hostingEnd.minusSeconds(1)))
			.isEqualTo(EventPhase.WRAP_UP);
		// 호스팅 종료: WF4가 아직이어도(OPEN · HOSTING) 종료 처리 중, WF4 성공 뒤 집계 전도 종료 처리 중
		assertThat(phase(start, endAt, hostingEnd, EventStatus.OPEN, HostingStatus.HOSTING, false, hostingEnd))
			.isEqualTo(EventPhase.ENDING);
		assertThat(phase(start, endAt, hostingEnd, EventStatus.EXPIRED, HostingStatus.ENDED, false, hostingEnd.plusMinutes(1)))
			.isEqualTo(EventPhase.ENDING);
		// 집계 완료 = 종료. WF4 결과가 시각보다 먼저 와도(일찍 끝냄) 호스팅 종료 뒤로 본다
		assertThat(phase(start, endAt, hostingEnd, EventStatus.EXPIRED, HostingStatus.ENDED, true, hostingEnd.plusMinutes(2)))
			.isEqualTo(EventPhase.ENDED);
		assertThat(phase(start, endAt, hostingEnd, EventStatus.EXPIRED, HostingStatus.ENDED, true, endAt))
			.isEqualTo(EventPhase.ENDED);
		// WF4 실패 = 종료 · 집계 실패(집계 기록과 상관없이)
		assertThat(phase(start, endAt, hostingEnd, EventStatus.OPEN, HostingStatus.END_FAILED, false, hostingEnd.plusMinutes(1)))
			.isEqualTo(EventPhase.END_FAILED);
		// end_at을 모르면 마무리 없이 호스팅 종료까지 진행 중
		assertThat(phase(start, null, hostingEnd, EventStatus.OPEN, HostingStatus.HOSTING, false, endAt))
			.isEqualTo(EventPhase.LIVE);

		assertThat(EventPhase.WRAP_UP.entryOpened()).isTrue();
		assertThat(EventPhase.WRAP_UP.closed()).isFalse();
		for (EventPhase closed : List.of(EventPhase.ENDING, EventPhase.ENDED, EventPhase.END_FAILED)) {
			assertThat(closed.closed()).isTrue();
			assertThat(closed.entryOpened()).isFalse();
			assertThat(closed.hasMetrics()).isTrue();
		}
		assertThat(EventPhase.LIVE.closed()).isFalse();
	}

	private static EventPhase phase(LocalDateTime start, LocalDateTime endAt, LocalDateTime hostingEnd, EventStatus event,
			HostingStatus hosting, boolean aggregated, LocalDateTime now) {
		return EventPhase.of(start, endAt, hostingEnd, event, hosting, aggregated, now);
	}

	@Test
	void failurePhasesComeFirst() {
		LocalDateTime start = LocalDateTime.of(2026, 10, 26, 15, 0);
		LocalDateTime end = start.plusMinutes(40);
		// 배포 실패 → 호스팅 실패 → 준비 실패 → 종료 순으로 먼저 본다(시각 · 다른 실패와 상관없이)
		assertThat(EventPhase.of(start, end, EventStatus.SCHEDULED, HostingStatus.DEPLOY_FAILED, start.minusDays(1)))
			.isEqualTo(EventPhase.DEPLOY_FAILED);
		assertThat(EventPhase.of(start, end, EventStatus.OPEN_FAILED, HostingStatus.DEPLOY_FAILED, end))
			.isEqualTo(EventPhase.DEPLOY_FAILED);
		assertThat(EventPhase.of(start, end, EventStatus.SCHEDULED, HostingStatus.HOSTING_FAILED, start))
			.isEqualTo(EventPhase.HOSTING_FAILED);
		assertThat(EventPhase.of(start, end, EventStatus.OPEN_FAILED, HostingStatus.HOSTING_FAILED, end.plusDays(1)))
			.isEqualTo(EventPhase.HOSTING_FAILED);
		assertThat(EventPhase.of(start, end, EventStatus.OPEN_FAILED, HostingStatus.HOSTING, end.plusDays(1)))
			.isEqualTo(EventPhase.PREPARE_FAILED);
		assertThat(EventPhase.HOSTING_FAILED.hasMetrics()).isFalse();
		assertThat(EventPhase.DEPLOY_FAILED.hasMetrics()).isFalse();
	}

	@Test
	void windows() {
		LocalDateTime start = LocalDateTime.of(2026, 10, 26, 15, 0);
		var now = start.plusMinutes(10).atZone(Kst.ZONE).toInstant();
		TrafficWindow entry = TrafficWindow.of(EventPhase.LIVE, null, start, start.plusMinutes(40), now,
				TrafficWindow.PROMETHEUS_MIN_STEP);
		assertThat(entry.key()).isEqualTo("entry");
		// TEST-LEAD: 서비스 값 80분 · 30초
		// assertThat(Duration.between(entry.from(), entry.to())).isEqualTo(Duration.ofMinutes(80));
		// assertThat(entry.step()).isEqualTo(Duration.ofSeconds(30)); // 4800초 ÷ 300 = 16초 → 15초 배수 올림
		assertThat(Duration.between(entry.from(), entry.to())).isEqualTo(Duration.ofMinutes(35)); // TEST-LEAD
		assertThat(entry.step()).isEqualTo(Duration.ofSeconds(15)); // 2100초 ÷ 300 = 7초 → 최소 15초 // TEST-LEAD
		assertThat(TrafficWindow.of(EventPhase.LIVE, "15m", start, null, now, TrafficWindow.PROMETHEUS_MIN_STEP).step())
			.isEqualTo(Duration.ofSeconds(15));
		// 입장 중(시작 전)도 진행 중과 같은 범위 · 선택지
		TrafficWindow entering = TrafficWindow.of(EventPhase.ENTRY_OPEN, null, start, start.plusMinutes(40),
				start.minusMinutes(1).atZone(Kst.ZONE).toInstant(), TrafficWindow.PROMETHEUS_MIN_STEP);
		assertThat(entering.key()).isEqualTo("entry");
		assertThat(entering.options()).containsExactly("entry", "15m", "1h");
		// 종료 상태에서 15m은 기본값 full로
		var later = start.plusHours(5).atZone(Kst.ZONE).toInstant();
		TrafficWindow full = TrafficWindow.of(EventPhase.ENDED, "15m", start, start.plusMinutes(40), later,
				TrafficWindow.AWS_MIN_STEP);
		assertThat(full.key()).isEqualTo("full");
		// TEST-LEAD: 서비스 값 120
		// assertThat(Duration.between(full.from(), full.to())).isEqualTo(Duration.ofMinutes(120));
		assertThat(Duration.between(full.from(), full.to())).isEqualTo(Duration.ofMinutes(75)); // TEST-LEAD
		assertThat(full.step()).isEqualTo(Duration.ofSeconds(60));
		TrafficWindow around = TrafficWindow.of(EventPhase.ENDED, "start", start, start.plusMinutes(40), later,
				TrafficWindow.PROMETHEUS_MIN_STEP);
		assertThat(Duration.between(around.from(), around.to())).isEqualTo(Duration.ofMinutes(20));
		assertThat(TrafficWindow.of(EventPhase.SCHEDULED, null, start, null, now, TrafficWindow.PROMETHEUS_MIN_STEP))
			.isNull();
		assertThat(TrafficWindow.isKnown("2h")).isFalse();
	}

	@Test
	void windowsAfterHostingEndAreFixed() {
		LocalDateTime start = LocalDateTime.of(2026, 10, 28, 10, 0);
		LocalDateTime hostingEnd = start.plusMinutes(25);
		var justEnded = hostingEnd.plusSeconds(30).atZone(Kst.ZONE).toInstant();
		// 호스팅 종료 직후에도 끝은 호스팅 종료 +10분(지금으로 자르지 않음)
		for (EventPhase closed : List.of(EventPhase.ENDING, EventPhase.ENDED, EventPhase.END_FAILED)) {
			TrafficWindow full = TrafficWindow.of(closed, null, start, hostingEnd, justEnded, TrafficWindow.PROMETHEUS_MIN_STEP);
			assertThat(full.key()).isEqualTo("full");
			assertThat(full.options()).containsExactly("full", "start");
			assertThat(full.from()).isEqualTo(EntryOpen.at(start).minusMinutes(10).atZone(Kst.ZONE).toInstant());
			assertThat(full.to()).isEqualTo(hostingEnd.plusMinutes(10).atZone(Kst.ZONE).toInstant());
		}
		// 마무리는 진행 중과 같은 범위 · 선택지
		TrafficWindow wrapUp = TrafficWindow.of(EventPhase.WRAP_UP, null, start, hostingEnd,
				start.plusMinutes(20).atZone(Kst.ZONE).toInstant(), TrafficWindow.PROMETHEUS_MIN_STEP);
		assertThat(wrapUp.key()).isEqualTo("entry");
		assertThat(wrapUp.options()).containsExactly("entry", "15m", "1h");
		assertThat(wrapUp.to()).isEqualTo(start.plusMinutes(20).atZone(Kst.ZONE).toInstant());
	}

}
