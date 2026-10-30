package com.tetra.ops.monitoring;

import java.time.Duration;

/**
 * 예정 단계 지연 5종({@link TrafficRules#delays}). 1분 주기 확인 알림(DELAY)과 OPS-05 자동화 칸이 같은 규칙을 쓴다.
 * 이름은 알림 중복 방지 키(DELAY:{event_id}:{이름})에 그대로 쓴다.
 * urgent = 긴급(알림 URGENT · 자동화 칸 위험), 아니면 주의(알림 WARNING · 자동화 칸 주의).
 */
public enum DelayStep {

	/** WF2-1: hosting_start_at + 10분까지 호스팅이 HOSTING이 아님 */
	HOSTING_START(TrafficRules.STEP_DELAY, true, "호스팅 시작"),
	/** WF2-2: 준비 스케줄(EventPhase.prepareAt) + 10분까지 WF2-2 실행이 없음 */
	PREPARE_START(TrafficRules.STEP_DELAY, true, "준비 시작"),
	/** 입장(EntryOpen.at) + 5분까지 이벤트가 OPEN이 아님 */
	ENTRY_OPEN(TrafficRules.ENTRY_OPEN_DELAY, true, "입장"),
	/** WF4: hosting_end_at + 10분까지 WF4 실행이 없음 */
	END_START(TrafficRules.STEP_DELAY, false, "종료 시작"),
	/** WF4: hosting_end_at + 60분까지 호스팅이 ENDED가 아님 */
	END_DONE(TrafficRules.END_DONE_DELAY, false, "종료 완료");

	private final Duration after;

	private final boolean urgent;

	private final String label;

	DelayStep(Duration after, boolean urgent, String label) {
		this.after = after;
		this.urgent = urgent;
		this.label = label;
	}

	/** 예정 시각에서 이만큼 지나면 지연 */
	public Duration after() {
		return after;
	}

	public boolean urgent() {
		return urgent;
	}

	/** 자동화 칸 사유 문구용 짧은 이름 */
	String label() {
		return label;
	}

}
