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
 * 진행 단계 8개(명세 1절 C, 2026-10-26 결정 · 2026-10-27 배포 칸 추가).
 * | 단계 | 계획 | 실제 · 실패 |
 * | 승인 | – | APPLICATION → APPROVED 로그 |
 * | 배포(WF1-2) | – | hosting_status: PENDING 지금 단계 · DEPLOYED 이후 완료(마지막 → DEPLOYED 로그) / DEPLOY_FAILED detail |
 * | 호스팅 시작(WF2-1) | hosting_start_at | hosting_status HOSTING · ENDED · END_FAILED면 완료(마지막 → HOSTING 로그) / HOSTING_FAILED detail |
 * | 준비(T−90, WF2-2) | 시작 − 90분 | EVENT → OPEN 로그(입장 대기 포함이라 걸린 시간은 Pod 시작 → Ready로 따로) / OPEN_FAILED detail |
 * | 입장(T−60) | 시작 − 60분 | EVENT → OPEN 로그(입장 중 ~ 시작 전에는 지금 단계) |
 * | 시작 | start_at | 시작 시각이 지나면 지금 단계, 종료되면 완료 |
 * | 종료(WF4) | hosting_end_at | HOSTING → ENDED / END_FAILED detail |
 * | 집계 | – | event_result_summary.history_generated_at |
 * 입장 직전 · 진행 중에는 처음으로 끝나지 않은 단계 하나를, 입장 중에는 입장을 ACTIVE로 둔다. 배포 중(PENDING)이면 상태와 상관없이 배포가 ACTIVE.
 * 실패 뒤 단계는 SKIPPED: 배포 실패면 뒤 전부, 호스팅 · 준비 실패면 준비(호스팅 실패만) · 입장 · 시작 · 집계, 종료는 기록이 없을 때만.
 */
final class TrafficSteps {

	private TrafficSteps() {
	}

	static List<Step> build(Event event, List<EventStatusLog> logs, EventResultSummary summary, EventPhase phase,
			LocalDateTime now, Long prepareSeconds) {
		LocalDateTime startAt = event.getStartAt();
		Optional<EventStatusLog> approved = first(logs, StatusType.APPLICATION, "APPROVED");
		Optional<EventStatusLog> open = first(logs, StatusType.EVENT, "OPEN");
		Optional<EventStatusLog> ended = first(logs, StatusType.HOSTING, "ENDED");
		HostingStatus hostingStatus = event.getHostingStatus();

		List<MutableStep> steps = new ArrayList<>();

		MutableStep approve = new MutableStep(StepKey.APPROVE, null);
		if (event.getApplicationStatus() == ApplicationStatus.APPROVED) {
			approve.done(approved.map(EventStatusLog::getChangedAt).orElse(event.getProcessedAt()));
		}
		steps.add(approve);

		boolean deployFailed = hostingStatus == HostingStatus.DEPLOY_FAILED;
		MutableStep deploy = new MutableStep(StepKey.DEPLOY, null);
		if (hostingStatus == HostingStatus.PENDING) {
			deploy.state = StepState.ACTIVE;
		}
		else if (deployFailed) {
			deploy.fail(latest(logs, StatusType.HOSTING, "DEPLOY_FAILED"));
		}
		else if (hostingStatus != null) {
			deploy.done(latestAt(logs, StatusType.HOSTING, "DEPLOYED"));
		}
		steps.add(deploy);

		boolean hostingFailed = hostingStatus == HostingStatus.HOSTING_FAILED;
		MutableStep hostingStart = new MutableStep(StepKey.HOSTING_START, event.getHostingStartAt());
		if (hostingStatus == HostingStatus.HOSTING || hostingStatus == HostingStatus.ENDED
				|| hostingStatus == HostingStatus.END_FAILED) {
			hostingStart.done(latestAt(logs, StatusType.HOSTING, "HOSTING"));
		}
		if (hostingFailed) {
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

		if (deployFailed) {
			// 배포가 안 돼 뒤 단계는 하나도 진행되지 않는다
			for (MutableStep s : List.of(hostingStart, prepare, entry, start, end, aggregate)) {
				s.state = StepState.SKIPPED;
			}
		}
		else if (hostingFailed || prepareFailed) {
			// 이벤트 페이지 · 입장 게이트가 열리지 않아 뒤 단계는 진행 안 됨(WF4는 돌아도 집계는 없다)
			List<MutableStep> skipped = hostingFailed ? List.of(prepare, entry, start, aggregate)
					: List.of(entry, start, aggregate);
			for (MutableStep s : skipped) {
				s.state = StepState.SKIPPED;
			}
			if (end.state == StepState.WAITING) {
				end.state = StepState.SKIPPED;
			}
		}
		if (deploy.state != StepState.ACTIVE) {
			if (phase == EventPhase.ENTRY_OPEN) {
				// 입장 중(시작 전): 입장이 지금 단계(시각은 OPEN 로그), 시작은 아직 예정
				entry.state = StepState.ACTIVE;
			}
			else if (phase == EventPhase.PRE_ENTRY || phase == EventPhase.LIVE) {
				steps.stream().filter(s -> s.state == StepState.WAITING).findFirst().ifPresent(s -> s.state = StepState.ACTIVE);
			}
		}
		return steps.stream().map(MutableStep::toStep).toList();
	}

	private static Optional<EventStatusLog> first(List<EventStatusLog> logs, StatusType type, String newStatus) {
		return logs.stream()
			.filter(l -> l.getStatusType() == type && newStatus.equals(l.getNewStatus()))
			.min(Comparator.comparing(EventStatusLog::getChangedAt).thenComparing(EventStatusLog::getLogId));
	}

	/** 상태로 완료를 정한 단계의 시각: 마지막 전이 로그, 없으면 null(화면 "– 완료") */
	private static LocalDateTime latestAt(List<EventStatusLog> logs, StatusType type, String newStatus) {
		EventStatusLog log = latest(logs, type, newStatus);
		return log == null ? null : log.getChangedAt();
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
