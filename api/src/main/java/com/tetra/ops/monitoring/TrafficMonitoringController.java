package com.tetra.ops.monitoring;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/** OPS-05 트래픽 모니터링(명세 docs/ops05). 값은 Prometheus · AWS · DB, 출처별 캐시(15초 · 60초(LCU 예약 30초) · 15초). */
@Tag(name = "트래픽 모니터링", description = "OPS-05 트래픽 모니터링")
@RestController
@RequestMapping("/api/ops/monitoring")
public class TrafficMonitoringController {

	private final TrafficMonitoringService service;

	private final EventExecutionService executions;

	public TrafficMonitoringController(TrafficMonitoringService service, EventExecutionService executions) {
		this.service = service;
		this.executions = executions;
	}

	@Operation(summary = "OPS-05 상태 요약 · 노드 · 플랫폼 · 다가오는 이벤트 · 최근 자동화 실패",
			description = "상태 요약 4칸(CLUSTER · EVENT · AUTOMATION · PLATFORM, level OK · WARN · DANGER · UNKNOWN), "
					+ "노드(클러스터 CPU · 메모리 0~1, 최근 1시간, 풀별 노드), 애드온 · RDS · Portal · Console · 발급 ALB LCU 예약, "
					+ "다가오는 이벤트, 최근 7일 자동화 실패 · 긴급 알림. 시리즈가 없으면 null. "
					+ "prometheus · aws · lcu 각각 asOf · stale(실패 · 미설정이면 마지막 값 + stale, 없으면 수치 null)")
	@GetMapping("/platform")
	public TrafficPlatformResponse platform() {
		return service.platform();
	}

	@Operation(summary = "OPS-05 이벤트 드롭다운 목록 + 기본 선택",
			description = "진행 중(OPEN, 나쁜 순) → 오늘 예정(입장 이른 순) → 최근 7일 종료 · 준비 실패(늦은 순). "
					+ "defaultEventId = 목록 첫 항목(없으면 null)")
	@GetMapping("/events")
	public TrafficEventsResponse events() {
		return service.events();
	}

	@Operation(summary = "OPS-05 이벤트 진행 단계 · KPI · 그래프",
			description = "phase(SCHEDULED · PRE_ENTRY · ENTRY_OPEN · LIVE · ENDED · PREPARE_FAILED · DEPLOY_FAILED · HOSTING_FAILED)에 따라 kpi · range · charts가 달라진다. "
					+ "없는 이벤트 404 EVENT_NOT_FOUND, 테넌트 준비 전 409 TENANT_NOT_READY, 모르는 range 400")
	@GetMapping("/events/{eventId}")
	public TrafficEventResponse event(@PathVariable Long eventId,
			@Parameter(description = "entry · 15m · 1h(입장 직전 · 진행 중), full · start(종료). 이 상태에서 못 고르는 값이면 기본값")
			@RequestParam(required = false) String range) {
		return service.event(eventId, range);
	}

	@Operation(summary = "OPS-05 자동화 실행 상세(Step Functions)",
			description = "이벤트의 WF1-2(DEPLOY) · WF2-1(HOSTING_START) · WF2-2(PREPARE) · WF4(END) 실행: 상태 · 시작 · 끝, "
					+ "단계별 시작 · 끝 · 소요 · 횟수(Wait 반복은 LOOP 한 줄) · 재시도, 실패 단계와 사유(비밀값 가림). "
					+ "실행 입력 · 출력 · ARN은 없다. 목록 30초 · 이벤트 15초 캐시, 실패하면 마지막 값 + aws.stale. "
					+ "승인 전이면 executions []. 없는 이벤트 404 EVENT_NOT_FOUND, 테넌트 준비 전 409 TENANT_NOT_READY")
	@GetMapping("/events/{eventId}/executions")
	public EventExecutionsResponse executions(@PathVariable Long eventId) {
		return executions.executions(eventId);
	}

}
