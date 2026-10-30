package com.tetra.ops.monitoring;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.tetra.ops.common.error.ApiException;
import com.tetra.ops.common.error.ErrorCode;
import com.tetra.ops.common.time.Kst;
import com.tetra.ops.config.TetraProperties;
import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.StatusType;
import com.tetra.ops.domain.tenant.Event;
import com.tetra.ops.domain.tenant.EventStatusLog;
import com.tetra.ops.event.EntryOpen;
import com.tetra.ops.monitoring.MonitoringSource.StaleReason;
import com.tetra.ops.monitoring.TrafficAwsCollector.EventAws;
import com.tetra.ops.monitoring.TrafficAwsCollector.PlatformAws;
import com.tetra.ops.monitoring.TrafficCatalog.FailureItem;
import com.tetra.ops.monitoring.TrafficEventCollector.PromSeries;
import com.tetra.ops.monitoring.TrafficEventResponse.Charts;
import com.tetra.ops.monitoring.TrafficEventResponse.Kpi;
import com.tetra.ops.monitoring.TrafficEventResponse.Live;
import com.tetra.ops.monitoring.TrafficEventsResponse.Group;
import com.tetra.ops.monitoring.TrafficPlatformCollector.NodeMetric;
import com.tetra.ops.monitoring.TrafficPlatformResponse.AddonGroup;
import com.tetra.ops.monitoring.TrafficPlatformResponse.AddonMember;
import com.tetra.ops.monitoring.TrafficPlatformResponse.Area;
import com.tetra.ops.monitoring.TrafficPlatformResponse.Pool;
import com.tetra.ops.monitoring.TrafficPlatformResponse.ServiceStatus;
import com.tetra.ops.monitoring.TrafficPlatformResponse.StatusCell;
import com.tetra.ops.monitoring.TrafficRules.EventSignals;
import com.tetra.ops.monitoring.TrafficRules.Judgment;
import com.tetra.ops.tenancy.TenantRouter;
import com.tetra.ops.tenancy.TenantRouter.RoutedEvent;

import jakarta.annotation.PreDestroy;

/**
 * OPS-05 트래픽 모니터링(명세 docs/ops05). 출처별 캐시(Prometheus · DB 15초, AWS 60초)는 {@link RefreshCache} —
 * single-flight · 갱신 상한 · 실패 시 마지막 값 + stale. 응답은 출처별 asOf · stale을 따로 준다(한쪽이 죽어도 다른 칸은 보인다).
 * 이벤트 상세는 (이벤트 · 상태 · 범위)별 캐시, 2분 안 쓰이면 지운다. 호스팅이 끝난 상태(종료 처리 중 · 종료 · 종료 실패)는 범위가
 * 고정이라 Prometheus · CloudWatch 값을 한 번 받으면 다시 조회하지 않는다(진행 단계 DB만 15초, {@link #CLOSED_TTL}). 판정은 {@link TrafficRules}.
 */
@Service
public class TrafficMonitoringService {

	private static final Logger log = LoggerFactory.getLogger(TrafficMonitoringService.class);

	static final Duration UPCOMING_WINDOW = Duration.ofDays(7);

	static final int UPCOMING_LIMIT = 5;

	static final Duration ENDED_LIST_WINDOW = Duration.ofDays(7);

	static final Duration RECENT_NODE = Duration.ofMinutes(10);

	static final List<String> POOLS = List.of("system", "app", "redis");

	static final List<String> ADDON_GROUPS = List.of("KEDA", "Karpenter", "LBC", "Prometheus", "External Secrets",
			"ArgoCD");

	/** 호스팅이 끝난 이벤트의 Prometheus · AWS 값 TTL — 범위가 고정이라 사실상 다시 조회하지 않는다(실패 뒤 재시도는 보통 TTL) */
	static final Duration CLOSED_TTL = Duration.ofDays(1);

	/** 조회 결과 + 출처 기준 시각. asOf가 null이면 한 번도 성공 못 함. staleReason은 stale일 때만 */
	record Stamped<T>(T value, Instant asOf, boolean stale, StaleReason staleReason) {

		static <T> Stamped<T> fresh(T value, Instant asOf) {
			return new Stamped<>(value, asOf, false, null);
		}

		/** 값을 못 받음(갱신 실패 · 미설정) */
		static <T> Stamped<T> unavailable(T value, StaleReason reason) {
			return new Stamped<>(value, null, true, reason);
		}

