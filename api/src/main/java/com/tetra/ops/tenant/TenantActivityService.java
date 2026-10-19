package com.tetra.ops.tenant;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.tetra.ops.common.time.Kst;
import com.tetra.ops.domain.AccountStatus;
import com.tetra.ops.domain.platform.EventRegistryRepository;
import com.tetra.ops.domain.platform.Tenant;
import com.tetra.ops.domain.platform.TenantRepository;
import com.tetra.ops.domain.platform.TenantStatusLog;
import com.tetra.ops.domain.platform.TenantStatusLogRepository;
import com.tetra.ops.notification.NotificationService;

/**
 * 테넌트 비활성 판정을 한 메서드로 둔다 (§5). WF1-3 반려가 쓰고, WF4 종료도 같은 메서드를 쓴다.
 * 호출한 쪽의 트랜잭션 안에서만 동작한다.
 */
@Service
public class TenantActivityService {

	private final TenantRepository tenants;

	private final EventRegistryRepository registries;

	private final TenantStatusLogRepository statusLogs;

	private final NotificationService notifications;

	private final Clock clock;

	public TenantActivityService(TenantRepository tenants, EventRegistryRepository registries,
			TenantStatusLogRepository statusLogs, NotificationService notifications, Clock clock) {
		this.notifications = notifications;
		this.tenants = tenants;
		this.registries = registries;
		this.statusLogs = statusLogs;
		this.clock = clock;
	}

	/**
	 * 같은 tenant_id의 다른 이벤트 중 APPLIED 또는 (APPROVED이고 SCHEDULED·OPEN)이 없고 지금 ACTIVE면 INACTIVE로 바꾼다.
	 * @return 비활성으로 바꿨으면 true
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean deactivateIfIdle(String tenantId, Long eventId) {
		Tenant tenant = tenants.findByIdForUpdate(tenantId)
			.orElseThrow(() -> new IllegalStateException("테넌트가 없습니다: " + tenantId));
		if (tenant.getAccountStatus() != AccountStatus.ACTIVE) {
			return false;
		}
		if (registries.countRemainingEvents(tenantId, eventId) > 0) {
			return false;
		}
		if (tenants.updateAccountStatus(tenantId, AccountStatus.ACTIVE, AccountStatus.INACTIVE) == 0) {
			return false;
		}
		statusLogs.save(TenantStatusLog.of(tenantId, Kst.now(clock), AccountStatus.ACTIVE, AccountStatus.INACTIVE, null,
				eventId));
		notifications.tenantDeactivated(tenantId, eventId);
		return true;
	}

}
