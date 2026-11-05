package com.tetra.ops.report.audit;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 감사 줄 하나(app = console)에서 리포트에 쓰는 값만. 키 이름은 감사 로그 형식(README "감사 로그(접속기록)", AuditEvent)과 같다.
 * user_agent는 넣지 않는다. changes는 JSON 원문(이미 가려진 값).
 * @param instant 정렬용(time의 시점)
 * @param time time을 KST 벽시계로
 */
record OperatorAuditRecord(Instant instant, LocalDateTime time, String auditId, String actorType, String actorId,
		String action, String path, String targetType, String targetId, String tenantId, Long eventId, String result,
		String reason, String ip, String changes, String attempt, String requestId) {

	static final String OPERATOR = "OPERATOR";

	/** 행위자가 운영자일 때 그 id, 아니면 null(로그인 실패 · 인증 전 요청) */
	String operatorId() {
		return OPERATOR.equals(actorType) ? actorId : null;
	}

}
