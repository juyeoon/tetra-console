package com.tetra.ops.monitoring;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.tetra.ops.common.error.ApiException;
import com.tetra.ops.common.error.ErrorCode;
import com.tetra.ops.common.time.Kst;
import com.tetra.ops.config.TetraProperties;
import com.tetra.ops.domain.ApplicationStatus;
import com.tetra.ops.domain.tenant.Event;
import com.tetra.ops.monitoring.AwsMonitoringGateway.ExecutionItem;
import com.tetra.ops.monitoring.EventExecutionsResponse.Execution;
import com.tetra.ops.monitoring.EventExecutionsResponse.Failure;
import com.tetra.ops.monitoring.EventExecutionsResponse.Flow;
import com.tetra.ops.monitoring.EventExecutionsResponse.State;
import com.tetra.ops.monitoring.EventExecutionsResponse.StateKind;
import com.tetra.ops.monitoring.EventExecutionsResponse.StateStatus;
import com.tetra.ops.monitoring.MonitoringSource.StaleReason;
import com.tetra.ops.monitoring.TrafficMonitoringService.Stamped;
import com.tetra.ops.tenancy.TenantRouter;
import com.tetra.ops.tenancy.TenantRouter.RoutedEvent;

import jakarta.annotation.PreDestroy;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OPS-05 자동화 실행 상세 — 이벤트의 Step Functions 실행 · 단계별 시간 · 실패 단계(명세 5-2, 2026-10-29).
 * 이벤트 → 실행 찾기:
 * - WF1-2(배포): 실행 이름 approve-{event_id}, 재실행 -rN(리스너와 같은 규칙)
 * - WF2-1 · WF2-2 · WF4: 스케줄러가 시작해 이름이 UUID → 시작 시각이 [승인 − 1분, 호스팅 종료 + 1일]인 실행만 DescribeExecution의
 *   input(event_id · tenant_id)으로 고른다. tenant_id가 다르면 뺀다(리스너와 같은 규칙)
 * 캐시 3단(Pod마다, 공유 상태 아님):
 * - ListExecutions: 상태 머신별 하나를 모든 이벤트가 같이 쓴다({@code MONITORING_SFN_LIST_TTL} 30초). 최근 {@link #LIST_LOOKBACK}만
 * - DescribeExecution: ARN → (event_id, tenant_id)는 바뀌지 않아 영구(개수 상한 {@link #OWNER_CACHE_MAX})
 * - GetExecutionHistory: (ARN, status, redriveCount) 키로 끝난 실행만 보관(상한 {@link #HISTORY_CACHE_MAX}). 실행 중이면 매번 받는다.
 *   재실행(redrive)되면 status · redriveCount가 바뀌어 다른 키
 * 이벤트 응답은 이벤트별 {@link RefreshCache}(Prometheus · DB와 같은 15초, single-flight, 실패하면 마지막 값 + stale).
 * 응답에 실행 입력 · 출력 · Task parameters · ARN을 넣지 않는다(이름 · 상태 · 시각 · 가린 원인만).
 */
@Service
public class EventExecutionService {

	private static final Logger log = LoggerFactory.getLogger(EventExecutionService.class);

	/** 목록을 받는 기간 — 최신순으로 이보다 오래된 실행이 나오면 멈춘다(실행 기록 보관은 90일) */
	static final Duration LIST_LOOKBACK = Duration.ofDays(14);

	/** 상태 머신 하나에서 받는 실행 수 상한 */
	static final int LIST_MAX = 1_000;

	/** 승인보다 조금 앞선 시작도 받는다(시각 오차) */
	static final Duration BEFORE_APPROVAL = Duration.ofMinutes(1);

	/** 호스팅 종료 뒤 수동 재실행까지 찾는 범위 */
	static final Duration AFTER_HOSTING_END = Duration.ofDays(1);

	static final int OWNER_CACHE_MAX = 5_000;

	static final int HISTORY_CACHE_MAX = 200;

	/** 기록 상한(3페이지). WF4 최악 약 900개 */
	static final int HISTORY_MAX_EVENTS = 3_000;

	/** 리스너(StepFunctionsResultHandler)와 같은 규칙. event_id는 INT UNSIGNED라 10자리 이하 */
	private static final Pattern APPROVE_NAME = Pattern.compile("^approve-(\\d{1,10})(-r\\d+)?$");

	private static final Map<Flow, String> WORKFLOWS = Map.of(Flow.DEPLOY, "WF1-2", Flow.HOSTING_START, "WF2-1",
			Flow.PREPARE, "WF2-2", Flow.END, "WF4");

	/** 이벤트 캐시 키. 승인 · 호스팅 종료 시각이 바뀌면 다른 키 */
	record EventKey(long eventId, String tenantId, LocalDateTime processedAt, LocalDateTime hostingEndAt) {
	}

	/** DescribeExecution input에서 고른 값. 못 읽으면 둘 다 null */
	record Owner(Long eventId, String tenantId) {
	}

	record HistoryKey(String executionArn, String status, int redriveCount) {
	}

	private final AwsMonitoringGateway aws;

	private final TrafficEventReader reader;

	private final TenantRouter router;

	private final JsonMapper jsonMapper;

	private final Clock clock;

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	/** 흐름 → 상태 머신 ARN. tetra.sfn 값이 ARN 형식이 아니면 빈 맵(항상 stale) */
	private final Map<Flow, String> stateMachines;

	private final Map<Flow, RefreshCache<Stamped<List<ExecutionItem>>>> listCaches = new EnumMap<>(Flow.class);

	private final KeyedRefreshCache<EventKey, Stamped<List<Execution>>> eventCache;

	private final Map<String, Owner> owners = lru(OWNER_CACHE_MAX);

	private final Map<HistoryKey, SfnHistories.Summary> histories = lru(HISTORY_CACHE_MAX);

	@Autowired
	public EventExecutionService(AwsMonitoringGateway aws, TrafficEventReader reader, TenantRouter router,
			JsonMapper jsonMapper, TetraProperties properties, Clock clock) {
		this(aws, reader, router, jsonMapper, properties.sfn(), properties.monitoring().cacheTtl(),
				properties.monitoring().aws().sfnListTtl(), properties.monitoring().aws().refreshTimeout(), clock);
	}

	/** 테스트용: 캐시 시간을 직접 준다 */
	EventExecutionService(AwsMonitoringGateway aws, TrafficEventReader reader, TenantRouter router,
			JsonMapper jsonMapper, TetraProperties.Sfn sfn, Duration eventTtl, Duration listTtl, Duration refreshTimeout,
			Clock clock) {
		this.aws = aws;
		this.reader = reader;
		this.router = router;
		this.jsonMapper = jsonMapper;
		this.clock = clock;
		this.stateMachines = stateMachines(sfn);
		if (stateMachines.isEmpty()) {
			log.warn("SFN_APPROVE_STATE_MACHINE_ARN이 상태 머신 ARN 형식이 아니라 자동화 실행 상세는 비워 둡니다");
		}
		stateMachines.forEach((flow, arn) -> listCaches.put(flow,
				new RefreshCache<>("자동화 실행 목록 · " + WORKFLOWS.get(flow), listTtl, refreshTimeout, executor,
						deadline -> Stamped.fresh(aws.executions(arn, clock.instant().minus(LIST_LOOKBACK), LIST_MAX),
								clock.instant()),
						Stamped::asStale, reason -> Stamped.unavailable(null, reason))));
		this.eventCache = new KeyedRefreshCache<>(key -> new RefreshCache<>(
				"자동화 실행 상세(" + key.eventId() + "번)", eventTtl, refreshTimeout, executor, deadline -> lookup(key),
				Stamped::asStale, reason -> Stamped.unavailable(List.of(), reason)));
	}

	/** 승인(WF1-2 · tetra.sfn.approve-state-machine-arn)과 같은 계정 · 리전에서 수명 주기 상태 머신 이름(tetra.sfn.lifecycle)으로 ARN을 만든다 */
	static Map<Flow, String> stateMachines(TetraProperties.Sfn sfn) {
		String approve = sfn.approveStateMachineArn();
		int at = approve == null ? -1 : approve.indexOf(":stateMachine:");
		if (at < 0) {
			return Map.of();
		}
		String prefix = approve.substring(0, at + ":stateMachine:".length());
		Map<Flow, String> arns = new EnumMap<>(Flow.class);
		arns.put(Flow.DEPLOY, approve);
		arns.put(Flow.HOSTING_START, prefix + sfn.lifecycle().hostingStartName());
		arns.put(Flow.PREPARE, prefix + sfn.lifecycle().eventOpenName());
		arns.put(Flow.END, prefix + sfn.lifecycle().eventEndName());
		return arns;
	}

	// ================================================================ GET /monitoring/events/{eventId}/executions

	public EventExecutionsResponse executions(Long eventId) {
		RoutedEvent routed = router.findEvent(eventId);
		if (!routed.tenant().isReady()) {
			throw new ApiException(ErrorCode.TENANT_NOT_READY);
		}
		Event event = router.inTenant(routed, () -> reader.detail(eventId))
			.map(TrafficEventReader.Detail::event)
			.orElseThrow(() -> new ApiException(ErrorCode.EVENT_NOT_FOUND));
		if (event.getApplicationStatus() != ApplicationStatus.APPROVED || event.getProcessedAt() == null) {
			// 승인 전에는 실행이 없다 — Step Functions를 부르지 않는다
			return new EventExecutionsResponse(new MonitoringSource(offset(clock.instant()), false, null), eventId,
					List.of());
		}
		return respond(new EventKey(eventId, routed.tenantId(), event.getProcessedAt(), event.getHostingEndAt()));
	}

	EventExecutionsResponse respond(EventKey key) {
		if (stateMachines.isEmpty()) {
			return new EventExecutionsResponse(new MonitoringSource(null, true, StaleReason.SOURCE), key.eventId(),
					List.of());
		}
		Stamped<List<Execution>> result = eventCache.get(key);
		return new EventExecutionsResponse(result.source(), key.eventId(), result.value());
	}

	/** 이벤트 하나의 실행 전부(흐름 순, 같은 흐름은 시작 순). 목록이 stale이면 결과도 stale */
	private Stamped<List<Execution>> lookup(EventKey key) {
		List<Execution> executions = new ArrayList<>();
		Instant asOf = clock.instant();
		boolean stale = false;
		StaleReason reason = null;
		for (Flow flow : Flow.values()) {
			Stamped<List<ExecutionItem>> listed = listCaches.get(flow).get();
			if (listed.value() == null) {
				throw new AwsMonitoringException("ListExecutions 실패(" + WORKFLOWS.get(flow) + ")");
			}
			if (listed.stale()) {
				stale = true;
				reason = listed.staleReason();
				if (listed.asOf() != null && listed.asOf().isBefore(asOf)) {
					asOf = listed.asOf();
				}
			}
			List<ExecutionItem> mine = listed.value()
				.stream()
				.filter(item -> item.startDate() != null && belongs(flow, item, key))
				.sorted(Comparator.comparing(ExecutionItem::startDate))
				.toList();
			for (int i = 0; i < mine.size(); i++) {
				executions.add(execution(flow, i + 1, mine.get(i)));
			}
		}
		return new Stamped<>(executions, asOf, stale, reason);
	}

	private boolean belongs(Flow flow, ExecutionItem item, EventKey key) {
		if (flow == Flow.DEPLOY) {
			Matcher m = APPROVE_NAME.matcher(item.name());
			return m.matches() && Long.parseLong(m.group(1)) == key.eventId();
		}
		Instant from = instant(key.processedAt()).minus(BEFORE_APPROVAL);
		if (item.startDate().isBefore(from)) {
			return false;
		}
		if (key.hostingEndAt() != null && item.startDate().isAfter(instant(key.hostingEndAt()).plus(AFTER_HOSTING_END))) {
			return false;
		}
		Owner owner = owner(item.executionArn());
		return owner.eventId() != null && owner.eventId() == key.eventId()
				&& (owner.tenantId() == null || owner.tenantId().equals(key.tenantId()));
	}

	private Owner owner(String executionArn) {
		Owner cached = owners.get(executionArn);
		if (cached != null) {
			return cached;
		}
		Owner owner = parseOwner(aws.executionInput(executionArn));
		owners.put(executionArn, owner);
		return owner;
	}

	/** input의 event_id(숫자 · 숫자 문자열)와 tenant_id(선택) — LifecycleResultHandler와 같은 규칙. 나머지 키는 읽지 않는다 */
	Owner parseOwner(String input) {
		if (input == null) {
			return new Owner(null, null);
		}
		JsonNode root;
		try {
			root = jsonMapper.readTree(input);
		}
		catch (JacksonException ex) {
			return new Owner(null, null);
		}
		JsonNode id = root.path("event_id");
		Long eventId = null;
		if (id.isIntegralNumber() && id.asLong() > 0) {
			eventId = id.asLong();
		}
		else if (id.isString() && id.asString().matches("\\d{1,10}")) {
			eventId = Long.valueOf(id.asString());
		}
		JsonNode tenant = root.path("tenant_id");
		return new Owner(eventId, eventId != null && tenant.isString() ? tenant.asString() : null);
	}

	private Execution execution(Flow flow, int attempt, ExecutionItem item) {
		SfnHistories.Summary summary = history(item);
		Instant now = clock.instant();
		Instant stoppedAt = summary.stoppedAt();
		SfnHistories.Failure f = summary.failure();
		Failure failure = f == null ? null : new Failure(f.state(), f.error(), f.reason(), f.cause());
		List<State> states = summary.rows()
			.stream()
			.map(r -> new State(StateKind.valueOf(r.kind().name()), r.names(), r.count(), offset(r.startedAt()),
					offset(r.endedAt()), seconds(r.startedAt(), r.endedAt() == null ? now : r.endedAt()), r.retries(),
					StateStatus.valueOf(r.status().name())))
			.toList();
		return new Execution(flow, WORKFLOWS.get(flow), item.name(), attempt, summary.status(),
				offset(item.startDate()), offset(stoppedAt), seconds(item.startDate(), stoppedAt == null ? now : stoppedAt),
				item.redriveCount(), failure, states, summary.truncated());
	}

	/** 끝난 실행은 (ARN, status, redriveCount)로 보관, 진행 중(재실행 대기 포함)이면 매번 받는다 */
	private SfnHistories.Summary history(ExecutionItem item) {
		boolean finished = !"RUNNING".equals(item.status()) && !"PENDING_REDRIVE".equals(item.status());
		HistoryKey key = new HistoryKey(item.executionArn(), item.status(), item.redriveCount());
		if (finished) {
			SfnHistories.Summary cached = histories.get(key);
			if (cached != null) {
				return cached;
			}
		}
		SfnHistories.Summary summary = SfnHistories.summarize(
				aws.executionHistory(item.executionArn(), HISTORY_MAX_EVENTS), item.status(), item.stopDate());
		if (finished) {
			histories.put(key, summary);
		}
		return summary;
	}

	private static Long seconds(Instant from, Instant to) {
		return from == null || to == null ? null : Math.max(0, Duration.between(from, to).toSeconds());
	}

	private static OffsetDateTime offset(Instant instant) {
		return instant == null ? null : Kst.toOffset(LocalDateTime.ofInstant(instant, Kst.ZONE));
	}

	private static Instant instant(LocalDateTime kst) {
		return kst.atZone(Kst.ZONE).toInstant();
	}

	/** 접근 순 LRU(동기화). 외부 호출은 맵 잠금 밖에서 한다 */
	private static <K, V> Map<K, V> lru(int max) {
		return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {

			@Override
			protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
				return size() > max;
			}

		});
	}

	@PreDestroy
	void shutdown() {
		executor.shutdownNow();
	}

}
