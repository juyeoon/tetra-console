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

	/**
	 * 환경 준비 결과 반영. 상태 로그는 없고 운영자 알림만 남긴다.
	 * - 실패: PROVISIONING → FAILED
	 * - 성공: PROVISIONING → READY, 또는 FAILED → READY (실패 뒤 수동 재실행 tenant-{id}-rN이 성공한 경우)
	 * 이미 FAILED인데 실패가 다시 오면 그대로 둔다(승인 쪽 DEPLOY_FAILED → DEPLOYED와 같은 방식, 조건부 UPDATE).
	 * @return 바꿨으면 true, 바꿀 상태가 아니면(이미 처리됨) false
	 */
	@Transactional
	public boolean finishProvisioning(String tenantId, boolean succeeded, String detail) {
		boolean changed = succeeded
				? tenants.updateProvisioningStatus(tenantId, ProvisioningStatus.PROVISIONING, ProvisioningStatus.READY) == 1
						|| tenants.updateProvisioningStatus(tenantId, ProvisioningStatus.FAILED, ProvisioningStatus.READY) == 1
				: tenants.updateProvisioningStatus(tenantId, ProvisioningStatus.PROVISIONING, ProvisioningStatus.FAILED) == 1;
		if (changed) {
			notifications.provisioningFinished(tenantId, succeeded, detail);
		}
		return changed;
	}

	/**
	 * 환경 준비 성공 결과가 왔지만 테넌트 스키마가 없을 때: READY로 바꾸지 않는다.
	 * PROVISIONING이면 FAILED로 바꾸고 운영자 알림(PROVISIONING/URGENT)을 남긴다. 이미 FAILED면 그대로 두고 알림도 남기지 않는다
	 * (중복 메시지 · -rN 재실행이 또 와도 한 번만).
	 */
	@Transactional
	public void keepNotReadyWithoutSchema(String tenantId, String detail) {
		if (tenants.updateProvisioningStatus(tenantId, ProvisioningStatus.PROVISIONING, ProvisioningStatus.FAILED) == 1) {
			notifications.provisioningFinished(tenantId, false, detail);
		}
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
