package com.tetra.ops.monitoring;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

/** 데이터 출처(Prometheus · AWS)별 기준 시각. 출처마다 따로 둬서 한쪽이 죽어도 다른 칸은 그대로 보인다 */
public record MonitoringSource(
		@Schema(description = "조회 시각(+09:00). stale이면 마지막으로 성공한 시각, 한 번도 성공 못 했으면 null") OffsetDateTime asOf,
		@Schema(description = "true면 지난 값이거나(조회 실패 · 시간 초과) 값이 없음(미설정)") boolean stale,
		@Schema(description = "stale 원인(화면 문구를 나눈다). SOURCE = 출처 자체 장애(연결 실패 · 5xx · 시간 초과 · 미설정), "
				+ "INTERNAL = 그 밖의 처리 오류. stale이 아니면 null") StaleReason staleReason) {

	/** stale 원인(2026-10-28) */
	public enum StaleReason {

		/** 출처 자체 장애: 연결 거부 · 연결 시간 초과 · 5xx · 갱신 상한 초과 · 미설정 */
		SOURCE,

		/** 그 밖의 처리 오류(Console 코드 · 결과 가공) */
		INTERNAL

	}

}
