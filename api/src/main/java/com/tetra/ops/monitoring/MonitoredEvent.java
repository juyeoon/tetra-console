package com.tetra.ops.monitoring;

import java.time.LocalDateTime;

import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.HostingStatus;
import com.tetra.ops.event.EntryOpen;

/**
 * OPS-05 목록 · 상태 판정에 쓰는 이벤트 한 건(registry + 테넌트 스키마 event). 시각은 KST.
 * hostingEndAt · stockCount는 테넌트 스키마 값 — 테넌트가 READY가 아니어서 못 읽었으면 null.
 * failedAt: 지금 실패 상태(DEPLOY_FAILED · HOSTING_FAILED · OPEN_FAILED)로 바뀐 마지막 로그 시각. 최근 7일 밖이거나 로그가 없으면 null.
 */
record MonitoredEvent(Long eventId, String tenantId, String tenantName, String name, LocalDateTime hostingStartAt,
		LocalDateTime startAt, LocalDateTime hostingEndAt, EventStatus eventStatus, HostingStatus hostingStatus,
		Long stockCount, LocalDateTime failedAt) {

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
		return new MonitoredEvent(eventId, tenantId, tenantName, name, hostingStartAt, startAt, hostingEndAt, eventStatus,
				hostingStatus, stockCount, at);
	}

	EventPhase phase(LocalDateTime now) {
		return EventPhase.of(startAt, hostingEndAt, eventStatus, hostingStatus, now);
	}

}
