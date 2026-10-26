package com.tetra.ops.monitoring;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

/** 데이터 출처(Prometheus · AWS)별 기준 시각. 출처마다 따로 둬서 한쪽이 죽어도 다른 칸은 그대로 보인다 */
public record MonitoringSource(
		@Schema(description = "조회 시각(+09:00). stale이면 마지막으로 성공한 시각, 한 번도 성공 못 했으면 null") OffsetDateTime asOf,
		@Schema(description = "true면 지난 값이거나(조회 실패 · 시간 초과) 값이 없음(미설정)") boolean stale) {

	static final MonitoringSource UNAVAILABLE = new MonitoringSource(null, true);

	MonitoringSource asStale() {
		return new MonitoringSource(asOf, true);
	}

}
