package com.tetra.ops.monitoring;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.tetra.ops.common.time.Kst;
import com.tetra.ops.config.TetraProperties;
import com.tetra.ops.config.TetraProperties.MonitoringAws;
import com.tetra.ops.monitoring.AwsMonitoringGateway.CapacityReservation;
import com.tetra.ops.monitoring.AwsMonitoringGateway.LoadBalancerRef;
import com.tetra.ops.monitoring.AwsMonitoringGateway.MetricPoint;
import com.tetra.ops.monitoring.AwsMonitoringGateway.MetricQuery;
import com.tetra.ops.monitoring.AwsMonitoringGateway.ServiceCount;
import com.tetra.ops.monitoring.AwsMonitoringGateway.TargetGroupRef;
import com.tetra.ops.monitoring.AwsMonitoringGateway.TargetHealthCount;
import com.tetra.ops.monitoring.MonitoringOverview.Point;
import com.tetra.ops.monitoring.TrafficPlatformResponse.IssuanceLcu;
import com.tetra.ops.monitoring.TrafficPlatformResponse.LcuState;
import com.tetra.ops.monitoring.TrafficPlatformResponse.LcuZone;
import com.tetra.ops.monitoring.TrafficPlatformResponse.Rds;
import com.tetra.ops.monitoring.TrafficPlatformResponse.ServiceStatus;

/**
 * OPS-05 AWS 갱신 1회(명세 5-2). 캐시(60초) · stale은 TrafficMonitoringService. 호출 하나라도 실패하면 갱신 전체 실패 —
 * 단 발급 ALB · 이벤트 대상 그룹 조회는 실패해도 그 지표만 비운다(명세: 실패 시 해당 지표만 null).
 * GetMetricData는 갱신 1회에 한 번(지표 1,000개당 약 $0.01).
 */
@Component
class TrafficAwsCollector {

	private static final Logger log = LoggerFactory.getLogger(TrafficAwsCollector.class);

	/** 발급 ALB ARN 캐시(ALB가 다시 만들어질 수 있어 실행 중 조회) */
	static final Duration LOOKUP_TTL = Duration.ofMinutes(10);

	/** 조회 실패 · 대상 그룹 못 찾음 뒤 다시 조회하기까지 */
	static final Duration LOOKUP_RETRY = Duration.ofMinutes(1);

	/** 현재 값 조회 구간(CloudWatch는 1분 단위 · 지연이 있어 최근 점을 쓴다) */
	static final Duration LATEST_WINDOW = Duration.ofMinutes(15);

	/** Portal · Console 5xx 합계 구간 */
	static final Duration FIVE_XX_WINDOW = Duration.ofMinutes(5);

	static final int PERIOD_SECONDS = 60;

	/**
	 * LBC가 만든 대상 그룹 태그에서 이벤트를 찾는다. ingress.k8s.aws/resource = tenant-<t>/issuance-<e>-issuance-<e>:http(추정),
	 * 없으면 kubernetes.io/service-name = tenant-<t>/issuance-<e>.
	 * TODO(미정): 첫 이벤트 때 실제 태그 확인(명세 5-2 "추정")
	 */
	static final Pattern EVENT_RESOURCE = Pattern.compile("^tenant-[a-z0-9]{12}/issuance-(\\d{1,10})(?![0-9])");

	static final List<String> EVENT_TAG_KEYS = List.of("ingress.k8s.aws/resource", "kubernetes.io/service-name");

	record PlatformAws(Instant at, Rds rds, List<ServiceStatus> services,
			/** 큐 이름 → 보이는 메시지 수(점 없음 = 0). MONITORING_DLQ_NAMES가 비면 null */
			Map<String, Integer> dlq) {

		static PlatformAws unavailable() {
			return new PlatformAws(null, null, List.of(), null);
		}

	}

	/** 비율 0~1, rds cpu %(0~100) */
	record EventAws(Instant at, List<Point> albErrorRatio, List<Point> eventTargetErrorRatio, List<Point> rdsCpu,
			List<Point> rdsConnections) {

		static EventAws unavailable() {
			return new EventAws(null, List.of(), List.of(), List.of(), List.of());
		}

	}

