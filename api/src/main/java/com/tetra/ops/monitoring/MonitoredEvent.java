package com.tetra.ops.monitoring;

import java.time.LocalDateTime;

import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.HostingStatus;
import com.tetra.ops.event.EntryOpen;

/**
 * OPS-05 목록 · 상태 판정에 쓰는 이벤트 한 건(registry + 테넌트 스키마 event). 시각은 KST.
 * endAt(이벤트 종료) · hostingEndAt · stockCount · aggregated는 테넌트 스키마 값 — 테넌트가 READY가 아니어서 못 읽었으면 null · false.
 * aggregated: 집계 완료(event_result_summary.history_generated_at 있음).
 * failedAt: 지금 실패 상태(DEPLOY_FAILED · HOSTING_FAILED · OPEN_FAILED)로 바뀐 마지막 로그 시각. 최근 7일 밖이거나 로그가 없으면 null.
 * approvedAt: registry processed_at(승인 시각) — 단계 지연의 WF2-2 · WF4 실행을 찾는 시작 기준.
 */
record MonitoredEvent(Long eventId, String tenantId, String tenantName, String name, LocalDateTime hostingStartAt,
		LocalDateTime startAt, LocalDateTime endAt, LocalDateTime hostingEndAt, EventStatus eventStatus,
		HostingStatus hostingStatus, Long stockCount, boolean aggregated, LocalDateTime failedAt,
		LocalDateTime approvedAt) {

	LocalDateTime prepareAt() {
		return EventPhase.prepareAt(startAt);
	}

	LocalDateTime entryAt() {
		return EntryOpen.at(startAt);
	}

	/** 지금 실패 상태 이름(로그 new_status). 실패가 아니면 null */
	String failedStatus() {
		if (hostingStatus == HostingStatus.DEPLOY_FAILED || hostingStatus == HostingStatus.HOSTING_FAILED) {
			return hostingStatus.name();
		}
		return eventStatus == EventStatus.OPEN_FAILED ? eventStatus.name() : null;
	}

	MonitoredEvent withFailedAt(LocalDateTime at) {
		return new MonitoredEvent(eventId, tenantId, tenantName, name, hostingStartAt, startAt, endAt, hostingEndAt,
				eventStatus, hostingStatus, stockCount, aggregated, at, approvedAt);
	}

	/** 단계 지연 판정 재료(TrafficRules.delays, 1분 주기 확인과 같은 규칙) */
	TrafficRules.DelayTarget delayTarget() {
		return new TrafficRules.DelayTarget(eventId, tenantId, approvedAt, hostingStartAt, startAt, hostingEndAt, eventStatus,
				hostingStatus);
	}

	EventPhase phase(LocalDateTime now) {
		return EventPhase.of(startAt, endAt, hostingEndAt, eventStatus, hostingStatus, aggregated, now);
	}

}
