package com.tetra.ops.event;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.tetra.ops.domain.tenant.EventRepository;

/**
 * OPS-04 진행 목록의 종료 시각 읽기(조회 전용). registry에 end_at · hosting_end_at이 없어 테넌트 스키마 event에서 읽는다.
 * TenantRouter.inTenant 안에서만 부른다. 읽은 행의 tenant_id 이중 확인은 TenantGuardListener(다르면 ERROR 로그 + 예외).
 */
@Component
class LiveEventTimesReader {

	/** endAt = event.end_at(이벤트 종료), hostingEndAt = event.hosting_end_at(호스팅 종료) */
	record Times(LocalDateTime endAt, LocalDateTime hostingEndAt) {
	}

	private final EventRepository events;

	LiveEventTimesReader(EventRepository events) {
		this.events = events;
	}

	/** 이벤트 id → 종료 시각. 테넌트 스키마에 행이 없는 id는 결과에 없다. */
	@Transactional(readOnly = true)
	public Map<Long, Times> read(Collection<Long> eventIds) {
		Map<Long, Times> times = new HashMap<>();
		if (eventIds.isEmpty()) {
			return times;
		}
		events.findAllById(eventIds).forEach(e -> times.put(e.getEventId(), new Times(e.getEndAt(), e.getHostingEndAt())));
		return times;
	}

}
