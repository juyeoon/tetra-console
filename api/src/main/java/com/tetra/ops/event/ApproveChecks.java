package com.tetra.ops.event;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.tetra.ops.common.error.ApiException;
import com.tetra.ops.common.error.ErrorCode;
import com.tetra.ops.common.time.Kst;
import com.tetra.ops.config.TetraProperties;
import com.tetra.ops.domain.platform.EventRegistry;
import com.tetra.ops.domain.platform.EventRegistryRepository;
import com.tetra.ops.domain.tenant.Event;
import com.tetra.ops.domain.tenant.EventRepository;
import com.tetra.ops.event.ApproveRules.Window;
import com.tetra.ops.tenancy.TenantIds;

import jakarta.persistence.EntityManager;

/**
 * 승인 전 검사(3-13). TenantRouter.inTenant 안에서 부른다.
 * - verify: 키를 만들기 전에 신청 데이터 규칙 + 동시 이벤트 수를 본다(잠금 없음, 빨리 거절하려는 것)
 * - verifyCapacity: 승인 트랜잭션 안에서 전역 잠금(ApproveCapacityLock)을 잡은 뒤 동시 이벤트 수를 다시 센다
 * 위반하면 409 APPLICATION_DATA_INVALID, fields = 사유 코드 → 문구.
 */
@Component
class ApproveChecks {

	private static final Logger log = LoggerFactory.getLogger(ApproveChecks.class);

	private final EventRepository events;

	private final EventRegistryRepository registries;

	private final EntityManager entityManager;

	private final Clock clock;

	private final int maxConcurrentEvents;

	ApproveChecks(EventRepository events, EventRegistryRepository registries, EntityManager entityManager, Clock clock,
			TetraProperties properties) {
		this.events = events;
		this.registries = registries;
		this.entityManager = entityManager;
		this.clock = clock;
		this.maxConcurrentEvents = properties.approve().maxConcurrentEvents();
	}

	@Transactional(readOnly = true)
	public void verify(Long eventId) {
		Event event = events.findById(eventId)
			.orElseThrow(() -> new IllegalStateException("registry에는 있고 event에는 없습니다: eventId=" + eventId));
		Map<String, String> reasons = ApproveRules.dataReasons(event.getName(), event.getEndpointUrl(),
				event.getHostingStartAt(), event.getStartAt(), Kst.now(clock));
		capacityReason(event).ifPresent(reason -> reasons.put(ApproveRules.CONCURRENT_EVENT_LIMIT, reason));
		if (!reasons.isEmpty()) {
			log.info("승인 전 검사 실패: eventId={}, reasons={}", eventId, reasons.keySet());
			throw new ApiException(ErrorCode.APPLICATION_DATA_INVALID, reasons);
		}
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void verifyCapacity(Event event) {
		Optional<String> reason = capacityReason(event);
		if (reason.isPresent()) {
			log.info("승인 전 검사 실패(잠금 후): eventId={}, reasons=[CONCURRENT_EVENT_LIMIT]", event.getEventId());
			throw new ApiException(ErrorCode.APPLICATION_DATA_INVALID,
					Map.of(ApproveRules.CONCURRENT_EVENT_LIMIT, reason.get()));
		}
	}

	/**
	 * 대상: 모든 테넌트의 APPROVED이고 EXPIRED가 아닌 이벤트(실패 상태 포함, 보수적으로 셈).
	 * registry에 hosting_end_at이 없어 각 테넌트 스키마 event에서 시작 · 호스팅 종료 시각을 읽는다.
	 */
	private Optional<String> capacityReason(Event event) {
		List<Window> others = new ArrayList<>();
		for (EventRegistry registry : registries.findApprovedNotExpired()) {
			if (registry.getEventId().equals(event.getEventId())) {
				continue;
			}
			Window window = redisWindowOf(registry.getTenantId(), registry.getEventId());
			if (window == null) {
				log.warn("동시 이벤트 계산: registry에는 있고 event에는 없습니다, 건너뜁니다: eventId={}", registry.getEventId());
				continue;
			}
			others.add(window);
		}
		Window target = ApproveRules.redisWindow(event.getStartAt(), event.getHostingEndAt());
		return ApproveRules.capacityReason(target, others, maxConcurrentEvents);
	}

	/**
	 * t_{tenant_id}.event의 start_at · hosting_end_at. 스키마 이름은 바인딩할 수 없어 문자열로 붙이므로
	 * tenant_id 형식(소문자 · 숫자 12자)을 먼저 확인한다. 일시는 저장된 KST 값 그대로 읽는다.
	 */
	@SuppressWarnings("unchecked")
	private Window redisWindowOf(String tenantId, Long eventId) {
		if (!TenantIds.isValid(tenantId)) {
			throw new IllegalStateException("event_registry.tenant_id 형식 오류: eventId=" + eventId);
		}
		String sql = "select date_format(start_at, '%Y-%m-%dT%H:%i:%s'), date_format(hosting_end_at, '%Y-%m-%dT%H:%i:%s')"
				+ " from t_" + tenantId + ".event where event_id = :eventId and tenant_id = :tenantId";
		List<Object[]> rows = entityManager.createNativeQuery(sql)
			.setParameter("eventId", eventId)
			.setParameter("tenantId", tenantId)
			.getResultList();
		if (rows.isEmpty()) {
			return null;
		}
		Object[] row = rows.get(0);
		return ApproveRules.redisWindow(LocalDateTime.parse(row[0].toString()), LocalDateTime.parse(row[1].toString()));
	}

}
