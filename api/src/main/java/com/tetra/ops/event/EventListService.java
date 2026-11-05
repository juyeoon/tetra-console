package com.tetra.ops.event;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tetra.ops.common.time.Kst;
import com.tetra.ops.domain.ApplicationStatus;
import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.EventType;
import com.tetra.ops.domain.HostingStatus;
import com.tetra.ops.domain.platform.EventRegistry;
import com.tetra.ops.domain.platform.EventRegistryRepository;
import com.tetra.ops.domain.platform.EventRegistryRepository.WithTenantName;
import com.tetra.ops.domain.platform.Tenant;
import com.tetra.ops.domain.platform.TenantRepository;
import com.tetra.ops.tenancy.TenantIds;
import com.tetra.ops.tenancy.TenantRouter;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * OPS-03 신청 목록, OPS-04 진행 목록. 목록 · 건수는 공용 스키마(registry + tenant)에서 읽는다.
 * OPS-04 종료 시각(end_at · hosting_end_at)만 registry에 없어 테넌트 스키마에서 테넌트마다 한 번 읽는다.
 */
@Service
public class EventListService {

	private static final Logger log = LoggerFactory.getLogger(EventListService.class);

	/** OPS-04 상단 건수에 쓰는 호스팅상태. */
	static final List<HostingStatus> LIVE_COUNT_STATUSES = List.of(HostingStatus.PENDING, HostingStatus.HOSTING,
			HostingStatus.ENDED);

	private final EventRegistryRepository registries;

	private final TenantRepository tenants;

	private final TenantRouter router;

	private final LiveEventTimesReader timesReader;

	public EventListService(EventRegistryRepository registries, TenantRepository tenants, TenantRouter router,
			LiveEventTimesReader timesReader) {
		this.registries = registries;
		this.tenants = tenants;
		this.router = router;
		this.timesReader = timesReader;
	}

	public record ApplicationListResponse(Map<ApplicationStatus, Long> counts, List<ApplicationItem> items) {
	}

	/**
	 * processedAt은 비고(처리 일시)에 쓴다.
	 * TODO(미정): APPLY_FAILED는 processed_at이 비어 있어 처리 일시 출처가 없다. 지금은 null.
	 */
	public record ApplicationItem(Long eventId, String tenantName, EventType eventType, String name,
			OffsetDateTime appliedAt, ApplicationStatus applicationStatus, OffsetDateTime processedAt) {
	}

	public record LiveEventListResponse(Map<HostingStatus, Long> counts, List<LiveEventItem> items) {
	}

	/**
	 * 타임라인용 시각 3개는 startAt 뒤에 시간 순서(entryAt → endAt → hostingEndAt)로 둔다.
	 * - entryAt = 입장 열림 = EntryOpen.at(startAt). 저장값이 아니라 지금 입장 리드 상수로 계산한다
	 *   (리드를 바꾸기 전에 배포된 이벤트도 새 리드로 계산된다 — 11-04 결정, 오차 감수).
	 * - endAt = 이벤트 종료(event.end_at). 상세 EventDetailResponse.basic.endAt과 같은 이름 · 뜻이다.
	 *   OPS-05 모니터링 API의 endAt은 호스팅 종료(hosting_end_at)라 뜻이 다르다(그쪽 이벤트 종료는 eventEndAt).
	 * - hostingEndAt = 호스팅 종료(event.hosting_end_at).
	 * endAt · hostingEndAt은 테넌트 스키마에서 읽어, 테넌트가 READY가 아니거나 테넌트 스키마에 행이 없거나
	 * 그 테넌트 스키마를 읽지 못했으면 null(목록에는 남긴다).
	 */
	public record LiveEventItem(Long eventId, String tenantName, EventType eventType, String name,
			OffsetDateTime hostingStartAt, OffsetDateTime startAt,
			@Schema(description = "입장 열림 = 이벤트 시작 − 입장 리드(저장값 아님)") OffsetDateTime entryAt,
			@Schema(description = "이벤트 종료(end_at). 상세 basic.endAt과 같은 뜻 — OPS-05의 endAt(호스팅 종료)과 다름. "
					+ "테넌트가 READY가 아니거나 테넌트 스키마에 행이 없거나 그 테넌트 스키마를 읽지 못했으면 null")
			OffsetDateTime endAt,
			@Schema(description = "호스팅 종료(hosting_end_at). 테넌트가 READY가 아니거나 테넌트 스키마에 행이 없거나 "
					+ "그 테넌트 스키마를 읽지 못했으면 null") OffsetDateTime hostingEndAt,
			HostingStatus hostingStatus, EventStatus eventStatus, String trafficPlanCode) {
	}

	// TODO(미정): 목록 페이지네이션. 지금은 전체를 돌려준다.
	@Transactional(readOnly = true)
	public ApplicationListResponse applications() {
		List<ApplicationItem> items = registries.findAllWithTenantName()
			.stream()
			.sorted(APPLICATION_ORDER)
			.map(EventListService::toApplicationItem)
			.toList();
		Map<ApplicationStatus, Long> counts = new EnumMap<>(ApplicationStatus.class);
		for (ApplicationStatus status : ApplicationStatus.values()) {
			counts.put(status, 0L);
		}
		for (Object[] row : registries.countByApplicationStatus()) {
			counts.put((ApplicationStatus) row[0], (Long) row[1]);
		}
		return new ApplicationListResponse(counts, items);
	}

