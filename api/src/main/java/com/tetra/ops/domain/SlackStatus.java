package com.tetra.ops.domain;

/**
 * operator_notification.slack_status (공용 V5, chk_operator_notif_slack_status). DB 값 그대로.
 * PENDING(보낼 것 · 재시도 대기) → SENDING(한 태스크가 선점) → SENT · FAILED · UNKNOWN. SKIPPED는 보내지 않을 행.
 */
public enum SlackStatus {

	PENDING,
	SENDING,
	SENT,
	/** 보내지 못했다(다시 해도 안 될 오류 · 재시도 횟수를 다 씀) */
	FAILED,
	/** V5 적용 전 행(BEFORE_V5) · 켠 뒤 한 번도 시도하지 않고 오래된 행(STALE) */
	SKIPPED,
	/** 보냈는지 모른다(응답 시간 초과 · 선점 만료 등). 다시 보내지 않는다 */
	UNKNOWN

}
