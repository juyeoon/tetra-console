package com.tetra.ops.monitoring;

/** OPS-05 상태(명세 2절). 화면은 배경색: 정상 초록 · 주의 황토 · 위험 빨강. UNKNOWN = 판단할 데이터가 없음(조회 실패 · 미설정) */
public enum MonitoringLevel {

	OK(1), WARN(2), DANGER(3), UNKNOWN(0);

	private final int severity;

	MonitoringLevel(int severity) {
		this.severity = severity;
	}

	/** 나쁜 순서 비교용. DANGER > WARN > OK > UNKNOWN */
	public int severity() {
		return severity;
	}

	public static MonitoringLevel worst(MonitoringLevel a, MonitoringLevel b) {
		return a.severity >= b.severity ? a : b;
	}

}
