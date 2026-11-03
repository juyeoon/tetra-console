package com.tetra.ops.report.event;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

import com.tetra.ops.domain.StatusType;

/**
 * 이벤트 결과 리포트 한 부의 값(테넌트 스키마에서 한 읽기 트랜잭션으로 모은 것, {@link EventResultReportReader}). 시각은 KST 벽시계.
 * 최종 사용자 값(user_id 등)과 내부 품질 지표(오류율 · 응답 시간 · Pod · 차단 사유)는 담지 않는다.
 * @param planCode 구매 플랜(traffic_plan_code)
 * @param maxCapacity 플랜 수용 인원(traffic_plan.max_capacity)
 * @param aggregatedAt 집계 기준 = history_generated_at(발급 이력을 처음 저장한 시각)
 */
record EventResultReportData(String tenantName, long eventId, String eventName, String planCode, Long maxCapacity,
		LocalDateTime startAt, LocalDateTime endAt, LocalDateTime aggregatedAt, Metrics metrics, Flow flow, Timing timing,
		List<Minute> minutes, List<StatusEntry> history) {

	/**
	 * 핵심 지표 — 결과 탭 API(OPS-04-3 metrics)와 같은 값. 비율은 0~1 소수 4자리.
	 * @param selloutRate success ÷ 총 재고(event_result_summary.sellout_rate)
	 * @param capacityUsageRate 최대 동시 접속 ÷ 플랜 수용(수용 0이면 null)
	 */
	record Metrics(long successCount, long totalStock, BigDecimal selloutRate, long soldoutCount, long peakConcurrent,
			BigDecimal capacityUsageRate, LocalDateTime peakAt) {

		/** 재고를 다 썼는지(재고 0이면 아님). 소진율은 반올림 값이라(9,999 / 10,000 → 0.9999 · 99,995 / 100,000 → 1.0000) 개수로 본다 */
		boolean soldOut() {
			return totalStock > 0 && successCount >= totalStock;
		}

	}

	/**
	 * 참여 흐름: 입장(세션) → 번호표 → 발급 성공 / 품절 / 이탈. 이탈은 결과 탭과 같은 정의
	 * (session_count − 성공 − 품절, 0 미만이면 0 — 입장했다가 결과 없이 나간 사람, 번호표를 받기 전에 나간 사람 포함).
	 */
	record Flow(long sessionCount, long ticketCount, long successCount, long soldoutCount, long abandonedCount) {

		/** 비율의 분모 = 성공 + 품절 + 이탈 — 결과 탭 '발급 결과 구성'과 같다(정상 데이터에서는 입장 수와 같음) */
		long base() {
			return successCount + soldoutCount + abandonedCount;
		}

		/** count ÷ {@link #base()}, 소수 4자리(반올림). 분모가 0이면 0 */
		BigDecimal ratio(long count) {
			long base = base();
			if (base == 0) {
				return BigDecimal.ZERO.setScale(4);
			}
			return BigDecimal.valueOf(count).divide(BigDecimal.valueOf(base), 4, RoundingMode.HALF_UP);
		}

	}

	/**
	 * 시간(초, 소수 3자리). 값이 없으면 null.
	 * @param selloutSeconds 소진까지 걸린 시간 — 재고를 다 쓴 경우만, 이벤트 오픈(start_at)부터 마지막 발급 성공(issued_at)까지
	 * @param decidedCount 대기 시간의 기준 인원 = 발급 결과(성공 · 품절)가 있는 사람
	 * @param waitAverageSeconds 대기 시간 = 번호표를 받아 대기열에 들어간 시각(queue_entered_at) → 발급 결과가 정해진 시각(issued_at)
	 */
	record Timing(BigDecimal selloutSeconds, long decidedCount, BigDecimal waitAverageSeconds,
			BigDecimal waitMaxSeconds) {
	}

	/** 분당 발급 결과 — 결과 탭과 같은 issuance_history의 queue_entered_at 분 단위. 이탈한 사람은 issuance_history에 없어 빠진다 */
	record Minute(LocalDateTime minute, long successCount, long soldoutCount) {
	}

	/**
	 * 운영 이력 한 줄(event_status_log). detail 원문은 넣지 않는다 — 반려 행만 반려 사유(event.reject_reason).
	 * @param prevStatus 처음 기록이면 null
	 */
	record StatusEntry(LocalDateTime changedAt, StatusType statusType, String prevStatus, String newStatus,
			String rejectReason) {
	}

}
