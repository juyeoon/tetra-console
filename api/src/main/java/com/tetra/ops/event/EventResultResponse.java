package com.tetra.ops.event;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.HostingStatus;

/**
 * OPS-04-3 이벤트 진행 결과. state로 화면이 상태 화면을 고른다.
 * state별로 쓰지 않는 값은 null이다.
 */
public record EventResultResponse(Long eventId, State state, OffsetDateTime eventOpenAt, OffsetDateTime aggregatedAt,
		HostingStatus hostingStatus, EventStatus eventStatus, OffsetDateTime endAt, Failure failure, Result result) {

	public enum State {

		END_FAILED, READY, AGGREGATING, IN_PROGRESS

	}

	/** END_FAILED: 가장 최근 HOSTING → END_FAILED 로그의 detail·changed_at. */
	public record Failure(String cause, OffsetDateTime occurredAt) {
	}

	public record Result(Metrics metrics, Composition composition, List<MinuteBucket> perMinute) {
	}

	/**
	 * 핵심 지표. 비율은 0~1 소수(DECIMAL(5,4)와 같은 자리수).
	 * capacityUsageRate = peakConcurrent / maxCapacity.
	 */
	public record Metrics(Long successCount, Long totalStock, BigDecimal selloutRate, Long soldoutCount,
			Long peakConcurrent, Long maxCapacity, BigDecimal capacityUsageRate, OffsetDateTime peakAt) {
	}

	/** 발급 결과 구성. TODO(미정): 이탈 산출 기준. 임시로 ticket_count − success_count − soldout_count (0 미만이면 0). */
	public record Composition(Long successCount, Long soldoutCount, Long abandonedCount) {
	}

	/** queue_entered_at을 분 단위로 묶은 SUCCESS·FAILED_SOLDOUT 건수. */
	public record MinuteBucket(OffsetDateTime minute, long successCount, long soldoutCount) {
	}

}
