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
 * 진행 단계 8개(명세 1절 C, 2026-10-26 결정 · 2026-10-27 배포 칸 추가 · 2026-10-28 이름 · 모양 정리).
 * | 단계 | 계획 | 끝남 · 실패 | 진행 중(ACTIVE) |
 * | 승인 | – | APPLICATION → APPROVED 로그 | – |
 * | 배포(WF1-2) | – | DEPLOYED 이후(마지막 → DEPLOYED 로그) / DEPLOY_FAILED | hosting PENDING(시작 = 승인) |
 * | 호스팅 시작(WF2-1) | hosting_start_at | HOSTING · ENDED · END_FAILED(마지막 → HOSTING 로그) / HOSTING_FAILED | DEPLOYED이고 계획 시각이 지남 |
 * | 준비(WF2-2) | 시작 − 90분 | EVENT → OPEN 로그(걸린 시간은 Pod 시작 → Ready) / OPEN_FAILED | 계획 시각이 지났고 OPEN 전 |
 * | 입장 | 시작 − 60분 | OPEN 로그 | 입장 중(입장 ~ 시작, 시작 = OPEN 로그) |
 * | 이벤트 진행 | start_at ~ end_at | end_at이 지남(또는 호스팅 종료) | 시작 ~ end_at |
 * | 호스팅 종료(WF4) | hosting_end_at | HOSTING → ENDED 로그(없으면 상태) / END_FAILED | 호스팅 종료 뒤인데 WF4 결과가 없음(종료 처리 중) |
 * | 집계 | – | event_result_summary.history_generated_at | WF4 성공 뒤 발급 이력 저장 전(집계 중) |
 * 진행 중은 지금 실제로 돌고 있는 단계만이다 — 앞 단계가 끝나고 다음 단계가 시작하기 전(기다리는 구간)에는 없다(다음 단계는 WAITING).
 * 계획 시각이 지났어도 시작 기록이 없으면 WAITING으로 두고 화면은 "HH:mm 예정"(화면에 "기록 없음"을 쓰지 않는다, 실패처럼 읽힌다).
 * 실패 뒤 단계는 SKIPPED: 배포 실패면 뒤 전부, 호스팅 · 준비 실패면 준비(호스팅 실패만) · 입장 · 이벤트 진행 · 집계, 호스팅 종료는 기록이
 * 없을 때만, WF4 실패면 집계. 실패 사유는 요약 · 비밀값 가림({@link FailureReasons}).
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
			// WF1-2는 승인 때 시작한다
			deploy.active(approve.actualAt);
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
		else if (hostingFailed) {
			hostingStart.fail(latest(logs, StatusType.HOSTING, "HOSTING_FAILED"));
		}
		else if (hostingStatus == HostingStatus.DEPLOYED && reached(event.getHostingStartAt(), now)) {
			hostingStart.active(null);
		}
		steps.add(hostingStart);

		boolean prepareFailed = event.getEventStatus() == EventStatus.OPEN_FAILED;
		MutableStep prepare = new MutableStep(StepKey.PREPARE, EventPhase.prepareAt(startAt));
		if (open.isPresent()) {
			prepare.done(open.get().getChangedAt());
			prepare.durationSeconds = prepareSeconds;
		}
		else if (prepareFailed) {
			prepare.fail(latest(logs, StatusType.EVENT, "OPEN_FAILED"));
		}
		else if (!phase.closed() && reached(prepare.plannedAt, now)) {
			// WF2-2는 계획 시각에 시작해 입장까지 기다린 뒤 OPEN으로 끝난다
			prepare.active(null);
		}
		steps.add(prepare);

		MutableStep entry = new MutableStep(StepKey.ENTRY, EntryOpen.at(startAt));
		open.ifPresent(l -> {
			if (phase == EventPhase.ENTRY_OPEN) {
				// 입장 중(시작 전): 입장이 진행 중, 시작 시각은 OPEN 로그
				entry.active(l.getChangedAt());
			}
			else {
				entry.done(l.getChangedAt());
			}
		});
		steps.add(entry);

		MutableStep start = new MutableStep(StepKey.START, startAt);
		start.endAt = event.getEndAt();
		boolean opened = open.isPresent() || event.getEventStatus() == EventStatus.OPEN
				|| event.getEventStatus() == EventStatus.EXPIRED;
		if (opened && reached(startAt, now)) {
			if (phase.closed() || reached(event.getEndAt(), now)) {
				start.done(startAt);
			}
			else {
				start.active(startAt);
			}
		}
		steps.add(start);

		MutableStep end = new MutableStep(StepKey.END, event.getHostingEndAt());
		if (ended.isPresent()) {
			end.done(ended.get().getChangedAt());
		}
		else if (hostingStatus == HostingStatus.END_FAILED) {
			end.fail(latest(logs, StatusType.HOSTING, "END_FAILED"));
		}
		else if (hostingStatus == HostingStatus.ENDED) {
			// 로그 누락: 상태로 완료(시각 없음)
			end.done(null);
		}
		else if (phase.closed()) {
			// 호스팅 종료 시각이 지났는데 WF4 결과가 아직 없음 = 종료 처리 중
			end.active(null);
		}
		steps.add(end);

		MutableStep aggregate = new MutableStep(StepKey.AGGREGATE, null);
		if (summary != null && summary.getHistoryGeneratedAt() != null) {
			aggregate.done(summary.getHistoryGeneratedAt());
		}
		else if (hostingStatus == HostingStatus.END_FAILED) {
			// WF4가 실패하면 발급 이력 저장(집계)을 하지 않는다
			aggregate.skip();
		}
		else if (hostingStatus == HostingStatus.ENDED) {
			// WF4 성공 뒤 발급 이력 저장 전 = 집계 중
			aggregate.active(null);
		}
		steps.add(aggregate);

		if (deployFailed) {
			// 배포가 안 돼 뒤 단계는 하나도 진행되지 않는다
			for (MutableStep s : List.of(hostingStart, prepare, entry, start, end, aggregate)) {
				s.skip();
			}
		}
		else if (hostingFailed || prepareFailed) {
			// 이벤트 페이지 · 입장 게이트가 열리지 않아 뒤 단계는 진행 안 됨(WF4는 돌아도 집계는 없다)
			List<MutableStep> skipped = hostingFailed ? List.of(prepare, entry, start, aggregate)
					: List.of(entry, start, aggregate);
			for (MutableStep s : skipped) {
				s.skip();
			}
			if (end.state == StepState.WAITING || end.state == StepState.ACTIVE) {
				end.skip();
			}
		}
		return steps.stream().map(MutableStep::toStep).toList();
	}

	/** 시각이 있고 지났다 */
	private static boolean reached(LocalDateTime at, LocalDateTime now) {
		return at != null && !now.isBefore(at);
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

		private LocalDateTime endAt;

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

		/** 지금 진행 중. startedAt = 시작한 시각(모르면 null — 화면은 계획 시각) */
		void active(LocalDateTime startedAt) {
			actualAt = startedAt;
			state = StepState.ACTIVE;
		}

		/** 앞 단계 실패로 진행 안 됨(진행 중으로 잡았던 시각은 지운다) */
		void skip() {
			if (state == StepState.ACTIVE) {
				actualAt = null;
			}
			state = StepState.SKIPPED;
		}

		/** 실패 로그가 없어도(로그 누락) 상태가 실패면 실패로 둔다. 사유는 요약 · 비밀값 가림 */
		void fail(EventStatusLog log) {
			state = StepState.FAILED;
			if (log != null) {
				actualAt = log.getChangedAt();
				reason = FailureReasons.summarize(log.getDetail());
			}
		}

		Step toStep() {
			return new Step(key, Kst.toOffset(plannedAt), Kst.toOffset(actualAt), Kst.toOffset(endAt), durationSeconds,
					state, reason);
		}

	}

}
