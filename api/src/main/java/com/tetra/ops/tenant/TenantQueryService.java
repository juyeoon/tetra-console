package com.tetra.ops.tenant;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tetra.ops.common.time.Kst;
import com.tetra.ops.domain.AccountStatus;
import com.tetra.ops.domain.ApplicationStatus;
import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.EventType;
import com.tetra.ops.domain.HostingStatus;
import com.tetra.ops.domain.platform.EventRegistry;
import com.tetra.ops.domain.platform.EventRegistryRepository;
import com.tetra.ops.domain.platform.Tenant;
import com.tetra.ops.domain.platform.TenantRepository;
import com.tetra.ops.domain.platform.TenantStatusLog;
import com.tetra.ops.domain.platform.TenantStatusLogRepository;
import com.tetra.ops.tenancy.TenantRouter;

/** OPS-06 목록, 06-1 개요, 06-2 이벤트. 06-3 리소스만 테넌트 스키마로 라우팅한다. */
@Service
public class TenantQueryService {

	private final TenantRepository tenants;

	private final TenantStatusLogRepository statusLogs;

	private final EventRegistryRepository registries;

	private final TenantRouter router;

	private final TenantResourceReader resourceReader;

	private final TenantBuckets buckets;

	public TenantQueryService(TenantRepository tenants, TenantStatusLogRepository statusLogs,
			EventRegistryRepository registries, TenantRouter router, TenantResourceReader resourceReader,
			TenantBuckets buckets) {
		this.tenants = tenants;
		this.statusLogs = statusLogs;
		this.registries = registries;
		this.router = router;
		this.resourceReader = resourceReader;
		this.buckets = buckets;
	}

	public record TenantListResponse(Map<AccountStatus, Long> counts, List<TenantItem> items) {
	}

	public record TenantItem(String tenantId, String name, String email, AccountStatus accountStatus) {
	}

	public record TenantOverviewResponse(Basic basic, Buckets buckets, List<StatusLogItem> statusLogs) {
	}

	public record Basic(String tenantId, String name, String contactName, String email, AccountStatus accountStatus,
			OffsetDateTime createdAt) {
	}

	public record Buckets(String content, String auditLog) {
	}

	/** 사유 배지 (OPS-06-1). */
	public enum Badge {

		/** "테넌트 등록" (prev_status NULL, 최초 기록) */
		REGISTERED,
		/** [신청 완료] */
		APPLIED,
		/** [반려] */
		REJECTED,
		/** [종료] */
		ENDED

	}

	public record StatusLogItem(OffsetDateTime changedAt, AccountStatus prevStatus, AccountStatus newStatus,
			Badge badge, String reason, RelatedEvent relatedEvent) {
	}

	public record RelatedEvent(Long eventId, String name, ApplicationStatus applicationStatus) {
	}

	public record TenantEventItem(Long eventId, String name, EventType eventType, ApplicationStatus applicationStatus,
			HostingStatus hostingStatus, EventStatus eventStatus, String trafficPlanCode, OffsetDateTime appliedAt) {
	}

	public record TenantResourceItem(Long eventId, String name, EventStatus eventStatus, String subdomain,
			String publicKey) {
	}

	// TODO(미정): 목록 페이지네이션. 지금은 전체를 돌려준다.
	@Transactional(readOnly = true)
	public TenantListResponse list() {
		List<TenantItem> items = tenants.findAllByOrderByCreatedAtDescTenantIdAsc()
			.stream()
			.map(t -> new TenantItem(t.getTenantId(), t.getName(), t.getEmail(), t.getAccountStatus()))
			.toList();
		Map<AccountStatus, Long> counts = new EnumMap<>(AccountStatus.class);
		for (AccountStatus status : AccountStatus.values()) {
			counts.put(status, tenants.countByAccountStatus(status));
		}
		return new TenantListResponse(counts, items);
	}

	/** 공용 테이블만 읽으므로 provisioning_status와 무관하게 동작한다. */
	@Transactional(readOnly = true)
	public TenantOverviewResponse overview(String tenantId) {
		Tenant tenant = router.findTenant(tenantId);
		List<TenantStatusLog> logs = statusLogs.findByTenantIdOrderByChangedAtDescLogIdDesc(tenantId);
		List<Long> eventIds = logs.stream().map(TenantStatusLog::getRelatedEventId).filter(Objects::nonNull).toList();
		// 연관 이벤트의 이름·상태는 event_registry에서 읽는다 (테넌트 스키마로 라우팅하지 않음)
		Map<Long, EventRegistry> related = eventIds.isEmpty() ? Map.of()
				: registries.findByEventIdIn(eventIds)
					.stream()
					.collect(Collectors.toMap(EventRegistry::getEventId, Function.identity()));
		List<StatusLogItem> items = logs.stream().map(log -> {
			EventRegistry event = log.getRelatedEventId() == null ? null : related.get(log.getRelatedEventId());
			RelatedEvent relatedEvent = event == null ? null
					: new RelatedEvent(event.getEventId(), event.getName(), event.getApplicationStatus());
			return new StatusLogItem(Kst.toOffset(log.getChangedAt()), log.getPrevStatus(), log.getNewStatus(),
					badge(log, event), log.getReason(), relatedEvent);
		}).toList();
		return new TenantOverviewResponse(
				new Basic(tenant.getTenantId(), tenant.getName(), tenant.getContactName(), tenant.getEmail(),
						tenant.getAccountStatus(), Kst.toOffset(tenant.getCreatedAt())),
				new Buckets(buckets.content(tenantId), buckets.auditLog(tenantId)), items);
	}

	@Transactional(readOnly = true)
	public List<TenantEventItem> events(String tenantId) {
		router.findTenant(tenantId);
		return registries.findByTenantIdOrderByAppliedAtDescEventIdDesc(tenantId)
			.stream()
			.map(r -> new TenantEventItem(r.getEventId(), r.getName(), r.getEventType(), r.getApplicationStatus(),
					r.getHostingStatus(), r.getEventStatus(), r.getTrafficPlanCode(), Kst.toOffset(r.getAppliedAt())))
			.toList();
	}

	/**
	 * READY 테넌트만 테넌트 스키마로 라우팅한다.
	 * TODO(미정): READY가 아닌 테넌트(스키마가 아직 없거나 실패)는 승인된 이벤트가 있을 수 없어 빈 목록을 준다.
	 */
	public List<TenantResourceItem> resources(String tenantId) {
		Tenant tenant = router.findTenant(tenantId);
		if (!tenant.isReady()) {
			return List.of();
		}
		return router.inTenant(tenant, resourceReader::approvedEventResources);
	}

	static Badge badge(TenantStatusLog log, EventRegistry relatedEvent) {
		if (log.getPrevStatus() == null) {
			return Badge.REGISTERED;
		}
		if (log.getNewStatus() == AccountStatus.ACTIVE) {
			return Badge.APPLIED;
		}
		if (relatedEvent != null && relatedEvent.getApplicationStatus() == ApplicationStatus.REJECTED) {
			return Badge.REJECTED;
		}
		return Badge.ENDED;
	}

}