	/** Portal · Console 이름별 설정 */
	private record ServiceConfig(String name, String alb, String tg, String tgArn) {
	}

	private final AwsMonitoringGateway gateway;

	private final MonitoringAws config;

	private final Clock clock;

	private final Object lookupLock = new Object();

	// ---- lookupLock으로 보호 ----

	private Optional<LoadBalancerRef> issuanceAlb = Optional.empty();

	private Instant albCheckedAt;

	private Map<Long, TargetGroupRef> eventTargetGroups = Map.of();

	private Instant targetGroupsCheckedAt;

	TrafficAwsCollector(AwsMonitoringGateway gateway, TetraProperties properties, Clock clock) {
		this.gateway = gateway;
		this.config = properties.monitoring().aws();
		this.clock = clock;
	}

	boolean configured() {
		return config.configured();
	}

	// ---------------------------------------------------------------- 플랫폼(B-2 · 자동화 DLQ)

	PlatformAws platform() {
		Instant now = clock.instant();
		List<MetricQuery> queries = new ArrayList<>();
		boolean rds = present(config.rdsId());
		if (rds) {
			Map<String, String> db = Map.of("DBInstanceIdentifier", config.rdsId());
			queries.add(new MetricQuery("rds_cpu", "AWS/RDS", "CPUUtilization", db, "Average", PERIOD_SECONDS));
			queries.add(new MetricQuery("rds_conn", "AWS/RDS", "DatabaseConnections", db, "Average", PERIOD_SECONDS));
			queries.add(new MetricQuery("rds_free", "AWS/RDS", "FreeStorageSpace", db, "Average", PERIOD_SECONDS));
		}
		List<ServiceConfig> services = serviceConfigs();
		for (int i = 0; i < services.size(); i++) {
			ServiceConfig s = services.get(i);
			if (present(s.alb())) {
				queries.add(new MetricQuery("svc" + i + "_elb", "AWS/ApplicationELB", "HTTPCode_ELB_5XX_Count",
						Map.of("LoadBalancer", s.alb()), "Sum", PERIOD_SECONDS));
				if (present(s.tg())) {
					queries.add(new MetricQuery("svc" + i + "_tg", "AWS/ApplicationELB", "HTTPCode_Target_5XX_Count",
							dims("TargetGroup", s.tg(), "LoadBalancer", s.alb()), "Sum", PERIOD_SECONDS));
				}
			}
		}
		List<String> dlqNames = config.dlqNameList();
		for (int i = 0; i < dlqNames.size(); i++) {
			queries.add(new MetricQuery("dlq" + i, "AWS/SQS", "ApproximateNumberOfMessagesVisible",
					Map.of("QueueName", dlqNames.get(i)), "Maximum", PERIOD_SECONDS));
		}
		Map<String, List<MetricPoint>> points = gateway.metricData(queries, now.minus(LATEST_WINDOW), now);

		Rds rdsValue = null;
		if (rds) {
			rdsValue = new Rds(gateway.dbInstanceStatus(config.rdsId()), PromValues.round(latest(points.get("rds_cpu")), 1),
					PromValues.round(latest(points.get("rds_conn")), 0), latest(points.get("rds_free")));
		}
		Map<String, ServiceCount> counts = new HashMap<>();
		if (present(config.ecsCluster()) && !services.isEmpty()) {
			gateway.services(config.ecsCluster(), services.stream().map(ServiceConfig::name).toList())
				.forEach(c -> counts.put(c.name(), c));
		}
		Instant fiveXxSince = now.minus(FIVE_XX_WINDOW).minusSeconds(PERIOD_SECONDS);
		List<ServiceStatus> statuses = new ArrayList<>();
		for (int i = 0; i < services.size(); i++) {
			ServiceConfig s = services.get(i);
			ServiceCount count = counts.get(s.name());
			TargetHealthCount health = present(s.tgArn()) ? gateway.targetHealth(s.tgArn()) : null;
			Long fiveXx = present(s.alb())
					? sumSince(points.get("svc" + i + "_elb"), fiveXxSince) + sumSince(points.get("svc" + i + "_tg"), fiveXxSince)
					: null;
			statuses.add(new ServiceStatus(s.name(), count == null ? null : count.running(),
					count == null ? null : count.desired(), health == null ? null : health.healthy(),
					health == null ? null : health.total(), fiveXx));
		}
		Map<String, Integer> dlq = null;
		if (!dlqNames.isEmpty()) {
			dlq = new LinkedHashMap<>();
			for (int i = 0; i < dlqNames.size(); i++) {
				// 빈 큐는 점이 없을 수 있다 → 0
				Double v = latest(points.get("dlq" + i));
				dlq.put(dlqNames.get(i), v == null ? 0 : (int) Math.round(v));
			}
		}
		return new PlatformAws(now, rdsValue, statuses, dlq);
	}

