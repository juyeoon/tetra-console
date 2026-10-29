package com.tetra.ops.monitoring;

import java.time.OffsetDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * GET /api/ops/monitoring/events/{eventId}/executions — OPS-05 자동화 실행 상세(Step Functions, 2026-10-29).
 * 실행 입력 · 출력 · Task parameters · ARN · 역할은 넣지 않는다. 원인은 비밀값을 가린 값만.
 */
public record EventExecutionsResponse(
		@Schema(description = "Step Functions 조회 시각 · stale(실패하면 마지막 값, 없으면 asOf null + executions [])") MonitoringSource aws,
		Long eventId,
		@Schema(description = "흐름 순서(DEPLOY → HOSTING_START → PREPARE → END), 같은 흐름은 시작 순. 실행이 없는 흐름은 빠진다") List<Execution> executions) {

	/** 진행 단계 칸(TrafficEventResponse.StepKey)과 같은 이름 */
	public enum Flow {
		/** 배포 — WF1-2 이벤트 등록(실행 이름 approve-{event_id}, 재실행 -rN) */
		DEPLOY,
		/** 호스팅 시작 — WF2-1(스케줄러가 시작, 실행 이름 UUID) */
		HOSTING_START,
		/** 준비 — WF2-2 이벤트 준비(스케줄러) */
		PREPARE,
		/** 호스팅 종료 — WF4 이벤트 종료(스케줄러) */
		END
	}

	public enum StateKind {
		TASK, WAIT, CHOICE, PASS, PARALLEL, MAP, SUCCEED, LOOP
	}

	public enum StateStatus {
		SUCCEEDED, FAILED, RUNNING
	}

	public record Execution(Flow flow, @Schema(description = "WF1-2 · WF2-1 · WF2-2 · WF4") String workflow,
			@Schema(description = "실행 이름(approve-13, 스케줄러가 시작한 실행은 UUID)") String name,
			@Schema(description = "같은 흐름 안의 순번(시작 순, 1부터) — 2 이상이면 재실행") int attempt,
			@Schema(description = "RUNNING · SUCCEEDED · FAILED · TIMED_OUT · ABORTED · PENDING_REDRIVE") String status,
			OffsetDateTime startedAt, @Schema(description = "진행 중이면 null") OffsetDateTime stoppedAt,
			@Schema(description = "끝 − 시작, 진행 중이면 지금까지") Long durationSeconds, int redriveCount,
			@Schema(description = "실패 · 시간 초과 · 중단일 때만") Failure failure, List<State> states,
			@Schema(description = "기록이 길어 뒤쪽을 받지 않음(단계가 덜 보일 수 있다)") boolean truncated) {
	}

	/**
	 * state: 실패한 단계(Fail Cause의 "<상태 이름>: " 접두어, 없으면 끝나지 않은 마지막 상태). error: Fail 상태 Error.
	 * reason: 요약 120자(FailureReasons), cause: 가린 원문 500자
	 */
	public record Failure(String state, String error, String reason, String cause) {
	}

	/**
	 * 단계 한 줄. 같은 이름은 한 줄(count = 들어간 횟수). LOOP는 Wait로 시작하는 반복 묶음 — names가 Wait부터 차례, count = 반복 횟수.
	 * Pass · Choice · Succeed는 실패 단계일 때만 나온다. endedAt은 진행 중이면 null, durationSeconds는 진행 중이면 지금까지.
	 * retries = Task 재시도 수
	 */
	public record State(StateKind kind, List<String> names, int count, OffsetDateTime startedAt,
			OffsetDateTime endedAt, Long durationSeconds, int retries, StateStatus status) {
	}

}