		Stamped<T> asStale(StaleReason reason) {
			return new Stamped<>(value, asOf, true, reason);
		}

		MonitoringSource source() {
			return new MonitoringSource(asOf == null ? null : offset(asOf), stale, staleReason);
		}

	}

	/** 이벤트 Prometheus 캐시 키. 이벤트 시각이 바뀌면 다른 키 */
	record EventPromKey(long eventId, String tenantId, EventPhase phase, String range, LocalDateTime startAt,
			LocalDateTime endAt, LocalDateTime hostingEndAt, LocalDateTime openAt, Long stockTotal) {
	}

	record EventAwsKey(long eventId, EventPhase phase, String range, LocalDateTime startAt, LocalDateTime hostingEndAt) {
	}

	/** 조회한 범위와 결과 */
	record EventProm(TrafficWindow window, TrafficEventCollector.Metrics metrics) {
	}

	private final TrafficPlatformCollector platformCollector;

	private final TrafficEventCollector eventCollector;

	private final TrafficAwsCollector awsCollector;

	private final TenantRouter router;

	private final EventExecutionService executions;

	/** 단계 지연을 보는 기간(예정 시각부터, 1분 주기 확인과 같은 값) */
	private final Duration delayWindow;

	private final Clock clock;

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	private final RefreshCache<TrafficCatalog.Snapshot> catalogCache;

	private final RefreshCache<Stamped<TrafficPlatformCollector.Metrics>> platformCache;

	private final RefreshCache<Stamped<PlatformAws>> platformAwsCache;

	private final KeyedRefreshCache<Long, Optional<TrafficEventReader.Detail>> eventDbCache;

	private final KeyedRefreshCache<EventPromKey, Stamped<EventProm>> eventPromCache;

	private final KeyedRefreshCache<EventAwsKey, Stamped<EventAws>> eventAwsCache;

	public TrafficMonitoringService(TrafficCatalog catalog, TrafficEventReader reader,
			TrafficPlatformCollector platformCollector, TrafficEventCollector eventCollector,
			TrafficAwsCollector awsCollector, TenantRouter router, EventExecutionService executions,
			TetraProperties properties, Clock clock) {
		this.platformCollector = platformCollector;
		this.eventCollector = eventCollector;
		this.awsCollector = awsCollector;
		this.router = router;
		this.executions = executions;
		this.delayWindow = properties.alerts().delayWindow();
		this.clock = clock;
		TetraProperties.Monitoring m = properties.monitoring();
		Duration ttl = m.cacheTtl();
		Duration timeout = m.refreshTimeout();
		Duration awsTtl = m.aws().cacheTtl();
		Duration awsTimeout = m.aws().refreshTimeout();

		this.catalogCache = new RefreshCache<>("트래픽 모니터링 · DB", ttl, timeout, executor, deadline -> catalog.load(),
				(last, reason) -> last, reason -> {
					throw new IllegalStateException("트래픽 모니터링 DB 조회 실패");
				});
		this.platformCache = new RefreshCache<>("트래픽 모니터링 · Prometheus", ttl, timeout, executor,
				deadline -> stamp(platformCollector.collect(deadline, executor)), Stamped::asStale,
				reason -> Stamped.unavailable(TrafficPlatformCollector.Metrics.unavailable(), reason));
		this.platformAwsCache = new RefreshCache<>("트래픽 모니터링 · AWS", awsTtl, awsTimeout, executor,
				deadline -> stamp(awsCollector.platform()), Stamped::asStale,
				reason -> Stamped.unavailable(PlatformAws.unavailable(), reason));
		// 이벤트별 캐시는 로그 이름에 이벤트 번호(어느 이벤트가 실패하는지)
		this.eventDbCache = new KeyedRefreshCache<>(eventId -> new RefreshCache<>(
				"트래픽 모니터링 · 이벤트 DB(" + eventId + "번)", ttl, timeout, executor,
				deadline -> router.inTenant(router.findEvent(eventId), () -> reader.detail(eventId)), (last, reason) -> last,
				reason -> {
					throw new IllegalStateException("트래픽 모니터링 이벤트 DB 조회 실패");
				}));
		this.eventPromCache = new KeyedRefreshCache<>(key -> new RefreshCache<>(
				"트래픽 모니터링 · 이벤트 Prometheus(" + key.eventId() + "번)", key.phase().closed() ? CLOSED_TTL : ttl, ttl,
				timeout, executor, deadline -> stamp(collectEvent(key, deadline)), Stamped::asStale,
				reason -> Stamped.<EventProm>unavailable(null, reason)));
		this.eventAwsCache = new KeyedRefreshCache<>(key -> new RefreshCache<>(
				"트래픽 모니터링 · 이벤트 AWS(" + key.eventId() + "번)", key.phase().closed() ? CLOSED_TTL : awsTtl, awsTtl,
				awsTimeout, executor, deadline -> stamp(collectEventAws(key)), Stamped::asStale,
				reason -> Stamped.unavailable(EventAws.unavailable(), reason)));
		if (!awsCollector.configured()) {
			log.warn("MONITORING_* AWS 설정이 비어 있어 트래픽 모니터링의 AWS 값(ALB · RDS · ECS · DLQ)은 비워 둡니다");
		}
	}