	/** MONITORING_ECS_SERVICES 순서(비면 대상 그룹이 설정된 console · portal) */
	private List<ServiceConfig> serviceConfigs() {
		List<String> names = new ArrayList<>(config.ecsServiceNames());
		if (names.isEmpty()) {
			if (present(config.consoleTgArn()) || present(config.consoleAlb())) {
				names.add("console");
			}
			if (present(config.portalTgArn()) || present(config.portalAlb())) {
				names.add("portal");
			}
		}
		return names.stream().map(name -> switch (name) {
			case "console" -> new ServiceConfig(name, config.consoleAlb(), config.consoleTg(), config.consoleTgArn());
			case "portal" -> new ServiceConfig(name, config.portalAlb(), config.portalTg(), config.portalTgArn());
			default -> new ServiceConfig(name, null, null, null);
		}).toList();
	}

	// ---------------------------------------------------------------- 이벤트 그래프(D-2 에러 · RDS)

	EventAws event(long eventId, TrafficWindow window) {
		Instant now = clock.instant();
		int period = (int) window.step().toSeconds();
		long periodMillis = period * 1000L;
		Instant start = Instant.ofEpochMilli(Math.floorDiv(window.from().toEpochMilli(), periodMillis) * periodMillis);
		Instant end = window.to();
		List<MetricQuery> queries = new ArrayList<>();
		Optional<LoadBalancerRef> alb = issuanceAlb();
		TargetGroupRef tg = alb.isPresent() ? targetGroup(alb.get(), eventId) : null;
		if (alb.isPresent()) {
			Map<String, String> lb = Map.of("LoadBalancer", alb.get().arnSuffix());
			queries.add(new MetricQuery("alb_req", "AWS/ApplicationELB", "RequestCount", lb, "Sum", period));
			queries.add(new MetricQuery("alb_5xx", "AWS/ApplicationELB", "HTTPCode_ELB_5XX_Count", lb, "Sum", period));
		}
		if (tg != null) {
			Map<String, String> d = dims("TargetGroup", tg.arnSuffix(), "LoadBalancer", alb.get().arnSuffix());
			queries.add(new MetricQuery("tg_req", "AWS/ApplicationELB", "RequestCount", d, "Sum", period));
			queries.add(new MetricQuery("tg_5xx", "AWS/ApplicationELB", "HTTPCode_Target_5XX_Count", d, "Sum", period));
		}
		if (present(config.rdsId())) {
			Map<String, String> db = Map.of("DBInstanceIdentifier", config.rdsId());
			queries.add(new MetricQuery("rds_cpu", "AWS/RDS", "CPUUtilization", db, "Average", period));
			queries.add(new MetricQuery("rds_conn", "AWS/RDS", "DatabaseConnections", db, "Average", period));
		}
		Map<String, List<MetricPoint>> points = queries.isEmpty() ? Map.of() : gateway.metricData(queries, start, end);
		return new EventAws(now, ratio(points.get("alb_5xx"), points.get("alb_req")),
				ratio(points.get("tg_5xx"), points.get("tg_req")), points(points.get("rds_cpu"), 1),
				points(points.get("rds_conn"), 0));
	}

	// ---------------------------------------------------------------- 발급 ALB LCU 예약(플랫폼 카드)

	/** 발급 ALB 이름이 있을 때만 LCU 예약을 조회한다 */
	boolean lcuConfigured() {
		return present(config.issuanceAlbName());
	}

