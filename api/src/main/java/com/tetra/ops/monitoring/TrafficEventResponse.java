package com.tetra.ops.monitoring;

import java.time.OffsetDateTime;
import java.util.List;

import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.HostingStatus;
import com.tetra.ops.monitoring.MonitoringOverview.Point;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * GET /api/ops/monitoring/events/{eventId}?range= — OPS-05 C(진행 단계) · D(KPI · 그래프). 구역 모습은 event.phase(명세 4절):
 * SCHEDULED · PREPARE_FAILED는 kpi · range · charts가 null(Prometheus를 조회하지 않음), PRE_ENTRY는 kpi.live 일부만,
 * LIVE는 kpi.live 전부, ENDED는 kpi.summary(최종값).
 */
public record TrafficEventResponse(MonitoringSource prometheus,
		@Schema(description = "ALB · RDS 그래프(60초 단위, 60초 캐시)") MonitoringSource aws, Info event, List<Step> steps,
		Kpi kpi, Range range, Charts charts) {

	/** endAt = hosting_end_at(WF4). prepareAt = 시작 − 90분, entryAt = 시작 − 60분 */
	public record Info(Long eventId, String tenantId, String tenantName, String name, EventPhase phase,
			EventStatus eventStatus, HostingStatus hostingStatus, OffsetDateTime prepareAt, OffsetDateTime entryAt,
			OffsetDateTime startAt, OffsetDateTime endAt, Long stockCount) {
	}

	public enum StepKey {
		/** 승인 */
		APPROVE,
		/** 호스팅 시작(WF2-1) */
		HOSTING_START,
		/** 준비(T−90, WF2-2) */
		PREPARE,
		/** 입장(T−60, OPEN) */
		ENTRY,
		/** 이벤트 시작 */
		START,
		/** 종료(WF4, hosting_end_at) */
		END,
		/** 집계(발급 이력 저장) */
		AGGREGATE
	}

	public enum StepState {
		/** 끝남 */
		DONE,
		/** 지금 단계(강조). 진행 중 · 입장 직전에만 하나 */
		ACTIVE,
		/** 아직 */
		WAITING,
		/** 실패(빨강) — reason에 사유 */
		FAILED,
		/** 앞 단계 실패로 진행 안 됨 */
		SKIPPED
	}

	/**
	 * plannedAt: 계획 시각(승인 · 집계는 null). actualAt: 상태 로그 · 결과 요약 시각(시작은 시작 시각).
	 * durationSeconds: 준비만 — api · Redis Pod 시작 → Ready(Prometheus), 못 구하면 null
	 */
	public record Step(StepKey key, OffsetDateTime plannedAt, OffsetDateTime actualAt, Long durationSeconds,
			StepState state, String reason) {
	}

	/** warn = 주의 값인 KPI 키(errorRatio · p95 · pods · redisMemory · redisRestarts · redisUp) */
	public record Kpi(MonitoringLevel level, String reason, List<String> warn, Live live, Summary summary) {
	}

	/**
	 * 지금 값. PRE_ENTRY는 rps · errorRatio · p95 · waiting이 null("—"). 시리즈가 없으면 null.
	 * errorRatio 0~1, p95는 ms(발급 · 번호표 중 큰 값), cpu는 코어, memory는 바이트, redisMemoryRatio 0~1
	 */
	public record Live(Double rps, Double errorRatio, Long p95Ms, Long p95IssuanceMs, Long p95TicketMs,
			Integer podsReady, Integer podsMax, Integer podsMin, Integer podsPending, Double cpuCores,
			Double cpuRequestCores, Double memoryBytes, Double memoryRequestBytes, Long waiting, Long stockRemaining,
			Long stockTotal, Double redisMemoryRatio, Double redisMemoryUsedBytes, Double redisMemoryMaxBytes,
			Double redisOpsPerSec, Long redisRestarts, Long redisConnections, Boolean redisUp) {
	}

	/**
	 * 종료된 이벤트 최종값(입장 −10분 ~ 종료 +10분 전체 구간). issued = 결과 요약 success_count(없으면 재고 − 최소 남은 재고).
	 * errorRatioAvg = 구간 5xx 합 ÷ 요청 합
	 */
	public record Summary(Peak peakRps, Double errorRatioAvg, Double errorRatioMax, Long p95MaxMs, Long p95AvgMs,
			Integer podsMax, Integer podsLimit, Peak peakWaiting, Long issued, Long stockTotal,
			OffsetDateTime soldOutAt, Double cpuMaxCores, Double cpuRequestCores, Double memoryMaxBytes,
			Double memoryRequestBytes, Double redisMemoryMax, Long redisRestarts) {
	}

	public record Peak(Double value, OffsetDateTime at) {
	}

	/**
	 * key: 진행 중 · 입장 직전 entry(입장 −10분 ~ 지금, 기본) · 15m · 1h, 종료 full(입장 −10분 ~ 종료 +10분, 기본) · start(시작 ±10분).
	 * options = 이 상태에서 고를 수 있는 key
	 */
	public record Range(String key, List<String> options, OffsetDateTime from, OffsetDateTime to, int stepSeconds,
			@Schema(description = "그래프 세로 점선") OffsetDateTime entryAt,
			@Schema(description = "그래프 세로 점선") OffsetDateTime startAt) {
	}

	/**
	 * Prometheus 그래프 값은 timestamps(epoch 밀리초, stepSeconds 간격)와 같은 길이의 배열(값 없으면 null).
	 * AWS 값(errors.alb · errors.eventTarget · rds)은 60초(긴 범위는 더 큰) 간격이라 [epoch 밀리초, 값] 점 목록.
	 */
	public record Charts(List<Long> timestamps, Traffic traffic, Errors errors, Latency latency, Pods pods,
			Progress progress, Redis redis, Resources resources, Rds rds) {
	}

	/** rps = API 전체 초당 요청, cursorPerSec = 커서 도달/초 */
	public record Traffic(List<Double> rps, List<Double> cursorPerSec) {
	}

	/**
	 * 0~1. 에러 그래프 = app(앱 5xx, 이 이벤트) + alb(발급 ALB 5xx, 전체 이벤트 합, HTTPCode_ELB_5XX ÷ RequestCount).
	 * eventTarget = 이 이벤트 대상 그룹 5xx(HTTPCode_Target_5XX ÷ RequestCount, 대상 그룹을 못 찾으면 []) —
	 * 응답에만 두고 화면 그래프에는 쓰지 않는다(2026-10-26 리뷰, 참고용)
	 */
	public record Errors(List<Double> app, List<Point> alb,
			@Schema(description = "참고용 — 화면 그래프에는 쓰지 않음") List<Point> eventTarget) {
	}

	/** ms. issuance = 발급(/coupons/claim), ticket = 번호표(/ticket) */
	public record Latency(List<Double> issuance, List<Double> ticket) {
	}

	/** min · max = KEDA(HPA) 최소 · 최대(점선). 진행 중은 지금 값, 종료는 구간 최대 */
	public record Pods(List<Double> ready, List<Double> pending, Integer min, Integer max) {
	}

	public record Progress(List<Double> waiting, List<Double> stock) {
	}

	/** memory · cpu 0~1. restarts = 재시작 누적(그래프에는 안 그림, 종료 최종값용) */
	public record Redis(List<Double> memory, List<Double> cpu, List<Double> restarts) {
	}

	/** api Pod 합계 — 그래프에는 안 그림, 종료 최종값용. cpu 코어, memory 바이트 */
	public record Resources(List<Double> cpu, List<Double> memory, List<Double> cpuRequest, List<Double> memoryRequest) {
	}

	/** cpu 0~100(%), connections 개수 */
	public record Rds(List<Point> cpu, List<Point> connections) {
	}

}
