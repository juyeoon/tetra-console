package com.tetra.ops.event;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

/** OPS-03 신청 목록, OPS-04 진행 목록. 공용 스키마(registry + tenant)만 읽는다. */
@Service
public class EventListService {

	/** OPS-04 상단 건수에 쓰는 호스팅상태. */
	static final List<HostingStatus> LIVE_COUNT_STATUSES = List.of(HostingStatus.PENDING, HostingStatus.HOSTING,
			HostingStatus.ENDED);

	private final EventRegistryRepository registries;

	public EventListService(EventRegistryRepository registries) {
		this.registries = registries;
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

	public record LiveEventItem(Long eventId, String tenantName, EventType eventType, String name,
			OffsetDateTime hostingStartAt, OffsetDateTime startAt, HostingStatus hostingStatus,
			EventStatus eventStatus, String trafficPlanCode) {
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

	@Transactional(readOnly = true)
	public LiveEventListResponse liveEvents() {
		List<LiveEventItem> items = registries.findApprovedWithTenantName()
			.stream()
			.map(row -> {
				EventRegistry r = row.registry();
				return new LiveEventItem(r.getEventId(), row.tenantName(), r.getEventType(), r.getName(),
						Kst.toOffset(r.getHostingStartAt()), Kst.toOffset(r.getStartAt()), r.getHostingStatus(),
						r.getEventStatus(), r.getTrafficPlanCode());
			})
			.toList();
		Map<HostingStatus, Long> counts = new LinkedHashMap<>();
		LIVE_COUNT_STATUSES.forEach(status -> counts.put(status, 0L));
		for (Object[] row : registries.countApprovedByHostingStatus(LIVE_COUNT_STATUSES)) {
			counts.put((HostingStatus) row[0], (Long) row[1]);
		}
		return new LiveEventListResponse(counts, items);
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
