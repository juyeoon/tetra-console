package com.tetra.ops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.tetra.ops.monitoring.AwsMonitoringGateway.ExecutionHistory;
import com.tetra.ops.monitoring.AwsMonitoringGateway.HistoryEvent;
import com.tetra.ops.monitoring.SfnHistories.Kind;
import com.tetra.ops.monitoring.SfnHistories.Row;
import com.tetra.ops.monitoring.SfnHistories.RowStatus;
import com.tetra.ops.monitoring.SfnHistories.Summary;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실행 기록 → 단계 행. 앞 4개는 dev 실제 기록(2026-10-27 · 28, includeExecutionData=false)을 쓰는 값만 남기고 테넌트 · slug를 바꾼 것
 * (src/test/resources/sfn), 뒤는 합성 기록(재시도 · 실행 중 · 시간 초과 · 가림 · 잘림 · 재실행).
 */
class SfnHistoriesTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void eventOpenLoopsAreFoldedWithIterationCount() {
		// 이벤트 13 WF2-2 성공: 앱 Ready 확인 6회, 입장 시각까지 대기, health 확인 2회
		Summary s = summarize("event-open-13", "SUCCEEDED");

		assertThat(s.status()).isEqualTo("SUCCEEDED");
		assertThat(s.failure()).isNull();
		assertThat(s.truncated()).isFalse();
		assertThat(s.rows()).extracting(Row::kind, Row::names, Row::count)
			.containsExactly(tuple(Kind.TASK, List.of("ScaleUpEvent"), 1),
					tuple(Kind.TASK, List.of("RefreshEventsAppSet"), 1),
					tuple(Kind.LOOP, List.of("WaitEventReady", "CheckEventApp", "IsEventAppReady"), 6),
					tuple(Kind.TASK, List.of("FreezeEvent"), 1), tuple(Kind.TASK, List.of("RefreshFrozenAppSet"), 1),
					tuple(Kind.WAIT, List.of("WaitFrozen"), 1), tuple(Kind.TASK, List.of("CheckFrozen"), 1),
					tuple(Kind.WAIT, List.of("WaitEntryOpen"), 1), tuple(Kind.TASK, List.of("OpenEntry"), 1),
					tuple(Kind.LOOP, List.of("WaitHealth", "CheckHealth", "IsHealthy"), 2));
		assertThat(s.rows()).allMatch(r -> r.status() == RowStatus.SUCCEEDED && r.endedAt() != null);