	// ================================================================ GET /monitoring/platform

	public TrafficPlatformResponse platform() {
		LocalDateTime now = Kst.now(clock);
		TrafficCatalog.Snapshot db = catalogCache.get();
		Stamped<TrafficPlatformCollector.Metrics> prom = prometheus();
		Stamped<PlatformAws> aws = aws();
		TrafficPlatformCollector.Metrics metrics = prom.value();
		PlatformAws awsValue = aws.value();

		// A. 상태 요약
		List<StatusCell> summary = new ArrayList<>();
		Map<String, Integer> readyByPool = metrics.readyByPool() == null ? Map.of() : metrics.readyByPool();
		int readyNodes = readyByPool.values().stream().filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
		Integer systemReady = metrics.kubeStateMetrics() && metrics.readyByPool() != null
				? readyByPool.getOrDefault("system", 0) : null;
		Judgment cluster = TrafficRules.cluster(metrics.available(), systemReady, readyNodes, metrics.addons(),
				metrics.pendingOver5m());
		summary.add(new StatusCell(Area.CLUSTER, cluster.level(), cluster.reason(), null, null));
		summary.add(eventCell(openJudged(db, prom, now)));
		LocalDateTime dayAgo = now.minus(TrafficRules.AUTOMATION_FAILURE_WINDOW);
		List<FailureItem> recent = db.failures()
			.stream()
			.filter(f -> !f.occurredAt().isBefore(dayAgo) && f.step().automation())
			.toList();
		List<TrafficRules.Delay> delays = TrafficRules.delays(
				db.events().stream().map(MonitoredEvent::delayTarget).toList(), now, delayWindow, this::executionStarted);
		Judgment automation = TrafficRules.automation(recent, aws.asOf() == null ? null : awsValue.dlq(), delays);
		summary.add(new StatusCell(Area.AUTOMATION, automation.level(), automation.reason(), null, null));
		summary.add(platformCell(aws));

		return new TrafficPlatformResponse(prom.source(), aws.source(), summary, nodes(metrics, now),
				new TrafficPlatformResponse.Platform(addons(prom), awsValue.rds(), awsValue.services()), upcoming(db, now),
				db.failures()
					.stream()
					.map(f -> new TrafficPlatformResponse.Failure(Kst.toOffset(f.occurredAt()), f.eventId(), f.tenantId(),
							f.tenantName(), f.eventName(), f.step(), FailureReasons.summarize(f.reason())))
					.toList());
	}

	/**
	 * 자동화 칸 준비 시작 · 종료 시작 지연의 실행 여부(EventExecutionService 목록 캐시 30초 · input 캐시).
	 * 판정 시각에 걸린 이벤트가 있을 때만 불린다. 못 읽으면 null — 그 2종은 이번 응답에서 보지 않는다(실패 로그는 목록 캐시가 남긴다).
	 */
	private Boolean executionStarted(DelayStep step, TrafficRules.DelayTarget target) {
		try {
			return executions.startedFor(step, target);
		}
		catch (AwsMonitoringException ex) {
			return null;
		}
	}

	private StatusCell eventCell(List<Judged> open) {
		if (open.isEmpty()) {
			// 진행 중 = event_status OPEN이고 호스팅 종료 전(입장 중 · 마무리 포함)
			return new StatusCell(Area.EVENT, MonitoringLevel.OK, "진행 중(입장 중 · 마무리 포함) 이벤트 없음", 0, null);
		}
		Judged worst = open.get(0);
		Judgment j = worst.judgment();
		String reason = switch (j.level()) {
			case OK -> "이상 없음";
			case UNKNOWN -> j.reason();
			default -> worst.event().eventId() + "번 " + j.reason();
		};
		return new StatusCell(Area.EVENT, j.level(), reason, open.size(), worst.event().eventId());
	}

