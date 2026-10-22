package com.tetra.ops.event;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.tetra.ops.common.time.Kst;
import com.tetra.ops.domain.ApplicationStatus;
import com.tetra.ops.domain.AuthKeyStatus;
import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.HostingStatus;
import com.tetra.ops.domain.StatusType;
import com.tetra.ops.domain.platform.Operator;
import com.tetra.ops.domain.platform.OperatorRepository;
import com.tetra.ops.domain.platform.Tenant;
import com.tetra.ops.domain.platform.TrafficPlanRepository;
import com.tetra.ops.domain.tenant.Event;
import com.tetra.ops.domain.tenant.EventAuthKey;
import com.tetra.ops.domain.tenant.EventAuthKeyRepository;
import com.tetra.ops.domain.tenant.EventRepository;
import com.tetra.ops.domain.tenant.EventResultSummary;
import com.tetra.ops.domain.tenant.EventResultSummaryRepository;
import com.tetra.ops.domain.tenant.EventStatusLog;
import com.tetra.ops.domain.tenant.EventStatusLogRepository;
import com.tetra.ops.event.EventResultResponse.Composition;
import com.tetra.ops.event.EventResultResponse.Failure;
import com.tetra.ops.event.EventResultResponse.Metrics;
import com.tetra.ops.event.EventResultResponse.MinuteBucket;
import com.tetra.ops.event.EventResultResponse.State;
import com.tetra.ops.tenancy.TenantContext;
import com.tetra.ops.tenancy.TenantMismatchException;

import jakarta.persistence.EntityManager;

/** 테넌트 스키마 읽기. TenantRouter.inTenant 안에서만 부른다. */
@Component
class EventReader {

	private static final Logger log = LoggerFactory.getLogger(EventReader.class);

	private final EventRepository events;

	private final EventAuthKeyRepository authKeys;

	private final EventStatusLogRepository statusLogs;

	private final EventResultSummaryRepository summaries;

	private final TrafficPlanRepository plans;

	private final EntityManager entityManager;

	private final EventDomains domains;

	private final OperatorRepository operators;

	EventReader(EventRepository events, EventAuthKeyRepository authKeys, EventStatusLogRepository statusLogs,
			EventResultSummaryRepository summaries, TrafficPlanRepository plans, EntityManager entityManager,
			EventDomains domains, OperatorRepository operators) {
		this.events = events;
		this.authKeys = authKeys;
		this.statusLogs = statusLogs;
		this.summaries = summaries;
		this.plans = plans;
		this.entityManager = entityManager;
		this.domains = domains;
		this.operators = operators;
	}

	private Map<Long, String> operatorNames(List<EventStatusLog> rows) {
		Set<Long> ids = rows.stream().map(EventStatusLog::getChangedBy).filter(Objects::nonNull).collect(Collectors.toSet());
		if (ids.isEmpty()) {
			return Map.of();
		}
		return operators.findAllById(ids)
			.stream()
			.collect(Collectors.toMap(Operator::getOperatorId, Operator::getContactName));
	}

	/**
	 * 처리자 판단(changed_by와 지금 데이터로 알 수 있는 범위).
	 * - changed_by 있음: 운영자(승인 · 반려). 운영자 행이 없으면 "운영자 #{id}"
	 * - 신청 접수(APPLICATION, prev 없음 → APPLYING): 테넌트(Portal에서 제출)
	 * - 그 밖: 자동 처리(신청 저장 · 실패, 승인 때 함께 쓰는 호스팅 · 이벤트 시작 로그, 워크플로우 결과)
	 */
	static EventDetailResponse.Actor actor(EventStatusLog log, String tenantName, Map<Long, String> operatorNames) {
		if (log.getChangedBy() != null) {
			return new EventDetailResponse.Actor(EventDetailResponse.ActorType.OPERATOR,
					operatorNames.getOrDefault(log.getChangedBy(), "운영자 #" + log.getChangedBy()));
		}
		if (log.getStatusType() == StatusType.APPLICATION && log.getPrevStatus() == null
				&& ApplicationStatus.APPLYING.name().equals(log.getNewStatus())) {
			return new EventDetailResponse.Actor(EventDetailResponse.ActorType.TENANT, tenantName);
		}
		return new EventDetailResponse.Actor(EventDetailResponse.ActorType.SYSTEM, null);
	}

