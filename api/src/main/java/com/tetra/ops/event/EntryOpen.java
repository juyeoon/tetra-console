package com.tetra.ops.event;

import java.time.Duration;
import java.time.LocalDateTime;

/** 입장(대기열) 열림 시각. event_status는 입장이 열린 직후 OPEN이 된다(WF2-2). */
public final class EntryOpen {

	/**
	 * 입장 열림 = 이벤트 시작 − 60분.
	 * web ENTRY_OPEN_LEAD_MINUTES(pages/events/eventView.ts), ops-workflows wf1-2 entryOpenAt,
	 * issuance-api tetra.session.entry-open-before와 같은 값 — 바꾸면 함께 바꾼다.
	 */
	public static final Duration ENTRY_OPEN_LEAD = Duration.ofMinutes(60);

	private EntryOpen() {
	}

	public static LocalDateTime at(LocalDateTime startAt) {
		return startAt.minus(ENTRY_OPEN_LEAD);
	}

}