	private StatusCell platformCell(Stamped<PlatformAws> aws) {
		PlatformAws v = aws.value();
		int healthy = 0;
		int total = 0;
		Integer portalHealthy = null;
		for (ServiceStatus s : v.services()) {
			if (s.healthyTargets() != null && s.totalTargets() != null) {
				healthy += s.healthyTargets();
				total += s.totalTargets();
			}
			if ("portal".equals(s.name())) {
				portalHealthy = s.healthyTargets();
			}
		}
		Judgment j = TrafficRules.platform(aws.asOf() != null, v.rds() == null ? null : v.rds().status(),
				v.rds() == null ? null : v.rds().cpuPercent(), portalHealthy, healthy, total);
		return new StatusCell(Area.PLATFORM, j.level(), j.reason(), null, null);
	}

	private TrafficPlatformResponse.Nodes nodes(TrafficPlatformCollector.Metrics m, LocalDateTime nowKst) {
		Instant now = nowKst.atZone(Kst.ZONE).toInstant();
		Map<String, List<NodeMetric>> byPool = new LinkedHashMap<>();
		POOLS.forEach(p -> byPool.put(p, new ArrayList<>()));
		for (NodeMetric n : m.nodes()) {
			byPool.computeIfAbsent(n.pool() == null ? "other" : n.pool(), p -> new ArrayList<>()).add(n);
		}
		List<Pool> pools = new ArrayList<>();
		byPool.forEach((pool, nodes) -> {
			nodes.sort(Comparator.comparing((NodeMetric n) -> n.createdAt() == null ? Instant.MAX : n.createdAt())
				.thenComparing(NodeMetric::name));
			// 풀별 Ready 쿼리가 오류면 null. 쿼리에 없는 풀(0대 · other)은 노드 목록에서 센다
			Integer count = m.readyByPool() == null ? null
					: m.readyByPool().containsKey(pool) ? m.readyByPool().get(pool)
							: m.kubeStateMetrics() ? (int) nodes.stream().filter(NodeMetric::ready).count() : null;
			int added = (int) nodes.stream().filter(n -> recent(n, now)).count();
			pools.add(new Pool(pool, count, added, stat(nodes.stream().map(NodeMetric::cpu).toList()),
					stat(nodes.stream().map(NodeMetric::memory).toList()),
					nodes.stream()
						.map(n -> new TrafficPlatformResponse.Node(n.name(), n.type(), n.ready(), n.cpu(), n.memory(),
								n.createdAt() == null ? null : offset(n.createdAt()), recent(n, now)))
						.toList()));
		});
		return new TrafficPlatformResponse.Nodes(m.cpu(), m.memory(),
				new TrafficPlatformResponse.History((int) TrafficPlatformCollector.HISTORY_STEP.toSeconds(), m.history()),
				pools);
	}

	private static boolean recent(NodeMetric n, Instant now) {
		return n.createdAt() != null && Duration.between(n.createdAt(), now).compareTo(RECENT_NODE) < 0;
	}

	private static TrafficPlatformResponse.Stat stat(List<Double> values) {
		List<Double> present = values.stream().filter(Objects::nonNull).toList();
		if (present.isEmpty()) {
			return new TrafficPlatformResponse.Stat(null, null);
		}
		double avg = present.stream().mapToDouble(Double::doubleValue).average().orElse(0);
		double max = present.stream().mapToDouble(Double::doubleValue).max().orElse(0);
		return new TrafficPlatformResponse.Stat(PromValues.round(avg, 4), PromValues.round(max, 4));
	}

	/** 그룹 6개 고정. Prometheus 자신은 API stale로 판단. Prometheus를 못 읽었으면 healthy null */
	private TrafficPlatformResponse.Addons addons(Stamped<TrafficPlatformCollector.Metrics> prom) {
		// 애드온 쿼리 오류(addons null)면 Prometheus 그룹만 판단하고 나머지는 모름
		boolean available = prom.value().available() && prom.value().addons() != null;
		Map<String, List<TrafficRules.AddonMember>> byGroup = (available ? prom.value().addons()
				: List.<TrafficRules.AddonMember>of())
			.stream()
			.collect(Collectors.groupingBy(TrafficRules.AddonMember::group, LinkedHashMap::new, Collectors.toList()));
		List<AddonGroup> groups = new ArrayList<>();
		for (String name : ADDON_GROUPS) {
			if ("Prometheus".equals(name)) {
				groups.add(new AddonGroup(name, !prom.stale(), List.of()));
				continue;
			}
			List<TrafficRules.AddonMember> members = byGroup.getOrDefault(name, List.of());
			List<AddonMember> problems = members.stream()
				.filter(a -> !a.healthy())
				.map(a -> new AddonMember(a.namespace(), a.name(), a.available(), a.desired()))
				.toList();
			groups.add(new AddonGroup(name, available ? problems.isEmpty() : null, available ? problems : List.of()));
		}
		Integer healthy = available ? (int) groups.stream().filter(g -> Boolean.TRUE.equals(g.healthy())).count() : null;
		return new TrafficPlatformResponse.Addons(healthy, groups.size(), groups);
	}

