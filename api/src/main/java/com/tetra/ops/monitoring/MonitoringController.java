package com.tetra.ops.monitoring;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** OPS-02 홈 모니터링 카드(클러스터 사용량 · 실시간 트래픽 처리 현황 · 테넌트 별 처리 현황). 값은 Prometheus. */
@Tag(name = "대시보드", description = "OPS-02 홈 대시보드")
@RestController
@RequestMapping("/api/ops")
public class MonitoringController {

	private final MonitoringService service;

	public MonitoringController(MonitoringService service) {
		this.service = service;
	}

	@Operation(summary = "OPS-02 홈 모니터링 카드 3개",
			description = "클러스터 CPU · 메모리 사용률(0~1), 풀별 Ready 노드 수, api · redis Pod Ready/전체, "
					+ "테넌트별 초당 요청 수(최근 30분, 15초 간격), OPEN 이벤트 테넌트별 rps · errorRps · Pod ready/max · "
					+ "입장 열림부터 누적 처리 완료/실패. 15초 캐시. 시리즈가 없으면 null. "
					+ "Prometheus 실패 · 미설정이면 stale = true(마지막 값, 없으면 수치 null)")
	@GetMapping("/monitoring/overview")
	public MonitoringOverview overview() {
		return service.overview();
	}

}
