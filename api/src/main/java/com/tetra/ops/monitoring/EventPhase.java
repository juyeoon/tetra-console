package com.tetra.ops.monitoring;

import java.time.Duration;
import java.time.LocalDateTime;

import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.HostingStatus;
import com.tetra.ops.event.EntryOpen;

/**
 * 이벤트 구역(C·D) 상태(명세 4절). 시각 기준이고, 실패 · 종료는 DB 상태도 본다.
 * 종료 = hosting_end_at(WF4)로 통일(2026-10-26 결정). end_at은 이 화면에서 쓰지 않는다.
 * WF2-2는 T−90에 시작해 입장(T−60)까지 기다린 뒤 성공하므로 SCHEDULED → OPEN은 입장 직후다(2026-10-26 정정).
 */
public enum EventPhase {

	/** 지금 < 입장 −10분. 진행 단계만, KPI 숨김, 그래프 빈 상태 */
	SCHEDULED,
	/** 입장 −10분 ~ 입장. Pod · 재고 · Redis · 자원만 값 */
	PRE_ENTRY,
	/** 입장 ~ 종료(hosting_end_at) */
	LIVE,
	/** 종료 이후(시각 또는 WF4 결과). 최종값, 그래프 범위 고정, 자동 갱신 꺼짐 */
	ENDED,
	/** WF2-2 실패(OPEN_FAILED). 진행 단계만 */
	PREPARE_FAILED;

	/** 그래프 · KPI를 그리기 시작하는 시점: 입장 10분 전 */
	static final Duration CHART_LEAD = Duration.ofMinutes(10);

	/** 준비(WF2-2) 스케줄 = 시작 − 90분 (WF1-2 eventOpenScheduleAt, ApproveRules.HOSTING_LEAD_BEFORE_START) */
	static final Duration PREPARE_LEAD = Duration.ofMinutes(90);

	static LocalDateTime prepareAt(LocalDateTime startAt) {
		return startAt.minus(PREPARE_LEAD);
	}

	static EventPhase of(LocalDateTime startAt, LocalDateTime hostingEndAt, EventStatus eventStatus,
			HostingStatus hostingStatus, LocalDateTime now) {
		if (eventStatus == EventStatus.OPEN_FAILED) {
			return PREPARE_FAILED;
		}
		if (eventStatus == EventStatus.EXPIRED || hostingStatus == HostingStatus.ENDED
				|| hostingStatus == HostingStatus.END_FAILED || (hostingEndAt != null && !now.isBefore(hostingEndAt))) {
			return ENDED;
		}
		LocalDateTime entryAt = EntryOpen.at(startAt);
		if (!now.isBefore(entryAt)) {
			return LIVE;
		}
		if (!now.isBefore(entryAt.minus(CHART_LEAD))) {
			return PRE_ENTRY;
		}
		return SCHEDULED;
	}

	/** Prometheus를 조회하는 상태 */
	boolean hasMetrics() {
		return this == PRE_ENTRY || this == LIVE || this == ENDED;
	}

}