	/** 7일 안에 시작할 SCHEDULED 이벤트(시작 순, 5개). 실패 상태(배포 · 호스팅 시작 실패)는 열리지 않으므로 뺀다 — 실패 목록이 보여 준다 */
	private static List<TrafficPlatformResponse.Upcoming> upcoming(TrafficCatalog.Snapshot db, LocalDateTime now) {
		return db.events()
			.stream()
			.filter(e -> e.eventStatus() == EventStatus.SCHEDULED && e.failedStatus() == null && e.startAt().isAfter(now)
					&& e.startAt().isBefore(now.plus(UPCOMING_WINDOW)))
			.sorted(Comparator.comparing(MonitoredEvent::startAt).thenComparing(MonitoredEvent::eventId))
			.limit(UPCOMING_LIMIT)
			.map(e -> new TrafficPlatformResponse.Upcoming(e.eventId(), e.tenantId(), e.tenantName(), e.name(),
					Kst.toOffset(e.prepareAt()), Kst.toOffset(e.entryAt()), Kst.toOffset(e.startAt()),
					Kst.toOffset(e.hostingEndAt())))
			.toList();
	}

	// ================================================================ GET /monitoring/events

	public TrafficEventsResponse events() {
		LocalDateTime now = Kst.now(clock);
		TrafficCatalog.Snapshot db = catalogCache.get();
		Stamped<TrafficPlatformCollector.Metrics> prom = prometheus();
		List<TrafficEventsResponse.Item> items = new ArrayList<>();
		List<Judged> open = openJudged(db, prom, now);
		open.forEach(j -> items.add(item(j.event(), Group.OPEN, j.judgment(), now)));
		List<MonitoredEvent> today = db.events()
			.stream()
			.filter(e -> e.eventStatus() == EventStatus.SCHEDULED && e.startAt().toLocalDate().equals(now.toLocalDate()))
			// DB 상태는 SCHEDULED여도 hosting_end_at이 지났으면(호스팅 안 된 DEPLOY_FAILED 등) 최근 종료에만 넣는다
			.filter(e -> endedAt(e, now) == null)
			.sorted(Comparator.comparing(MonitoredEvent::entryAt).thenComparing(MonitoredEvent::eventId))
			.toList();
		today.forEach(e -> items.add(item(e, Group.TODAY, null, now)));
		LocalDateTime endedSince = now.minus(ENDED_LIST_WINDOW);
		// DB 상태가 OPEN이어도 호스팅 종료 뒤(WF4 결과 전)면 최근 종료
		List<MonitoredEvent> ended = db.events()
			.stream()
			.filter(e -> e.eventStatus() != EventStatus.OPEN || e.phase(now).closed())
			.filter(e -> {
				LocalDateTime end = endedAt(e, now);
				return end != null && !end.isBefore(endedSince);
			})
			.sorted(Comparator.comparing((MonitoredEvent e) -> endedAt(e, now)).reversed()
				.thenComparing(MonitoredEvent::eventId))
			.toList();
		ended.forEach(e -> items.add(item(e, Group.ENDED, null, now)));
		// 같은 이벤트는 한 번만(앞 그룹 우선)
		Set<Long> seen = new HashSet<>();
		items.removeIf(i -> !seen.add(i.eventId()));
		Long defaultEventId = items.isEmpty() ? null : items.get(0).eventId();
		return new TrafficEventsResponse(defaultEventId, items);
	}