	@Transactional(readOnly = true)
	public EventDetailResponse detail(Long eventId, Tenant tenant) {
		Event event = requireEvent(eventId);
		boolean resourcesVisible = event.getApplicationStatus() == ApplicationStatus.APPROVED
				&& event.getEventStatus() != EventStatus.EXPIRED;
		String domain = null;
		String fullDomain = null;
		String publicKey = null;
		if (resourcesVisible) {
			domain = domains.domain(event.getSlug(), event.getTenantId());
			// 이벤트 주소는 플랫폼 도메인 아래(테넌트 회사 도메인 root_domain이 아님)
			fullDomain = domains.fullDomain(event.getSlug(), event.getTenantId());
			publicKey = authKeys.findFirstByEventIdAndStatusOrderByKeyVersionDesc(eventId, AuthKeyStatus.ACTIVE)
				.map(EventAuthKey::getPublicKey)
				.orElse(null);
		}
		// 진행 결과 탭: 결과 API와 같은 판정(resultState)으로, 이벤트가 끝나기 전(IN_PROGRESS)이면 연다/막는다를 정한다
		boolean resultAvailable = resultState(event, summaries.findById(eventId).orElse(null)) != State.IN_PROGRESS;
		List<EventStatusLog> rows = statusLogs.findByEventIdOrderByChangedAtAscLogIdAsc(eventId);
		// 처리 운영자 이름은 공용 스키마에서 한 번에 읽는다(operator는 catalog tetra_platform)
		Map<Long, String> operatorNames = operatorNames(rows);
		var logs = rows.stream()
			.map(l -> new EventDetailResponse.StatusLog(Kst.toOffset(l.getChangedAt()), l.getStatusType(),
					l.getPrevStatus(), l.getNewStatus(), l.getDetail(), actor(l, tenant.getName(), operatorNames)))
			.toList();
		return new EventDetailResponse(
				new EventDetailResponse.Basic(event.getEventId(), event.getName(), event.getEventType(),
						event.getTenantId(), tenant.getName(), Kst.toOffset(event.getHostingStartAt()),
						Kst.toOffset(event.getHostingEndAt()), Kst.toOffset(event.getStartAt()),
						Kst.toOffset(event.getEndAt()), event.getTrafficPlanCode(), event.getStockCount(),
						Kst.toOffset(event.getAppliedAt()), event.getEndpointUrl()),
				new EventDetailResponse.Status(event.getApplicationStatus(), event.getHostingStatus(),
						event.getEventStatus(), resultAvailable),
				new EventDetailResponse.Resources(event.getTemplateId(), domain, fullDomain, publicKey), logs);
	}

	/** OPS-04-3 state 판정. 위에서부터 먼저 맞는 것. 결과 API와 상세 API(resultAvailable)가 같이 쓴다. */
	static State resultState(Event event, EventResultSummary summary) {
		if (event.getHostingStatus() == HostingStatus.END_FAILED) {
			return State.END_FAILED;
		}
		if (summary != null && summary.getHistoryGeneratedAt() != null) {
			return State.READY;
		}
		if (event.getEventStatus() == EventStatus.EXPIRED) {
			return State.AGGREGATING;
		}
		return State.IN_PROGRESS;
	}

	/** OPS-04-3. */
	@Transactional(readOnly = true)
	public EventResultResponse result(Long eventId) {
		Event event = requireEvent(eventId);
		var summary = summaries.findById(eventId).orElse(null);
		var openAt = Kst.toOffset(event.getStartAt());
		State state = resultState(event, summary);
		if (state == State.END_FAILED) {
			Failure failure = statusLogs
				.findFirstByEventIdAndStatusTypeAndNewStatusOrderByChangedAtDescLogIdDesc(eventId, StatusType.HOSTING,
						HostingStatus.END_FAILED.name())
				.map(l -> new Failure(l.getDetail(), Kst.toOffset(l.getChangedAt())))
				.orElse(new Failure(null, null));
			return new EventResultResponse(eventId, State.END_FAILED, openAt, null, event.getHostingStatus(),
					event.getEventStatus(), null, failure, null);
		}
		if (state == State.READY) {
			return new EventResultResponse(eventId, State.READY, openAt, Kst.toOffset(summary.getHistoryGeneratedAt()),
					event.getHostingStatus(), event.getEventStatus(), null, null, readyResult(event, summary));
		}
		if (state == State.AGGREGATING) {
			return new EventResultResponse(eventId, State.AGGREGATING, openAt, null, event.getHostingStatus(),
					event.getEventStatus(), null, null, null);
		}
		return new EventResultResponse(eventId, State.IN_PROGRESS, openAt, null, event.getHostingStatus(),
				event.getEventStatus(), Kst.toOffset(event.getEndAt()), null, null);
	}