	/**
	 * 트랜잭션을 걸지 않는다 — TenantRouter는 트랜잭션 밖에서 불러야 한다. 공용 스키마 읽기(목록 · 건수 · 테넌트)는
	 * 저장소 메서드마다 기본 트랜잭션, 테넌트 스키마 읽기는 LiveEventTimesReader의 읽기 트랜잭션(테넌트마다 한 번).
	 */
	public LiveEventListResponse liveEvents() {
		List<WithTenantName> rows = registries.findApprovedWithTenantName();
		Map<HostingStatus, Long> counts = new LinkedHashMap<>();
		LIVE_COUNT_STATUSES.forEach(status -> counts.put(status, 0L));
		for (Object[] row : registries.countApprovedByHostingStatus(LIVE_COUNT_STATUSES)) {
			counts.put((HostingStatus) row[0], (Long) row[1]);
		}
		Map<Long, LiveEventTimesReader.Times> times = endTimes(rows);
		List<LiveEventItem> items = rows.stream().map(row -> {
			EventRegistry r = row.registry();
			LiveEventTimesReader.Times t = times.get(r.getEventId());
			return new LiveEventItem(r.getEventId(), row.tenantName(), r.getEventType(), r.getName(),
					Kst.toOffset(r.getHostingStartAt()), Kst.toOffset(r.getStartAt()),
					r.getStartAt() == null ? null : Kst.toOffset(EntryOpen.at(r.getStartAt())),
					t == null ? null : Kst.toOffset(t.endAt()), t == null ? null : Kst.toOffset(t.hostingEndAt()),
					r.getHostingStatus(), r.getEventStatus(), r.getTrafficPlanCode());
		}).toList();
		return new LiveEventListResponse(counts, items);
	}

	/**
	 * 테넌트 스키마 event의 end_at · hosting_end_at을 테넌트마다 한 번에 읽는다(OPS-05 TrafficCatalog와 같은 방식).
	 * READY가 아니거나 tenant_id 형식이 아닌 테넌트는 스키마로 들어가지 않는다(그 이벤트는 결과에 없음 → null).
	 * 한 테넌트 스키마 읽기가 실패해도 그 테넌트 이벤트만 null이고 다른 테넌트 · 목록 · 건수는 그대로 응답한다.
	 */
	private Map<Long, LiveEventTimesReader.Times> endTimes(List<WithTenantName> rows) {
		Map<String, Set<Long>> byTenant = new LinkedHashMap<>();
		for (WithTenantName row : rows) {
			EventRegistry r = row.registry();
			if (TenantIds.isValid(r.getTenantId())) {
				byTenant.computeIfAbsent(r.getTenantId(), id -> new LinkedHashSet<>()).add(r.getEventId());
			}
		}
		Map<Long, LiveEventTimesReader.Times> times = new HashMap<>();
		if (byTenant.isEmpty()) {
			return times;
		}
		for (Tenant tenant : tenants.findAllById(byTenant.keySet())) {
			if (!tenant.isReady()) {
				continue;
			}
			try {
				times.putAll(router.inTenant(tenant, () -> timesReader.read(byTenant.get(tenant.getTenantId()))));
			}
			catch (RuntimeException ex) {
				// 목록 화면은 이 값을 쓰지 않는다 — 시각 때문에 목록이 깨지지 않게 그 테넌트 이벤트만 null로 둔다
				log.warn("OPS-04 진행 목록: 테넌트 {} 스키마의 종료 시각을 읽지 못해 그 테넌트 이벤트는 비워 둡니다: {}",
						tenant.getTenantId(), ex.getClass().getSimpleName());
			}
		}
		return times;
	}

	/**
	 * APPLIED → APPLYING → 나머지(APPLY_FAILED·APPROVED·REJECTED) 순.
	 * 마지막 그룹은 applied_at 내림차순.
	 * TODO(미정): 앞 두 그룹 안의 정렬. 임시로 applied_at 오름차순.
	 */
	static final Comparator<WithTenantName> APPLICATION_ORDER = (a, b) -> {
		int groupA = group(a.registry().getApplicationStatus());
		int groupB = group(b.registry().getApplicationStatus());
		if (groupA != groupB) {
			return Integer.compare(groupA, groupB);
		}
		LocalDateTime appliedA = a.registry().getAppliedAt();
		LocalDateTime appliedB = b.registry().getAppliedAt();
		int byApplied = groupA == 2 ? appliedB.compareTo(appliedA) : appliedA.compareTo(appliedB);
		if (byApplied != 0) {
			return byApplied;
		}
		return groupA == 2 ? b.registry().getEventId().compareTo(a.registry().getEventId())
				: a.registry().getEventId().compareTo(b.registry().getEventId());
	};

	private static int group(ApplicationStatus status) {
		return switch (status) {
			case APPLIED -> 0;
			case APPLYING -> 1;
			default -> 2;
		};
	}

	private static ApplicationItem toApplicationItem(WithTenantName row) {
		EventRegistry r = row.registry();
		return new ApplicationItem(r.getEventId(), row.tenantName(), r.getEventType(), r.getName(),
				Kst.toOffset(r.getAppliedAt()), r.getApplicationStatus(), Kst.toOffset(r.getProcessedAt()));
	}

}