	/**
	 * 최근 종료 목록의 기준 시각: 배포 실패는 실패 시각(없으면 입장 예정 시각), 준비 실패는 입장 예정 시각,
	 * 호스팅 종료 뒤(종료 처리 중 · 종료 · 종료 실패)는 hosting_end_at, 호스팅 실패는 hosting_end_at이 지났을 때만 hosting_end_at.
	 * 해당 없으면 null(목록 위치는 시각대로)
	 */
	private static LocalDateTime endedAt(MonitoredEvent e, LocalDateTime now) {
		return switch (e.phase(now)) {
			case DEPLOY_FAILED -> e.failedAt() != null ? e.failedAt() : e.entryAt();
			case HOSTING_FAILED -> e.hostingEndAt() != null && !now.isBefore(e.hostingEndAt()) ? e.hostingEndAt() : null;
			case PREPARE_FAILED -> e.entryAt();
			case ENDING, ENDED, END_FAILED -> e.hostingEndAt() != null ? e.hostingEndAt() : e.startAt();
			default -> null;
		};
	}

	private static TrafficEventsResponse.Item item(MonitoredEvent e, Group group, Judgment judgment, LocalDateTime now) {
		return new TrafficEventsResponse.Item(e.eventId(), e.tenantId(), e.tenantName(), e.name(), group, e.phase(now),
				judgment == null ? null : judgment.level(), judgment == null ? null : judgment.reason(),
				Kst.toOffset(e.prepareAt()), Kst.toOffset(e.entryAt()), Kst.toOffset(e.startAt()),
				Kst.toOffset(e.hostingEndAt()), e.eventStatus(), e.hostingStatus(), Kst.toOffset(e.failedAt()));
	}

	private record Judged(MonitoredEvent event, Judgment judgment) {
	}

	/**
	 * 진행 중 이벤트 판정, 나쁜 순 → 시작 늦은 순(상태 요약 이벤트 칸 · 드롭다운 기본 선택이 같이 쓴다).
	 * 진행 중 = event_status OPEN이고 호스팅 종료 전(입장 중 · 진행 중 · 마무리). 호스팅 종료 뒤에는 WF4가 EXPIRED로 바꾸기 전에도 뺀다
	 * (이벤트 12: 10:25 호스팅 종료 화면에서도 "진행 중 1"이던 것, 2026-10-28)
	 */
	private static List<Judged> openJudged(TrafficCatalog.Snapshot db, Stamped<TrafficPlatformCollector.Metrics> prom,
			LocalDateTime now) {
		return db.events()
			.stream()
			.filter(e -> e.eventStatus() == EventStatus.OPEN && !e.phase(now).closed())
			.map(e -> new Judged(e, judge(e, prom.value().signals(e.eventId()), now)))
			.sorted(Comparator.comparing((Judged j) -> j.judgment().level().severity())
				.reversed()
				.thenComparing(j -> j.event().startAt(), Comparator.reverseOrder())
				.thenComparing(j -> j.event().eventId()))
			.toList();
	}

	private static Judgment judge(MonitoredEvent e, EventSignals signals, LocalDateTime now) {
		EventPhase phase = e.phase(now);
		if (phase == EventPhase.SCHEDULED || phase == EventPhase.PRE_ENTRY) {
			return TrafficRules.preEntry(signals);
		}
		return TrafficRules.event(signals, cursorExpected(phase));
	}

	/** 커서가 움직여야 하는 상태: 진행 중(시작 ~ end_at)만. 입장 중은 아직, 마무리는 이벤트가 끝나 멈추는 게 정상 */
	private static boolean cursorExpected(EventPhase phase) {
		return phase == EventPhase.LIVE;
	}

	// ================================================================ GET /monitoring/events/{eventId}

