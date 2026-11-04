package com.tetra.ops.monitoring;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * GET /api/ops/monitoring/platform — OPS-05 A(상태 요약) · B(노드 · 플랫폼 · 다가오는 이벤트) · E(최근 자동화 실패).
 * 시리즈가 없는 수치는 null(0으로 바꾸지 않음). 개수는 kube-state-metrics 응답이 있으면 빠진 그룹 0.
 */
public record TrafficPlatformResponse(MonitoringSource prometheus,
		@Schema(description = "AWS(ALB · RDS · ECS · DLQ). 60초 캐시") MonitoringSource aws,
		@Schema(description = "발급 ALB LCU 예약(DescribeCapacityReservation). 30초 캐시, aws와 따로 — 권한 없음 · 오류면 이것만 stale"
				+ "(한 번도 못 읽었으면 platform.issuanceLcu null → 화면 '확인 불가')") MonitoringSource lcu,
		@Schema(description = "'Grafana에서 자세히' 링크의 Grafana 주소(GRAFANA_URL, 기본 http://localhost:13000). 비었으면 null — 링크 숨김") String grafanaUrl,
		@Schema(description = "4칸 고정 순서: CLUSTER · EVENT · AUTOMATION · PLATFORM") List<StatusCell> summary,
		Nodes nodes, Platform platform,
		@Schema(description = "SCHEDULED, 7일 안에 시작, 시작 순 최대 5건") List<Upcoming> upcoming,
		@Schema(description = "최근 7일 *_FAILED 상태 로그 + URGENT 운영자 알림, 최신순. 없으면 []") List<Failure> failures) {

	public enum Area {
		CLUSTER, EVENT, AUTOMATION, PLATFORM
	}

	/** openCount · eventId는 EVENT 칸만(eventId = 가장 나쁜 진행 중 이벤트, 드롭다운 기본 선택과 같음) */
	public record StatusCell(Area area, MonitoringLevel level, String reason, Integer openCount, Long eventId) {
	}

	/** cpu · memory는 클러스터 전체 0~1(홈과 같은 식) */
	public record Nodes(Double cpu, Double memory, History history, List<Pool> pools) {
	}

	/** 최근 1시간, stepSeconds 간격 */
	public record History(int stepSeconds, List<HistoryPoint> points) {
	}

	/** [epoch 밀리초, CPU 0~1 | null, 메모리 0~1 | null] */
	@JsonFormat(shape = JsonFormat.Shape.ARRAY)
	public record HistoryPoint(long epochMillis, Double cpu, Double memory) {
	}

	/**
	 * pool = system · app · redis(라벨 없는 노드는 other). count = Ready 노드 수, addedRecently = 최근 10분 안에 생긴 노드 수.
	 * cpu · memory = 풀 안 노드의 평균 · 최고(0~1)
	 */
	public record Pool(String pool, Integer count, int addedRecently, Stat cpu, Stat memory, List<Node> nodes) {
	}

	public record Stat(Double avg, Double max) {
	}

	/** type은 인스턴스 타입(kube-state-metrics 라벨 허용 전에는 null). recent = 최근 10분 안에 생김(점선 테두리) */
	public record Node(String name, String type, boolean ready, Double cpu, Double memory, OffsetDateTime createdAt,
			boolean recent) {
	}

	/** issuanceLcu: 발급 ALB 이름이 없거나 한 번도 못 읽었으면 null(lcu.stale = true) */
	public record Platform(Addons addons, Rds rds, List<ServiceStatus> services, IssuanceLcu issuanceLcu) {
	}

	/**
	 * 발급 ALB LCU 예약 상태 — AZ 상태를 합친 것, 나쁜 것이 이긴다(FAILED > PENDING > REBALANCING > PROVISIONED).
	 * 예약 값도 AZ도 없으면 NONE(예약 없음). 해제 중(예약 0 + AZ pending)은 PENDING
	 */
	public enum LcuState {
		PROVISIONED, PENDING, REBALANCING, FAILED, NONE
	}

	/**
	 * reservedUnits = 예약 LCU(예약 없음 null, 해제 중 0). effectiveUnits = AZ 실효 LCU 합(값이 있는 AZ만, 하나도 없으면 null).
	 * zones = AZ 이름 순. lastModifiedAt = 마지막 변경, decreaseRequestsRemaining = 오늘 남은 줄이기 횟수(AWS 값 그대로)
	 */
	public record IssuanceLcu(Integer reservedUnits, LcuState state, Double effectiveUnits, List<LcuZone> zones,
			OffsetDateTime lastModifiedAt, Integer decreaseRequestsRemaining) {
	}

	/** state = PROVISIONED · PENDING · REBALANCING · FAILED(AWS 코드 대문자, 모르는 값도 그대로). reason = 실패 사유 등, 없으면 null */
	public record LcuZone(String zone, String state, Double effectiveUnits, String reason) {
	}

	/** healthy = 정상인 그룹 수(Prometheus를 못 읽었으면 null), total = 그룹 수(6) */
	public record Addons(Integer healthy, int total, List<AddonGroup> groups) {
	}

	/** name: KEDA · Karpenter · LBC · Prometheus · External Secrets · ArgoCD. problems = Ready가 모자란 것만 */
	public record AddonGroup(String name, Boolean healthy, List<AddonMember> problems) {
	}

	/** ready가 null이면 시리즈 없음(배포 안 됨) */
	public record AddonMember(String namespace, String name, Integer ready, Integer desired) {
	}

	/** MONITORING_RDS_ID가 비면 null. cpuPercent 0~100 */
	public record Rds(String status, Double cpuPercent, Double connections, Double freeStorageBytes) {
	}

	/** Portal · Console. fiveXx = 최근 5분 대상 5xx + ALB 5xx 합 */
	public record ServiceStatus(String name, Integer running, Integer desired, Integer healthyTargets,
			Integer totalTargets, Long fiveXx) {
	}

	/** endAt = hosting_end_at(WF4) */
	public record Upcoming(Long eventId, String tenantId, String tenantName, String name, OffsetDateTime prepareAt,
			OffsetDateTime entryAt, OffsetDateTime startAt, OffsetDateTime endAt) {
	}

	/** reason = 상태 로그 detail(없으면 알림 내용). tenantName · eventName은 없으면 null */
	public record Failure(OffsetDateTime occurredAt, Long eventId, String tenantId, String tenantName,
			String eventName, FailureStep step, String reason) {
	}

}
