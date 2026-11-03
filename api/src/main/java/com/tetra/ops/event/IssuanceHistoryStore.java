package com.tetra.ops.event;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.tetra.ops.common.CsvWriter;
import com.tetra.ops.domain.tenant.Event;
import com.tetra.ops.domain.tenant.EventRepository;
import com.tetra.ops.notification.NotificationService;
import com.tetra.ops.tenancy.TenantContext;
import com.tetra.ops.tenancy.TenantMismatchException;

import jakarta.persistence.EntityManager;

/**
 * 발급 이력 저장(3-12)의 테넌트 스키마 읽기 · 쓰기. TenantRouter.inTenant 안에서만 부른다.
 * 결과 수와 CSV를 한 번 읽은 행에서 같이 만들어 서로 어긋나지 않게 한다.
 */
@Component
class IssuanceHistoryStore {

	private static final Logger log = LoggerFactory.getLogger(IssuanceHistoryStore.class);

	static final String[] CSV_HEADER = { "issuance_id", "user_id", "ticket_number", "result", "session_issued_at",
			"queue_entered_at", "issued_at" };

	private final EventRepository events;

	private final EntityManager entityManager;

	private final NotificationService notifications;

	IssuanceHistoryStore(EventRepository events, EntityManager entityManager, NotificationService notifications) {
		this.events = events;
		this.entityManager = entityManager;
		this.notifications = notifications;
	}

	record Snapshot(long totalStock, long successCount, long soldoutCount, BigDecimal selloutRate, byte[] csv) {
	}

	/**
	 * issuance_history를 issuance_id 순으로 읽어 CSV와 결과 수를 만든다. CSV 형식(BOM · CRLF · 따옴표 · 시각 KST +09:00)은
	 * 리포트와 같은 {@link CsvWriter}. 시각은 저장된 KST 값 그대로다(date_format 문자열을 읽어 시간대 변환 없이).
	 * 엔티티를 거치지 않으므로 다른 tenant_id 행이 섞였는지 여기서 직접 확인한다 (§4-6).
	 */
	@SuppressWarnings("unchecked")
	@Transactional(readOnly = true)
	public Snapshot snapshot(Long eventId) {
		String tenantId = TenantContext.current();
		Event event = events.findById(eventId)
			.orElseThrow(() -> new IllegalStateException("registry에는 있고 event에는 없습니다: eventId=" + eventId));
		CsvWriter csv = new CsvWriter(CSV_HEADER);
		long success = 0;
		long soldout = 0;
		try (var rows = entityManager.createNativeQuery("""
				select issuance_id, user_id, ticket_number, result,
				       date_format(session_issued_at, '%Y-%m-%dT%H:%i:%s.%f'),
				       date_format(queue_entered_at, '%Y-%m-%dT%H:%i:%s.%f'),
				       date_format(issued_at, '%Y-%m-%dT%H:%i:%s.%f'),
				       tenant_id
				  from issuance_history
				 where event_id = :eventId
				 order by issuance_id""")
			.setParameter("eventId", eventId)
			.getResultStream()) {
			for (Object item : (Iterable<?>) rows::iterator) {
				Object[] row = (Object[]) item;
				if (!tenantId.equals(row[7])) {
					log.error("테넌트 불일치: issuance_history eventId={}, routedTenant={}", eventId, tenantId);
					throw new TenantMismatchException();
				}
				String result = row[3].toString();
				if ("SUCCESS".equals(result)) {
					success++;
				}
				else if ("FAILED_SOLDOUT".equals(result)) {
					soldout++;
				}
				csv.row(row[0], row[1], row[2], result, wallClock(row[4]), wallClock(row[5]), wallClock(row[6]));
			}
		}
		long total = event.getStockCount();
		return new Snapshot(total, success, soldout, selloutRate(eventId, success, total), csv.toBytes());
	}