	public TrafficEventResponse event(Long eventId, String range) {
		if (!TrafficWindow.isKnown(range)) {
			throw new ApiException(ErrorCode.INVALID_INPUT);
		}
		RoutedEvent routed = router.findEvent(eventId);
		if (!routed.tenant().isReady()) {
			throw new ApiException(ErrorCode.TENANT_NOT_READY);
		}
		TrafficEventReader.Detail detail = eventDbCache.get(eventId)
			.orElseThrow(() -> new ApiException(ErrorCode.EVENT_NOT_FOUND));
		Event event = detail.event();
		LocalDateTime now = Kst.now(clock);
		boolean aggregated = detail.summary() != null && detail.summary().getHistoryGeneratedAt() != null;
		EventPhase phase = EventPhase.of(event.getStartAt(), event.getEndAt(), event.getHostingEndAt(),
				event.getEventStatus(), event.getHostingStatus(), aggregated, now);
		LocalDateTime openAt = detail.logs()
			.stream()
			.filter(l -> l.getStatusType() == StatusType.EVENT && "OPEN".equals(l.getNewStatus()))
			.map(EventStatusLog::getChangedAt)
			.min(Comparator.naturalOrder())
			.orElse(null);

		Stamped<EventProm> prom = new Stamped<>(null, null, false, null);
		Stamped<EventAws> aws = new Stamped<>(EventAws.unavailable(), null, false, null);
		if (phase.hasMetrics()) {
			prom = platformCollector.configured()
					? eventPromCache.get(new EventPromKey(eventId, routed.tenantId(), phase, range, event.getStartAt(),
							event.getEndAt(), event.getHostingEndAt(), openAt, event.getStockCount()))
					: Stamped.<EventProm>unavailable(null, StaleReason.SOURCE);
			aws = awsCollector.configured()
					? eventAwsCache.get(new EventAwsKey(eventId, phase, range, event.getStartAt(), event.getHostingEndAt()))
					: Stamped.unavailable(EventAws.unavailable(), StaleReason.SOURCE);
		}
		TrafficEventCollector.Metrics metrics = prom.value() == null ? null : prom.value().metrics();
		TrafficWindow window = prom.value() != null ? prom.value().window()
				: TrafficWindow.of(phase, range, event.getStartAt(), event.getHostingEndAt(), clock.instant(),
						TrafficWindow.PROMETHEUS_MIN_STEP);

		TrafficEventResponse.Info info = new TrafficEventResponse.Info(eventId, routed.tenantId(), routed.tenant().getName(),
				event.getName(), phase, event.getEventStatus(), event.getHostingStatus(),
				Kst.toOffset(EventPhase.prepareAt(event.getStartAt())), Kst.toOffset(EntryOpen.at(event.getStartAt())),
				Kst.toOffset(event.getStartAt()), Kst.toOffset(event.getHostingEndAt()), Kst.toOffset(event.getEndAt()),
				event.getStockCount());
		List<TrafficEventResponse.Step> steps = TrafficSteps.build(event, detail.logs(), detail.summary(), phase, now,
				metrics == null ? null : metrics.prepareSeconds());
		TrafficEventResponse.Range rangeView = window == null ? null
				: new TrafficEventResponse.Range(window.key(), window.options(), offset(window.from()),
						offset(window.to()), (int) window.step().toSeconds(), info.entryAt(), info.startAt());
		return new TrafficEventResponse(prom.source(), aws.source(), info, steps,
				kpi(phase, metrics, event, detail, now), rangeView,
				// 그래프 구역만 가공에 실패하면 charts만 null(stale 아님 → 화면 "그래프를 만들지 못했어요")
				metrics == null || metrics.charts() == null ? null : charts(phase, metrics.charts(), aws.value()));
	}

	private static Kpi kpi(EventPhase phase, TrafficEventCollector.Metrics m, Event event,
			TrafficEventReader.Detail detail, LocalDateTime now) {
		switch (phase) {
			case PRE_ENTRY, ENTRY_OPEN, LIVE, WRAP_UP -> {
				if (m == null || m.live() == null) {
					return new Kpi(MonitoringLevel.UNKNOWN, TrafficRules.Judgment.NO_DATA.reason(), List.of(), null, null);
				}
				Live live = m.live();
				Judgment j = phase.entryOpened() ? TrafficRules.event(m.signals(), cursorExpected(phase))
						: TrafficRules.preEntry(m.signals());
				List<String> warn = TrafficRules.kpiWarnings(live.errorRatio(),
						live.p95Ms() == null ? null : live.p95Ms() / 1000.0, live.podsReady(), live.podsMax(),
						live.redisMemoryRatio(), live.redisRestarts() == null ? null : live.redisRestarts().doubleValue(),
						live.redisUp());
				return new Kpi(j.level(), j.reason(), warn, live, null);
			}
			case ENDING, ENDED, END_FAILED -> {
				if (m == null || m.full() == null) {
					return new Kpi(null, null, List.of(), null, null);
				}
				Long success = detail.summary() == null ? null : detail.summary().getSuccessCount();
				// 최종값은 입장 ~ 이벤트 종료(end_at)만 — 그래프 범위(입장 −10분 ~ 호스팅 종료 +10분)의 웜업 · 정리는 넣지 않는다
				return new Kpi(null, null, List.of(), null, TrafficEventCollector.summary(m.full(),
						instant(EntryOpen.at(event.getStartAt())), event.getEndAt() == null ? null : instant(event.getEndAt()),
						event.getStockCount(), success));
			}
			default -> {
				return null;
			}
		}
	}

