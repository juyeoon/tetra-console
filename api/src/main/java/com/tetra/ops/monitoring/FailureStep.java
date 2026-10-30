package com.tetra.ops.monitoring;

/** 최근 자동화 실패 · 긴급 알림(E)의 단계. 화면 표기는 프런트(예: PREPARE → "준비(WF2-2) 실패") */
public enum FailureStep {

	/** WF1-2 승인 후 등록(DEPLOY_FAILED) */
	DEPLOY,
	/** WF2-1 호스팅 시작(HOSTING_FAILED) */
	HOSTING_START,
	/** WF2-2 이벤트 준비(OPEN_FAILED) */
	PREPARE,
	/** WF4 종료(END_FAILED) */
	END,
	/** WF5 테넌트 환경 준비(provisioning FAILED) */
	PROVISIONING,
	/** 메일 발송 실패(SES) — 자동화 실패 수에는 넣지 않는다 */
	MAIL,
	/** 단계 지연(1분 주기 확인 DELAY 알림) — 자동화 실패 수에는 넣지 않는다(자동화 칸이 지연을 따로 판정한다) */
	DELAY,
	/** DLQ 메시지 등 시스템 알림(SYSTEM) — 자동화 실패 수에는 넣지 않는다(자동화 칸이 DLQ를 따로 판정한다) */
	SYSTEM,
	/** 그 밖의 URGENT 알림 */
	OTHER;

	static FailureStep ofStatus(String newStatus) {
		return switch (newStatus == null ? "" : newStatus) {
			case "DEPLOY_FAILED" -> DEPLOY;
			case "HOSTING_FAILED" -> HOSTING_START;
			case "OPEN_FAILED" -> PREPARE;
			case "END_FAILED" -> END;
			default -> OTHER;
		};
	}

	static FailureStep ofCategory(String category) {
		return switch (category == null ? "" : category) {
			case "PROVISIONING" -> PROVISIONING;
			case "MAIL" -> MAIL;
			case "EVENT" -> PREPARE;
			case "DELAY" -> DELAY;
			case "SYSTEM" -> SYSTEM;
			default -> OTHER;
		};
	}

	/** 자동화 칸(최근 24시간 실패)에 세는 단계. 메일 · 지연 · 시스템 알림은 E 목록에만 보인다(D6, 2026-10-30) */
	boolean automation() {
		return this != MAIL && this != DELAY && this != SYSTEM;
	}

}
