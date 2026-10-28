package com.tetra.ops.monitoring;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.tetra.ops.common.time.Kst;
import com.tetra.ops.domain.StatusType;
import com.tetra.ops.domain.platform.EventRegistry;
import com.tetra.ops.domain.platform.EventRegistryRepository;
import com.tetra.ops.domain.platform.OperatorNotification;
import com.tetra.ops.domain.platform.OperatorNotificationRepository;
import com.tetra.ops.domain.platform.Tenant;
import com.tetra.ops.domain.platform.TenantRepository;
import com.tetra.ops.domain.tenant.Event;
import com.tetra.ops.domain.tenant.EventStatusLog;
import com.tetra.ops.tenancy.TenantIds;
import com.tetra.ops.tenancy.TenantRouter;

/**
 * OPS-05 DB 갱신 1회(명세 5-3): 모니터링 대상 이벤트 목록 + 최근 7일 자동화 실패 · 긴급 알림. 캐시는 TrafficMonitoringService(15초).
 * - 이벤트: registry 후보(승인됨, 호스팅이 안 끝났거나 최근 시작)에 테넌트 스키마 event의 end_at · hosting_end_at · stock_count와
 *   집계 완료 여부(event_result_summary)를 붙인다. READY가 아닌 테넌트는 스키마로 들어가지 않는다(null · 집계 전).
 * - 실패: URGENT 운영자 알림이 기준(공용 스키마 한 번). HOSTING · EVENT 알림은 같은 이벤트의 *_FAILED 상태 로그(±5분, 가장 가까운 것)로
 *   단계 · 사유를 정확히 바꾼다. 알림과 짝이 없는 *_FAILED 로그도 넣는다(§7.13상 모든 *_FAILED에 URGENT 알림이 남지만 빠뜨리지 않게).
 */
@Component
class TrafficCatalog {

	private static final Logger log = LoggerFactory.getLogger(TrafficCatalog.class);

	/**
	 * 호스팅이 끝난 이벤트는 시작이 이 기간 안인 것만 본다(최근 7일 종료 · 실패 목록용).
	 * TODO(미정): registry에 hosting_end_at이 없어 시작 기준으로 자른다. 호스팅이 시작 뒤 30일을 넘게 이어지는 이벤트가 생기면 늘린다
	 */
	static final Duration CANDIDATE_START_WINDOW = Duration.ofDays(30);

	static final Duration FAILURE_WINDOW = Duration.ofDays(7);

	/** 알림과 상태 로그를 같은 사건으로 보는 시각 차 */
	static final Duration LOG_MATCH = Duration.ofMinutes(5);

	record FailureItem(LocalDateTime occurredAt, Long eventId, String tenantId, String tenantName, String eventName,
			FailureStep step, String reason) {
	}

	record Snapshot(LocalDateTime loadedAt, List<MonitoredEvent> events, List<FailureItem> failures) {

		Optional<MonitoredEvent> event(Long eventId) {
			return events.stream().filter(e -> e.eventId().equals(eventId)).findFirst();
		}

	}

	private final EventRegistryRepository registries;

	private final OperatorNotificationRepository notifications;

	private final TenantRepository tenants;

	private final TenantRouter router;

	private final TrafficEventReader reader;

	private final Clock clock;

	TrafficCatalog(EventRegistryRepository registries, OperatorNotificationRepository notifications,
			TenantRepository tenants, TenantRouter router, TrafficEventReader reader, Clock clock) {
		this.registries = registries;
		this.notifications = notifications;
		this.tenants = tenants;
		this.router = router;
		this.reader = reader;
		this.clock = clock;
	}

