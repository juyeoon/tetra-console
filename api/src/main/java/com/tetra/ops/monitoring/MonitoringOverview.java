package com.tetra.ops.monitoring;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * GET /api/ops/monitoring/overview 응답. 시리즈가 없는 수치는 null(0으로 바꾸지 않음).
 * Prometheus 실패 · 미설정이면 stale = true(마지막 값, 없으면 수치 null).
 */
public record MonitoringOverview(
		@Schema(description = "Prometheus 조회 시각(+09:00). stale이면 마지막으로 성공한 시각") OffsetDateTime asOf,
		@Schema(description = "true면 지난 값이거나(Prometheus 실패 · 타임아웃) 값이 없음(미설정)") boolean stale,
		Cluster cluster, Traffic traffic,
		@Schema(description = "OPEN 이벤트가 있는 테넌트 전부. 없으면 []") List<TenantRow> tenants) {

	public MonitoringOverview asStale() {
		return new MonitoringOverview(asOf, true, cluster, traffic, tenants);
	}

	/** cpuUsage · memoryUsage는 0~1 */
	public record Cluster(Double cpuUsage, Double memoryUsage, Nodes nodes, Pods pods) {

		static final Cluster UNAVAILABLE = new Cluster(null, null, new Nodes(null, null, null),
				new Pods(new PodCount(null, null), new PodCount(null, null)));

	}

	/** 풀별 Ready 노드 수 */
	public record Nodes(Integer app, Integer redis, Integer system) {
	}

	public record Pods(PodCount api, PodCount redis) {
	}

	/** total = phase Pending · Running */
	public record PodCount(Integer ready, Integer total) {
	}

	/** totalRps는 시리즈가 없으면 0, Prometheus를 못 읽었으면 null */
	public record Traffic(int rangeMinutes, int stepSeconds, Double totalRps, List<Series> series) {
	}

	/** points는 모든 시리즈가 같은 시각 격자(stepSeconds 간격). 값이 없으면 null */
	public record Series(String tenantId, @Schema(description = "DB에 없는 tenant_id면 null") String tenantName,
			@Schema(description = "그 테넌트의 OPEN 이벤트") List<EventRef> events,
			@Schema(description = "[epoch 밀리초, 초당 요청 수 | null]") List<Point> points) {
	}

	@JsonFormat(shape = JsonFormat.Shape.ARRAY)
	public record Point(long epochMillis, Double value) {
	}

	public record EventRef(Long eventId, String name, OffsetDateTime startAt) {
	}

	/**
	 * completed · failed는 since(가장 이른 OPEN 이벤트의 입장 열림)부터 누적. failed = 5xx(앱까지 온 요청만).
	 * errorRps = 지금 5xx 초당 요청 수(화면의 완료 · 실패 점 분배용)
	 */
	public record TenantRow(String tenantId, String tenantName, String namespace, List<EventRef> events, Double rps,
			Double errorRps, TenantPods pods, Long completed, Long failed, OffsetDateTime since) {
	}

	/** ready = component=api Ready Pod, max = HPA max_replicas 합 */
	public record TenantPods(Integer ready, Integer max) {
	}

}
