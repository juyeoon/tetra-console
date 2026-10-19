package com.tetra.ops.tenant;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.tetra.ops.common.error.ApiException;
import com.tetra.ops.common.error.ErrorCode;
import com.tetra.ops.common.time.Kst;
import com.tetra.ops.domain.AccountStatus;
import com.tetra.ops.domain.ProvisioningStatus;
import com.tetra.ops.domain.platform.Tenant;
import com.tetra.ops.domain.platform.TenantRepository;
import com.tetra.ops.domain.platform.TenantStatusLog;
import com.tetra.ops.domain.platform.TenantStatusLogRepository;

import com.tetra.ops.notification.NotificationService;

import jakarta.persistence.EntityManager;

/** tetra_platform.tenant 쓰기 트랜잭션. 외부 호출은 하지 않는다. @Repository: flush 중 DB 오류를 DataIntegrityViolationException으로 바꾸려고 */
@Repository
class TenantWriter {

	static final String REGISTER_REASON = "테넌트 등록";

	private final TenantRepository tenants;

	private final TenantStatusLogRepository statusLogs;

	private final EntityManager entityManager;

	private final NotificationService notifications;

	private final Clock clock;

	TenantWriter(TenantRepository tenants, TenantStatusLogRepository statusLogs, EntityManager entityManager,
			NotificationService notifications, Clock clock) {
		this.notifications = notifications;
		this.tenants = tenants;
		this.statusLogs = statusLogs;
		this.entityManager = entityManager;
		this.clock = clock;
	}

	/**
	 * WF5 [3]: tenant INSERT(password NULL, INACTIVE, PROVISIONING) + tenant_status_log NULL → INACTIVE.
	 * PK·이메일 중복은 DataIntegrityViolationException으로 올라간다.
	 */
	@Transactional
	public Tenant insert(String tenantId, String name, String rootDomain, String email, String contactName) {
		LocalDateTime now = Kst.now(clock);
		Tenant tenant = Tenant.register(tenantId, name, rootDomain, email, contactName, now);
		// tenant_id를 앱이 정하므로 save(merge)가 아니라 persist로 넣는다. 같은 PK가 있으면 덮어쓰지 않고 1062가 난다.
		entityManager.persist(tenant);
		entityManager.flush();
		statusLogs.save(TenantStatusLog.of(tenantId, now, null, AccountStatus.INACTIVE, REGISTER_REASON, null));
		notifications.tenantRegistered(tenantId, name);
		return tenant;
	}

	/** provisioning_status PROVISIONING → READY / FAILED. 상태 로그는 없고 운영자 알림만 남긴다. @return 바꿨으면 true */
	@Transactional
	public boolean finishProvisioning(String tenantId, boolean succeeded, String detail) {
		ProvisioningStatus to = succeeded ? ProvisioningStatus.READY : ProvisioningStatus.FAILED;
		if (tenants.updateProvisioningStatus(tenantId, ProvisioningStatus.PROVISIONING, to) == 0) {
			return false;
		}
		notifications.provisioningFinished(tenantId, succeeded, detail);
		return true;
	}

	@Transactional
	public Tenant update(String tenantId, String name, String contactName, String email) {
		Tenant tenant = tenants.findById(tenantId).orElseThrow(() -> new ApiException(ErrorCode.TENANT_NOT_FOUND));
		if (name != null) {
			tenant.changeName(name);
		}
		if (contactName != null) {
			tenant.changeContactName(contactName);
		}
		if (email != null) {
			tenant.changeEmail(email);
		}
		entityManager.flush();
		return tenant;
	}

}