	/**
	 * event_result_summary에 결과 수 · CSV 키를 쓰고, 처음 저장이면 같은 트랜잭션에서 테넌트에 집계 완료 알림을 남긴다.
	 * 1. 행이 없으면(집계 Job을 건너뜀 — 열린 적 없는 이벤트) 지표를 0으로 채워 만든다. 있으면 집계 Job 지표를 건드리지 않는다.
	 *    집계 Job(issuance-api AggregateJob)은 나중에 와도 지표 열만 덮어쓰므로 서로 겹치지 않는다. INSERT IGNORE는 다른 오류까지 삼켜 쓰지 않는다
	 * 2. history_generated_at은 비어 있을 때만 채운다(= 처음 저장 시각). 1행이면 처음 저장이다
	 *    (조건부 UPDATE라 재전달 · 동시 처리 · 재실행이 겹쳐도 한 번만 1행)
	 * 3. 결과 수 · CSV 키는 매번 덮어쓴다(같은 값으로 다시 써도 된다)
	 * @return 처음 저장이면 true
	 */
	@Transactional
	public boolean saveSummary(Long eventId, Snapshot snapshot, String historyKey, LocalDateTime now) {
		String tenantId = TenantContext.current();
		entityManager.createNativeQuery("""
				insert into event_result_summary (event_id, tenant_id, session_count, ticket_count, peak_concurrent,
				    peak_at, metrics_finalized_at)
				values (:eventId, :tenantId, 0, 0, 0, null, :now)
				on duplicate key update event_id = event_id""")
			.setParameter("eventId", eventId)
			.setParameter("tenantId", tenantId)
			.setParameter("now", now)
			.executeUpdate();
		Number foreign = (Number) entityManager
			.createNativeQuery("select count(*) from event_result_summary where event_id = :eventId and tenant_id <> :tenantId")
			.setParameter("eventId", eventId)
			.setParameter("tenantId", tenantId)
			.getSingleResult();
		if (foreign.longValue() > 0) {
			log.error("테넌트 불일치: event_result_summary eventId={}, routedTenant={}", eventId, tenantId);
			throw new TenantMismatchException();
		}
		boolean first = entityManager.createNativeQuery("""
				update event_result_summary set history_generated_at = :now
				 where event_id = :eventId and history_generated_at is null""")
			.setParameter("now", now)
			.setParameter("eventId", eventId)
			.executeUpdate() == 1;
		entityManager.createNativeQuery("""
				update event_result_summary set total_stock = :totalStock, success_count = :successCount,
				    soldout_count = :soldoutCount, sellout_rate = :selloutRate, history_file_path = :historyKey
				 where event_id = :eventId""")
			.setParameter("totalStock", snapshot.totalStock())
			.setParameter("successCount", snapshot.successCount())
			.setParameter("soldoutCount", snapshot.soldoutCount())
			.setParameter("selloutRate", snapshot.selloutRate())
			.setParameter("historyKey", historyKey)
			.setParameter("eventId", eventId)
			.executeUpdate();
		if (first) {
			notifications.eventAggregated(tenantId, eventId);
		}
		return first;
	}

	/** success ÷ total, 소수 4자리(반올림). total 0이면 0. 1을 넘으면(재고보다 성공이 많음) WARN 후 1 */
	static BigDecimal selloutRate(Long eventId, long success, long total) {
		if (total == 0) {
			return BigDecimal.ZERO.setScale(4);
		}
		BigDecimal rate = BigDecimal.valueOf(success).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
		if (rate.compareTo(BigDecimal.ONE) > 0) {
			log.warn("발급 성공 수가 총 재고보다 많습니다, 소진율을 1로 둡니다: eventId={}, success={}, total={}", eventId,
					success, total);
			return BigDecimal.ONE.setScale(4);
		}
		return rate;
	}

	/** date_format(…, '%f') 문자열(마이크로초 6자리) → KST 벽시계. CSV에는 밀리초 3자리까지(남는 자리는 버림) 나간다 */
	private static LocalDateTime wallClock(Object value) {
		return LocalDateTime.parse(value.toString());
	}

}