	/**
	 * 발급 ALB LCU 예약(DescribeCapacityReservation). ARN은 {@link #issuanceAlb()}(DescribeLoadBalancers, 10분 캐시)를 그대로 쓴다.
	 * ALB를 못 찾음 · 조회 실패 · 예약 조회 실패(권한 반영 전 AccessDenied 등)는 {@link AwsMonitoringException} — 캐시
	 * (TrafficMonitoringService, 30초)가 마지막 값 + stale, 없으면 값 없음(화면 '확인 불가'). 다른 AWS 값과 캐시가 따로라 영향이 없다
	 */
	IssuanceLcu issuanceLcu() {
		LoadBalancerRef alb = issuanceAlb().orElseThrow(() -> new AwsMonitoringException("발급 ALB 없음"));
		CapacityReservation reservation = gateway.capacityReservation(alb.arn())
			.orElseThrow(() -> new AwsMonitoringException("DescribeCapacityReservation LoadBalancerNotFound"));
		return lcu(reservation);
	}

	/** AZ 상태를 합친다(나쁜 것이 이김). 예약 값도 AZ도 없으면 NONE, 예약은 있는데 AZ가 아직 없으면 PENDING */
	static IssuanceLcu lcu(CapacityReservation r) {
		List<LcuZone> zones = r.zones()
			.stream()
			.sorted(Comparator.comparing(z -> z.availabilityZone() == null ? "" : z.availabilityZone()))
			.map(z -> new LcuZone(z.availabilityZone(), z.state() == null ? null : z.state().toUpperCase(Locale.ROOT),
					z.effectiveCapacityUnits(), z.reason()))
			.toList();
		LcuState state;
		if (zones.isEmpty()) {
			state = r.capacityUnits() == null ? LcuState.NONE : LcuState.PENDING;
		}
		else {
			state = zones.stream().map(z -> zoneState(z.state())).max(Comparator.comparingInt(TrafficAwsCollector::severity))
				.orElse(LcuState.PENDING);
		}
		List<Double> effective = zones.stream().map(LcuZone::effectiveUnits).filter(Objects::nonNull).toList();
		Double effectiveSum = effective.isEmpty() ? null
				: PromValues.round(effective.stream().mapToDouble(Double::doubleValue).sum(), 1);
		return new IssuanceLcu(r.capacityUnits(), state, effectiveSum, zones,
				r.lastModifiedTime() == null ? null : Kst.toOffset(LocalDateTime.ofInstant(r.lastModifiedTime(), Kst.ZONE)),
				r.decreaseRequestsRemaining());
	}

	/** AWS 코드 → 상태. 모르는 값(SDK보다 새 코드)은 아직 반영 전으로 본다 */
	private static LcuState zoneState(String code) {
		if (code == null) {
			return LcuState.PENDING;
		}
		return switch (code) {
			case "PROVISIONED" -> LcuState.PROVISIONED;
			case "REBALANCING" -> LcuState.REBALANCING;
			case "FAILED" -> LcuState.FAILED;
			default -> LcuState.PENDING;
		};
	}

	private static int severity(LcuState s) {
		return switch (s) {
			case FAILED -> 4;
			case PENDING -> 3;
			case REBALANCING -> 2;
			case PROVISIONED -> 1;
			case NONE -> 0;
		};
	}

	/** 발급 ALB(이름 → ARN · arn_suffix). 10분 캐시, 실패 · 없음이면 1분 뒤 다시. 실패해도 예외를 던지지 않는다 */
	Optional<LoadBalancerRef> issuanceAlb() {
		if (!present(config.issuanceAlbName())) {
			return Optional.empty();
		}
		synchronized (lookupLock) {
			Instant now = clock.instant();
			Duration ttl = issuanceAlb.isPresent() ? LOOKUP_TTL : LOOKUP_RETRY;
			if (albCheckedAt != null && Duration.between(albCheckedAt, now).compareTo(ttl) < 0) {
				return issuanceAlb;
			}
			albCheckedAt = now;
			try {
				Optional<LoadBalancerRef> found = gateway.findLoadBalancer(config.issuanceAlbName());
				if (found.isEmpty()) {
					log.warn("발급 ALB를 찾지 못했습니다(MONITORING_ISSUANCE_ALB_NAME) — ALB 5xx 그래프는 비워 둡니다");
				}
				if (!found.equals(issuanceAlb)) {
					eventTargetGroups = Map.of();
					targetGroupsCheckedAt = null;
				}
				issuanceAlb = found;
			}
			catch (AwsMonitoringException ex) {
				log.warn("발급 ALB 조회 실패({}) — 마지막 값을 씁니다", ex.getMessage());
			}
			return issuanceAlb;
		}
	}

