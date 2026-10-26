package com.tetra.ops.monitoring;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.tetra.ops.common.time.Kst;
import com.tetra.ops.domain.ApplicationStatus;
import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.HostingStatus;
import com.tetra.ops.domain.StatusType;
import com.tetra.ops.domain.tenant.Event;
import com.tetra.ops.domain.tenant.EventResultSummary;
import com.tetra.ops.domain.tenant.EventStatusLog;
import com.tetra.ops.event.EntryOpen;
import com.tetra.ops.monitoring.TrafficEventResponse.Step;
import com.tetra.ops.monitoring.TrafficEventResponse.StepKey;
import com.tetra.ops.monitoring.TrafficEventResponse.StepState;

/**
 * 진행 단계 7개(명세 1절 C, 2026-10-26 결정).
 * | 단계 | 계획 | 실제 · 실패 |
 * | 승인 | – | APPLICATION → APPROVED 로그(WF1-2 등록 실패 DEPLOY_FAILED면 실패 + 사유) |
 * | 호스팅 시작(WF2-1) | hosting_start_at | HOSTING → HOSTING / HOSTING_FAILED detail |
 * | 준비(T−90, WF2-2) | 시작 − 90분 | EVENT → OPEN 로그(입장 대기 포함이라 걸린 시간은 Pod 시작 → Ready로 따로) / OPEN_FAILED detail |
 * | 입장(T−60) | 시작 − 60분 | EVENT → OPEN 로그 |
 * | 시작 | start_at | 시각(종료 전까지는 진행 중) |
 * | 종료(WF4) | hosting_end_at | HOSTING → ENDED / END_FAILED detail |
 * | 집계 | – | event_result_summary.history_generated_at |
 * 진행 중 · 입장 직전에는 처음으로 끝나지 않은 단계 하나를 ACTIVE로 둔다.
 */
final class TrafficSteps {

	private TrafficSteps() {
	}

	static List<Step> build(Event event, List<EventStatusLog> logs, EventResultSummary summary, EventPhase phase,
			LocalDateTime now, Long prepareSeconds) {
		LocalDateTime startAt = event.getStartAt();
		Optional<EventStatusLog> approved = first(logs, StatusType.APPLICATION, "APPROVED");
		Optional<EventStatusLog> hosting = first(logs, StatusType.HOSTING, "HOSTING");
		Optional<EventStatusLog> open = first(logs, StatusType.EVENT, "OPEN");
		Optional<EventStatusLog> ended = first(logs, StatusType.HOSTING, "ENDED");

		List<MutableStep> steps = new ArrayList<>();

		MutableStep approve = new MutableStep(StepKey.APPROVE, null);
		if (event.getApplicationStatus() == ApplicationStatus.APPROVED) {
			approve.done(approved.map(EventStatusLog::getChangedAt).orElse(event.getProcessedAt()));
		}
		if (event.getHostingStatus() == HostingStatus.DEPLOY_FAILED) {
			approve.fail(latest(logs, StatusType.HOSTING, "DEPLOY_FAILED"));
		}
		steps.add(approve);

		MutableStep hostingStart = new MutableStep(StepKey.HOSTING_START, event.getHostingStartAt());
		hosting.ifPresent(l -> hostingStart.done(l.getChangedAt()));
		if (event.getHostingStatus() == HostingStatus.HOSTING_FAILED) {
			hostingStart.fail(latest(logs, StatusType.HOSTING, "HOSTING_FAILED"));
		}
		steps.add(hostingStart);

		boolean prepareFailed = event.getEventStatus() == EventStatus.OPEN_FAILED;
		MutableStep prepare = new MutableStep(StepKey.PREPARE, EventPhase.prepareAt(startAt));
		open.ifPresent(l -> {
			prepare.done(l.getChangedAt());
			prepare.durationSeconds = prepareSeconds;
		});
		if (prepareFailed) {
			prepare.fail(latest(logs, StatusType.EVENT, "OPEN_FAILED"));
		}
		steps.add(prepare);

		MutableStep entry = new MutableStep(StepKey.ENTRY, EntryOpen.at(startAt));
		open.ifPresent(l -> entry.done(l.getChangedAt()));
		steps.add(entry);

		MutableStep start = new MutableStep(StepKey.START, startAt);
		boolean opened = open.isPresent() || event.getEventStatus() == EventStatus.OPEN
				|| event.getEventStatus() == EventStatus.EXPIRED;
		if (opened && !now.isBefore(startAt)) {
			start.actualAt = startAt;
			if (phase == EventPhase.ENDED) {
				start.state = StepState.DONE;
			}
		}
		steps.add(start);

		MutableStep end = new MutableStep(StepKey.END, event.getHostingEndAt());
		ended.ifPresent(l -> end.done(l.getChangedAt()));
		if (event.getHostingStatus() == HostingStatus.END_FAILED) {
			end.fail(latest(logs, StatusType.HOSTING, "END_FAILED"));
		}
		steps.add(end);

		MutableStep aggregate = new MutableStep(StepKey.AGGREGATE, null);
		if (summary != null && summary.getHistoryGeneratedAt() != null) {
			aggregate.done(summary.getHistoryGeneratedAt());
		}
		steps.add(aggregate);

		if (prepareFailed) {
			// 입장 게이트가 열리지 않아 뒤 단계는 진행 안 됨(WF4는 돌아도 집계는 없다)
			for (MutableStep s : List.of(entry, start, aggregate)) {
				s.state = StepState.SKIPPED;
			}
			if (end.state == StepState.WAITING) {
				end.state = StepState.SKIPPED;
			}
		}
		if (phase == EventPhase.PRE_ENTRY || phase == EventPhase.LIVE) {
			steps.stream().filter(s -> s.state == StepState.WAITING).findFirst().ifPresent(s -> s.state = StepState.ACTIVE);
		}
		return steps.stream().map(MutableStep::toStep).toList();
	}

	private static Optional<EventStatusLog> first(List<EventStatusLog> logs, StatusType type, String newStatus) {
		return logs.stream()
			.filter(l -> l.getStatusType() == type && newStatus.equals(l.getNewStatus()))
			.min(Comparator.comparing(EventStatusLog::getChangedAt).thenComparing(EventStatusLog::getLogId));
	}

	private static EventStatusLog latest(List<EventStatusLog> logs, StatusType type, String newStatus) {
		return logs.stream()
			.filter(l -> l.getStatusType() == type && newStatus.equals(l.getNewStatus()))
			.max(Comparator.comparing(EventStatusLog::getChangedAt).thenComparing(EventStatusLog::getLogId))
			.orElse(null);
	}

	private static final class MutableStep {

		private final StepKey key;

		private final LocalDateTime plannedAt;

		private LocalDateTime actualAt;

		private Long durationSeconds;

		private StepState state = StepState.WAITING;

		private String reason;

		MutableStep(StepKey key, LocalDateTime plannedAt) {
			this.key = key;
			this.plannedAt = plannedAt;
		}

		void done(LocalDateTime at) {
			actualAt = at;
			state = StepState.DONE;
		}

		/** 실패 로그가 없어도(로그 누락) 상태가 실패면 실패로 둔다 */
		void fail(EventStatusLog log) {
			state = StepState.FAILED;
			if (log != null) {
				actualAt = log.getChangedAt();
				reason = log.getDetail();
			}
		}

		Step toStep() {
			return new Step(key, Kst.toOffset(plannedAt), Kst.toOffset(actualAt), durationSeconds, state, reason);
		}

	}

}
