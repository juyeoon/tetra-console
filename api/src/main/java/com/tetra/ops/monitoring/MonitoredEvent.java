package com.tetra.ops.monitoring;

import java.time.LocalDateTime;

import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.HostingStatus;
import com.tetra.ops.event.EntryOpen;

/**
 * OPS-05 목록 · 상태 판정에 쓰는 이벤트 한 건(registry + 테넌트 스키마 event). 시각은 KST.
 * hostingEndAt · stockCount는 테넌트 스키마 값 — 테넌트가 READY가 아니어서 못 읽었으면 null.
 */
record MonitoredEvent(Long eventId, String tenantId, String tenantName, String name, LocalDateTime hostingStartAt,
		LocalDateTime startAt, LocalDateTime hostingEndAt, EventStatus eventStatus, HostingStatus hostingStatus,
		Long stockCount) {

	LocalDateTime prepareAt() {
		return EventPhase.prepareAt(startAt);
	}

	LocalDateTime entryAt() {
		return EntryOpen.at(startAt);
	}

	EventPhase phase(LocalDateTime now) {
		return EventPhase.of(startAt, hostingEndAt, eventStatus, hostingStatus, now);
	}

}