	private static Charts charts(EventPhase phase, PromSeries s, EventAws aws) {
		boolean ended = phase.closed();
		return new Charts(s.timestamps(),
				new TrafficEventResponse.Traffic(s.get(TrafficEventCollector.RPS)),
				new TrafficEventResponse.Errors(s.get(TrafficEventCollector.ERROR_RATIO), aws.albErrorRatio(),
						aws.eventTargetErrorRatio()),
				new TrafficEventResponse.Latency(s.get(TrafficEventCollector.P95_ISSUANCE),
						s.get(TrafficEventCollector.P95_TICKET)),
				new TrafficEventResponse.Pods(s.get(TrafficEventCollector.READY), s.get(TrafficEventCollector.PENDING),
						PromValues.toInt(pick(s.get(TrafficEventCollector.HPA_MIN), ended)),
						PromValues.toInt(pick(s.get(TrafficEventCollector.HPA_MAX), ended))),
				new TrafficEventResponse.Progress(s.get(TrafficEventCollector.WAITING), s.get(TrafficEventCollector.STOCK)),
				new TrafficEventResponse.Admission(s.get(TrafficEventCollector.ADMITTED),
						s.get(TrafficEventCollector.CURSOR)),
				new TrafficEventResponse.Redis(s.get(TrafficEventCollector.REDIS_MEMORY),
						s.get(TrafficEventCollector.REDIS_CPU), s.get(TrafficEventCollector.REDIS_RESTARTS)),
				new TrafficEventResponse.Resources(s.get(TrafficEventCollector.CPU), s.get(TrafficEventCollector.MEMORY),
						s.get(TrafficEventCollector.CPU_REQUEST), s.get(TrafficEventCollector.MEMORY_REQUEST)),
				new TrafficEventResponse.Rds(aws.rdsCpu(), aws.rdsConnections()));
	}

	/** 진행 중은 마지막 값, 호스팅 종료 뒤는 구간 최대 */
	private static Double pick(List<Double> values, boolean max) {
		Double out = null;
		for (Double v : values) {
			if (v != null) {
				out = max && out != null ? Math.max(out, v) : v;
			}
		}
		return out;
	}

	// ================================================================ 갱신

	private Stamped<TrafficPlatformCollector.Metrics> prometheus() {
		return platformCollector.configured() ? platformCache.get()
				: Stamped.unavailable(TrafficPlatformCollector.Metrics.unavailable(), StaleReason.SOURCE);
	}

	private Stamped<PlatformAws> aws() {
		return awsCollector.configured() ? platformAwsCache.get()
				: Stamped.unavailable(PlatformAws.unavailable(), StaleReason.SOURCE);
	}

	private EventProm collectEvent(EventPromKey key, long deadlineNanos) {
		Instant now = clock.instant();
		TrafficWindow window = TrafficWindow.of(key.phase(), key.range(), key.startAt(), key.hostingEndAt(), now,
				TrafficWindow.PROMETHEUS_MIN_STEP);
		TrafficWindow full = key.phase().closed()
				? TrafficWindow.of(key.phase(), "full", key.startAt(), key.hostingEndAt(), now,
						TrafficWindow.PROMETHEUS_MIN_STEP)
				: null;
		Instant prepareAt = key.openAt() != null && (key.phase().entryOpened() || key.phase().closed())
				? instant(key.openAt()) : null;
		TrafficEventCollector.Metrics metrics = eventCollector.collect(new TrafficEventCollector.Request(key.eventId(),
				key.tenantId(), key.phase(), window, full, prepareAt, key.stockTotal()), deadlineNanos, executor);
		return new EventProm(window, metrics);
	}

	private EventAws collectEventAws(EventAwsKey key) {
		TrafficWindow window = TrafficWindow.of(key.phase(), key.range(), key.startAt(), key.hostingEndAt(),
				clock.instant(), TrafficWindow.AWS_MIN_STEP);
		return awsCollector.event(key.eventId(), window);
	}

	private <T> Stamped<T> stamp(T value) {
		return Stamped.fresh(value, clock.instant());
	}

	private static OffsetDateTime offset(Instant instant) {
		return Kst.toOffset(LocalDateTime.ofInstant(instant, Kst.ZONE));
	}

	private static Instant instant(LocalDateTime kst) {
		return kst.atZone(Kst.ZONE).toInstant();
	}

	@PreDestroy
	void shutdown() {
		executor.shutdownNow();
	}

}
