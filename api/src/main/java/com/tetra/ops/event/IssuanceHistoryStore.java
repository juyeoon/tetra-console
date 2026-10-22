package com.tetra.ops.event;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.tetra.ops.domain.tenant.Event;
import com.tetra.ops.domain.tenant.EventRepository;
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

	static final String CSV_HEADER = "issuance_id,user_id,ticket_number,result,session_issued_at,queue_entered_at,issued_at";

	/** Excel(한국어 Windows)에서 user_id가 깨지지 않도록 UTF-8 BOM을 붙인다 */
	private static final byte[] BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

	private final EventRepository events;

	private final EntityManager entityManager;

	IssuanceHistoryStore(EventRepository events, EntityManager entityManager) {
		this.events = events;
		this.entityManager = entityManager;
	}

	record Snapshot(long totalStock, long successCount, long soldoutCount, BigDecimal selloutRate, byte[] csv) {
	}

	/**
	 * issuance_history를 issuance_id 순으로 읽어 CSV와 결과 수를 만든다. 시각은 저장된 KST 값 그대로 +09:00을 붙인다.
	 * 엔티티를 거치지 않으므로 다른 tenant_id 행이 섞였는지 여기서 직접 확인한다 (§4-6).
	 */
	@SuppressWarnings("unchecked")
	@Transactional(readOnly = true)
	public Snapshot snapshot(Long eventId) {
		String tenantId = TenantContext.current();
		Event event = events.findById(eventId)
			.orElseThrow(() -> new IllegalStateException("registry에는 있고 event에는 없습니다: eventId=" + eventId));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(BOM);
		StringBuilder csv = new StringBuilder(CSV_HEADER).append("\r\n");
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
				csv.append(row[0])
					.append(',')
					.append(csvField(row[1].toString()))
					.append(',')
					.append(row[2])
					.append(',')
					.append(result)
					.append(',')
					.append(kst(row[4]))
					.append(',')
					.append(kst(row[5]))
					.append(',')
					.append(kst(row[6]))
					.append("\r\n");
			}
		}
		out.writeBytes(csv.toString().getBytes(StandardCharsets.UTF_8));
		long total = event.getStockCount();
		return new Snapshot(total, success, soldout, selloutRate(eventId, success, total), out.toByteArray());
	}

	/**
	 * event_result_summary에 결과 수 · CSV 키를 쓴다. 집계 Job이 만든 행이 있으면 그 지표는 두고 발급 이력 쪽만 바꾸고,
	 * 없으면(집계 Job을 건너뜀 — 열린 적 없는 이벤트) 지표를 0으로 채워 만든다. 같은 값으로 다시 써도 된다.
	 */
	@Transactional
	public void saveSummary(Long eventId, Snapshot snapshot, String historyKey, LocalDateTime now) {
		String tenantId = TenantContext.current();
		entityManager.createNativeQuery("""
				insert into event_result_summary (event_id, tenant_id, session_count, ticket_count, peak_concurrent,
				    peak_at, metrics_finalized_at, total_stock, success_count, soldout_count, sellout_rate,
				    history_file_path, history_generated_at)
				values (:eventId, :tenantId, 0, 0, 0, null, :now, :totalStock, :successCount, :soldoutCount,
				    :selloutRate, :historyKey, :now) as new
				on duplicate key update total_stock = new.total_stock, success_count = new.success_count,
				    soldout_count = new.soldout_count, sellout_rate = new.sellout_rate,
				    history_file_path = new.history_file_path, history_generated_at = new.history_generated_at""")
			.setParameter("eventId", eventId)
			.setParameter("tenantId", tenantId)
			.setParameter("now", now)
			.setParameter("totalStock", snapshot.totalStock())
			.setParameter("successCount", snapshot.successCount())
			.setParameter("soldoutCount", snapshot.soldoutCount())
			.setParameter("selloutRate", snapshot.selloutRate())
			.setParameter("historyKey", historyKey)
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

	/** date_format(…, '%f')는 마이크로초 6자리 → 밀리초 3자리 + KST 오프셋 */
	private static String kst(Object value) {
		String text = value.toString();
		return text.substring(0, "yyyy-MM-ddTHH:mm:ss.SSS".length()) + "+09:00";
	}

	/** RFC 4180: 쉼표 · 큰따옴표 · 줄바꿈이 있으면 큰따옴표로 감싸고 안의 큰따옴표는 두 번 쓴다 */
	static String csvField(String value) {
		if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
			return value;
		}
		return '"' + value.replace("\"", "\"\"") + '"';
	}

}
