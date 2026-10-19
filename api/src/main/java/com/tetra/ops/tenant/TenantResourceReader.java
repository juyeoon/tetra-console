package com.tetra.ops.tenant;

import java.util.List;
import java.util.Map;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.tetra.ops.domain.ApplicationStatus;
import com.tetra.ops.domain.AuthKeyStatus;
import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.tenant.Event;
import com.tetra.ops.domain.tenant.EventAuthKey;
import com.tetra.ops.domain.tenant.EventAuthKeyRepository;
import com.tetra.ops.domain.tenant.EventRepository;
import com.tetra.ops.tenant.TenantQueryService.TenantResourceItem;

/** OPS-06-3 리소스. TenantRouter.inTenant 안에서만 부른다. */
@Component
class TenantResourceReader {

	private final EventRepository events;

	private final EventAuthKeyRepository authKeys;

	TenantResourceReader(EventRepository events, EventAuthKeyRepository authKeys) {
		this.events = events;
		this.authKeys = authKeys;
	}

	/**
	 * 승인된 이벤트만. 서브 도메인 {slug}.{tenant_id}, 공개키는 ACTIVE 중 최신 버전의 PEM 원문(공개 값이라 마스킹하지 않음).
	 * event_status = EXPIRED이면 서브 도메인·공개키 null.
	 * TODO(미정): 정렬 기준이 설계서에 없음. 임시로 신청시각 내림차순(06-2와 같게)
	 */
	@Transactional(readOnly = true)
	public List<TenantResourceItem> approvedEventResources() {
		List<Event> approved = events.findByApplicationStatusOrderByAppliedAtDescEventIdDesc(ApplicationStatus.APPROVED);
		if (approved.isEmpty()) {
			return List.of();
		}
		BinaryOperator<EventAuthKey> latest = (a, b) -> a.getKeyVersion() >= b.getKeyVersion() ? a : b;
		Map<Long, EventAuthKey> activeKeys = authKeys
			.findByEventIdInAndStatus(approved.stream().map(Event::getEventId).toList(), AuthKeyStatus.ACTIVE)
			.stream()
			.collect(Collectors.toMap(EventAuthKey::getEventId, k -> k, latest));
		return approved.stream().map(event -> {
			boolean expired = event.getEventStatus() == EventStatus.EXPIRED;
			EventAuthKey key = activeKeys.get(event.getEventId());
			return new TenantResourceItem(event.getEventId(), event.getName(), event.getEventStatus(),
					expired ? null : event.getSlug() + "." + event.getTenantId(),
					expired || key == null ? null : key.getPublicKey());
		}).toList();
	}

}
