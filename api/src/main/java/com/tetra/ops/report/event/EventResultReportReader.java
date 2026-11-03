package com.tetra.ops.report.event;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.tetra.ops.domain.ApplicationStatus;
import com.tetra.ops.domain.StatusType;
import com.tetra.ops.domain.tenant.Event;
import com.tetra.ops.domain.tenant.EventRepository;
import com.tetra.ops.domain.tenant.EventResultSummary;
import com.tetra.ops.domain.tenant.EventResultSummaryRepository;
import com.tetra.ops.domain.tenant.EventStatusLog;
import com.tetra.ops.domain.tenant.EventStatusLogRepository;
import com.tetra.ops.domain.tenant.TenantReportMetaRepository;
import com.tetra.ops.event.EventQueryService;
import com.tetra.ops.event.EventResultResponse;
import com.tetra.ops.event.EventResultResponse.State;
import com.tetra.ops.report.event.EventResultReportData.Flow;
import com.tetra.ops.report.event.EventResultReportData.Metrics;
import com.tetra.ops.report.event.EventResultReportData.Minute;
import com.tetra.ops.report.event.EventResultReportData.StatusEntry;
import com.tetra.ops.report.event.EventResultReportData.Timing;
import com.tetra.ops.tenancy.TenantContext;
import com.tetra.ops.tenancy.TenantMismatchException;

import jakarta.persistence.EntityManager;

/**
 * 이벤트 결과 리포트의 테넌트 스키마 읽기. TenantRouter.inTenant 안에서만 부른다.
 * 핵심 지표 · 발급 결과 구성 · 분당은 결과 탭 API의 계산({@link EventQueryService#result})을 그대로 쓴다 — 같은 테넌트라 라우팅을
 * 다시 바꾸지 않고 이 읽기 트랜잭션에 합류한다. 그 밖(입장 · 번호표 수, 시간, 운영 이력)만 여기서 읽는다.
 */
@Component
class EventResultReportReader {

	private static final Logger log = LoggerFactory.getLogger(EventResultReportReader.class);

	private final EventQueryService eventQueries;

	private final EventRepository events;

	private final EventResultSummaryRepository summaries;

	private final EventStatusLogRepository statusLogs;

	private final TenantReportMetaRepository reports;

	private final EntityManager entityManager;

	EventResultReportReader(EventQueryService eventQueries, EventRepository events,
			EventResultSummaryRepository summaries, EventStatusLogRepository statusLogs, TenantReportMetaRepository reports,
			EntityManager entityManager) {
		this.eventQueries = eventQueries;
		this.events = events;
		this.summaries = summaries;
		this.statusLogs = statusLogs;
		this.reports = reports;
		this.entityManager = entityManager;
	}

	/**
	 * 리포트 값. 결과 탭이 READY(발급 이력 저장 끝남, 종료 실패 아님)가 아니면 비어 있다 — 결과가 없는 이벤트는 만들지 않는다.
	 * @param tenantName 머리에 쓸 테넌트명(공용 tenant.name)
	 */
	@Transactional(readOnly = true)
	public Optional<EventResultReportData> read(long eventId, String tenantName) {
		EventResultResponse result = eventQueries.result(eventId);
		if (result.state() != State.READY || result.result() == null) {
			return Optional.empty();
		}
		Event event = events.findById(eventId)
			.orElseThrow(() -> new IllegalStateException("registry에는 있고 event에는 없습니다: eventId=" + eventId));
		EventResultSummary summary = summaries.findById(eventId)
			.orElseThrow(() -> new IllegalStateException("결과 탭은 READY인데 event_result_summary가 없습니다: eventId=" + eventId));
		EventResultResponse.Metrics m = result.result().metrics();
		EventResultResponse.Composition c = result.result().composition();
		Metrics metrics = new Metrics(nz(m.successCount()), nz(m.totalStock()), m.selloutRate(), nz(m.soldoutCount()),
				nz(m.peakConcurrent()), m.capacityUsageRate(), local(m.peakAt()));
		Flow flow = new Flow(nz(summary.getSessionCount()), nz(summary.getTicketCount()), nz(c.successCount()),
				nz(c.soldoutCount()), nz(c.abandonedCount()));
		List<Minute> minutes = result.result()
			.perMinute()
			.stream()
			.map(b -> new Minute(local(b.minute()), b.successCount(), b.soldoutCount()))
			.toList();
		List<StatusEntry> history = statusLogs.findByEventIdOrderByChangedAtAscLogIdAsc(eventId)
			.stream()
			.map(l -> new StatusEntry(l.getChangedAt(), l.getStatusType(), l.getPrevStatus(), l.getNewStatus(),
					rejected(l) ? event.getRejectReason() : null))
			.toList();
		return Optional.of(new EventResultReportData(tenantName, eventId, event.getName(), event.getTrafficPlanCode(),
				m.maxCapacity(), event.getStartAt(), event.getEndAt(), local(result.aggregatedAt()), metrics, flow,
				timing(eventId, metrics, event.getStartAt()), minutes, history));
	}

