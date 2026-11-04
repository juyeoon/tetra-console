package com.tetra.ops.monitoring;

import java.time.Duration;
import java.time.LocalDateTime;

import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.HostingStatus;
import com.tetra.ops.event.EntryOpen;

/**
 * 이벤트 구역(C·D) 상태(명세 4절). 시각 기준이고, 실패 · 종료는 DB 상태도 본다.
 * 종료 시각은 둘(2026-10-28 dev 테스트 이벤트 12 뒤 정리): 이벤트 종료 end_at, 호스팅 종료 hosting_end_at(WF4).
 * | 상태 | 언제 | KPI · 그래프 · 갱신 |
 * | 진행 중(입장 중 · 진행 중) | 입장 ~ end_at | 실시간, 입장 −10분 ~ 지금 |
 * | 마무리 | end_at ~ hosting_end_at | 실시간(Pod · Redis가 아직 있음), 이벤트 종료 점선 |
 * | 종료 처리 중 | hosting_end_at(또는 WF4 결과) ~ 집계 완료 | 최종값 · 범위 고정, 진행 단계만 갱신(Prometheus · CloudWatch 다시 조회 안 함) |
 * | 종료 | 집계 완료(history_generated_at) | 최종값, 자동 갱신 꺼짐 |
 * | 종료 · 집계 실패 | WF4 실패(END_FAILED) | 최종값, 자동 갱신 꺼짐 |
 * 집계(발급 이력 저장) 실패는 DB에 상태가 남지 않아(메시지 재전달 → DLQ) 종료 처리 중으로 남는다 — 자동화 칸 DLQ로 보인다.
 * WF2-2는 T−90에 시작해 입장(T−60)까지 기다린 뒤 성공하므로 SCHEDULED → OPEN은 입장 직후다(2026-10-26 정정).
 */
public enum EventPhase {

	/** 지금 < 입장 −10분. 진행 단계만, KPI 숨김, 그래프 빈 상태 */
	SCHEDULED,
	/** 입장 −10분 ~ 입장. Pod · 재고 · Redis · 자원만 값 */
	PRE_ENTRY,
	/** 입장 ~ 이벤트 시작(대기방 입장, 발급 전). 그래프 · KPI는 진행 중과 같다 */
	ENTRY_OPEN,
	/** 이벤트 시작 ~ 이벤트 종료(end_at) */
	LIVE,
	/** 이벤트 종료(end_at) ~ 호스팅 종료(hosting_end_at). KPI · 그래프는 진행 중과 같다(커서 정지는 보지 않음) */
	WRAP_UP,
	/** 호스팅 종료(시각 또는 WF4 결과) ~ 집계 완료. 최종값, 그래프 범위 고정, 진행 단계만 갱신 */
	ENDING,
	/** 집계 완료(event_result_summary.history_generated_at). 최종값, 그래프 범위 고정, 자동 갱신 꺼짐 */
	ENDED,
	/** WF4 실패(hosting END_FAILED) — 집계도 없다. 최종값, 그래프 범위 고정, 자동 갱신 꺼짐 */
	END_FAILED,
	/** WF2-2 실패(OPEN_FAILED). 진행 단계만 */
	PREPARE_FAILED,
	/** WF1-2 배포 실패(hosting DEPLOY_FAILED). 진행 단계만, 목록은 바로 최근 종료 */
	DEPLOY_FAILED,
	/** WF2-1 호스팅 시작 실패(HOSTING_FAILED). 진행 단계만, 목록은 hosting_end_at 전엔 시각대로 · 지나면 최근 종료 */
	HOSTING_FAILED;

	/** 그래프 · KPI를 그리기 시작하는 시점: 입장 10분 전 */
	static final Duration CHART_LEAD = Duration.ofMinutes(10);

	/** 준비(WF2-2) 스케줄 = 시작 − 90분 (WF1-2 eventOpenScheduleAt, ApproveRules.HOSTING_LEAD_BEFORE_START) */
	// TEST-LEAD: 서비스 값 90
	// static final Duration PREPARE_LEAD = Duration.ofMinutes(90);
	static final Duration PREPARE_LEAD = Duration.ofMinutes(25); // TEST-LEAD

	static LocalDateTime prepareAt(LocalDateTime startAt) {
		return startAt.minus(PREPARE_LEAD);
	}

	/** end_at · 집계 여부를 모를 때: 마무리 없이 호스팅 종료까지 진행 중, 그 뒤는 종료 처리 중 */
	static EventPhase of(LocalDateTime startAt, LocalDateTime hostingEndAt, EventStatus eventStatus,
			HostingStatus hostingStatus, LocalDateTime now) {
		return of(startAt, null, hostingEndAt, eventStatus, hostingStatus, false, now);
	}

	/**
	 * 실패를 먼저 본다: DEPLOY_FAILED → HOSTING_FAILED → OPEN_FAILED → WF4 실패 → 호스팅 종료(집계 완료면 종료) → 시각.
	 * @param endAt 이벤트 종료(end_at). null이면 마무리 없음
	 * @param aggregated 집계 완료(event_result_summary.history_generated_at 있음)
	 */
	static EventPhase of(LocalDateTime startAt, LocalDateTime endAt, LocalDateTime hostingEndAt,
			EventStatus eventStatus, HostingStatus hostingStatus, boolean aggregated, LocalDateTime now) {
		if (hostingStatus == HostingStatus.DEPLOY_FAILED) {
			return DEPLOY_FAILED;
		}
		if (hostingStatus == HostingStatus.HOSTING_FAILED) {
			return HOSTING_FAILED;
		}
		if (eventStatus == EventStatus.OPEN_FAILED) {
			return PREPARE_FAILED;
		}
		if (hostingStatus == HostingStatus.END_FAILED) {
			return END_FAILED;
		}
		if (eventStatus == EventStatus.EXPIRED || hostingStatus == HostingStatus.ENDED
				|| (hostingEndAt != null && !now.isBefore(hostingEndAt))) {
			return aggregated ? ENDED : ENDING;
		}
		if (endAt != null && !now.isBefore(endAt)) {
			return WRAP_UP;
		}
		if (!now.isBefore(startAt)) {
			return LIVE;
		}
		LocalDateTime entryAt = EntryOpen.at(startAt);
		if (!now.isBefore(entryAt)) {
			return ENTRY_OPEN;
		}
		if (!now.isBefore(entryAt.minus(CHART_LEAD))) {
			return PRE_ENTRY;
		}
		return SCHEDULED;
	}

	/** 입장 뒤 ~ 호스팅 종료 전(입장 중 · 진행 중 · 마무리): KPI 순간값 전부 */
	boolean entryOpened() {
		return this == ENTRY_OPEN || this == LIVE || this == WRAP_UP;
	}

	/** 호스팅 종료 뒤(종료 처리 중 · 종료 · 종료 실패): 최종값, 그래프 범위 고정, 값을 다시 조회하지 않음 */
	boolean closed() {
		return this == ENDING || this == ENDED || this == END_FAILED;
	}

	/** Prometheus를 조회하는 상태 */
	boolean hasMetrics() {
		return this == PRE_ENTRY || entryOpened() || closed();
	}

}