		Row appLoop = s.rows().get(2);
		// 첫 Wait 진입 14:45:41 ~ 마지막 Choice 나옴 14:48:46
		assertThat(seconds(appLoop)).isBetween(184L, 185L);
		assertThat(seconds(s.rows().get(7))).isEqualTo(356L);
		assertThat(s.stoppedAt()).isEqualTo(OffsetDateTime.parse("2026-10-28T14:55:24.631+09:00").toInstant());
	}

	@Test
	void approveFailureNamesFailedStateFromCausePrefix() {
		// approve-9 WF1-2 실패: Parallel 가지 안 앱 확인 20회 뒤 EventAppSyncTimedOut
		Summary s = summarize("approve-9", "FAILED");

		assertThat(s.status()).isEqualTo("FAILED");
		assertThat(s.failure().state()).isEqualTo("IsEventAppReady");
		assertThat(s.failure().error()).isEqualTo("EventAppSyncTimedOut");
		assertThat(s.failure().reason()).startsWith("EventAppSyncTimedOut · 앱 event-9 상태 WAITING (sync=OutOfSync")
			.hasSizeLessThanOrEqualTo(FailureReasons.MAX_LENGTH);
		assertThat(s.failure().cause()).startsWith("IsEventAppReady: 앱 event-9 상태 WAITING").endsWith("확인 20회 (10분 초과)");

		assertThat(s.rows()).extracting(Row::kind, Row::names, Row::count, Row::status)
			.containsExactly(tuple(Kind.PARALLEL, List.of("PrepareEventAssets"), 1, RowStatus.FAILED),
					tuple(Kind.TASK, List.of("CopyEventTemplate"), 1, RowStatus.SUCCEEDED),
					tuple(Kind.TASK, List.of("CopyEventBanner"), 1, RowStatus.SUCCEEDED),
					tuple(Kind.TASK, List.of("CommitEventValues"), 1, RowStatus.SUCCEEDED),
					tuple(Kind.TASK, List.of("RefreshEventsAppSet"), 1, RowStatus.SUCCEEDED),
					tuple(Kind.LOOP, List.of("WaitEventAppSync", "CheckEventAppStatus", "IsEventAppReady"), 20,
							RowStatus.FAILED));
		// 나오지 못한 Parallel은 실행 끝 시각까지
		assertThat(s.rows().get(0).endedAt()).isEqualTo(s.stoppedAt());
		assertThat(seconds(s.rows().get(5))).isBetween(617L, 620L);
	}

	@Test
	void mapItemsCountWithoutLoop() {
		// approve-13 WF1-2 성공: Parallel 가지 · Map 항목 3개(동시)
		Summary s = summarize("approve-13", "SUCCEEDED");

		assertThat(s.rows()).extracting(Row::kind, Row::names, Row::count)
			.containsExactly(tuple(Kind.PARALLEL, List.of("PrepareEventAssets"), 1),
					tuple(Kind.TASK, List.of("CopyEventTemplate"), 1), tuple(Kind.TASK, List.of("CopyEventBanner"), 1),
					tuple(Kind.TASK, List.of("CommitEventValues"), 1), tuple(Kind.TASK, List.of("RefreshEventsAppSet"), 1),
					tuple(Kind.WAIT, List.of("WaitEventAppSync"), 1), tuple(Kind.TASK, List.of("CheckEventAppStatus"), 1),
					tuple(Kind.TASK, List.of("WriteEventJson"), 1), tuple(Kind.TASK, List.of("PutEventKvs"), 1),
					tuple(Kind.MAP, List.of("CreateSchedules"), 1), tuple(Kind.TASK, List.of("CreateEventSchedule"), 3));
		assertThat(seconds(s.rows().get(0))).isBetween(34L, 35L);
		assertThat(s.rows()).allMatch(r -> r.status() == RowStatus.SUCCEEDED);
	}

	@Test
	void eventEndLoopBodyStopsBeforeNextStates() {
		// 이벤트 13 WF4 성공: 닫힘 확인 3회, 집계 확인 2회, 앱 삭제 확인 1회(반복 아님)
		Summary s = summarize("event-end-13", "SUCCEEDED");

		assertThat(s.rows()).extracting(Row::kind, Row::names, Row::count)
			.containsExactly(tuple(Kind.TASK, List.of("CloseEvent"), 1),
					tuple(Kind.LOOP, List.of("WaitClosed", "CheckClosed", "IsClosed"), 3),
					tuple(Kind.TASK, List.of("ReadEventFile"), 1), tuple(Kind.TASK, List.of("StartAggregate"), 1),
					tuple(Kind.TASK, List.of("RefreshForAggregate"), 1),
					tuple(Kind.LOOP, List.of("WaitAggregate", "CheckAggregate", "IsAggregateDone"), 2),
					tuple(Kind.TASK, List.of("DeleteEventFile"), 1), tuple(Kind.TASK, List.of("RefreshEventsAppSet"), 1),
					tuple(Kind.WAIT, List.of("WaitAppRemoved"), 1), tuple(Kind.TASK, List.of("CheckAppRemoved"), 1),
					tuple(Kind.TASK, List.of("DeleteEventKvs"), 1));
	}

	@Test
	void taskRetriesAreCounted() {
		Builder h = new Builder();
		long p = h.started();
		p = h.task("ScaleUpEvent", p, 0, 9, 3);
		h.add("ExecutionSucceeded", null, p, 9);

		Summary s = SfnHistories.summarize(h.history(false), "SUCCEEDED", null);
		assertThat(s.rows()).extracting(Row::names, Row::retries, Row::status)
			.containsExactly(tuple(List.of("ScaleUpEvent"), 2, RowStatus.SUCCEEDED));
	}

	@Test
	void runningLoopIsFoldedAndOpen() {
		// 실행 중: 앱 확인 세 번째 대기 중(Wait 3, Check 2, Choice 2)
		Builder h = new Builder();
		long p = h.started();
		p = h.task("ScaleUpEvent", p, 0, 2, 1);
		for (int i = 0; i < 2; i++) {
			p = h.state("Wait", "WaitEventReady", p, 2 + i * 31, 32 + i * 31);
			p = h.task("CheckEventApp", p, 32 + i * 31, 33 + i * 31, 1);
			p = h.state("Choice", "IsEventAppReady", p, 33 + i * 31, 33 + i * 31);
		}
		h.add("WaitStateEntered", "WaitEventReady", p, 64);

		Summary s = SfnHistories.summarize(h.history(false), "RUNNING", null);
		assertThat(s.status()).isEqualTo("RUNNING");
		assertThat(s.stoppedAt()).isNull();
		assertThat(s.failure()).isNull();
		assertThat(s.rows()).extracting(Row::kind, Row::names, Row::count, Row::status)
			.containsExactly(tuple(Kind.TASK, List.of("ScaleUpEvent"), 1, RowStatus.SUCCEEDED),
					tuple(Kind.LOOP, List.of("WaitEventReady", "CheckEventApp", "IsEventAppReady"), 3, RowStatus.RUNNING));
		assertThat(s.rows().get(1).endedAt()).isNull();
	}

	@Test
	void timedOutWithoutCauseUsesLastUnfinishedState() {
		Builder h = new Builder();
		long p = h.started();
		p = h.task("FreezeEvent", p, 0, 1, 1);
		long wait = h.add("WaitStateEntered", "WaitEntryOpen", p, 1);
		h.add("ExecutionTimedOut", null, wait, 3600);

		Summary s = SfnHistories.summarize(h.history(false), "TIMED_OUT", null);
		assertThat(s.status()).isEqualTo("TIMED_OUT");
		assertThat(s.failure().state()).isEqualTo("WaitEntryOpen");
		assertThat(s.failure().error()).isNull();
		assertThat(s.failure().reason()).isNull();
		assertThat(s.failure().cause()).isNull();
		assertThat(s.rows()).extracting(Row::names, Row::status)
			.containsExactly(tuple(List.of("FreezeEvent"), RowStatus.SUCCEEDED),
					tuple(List.of("WaitEntryOpen"), RowStatus.FAILED));
		assertThat(s.rows().get(1).endedAt()).isEqualTo(s.stoppedAt());
	}

	@Test
	void causeIsMaskedAndCut() {
		// Lambda 오류 JSON(GitOpsFailed: "<상태>: <원래 cause>") — 짧은 원인은 errorMessage, 비밀값은 가림, 원문 500자
		String cause = "ScaleUpEvent: {\"errorMessage\":\"push rejected token=glpat-abc123 for https://ci:s3cr3t@gitlab.internal/x\","
				+ "\"stackTrace\":[\"" + "가".repeat(600) + "\"]}";
		Builder h = new Builder();
		long p = h.started();
		long task = h.add("TaskStateEntered", "ScaleUpEvent", p, 0);
		long scheduled = h.add("TaskScheduled", null, task, 0);
		long failed = h.add("TaskFailed", null, scheduled, 1);
		long exited = h.add("TaskStateExited", "ScaleUpEvent", failed, 1);
		long fail = h.add("FailStateEntered", "GitOpsFailed", exited, 1);
		h.add("ExecutionFailed", null, fail, 1, "Lambda.Unknown", cause);

		Summary s = SfnHistories.summarize(h.history(false), "FAILED", null);
		assertThat(s.failure().state()).isEqualTo("ScaleUpEvent");
		assertThat(s.failure().reason()).isEqualTo("Lambda.Unknown · push rejected token=*** for https://ci:***@gitlab.internal/x");
		assertThat(s.failure().cause()).hasSize(SfnHistories.CAUSE_MAX)
			.startsWith("ScaleUpEvent: {\"errorMessage\":\"push rejected token=*** for https://ci:***@gitlab.internal/x\"")
			.doesNotContain("glpat-abc123", "s3cr3t")
			.endsWith("…");
		// 잡힌(Catch) Task는 나왔어도 실패 단계라 FAILED, Fail 행은 실패 정보로 대신
		assertThat(s.rows()).extracting(Row::kind, Row::names, Row::status)
			.containsExactly(tuple(Kind.TASK, List.of("ScaleUpEvent"), RowStatus.FAILED));
	}

	@Test
	void causePrefixMustBeVisitedState() {
		Builder h = new Builder();
		long p = h.started();
		long check = h.add("TaskStateEntered", "CheckHealth", p, 0);
		h.add("ExecutionAborted", null, check, 5, null, "Error: 운영자가 중단");

		Summary s = SfnHistories.summarize(h.history(false), "ABORTED", null);
		assertThat(s.failure().state()).isEqualTo("CheckHealth");
		assertThat(s.failure().reason()).isEqualTo("Error: 운영자가 중단");
	}

	@Test
	void truncatedHistoryKeepsListedStatus() {
		Builder h = new Builder();
		long p = h.started();
		h.task("ScaleUpEvent", p, 0, 1, 1);
		Instant stopped = Builder.BASE.plusSeconds(900);

		Summary s = SfnHistories.summarize(h.history(true), "SUCCEEDED", stopped);
		assertThat(s.status()).isEqualTo("SUCCEEDED");
		assertThat(s.stoppedAt()).isEqualTo(stopped);
		assertThat(s.truncated()).isTrue();
		assertThat(s.failure()).isNull();
	}

	@Test
	void redrivenExecutionRunsAgain() {
		Builder h = new Builder();
		long p = h.started();
		long task = h.add("TaskStateEntered", "CheckClosed", p, 0);
		long failed = h.add("ExecutionFailed", null, task, 5, "States.TaskFailed", "CheckClosed: 연결 실패");
		long redriven = h.add("ExecutionRedriven", null, failed, 60);
		h.add("TaskStateEntered", "CheckClosed", redriven, 60);

		Summary s = SfnHistories.summarize(h.history(false), "RUNNING", null);
		assertThat(s.status()).isEqualTo("RUNNING");
		assertThat(s.failure()).isNull();
		assertThat(s.rows()).extracting(Row::names, Row::count, Row::status)
			.containsExactly(tuple(List.of("CheckClosed"), 2, RowStatus.RUNNING));
	}

	// ---------------------------------------------------------------- 도우미

	private static Summary summarize(String fixture, String listedStatus) {
		return SfnHistories.summarize(new ExecutionHistory(load(fixture), false), listedStatus, null);
	}

	private static long seconds(Row row) {
		return Duration.between(row.startedAt(), row.endedAt()).toSeconds();
	}

	static List<HistoryEvent> load(String fixture) {
		try (InputStream in = SfnHistoriesTest.class.getResourceAsStream("/sfn/" + fixture + ".json")) {
			List<HistoryEvent> events = new ArrayList<>();
			for (JsonNode e : JSON.readTree(in)) {
				events.add(new HistoryEvent(e.path("id").asLong(), e.path("previousEventId").asLong(),
						e.path("type").asString(), OffsetDateTime.parse(e.path("timestamp").asString()).toInstant(),
						text(e, "name"), text(e, "error"), text(e, "cause")));
			}
			return events;
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isString() ? value.asString() : null;
	}

	/** 합성 기록. 시각은 BASE + 초 */
	static final class Builder {

		static final Instant BASE = Instant.parse("2026-10-28T05:00:00Z");

		private final List<HistoryEvent> events = new ArrayList<>();

		long started() {
			return add("ExecutionStarted", null, 0, 0);
		}

		long add(String type, String name, long previous, long atSeconds) {
			return add(type, name, previous, atSeconds, null, null);
		}

		long add(String type, String name, long previous, long atSeconds, String error, String cause) {
			long id = events.size() + 1;
			events.add(new HistoryEvent(id, previous, type, BASE.plusSeconds(atSeconds), name, error, cause));
			return id;
		}

		/** Task 진입 → (예약 · 시작 · 실패) × (attempts − 1) → 예약 · 시작 · 성공 → 나옴. 나옴 id */
		long task(String name, long previous, long start, long end, int attempts) {
			long p = add("TaskStateEntered", name, previous, start);
			for (int i = 1; i < attempts; i++) {
				p = add("TaskScheduled", null, p, start);
				p = add("TaskStarted", null, p, start);
				p = add("TaskFailed", null, p, start);
			}
			p = add("TaskScheduled", null, p, start);
			p = add("TaskStarted", null, p, start);
			p = add("TaskSucceeded", null, p, end);
			return add("TaskStateExited", name, p, end);
		}

		/** Wait · Choice · Pass 진입 → 나옴. 나옴 id */
		long state(String kind, String name, long previous, long start, long end) {
			long p = add(kind + "StateEntered", name, previous, start);
			return add(kind + "StateExited", name, p, end);
		}

		ExecutionHistory history(boolean truncated) {
			return new ExecutionHistory(List.copyOf(events), truncated);
		}

	}

}
