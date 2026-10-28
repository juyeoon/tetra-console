package com.tetra.ops.monitoring;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.tetra.ops.domain.tenant.Event;
import com.tetra.ops.domain.tenant.EventRepository;
import com.tetra.ops.domain.tenant.EventResultSummary;
import com.tetra.ops.domain.tenant.EventResultSummaryRepository;
import com.tetra.ops.domain.tenant.EventStatusLog;
import com.tetra.ops.domain.tenant.EventStatusLogRepository;

/**
 * OPS-05 테넌트 스키마 읽기(조회 전용). TenantRouter.inTenant 안에서만 부른다.
 * 읽은 행의 tenant_id 이중 확인은 TenantGuardListener(다르면 ERROR 로그 + 예외).
 */
@Component
class TrafficEventReader {

	/** 자동화 실패로 보는 상태(WF1-2 · WF2-1 · WF2-2 · WF4) */
	static final List<String> FAILED_STATUSES = List.of("DEPLOY_FAILED", "HOSTING_FAILED", "OPEN_FAILED", "END_FAILED");

	private final EventRepository events;

	private final EventStatusLogRepository statusLogs;

	private final EventResultSummaryRepository summaries;

	TrafficEventReader(EventRepository events, EventStatusLogRepository statusLogs,
			EventResultSummaryRepository summaries) {
		this.events = events;
		this.statusLogs = statusLogs;
		this.summaries = summaries;
	}

	/** 목록용: event 행(종료 시각 · 재고), 집계를 마친 이벤트(발급 이력 저장 시각 있음), since 이후 *_FAILED 로그 */
	record TenantRows(List<Event> events, Set<Long> aggregated, List<EventStatusLog> failedLogs) {
	}

	@Transactional(readOnly = true)
	public TenantRows rows(Collection<Long> eventIds, Collection<Long> failureEventIds, LocalDateTime failedSince) {
		List<Event> rows = eventIds.isEmpty() ? List.of() : events.findAllById(eventIds);
		Set<Long> aggregated = eventIds.isEmpty() ? Set.of()
				: summaries.findAllById(eventIds)
					.stream()
					.filter(s -> s.getHistoryGeneratedAt() != null)
					.map(EventResultSummary::getEventId)
					.collect(Collectors.toSet());
		List<EventStatusLog> failed = failureEventIds.isEmpty() ? List.of()
				: statusLogs.findByEventIdInAndNewStatusInAndChangedAtGreaterThanEqual(failureEventIds, FAILED_STATUSES,
						failedSince);
		return new TenantRows(rows, aggregated, failed);
	}

	/** 이벤트 하나: 진행 단계용 상태 로그 전체(changed_at 오름차순)와 결과 요약 */
	record Detail(Event event, List<EventStatusLog> logs, EventResultSummary summary) {
	}

	@Transactional(readOnly = true)
	public Optional<Detail> detail(Long eventId) {
		return events.findById(eventId)
			.map(event -> new Detail(event, statusLogs.findByEventIdOrderByChangedAtAscLogIdAsc(eventId),
					summaries.findById(eventId).orElse(null)));
	}

}
