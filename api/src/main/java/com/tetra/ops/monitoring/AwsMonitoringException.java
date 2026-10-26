package com.tetra.ops.monitoring;

/** AWS 지표 조회 실패. 메시지는 로그에 그대로 써도 되는 짧은 원인만(호출 이름 · 오류 코드 — ARN · 응답 본문 · 자격 증명 없음) */
public class AwsMonitoringException extends RuntimeException {

	public AwsMonitoringException(String message) {
		super(message);
	}

}