	/** CSV 경로. READY가 아니면 null. */
	@Transactional(readOnly = true)
	public String historyFilePathIfReady(Long eventId) {
		EventResultResponse result = result(eventId);
		if (result.state() != State.READY) {
			return null;
		}
		return summaries.findById(eventId).map(EventResultSummary::getHistoryFilePath).orElse(null);
	}

	private EventResultResponse.Result readyResult(Event event, EventResultSummary summary) {
		// 총 재고는 event.stock_count (화면설계서 OPS-04-3)
		Long totalStock = event.getStockCount();
		Long maxCapacity = plans.findById(event.getTrafficPlanCode())
			.orElseThrow(() -> new IllegalStateException("플랜이 없습니다: " + event.getTrafficPlanCode()))
			.getMaxCapacity();
		BigDecimal usage = maxCapacity == null || maxCapacity == 0 ? null
				: BigDecimal.valueOf(summary.getPeakConcurrent())
					.divide(BigDecimal.valueOf(maxCapacity), 4, RoundingMode.HALF_UP);
		long success = nz(summary.getSuccessCount());
		long soldout = nz(summary.getSoldoutCount());
		long abandoned = Math.max(0, nz(summary.getTicketCount()) - success - soldout);
		return new EventResultResponse.Result(
				new Metrics(summary.getSuccessCount(), totalStock, summary.getSelloutRate(), summary.getSoldoutCount(),
						summary.getPeakConcurrent(), maxCapacity, usage, Kst.toOffset(summary.getPeakAt())),
				new Composition(success, soldout, abandoned), perMinute(event.getEventId()));
	}

	/**
	 * issuance_history를 queue_entered_at 분 단위로 묶는다. event_id로 uk_issuance_event_user 범위만 읽는다.
	 * 엔티티를 거치지 않으므로 다른 tenant_id 행이 섞였는지 여기서 직접 확인한다 (§4-6).
	 */
	@SuppressWarnings("unchecked")
	private List<MinuteBucket> perMinute(Long eventId) {
		String tenantId = TenantContext.current();
		List<Object[]> rows = entityManager.createNativeQuery("""
				select date_format(queue_entered_at, '%Y-%m-%d %H:%i:00') as minute_at,
				       sum(result = 'SUCCESS') as success_count,
				       sum(result = 'FAILED_SOLDOUT') as soldout_count,
				       sum(tenant_id <> :tenantId) as foreign_rows
				  from issuance_history
				 where event_id = :eventId
				 group by minute_at
				 order by minute_at""")
			.setParameter("tenantId", tenantId)
			.setParameter("eventId", eventId)
			.getResultList();
		List<MinuteBucket> buckets = new ArrayList<>(rows.size());
		for (Object[] row : rows) {
			if (toLong(row[3]) > 0) {
				log.error("테넌트 불일치: issuance_history eventId={}, routedTenant={}", eventId, tenantId);
				throw new TenantMismatchException();
			}
			LocalDateTime minute = LocalDateTime.parse(row[0].toString().replace(' ', 'T'));
			buckets.add(new MinuteBucket(Kst.toOffset(minute), toLong(row[1]), toLong(row[2])));
		}
		return buckets;
	}

	private Event requireEvent(Long eventId) {
		return events.findById(eventId)
			.orElseThrow(() -> new IllegalStateException("registry에는 있고 event에는 없습니다: eventId=" + eventId));
	}

	private static long nz(Long value) {
		return value == null ? 0 : value;
	}

	private static long toLong(Object value) {
		return value == null ? 0 : ((Number) value).longValue();
	}

}