	/**
	 * 보충 대상: 발급 이력 저장 시각(history_generated_at)이 since 이후(같으면 포함)이고 이 이벤트 리포트의 메타(같은 키)가 아직 없는
	 * 이벤트, event_id 순. 결과 탭 READY 여부는 만들 때({@link #read}) 다시 본다.
	 */
	@Transactional(readOnly = true)
	public List<Long> missingReports(LocalDateTime since) {
		String tenantId = TenantContext.current();
		List<EventResultSummary> rows = summaries.findByHistoryGeneratedAtGreaterThanEqualOrderByEventIdAsc(since);
		if (rows.isEmpty()) {
			return List.of();
		}
		Map<Long, Event> byId = events.findAllById(rows.stream().map(EventResultSummary::getEventId).toList())
			.stream()
			.collect(Collectors.toMap(Event::getEventId, Function.identity()));
		List<Long> missing = new ArrayList<>();
		for (EventResultSummary row : rows) {
			Event event = byId.get(row.getEventId());
			if (event != null && !reports.existsByTenantIdAndFilePath(tenantId,
					EventResultReportService.key(event.getEventId(), event.getStartAt()))) {
				missing.add(event.getEventId());
			}
		}
		return missing;
	}

	/**
	 * 소진까지 걸린 시간과 대기 시간. issuance_history는 발급 결과(성공 · 품절)가 정해진 사람만 있다.
	 * 대기 = queue_entered_at(번호표를 받아 대기열에 들어간 시각) → issued_at(재고 차감 결과가 정해진 시각), 음수는 0.
	 * 엔티티를 거치지 않으므로 다른 tenant_id 행이 섞였는지 여기서 직접 확인한다 (§4-6).
	 */
	private Timing timing(long eventId, Metrics metrics, LocalDateTime startAt) {
		String tenantId = TenantContext.current();
		Object[] row = (Object[]) entityManager.createNativeQuery("""
				select count(*),
				       avg(greatest(timestampdiff(microsecond, queue_entered_at, issued_at), 0)),
				       max(greatest(timestampdiff(microsecond, queue_entered_at, issued_at), 0)),
				       date_format(max(case when result = 'SUCCESS' then issued_at end), '%Y-%m-%dT%H:%i:%s.%f'),
				       sum(tenant_id <> :tenantId)
				  from issuance_history
				 where event_id = :eventId""")
			.setParameter("tenantId", tenantId)
			.setParameter("eventId", eventId)
			.getSingleResult();
		if (row[4] != null && ((Number) row[4]).longValue() > 0) {
			log.error("테넌트 불일치: issuance_history eventId={}, routedTenant={}", eventId, tenantId);
			throw new TenantMismatchException();
		}
		long decided = ((Number) row[0]).longValue();
		BigDecimal waitAverage = row[1] == null ? null : microsToSeconds(new BigDecimal(row[1].toString()));
		BigDecimal waitMax = row[2] == null ? null : microsToSeconds(new BigDecimal(row[2].toString()));
		BigDecimal sellout = null;
		if (metrics.soldOut() && row[3] != null) {
			long millis = Duration.between(startAt, LocalDateTime.parse(row[3].toString())).toMillis();
			sellout = BigDecimal.valueOf(Math.max(0, millis), 3);
		}
		return new Timing(sellout, decided, waitAverage, waitMax);
	}

	private static boolean rejected(EventStatusLog log) {
		return log.getStatusType() == StatusType.APPLICATION
				&& ApplicationStatus.REJECTED.name().equals(log.getNewStatus());
	}

	/** 마이크로초 → 초, 소수 3자리(반올림) */
	private static BigDecimal microsToSeconds(BigDecimal micros) {
		return micros.movePointLeft(6).setScale(3, RoundingMode.HALF_UP);
	}

	/** API 응답의 +09:00 시각 → KST 벽시계 */
	private static LocalDateTime local(OffsetDateTime value) {
		return value == null ? null : value.toLocalDateTime();
	}

	private static long nz(Long value) {
		return value == null ? 0 : value;
	}

}