	Snapshot load() {
		LocalDateTime now = Kst.now(clock);
		LocalDateTime failedSince = now.minus(FAILURE_WINDOW);
		List<EventRegistryRepository.WithTenantName> rows = registries
			.findMonitoringCandidates(now.minus(CANDIDATE_START_WINDOW));
		List<OperatorNotificationRepository.WithTenantName> urgent = notifications.findUrgentSince(failedSince);

		Map<String, Set<Long>> candidateIds = new LinkedHashMap<>();
		Map<String, Set<Long>> failureIds = new LinkedHashMap<>();
		for (EventRegistryRepository.WithTenantName row : rows) {
			EventRegistry r = row.registry();
			if (!TenantIds.isValid(r.getTenantId())) {
				log.warn("event_registry의 tenant_id 형식이 아니어서 트래픽 모니터링에서 뺍니다 (event_id {})", r.getEventId());
				continue;
			}
			candidateIds.computeIfAbsent(r.getTenantId(), id -> new LinkedHashSet<>()).add(r.getEventId());
			failureIds.computeIfAbsent(r.getTenantId(), id -> new LinkedHashSet<>()).add(r.getEventId());
		}
		for (OperatorNotificationRepository.WithTenantName n : urgent) {
			OperatorNotification notification = n.notification();
			if (notification.getEventId() != null && TenantIds.isValid(notification.getTenantId())) {
				failureIds.computeIfAbsent(notification.getTenantId(), id -> new LinkedHashSet<>())
					.add(notification.getEventId());
			}
		}

		// 테넌트 스키마: 테넌트마다 한 번(event 행 + *_FAILED 로그)
		Map<String, Tenant> tenantById = tenants.findAllById(failureIds.keySet())
			.stream()
			.collect(Collectors.toMap(Tenant::getTenantId, Function.identity()));
		Map<Long, Event> eventRows = new HashMap<>();
		Set<Long> aggregated = new HashSet<>();
		List<EventStatusLog> failedLogs = new ArrayList<>();
		for (Map.Entry<String, Set<Long>> entry : failureIds.entrySet()) {
			Tenant tenant = tenantById.get(entry.getKey());
			if (tenant == null || !tenant.isReady()) {
				continue;
			}
			Set<Long> candidates = candidateIds.getOrDefault(entry.getKey(), Set.of());
			TrafficEventReader.TenantRows tenantRows = router.inTenant(tenant,
					() -> reader.rows(candidates, entry.getValue(), failedSince.minus(LOG_MATCH)));
			tenantRows.events().forEach(e -> eventRows.put(e.getEventId(), e));
			aggregated.addAll(tenantRows.aggregated());
			failedLogs.addAll(tenantRows.failedLogs());
		}

		// 이벤트 · 실패 상태별 마지막 실패 시각(목록의 failedAt)
		Map<Long, Map<String, LocalDateTime>> lastFailed = new HashMap<>();
		for (EventStatusLog l : failedLogs) {
			lastFailed.computeIfAbsent(l.getEventId(), id -> new HashMap<>())
				.merge(l.getNewStatus(), l.getChangedAt(), (a, b) -> a.isAfter(b) ? a : b);
		}
		List<MonitoredEvent> events = new ArrayList<>();
		Map<Long, String[]> names = new HashMap<>();
		for (EventRegistryRepository.WithTenantName row : rows) {
			EventRegistry r = row.registry();
			names.put(r.getEventId(), new String[] { r.getName(), row.tenantName() });
			if (!TenantIds.isValid(r.getTenantId())) {
				continue;
			}
			Event e = eventRows.get(r.getEventId());
			MonitoredEvent base = new MonitoredEvent(r.getEventId(), r.getTenantId(), row.tenantName(), r.getName(),
					r.getHostingStartAt(), r.getStartAt(), e == null ? null : e.getEndAt(),
					e == null ? null : e.getHostingEndAt(), r.getEventStatus(), r.getHostingStatus(),
					e == null ? null : e.getStockCount(), aggregated.contains(r.getEventId()), null);
			String failedStatus = base.failedStatus();
			events.add(failedStatus == null ? base : base.withFailedAt(
					lastFailed.getOrDefault(r.getEventId(), Map.of()).get(failedStatus)));
		}
		return new Snapshot(now, List.copyOf(events), failures(urgent, failedLogs, failedSince, names));
	}

	/** 알림 기준으로 상태 로그를 짝지어 합친다. 최신순 */
	private List<FailureItem> failures(List<OperatorNotificationRepository.WithTenantName> urgent,
			List<EventStatusLog> failedLogs, LocalDateTime failedSince, Map<Long, String[]> names) {
		List<EventStatusLog> remaining = new ArrayList<>(failedLogs);
		Set<Long> missingNames = new LinkedHashSet<>();
		urgent.stream()
			.map(n -> n.notification().getEventId())
			.filter(id -> id != null && !names.containsKey(id))
			.forEach(missingNames::add);
		failedLogs.stream().map(EventStatusLog::getEventId).filter(id -> !names.containsKey(id)).forEach(missingNames::add);
		if (!missingNames.isEmpty()) {
			Map<String, String> tenantNames = new HashMap<>();
			urgent.forEach(n -> {
				if (n.notification().getTenantId() != null && n.tenantName() != null) {
					tenantNames.put(n.notification().getTenantId(), n.tenantName());
				}
			});
			registries.findByEventIdIn(missingNames)
				.forEach(r -> names.put(r.getEventId(), new String[] { r.getName(), tenantNames.get(r.getTenantId()) }));
		}

		List<FailureItem> items = new ArrayList<>();
		for (OperatorNotificationRepository.WithTenantName n : urgent) {
			OperatorNotification notification = n.notification();
			FailureStep step = FailureStep.ofCategory(notification.getCategory());
			String reason = notification.getContent();
			EventStatusLog matched = match(notification, remaining);
			if (matched != null) {
				remaining.remove(matched);
				step = FailureStep.ofStatus(matched.getNewStatus());
				reason = matched.getDetail() != null ? matched.getDetail() : reason;
			}
			String[] name = notification.getEventId() == null ? null : names.get(notification.getEventId());
			items.add(new FailureItem(notification.getOccurredAt(), notification.getEventId(), notification.getTenantId(),
					n.tenantName(), name == null ? null : name[0], step, reason));
		}
		for (EventStatusLog l : remaining) {
			if (l.getChangedAt().isBefore(failedSince)) {
				continue;
			}
			String[] name = names.get(l.getEventId());
			items.add(new FailureItem(l.getChangedAt(), l.getEventId(), l.getTenantId(), name == null ? null : name[1],
					name == null ? null : name[0], FailureStep.ofStatus(l.getNewStatus()), l.getDetail()));
		}
		items.sort(Comparator.comparing(FailureItem::occurredAt).reversed());
		return List.copyOf(items);
	}

	/** 같은 이벤트 · 같은 구분(HOSTING · EVENT)의 *_FAILED 로그 중 시각이 가장 가까운 것(±5분) */
	private static EventStatusLog match(OperatorNotification n, Collection<EventStatusLog> logs) {
		StatusType type = switch (n.getCategory() == null ? "" : n.getCategory()) {
			case "HOSTING" -> StatusType.HOSTING;
			case "EVENT" -> StatusType.EVENT;
			default -> null;
		};
		if (type == null || n.getEventId() == null) {
			return null;
		}
		return logs.stream()
			.filter(l -> l.getEventId().equals(n.getEventId()) && l.getStatusType() == type)
			.filter(l -> Duration.between(l.getChangedAt(), n.getOccurredAt()).abs().compareTo(LOG_MATCH) <= 0)
			.min(Comparator.comparing(l -> Duration.between(l.getChangedAt(), n.getOccurredAt()).abs()))
			.orElse(null);
	}

}