	/** 이벤트 대상 그룹. 10분 캐시, 맵에 없는 이벤트면 1분에 한 번만 다시 조회 */
	private TargetGroupRef targetGroup(LoadBalancerRef alb, long eventId) {
		synchronized (lookupLock) {
			Instant now = clock.instant();
			boolean stale = targetGroupsCheckedAt == null
					|| Duration.between(targetGroupsCheckedAt, now).compareTo(LOOKUP_TTL) >= 0;
			boolean missing = !eventTargetGroups.containsKey(eventId) && (targetGroupsCheckedAt == null
					|| Duration.between(targetGroupsCheckedAt, now).compareTo(LOOKUP_RETRY) >= 0);
			if (stale || missing) {
				targetGroupsCheckedAt = now;
				try {
					Map<Long, TargetGroupRef> byEvent = new HashMap<>();
					for (TargetGroupRef g : gateway.targetGroups(alb.arn())) {
						Long id = eventIdOf(g.tags());
						if (id != null) {
							byEvent.put(id, g);
						}
					}
					eventTargetGroups = Map.copyOf(byEvent);
				}
				catch (AwsMonitoringException ex) {
					log.warn("이벤트 대상 그룹 조회 실패({}) — 이벤트별 ALB 5xx는 비워 둡니다", ex.getMessage());
				}
			}
			return eventTargetGroups.get(eventId);
		}
	}

	static Long eventIdOf(Map<String, String> tags) {
		for (String key : EVENT_TAG_KEYS) {
			String value = tags.get(key);
			if (value != null) {
				Matcher m = EVENT_RESOURCE.matcher(value);
				if (m.find()) {
					return PromValues.parseId(m.group(1));
				}
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- 계산

	/** 같은 시각의 5xx ÷ 요청. 요청 점이 없으면 그 시각은 빼고(트래픽 없음), 요청이 있고 5xx 점만 없으면 0 */
	static List<Point> ratio(List<MetricPoint> errors, List<MetricPoint> requests) {
		if (requests == null) {
			return List.of();
		}
		Map<Long, Double> errorAt = new HashMap<>();
		if (errors != null) {
			errors.forEach(p -> errorAt.put(p.epochMillis(), p.value()));
		}
		List<Point> out = new ArrayList<>();
		for (MetricPoint r : requests) {
			if (r.value() > 0) {
				out.add(new Point(r.epochMillis(), PromValues.round(errorAt.getOrDefault(r.epochMillis(), 0.0) / r.value(), 5)));
			}
			else {
				out.add(new Point(r.epochMillis(), 0.0));
			}
		}
		return out;
	}

	private static List<Point> points(List<MetricPoint> values, int digits) {
		if (values == null) {
			return List.of();
		}
		return values.stream().map(p -> new Point(p.epochMillis(), PromValues.round(p.value(), digits))).toList();
	}

	private static Double latest(List<MetricPoint> points) {
		return points == null || points.isEmpty() ? null : points.get(points.size() - 1).value();
	}

	/** 카운트 지표는 0이면 점이 없다 → 합계 0 */
	private static long sumSince(List<MetricPoint> points, Instant since) {
		if (points == null) {
			return 0;
		}
		return Math.round(points.stream()
			.filter(p -> p.epochMillis() >= since.toEpochMilli())
			.mapToDouble(MetricPoint::value)
			.sum());
	}

	private static Map<String, String> dims(String k1, String v1, String k2, String v2) {
		Map<String, String> d = new LinkedHashMap<>();
		d.put(k1, v1);
		d.put(k2, v2);
		return d;
	}

	private static boolean present(String value) {
		return value != null && !value.isBlank();
	}

}
