package com.tetra.ops.event;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tetra.ops.common.error.ApiException;
import com.tetra.ops.common.error.ErrorCode;
import com.tetra.ops.common.time.Kst;
import com.tetra.ops.domain.ApplicationStatus;
import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.HostingStatus;
import com.tetra.ops.domain.StatusType;
import com.tetra.ops.domain.platform.EventRegistryRepository;
import com.tetra.ops.domain.tenant.Event;
import com.tetra.ops.domain.tenant.EventAuthKey;
import com.tetra.ops.domain.tenant.EventAuthKeyRepository;
import com.tetra.ops.domain.tenant.EventRepository;
import com.tetra.ops.domain.tenant.EventStatusLog;
import com.tetra.ops.domain.tenant.EventStatusLogRepository;
import com.tetra.ops.notification.NotificationService;
import com.tetra.ops.tenant.TenantActivityService;
import com.tetra.ops.tenancy.TenantContext;

/**
 * 이벤트 상태를 바꾸는 유일한 서비스 (§5).
 * 한 트랜잭션에서 event_registry → event 순서로 조건부 UPDATE하고 event_status_log와 알림(§7.13)을 남긴다.
 * registry(tetra_platform, catalog 지정)와 event(테넌트 스키마)는 같은 커넥션을 쓴다.
 * 반드시 TenantRouter.inTenant 안에서 불러야 한다. 외부 호출은 하지 않는다.
 */
@Service
public class EventStatusService {

	static final int DETAIL_MAX = 500;

	private final EventRegistryRepository registries;

	private final EventRepository events;

	private final EventAuthKeyRepository authKeys;

	private final EventStatusLogRepository statusLogs;

	private final NotificationService notifications;

	private final TenantActivityService tenantActivity;

	private final Clock clock;

	public EventStatusService(EventRegistryRepository registries, EventRepository events,
			EventAuthKeyRepository authKeys, EventStatusLogRepository statusLogs,
			NotificationService notifications, TenantActivityService tenantActivity, Clock clock) {
		this.registries = registries;
		this.events = events;
		this.authKeys = authKeys;
		this.statusLogs = statusLogs;
		this.notifications = notifications;
		this.tenantActivity = tenantActivity;
		this.clock = clock;
	}

	/**
	 * WF1-2 [3] 승인. APPLIED가 아니면 409, 호스팅 종료 시각이 지났으면 409(HOSTING_PERIOD_ENDED, 롤백).
	 * 같은 트랜잭션에서 운영자·테넌트 알림을 남긴다.
	 * slug 중복(uk_event_slug)이면 DataIntegrityViolationException이 나고 트랜잭션 전체가 롤백된다. 호출 쪽에서 새 slug로 다시 부른다.
	 */
	@Transactional
	public Event approve(Long eventId, Long operatorId, String slug, String publicKeyPem) {
		String tenantId = routedTenant();
		LocalDateTime now = Kst.now(clock);
		if (registries.approve(eventId, now) == 0) {
			throw new ApiException(ErrorCode.APPLICATION_NOT_APPLIED);
		}
		requireOneRow(events.approve(eventId, tenantId, operatorId, now, slug), eventId);
		Event event = events.findById(eventId).orElseThrow(() -> mismatch(eventId));
		// 인증키 만료(= hosting_end_at)가 발급 시각보다 뒤여야 한다 (chk_auth_key_expires)
		if (!event.getHostingEndAt().isAfter(now)) {
			throw new ApiException(ErrorCode.HOSTING_PERIOD_ENDED);
		}
		authKeys.save(EventAuthKey.firstActive(eventId, tenantId, publicKeyPem, now, event.getHostingEndAt()));
		statusLogs.saveAll(List.of(
				EventStatusLog.of(eventId, tenantId, StatusType.APPLICATION, ApplicationStatus.APPLIED,
						ApplicationStatus.APPROVED, now, null, operatorId),
				EventStatusLog.of(eventId, tenantId, StatusType.HOSTING, null, HostingStatus.PENDING, now, null, null),
				EventStatusLog.of(eventId, tenantId, StatusType.EVENT, null, EventStatus.SCHEDULED, now, null, null)));
		notifications.eventApproved(tenantId, eventId, event.getName());
		return event;
	}

	/** WF1-3 [2] 반려. APPLIED가 아니면 409. 같은 트랜잭션에서 운영자·테넌트 알림을 남기고 테넌트 자동 비활성을 판정한다. */
	@Transactional
	public void reject(Long eventId, Long operatorId, String reason) {
		String tenantId = routedTenant();
		LocalDateTime now = Kst.now(clock);
		if (registries.reject(eventId, now) == 0) {
			throw new ApiException(ErrorCode.APPLICATION_NOT_APPLIED);
		}
		requireOneRow(events.reject(eventId, tenantId, operatorId, now, reason), eventId);
		statusLogs.save(EventStatusLog.of(eventId, tenantId, StatusType.APPLICATION, ApplicationStatus.APPLIED,
				ApplicationStatus.REJECTED, now, reason, operatorId));
		Event event = events.findById(eventId).orElseThrow(() -> mismatch(eventId));
		notifications.eventRejected(tenantId, eventId, event.getName(), reason);
		tenantActivity.deactivateIfIdle(tenantId, eventId);
	}

	/**
	 * 승인 등록 결과 반영. StartExecution 실패(승인 API)와 SQS 결과 리스너가 함께 쓴다.
	 * - 실패: PENDING → DEPLOY_FAILED
	 * - 성공: PENDING → DEPLOYED, 또는 DEPLOY_FAILED → DEPLOYED
	 *   (StartExecution 호출이 시간 초과로 실패로 보였지만 실제로는 실행이 시작된 경우를 바로잡는다)
	 * @return 바꿨으면 true, 바꿀 상태가 아니면(이미 처리됨) false
	 */
	@Transactional
	public boolean applyDeployResult(Long eventId, boolean succeeded, String detail) {
		if (!succeeded) {
			return changeHostingStatus(eventId, HostingStatus.PENDING, HostingStatus.DEPLOY_FAILED, detail);
		}
		return changeHostingStatus(eventId, HostingStatus.PENDING, HostingStatus.DEPLOYED, detail)
				|| changeHostingStatus(eventId, HostingStatus.DEPLOY_FAILED, HostingStatus.DEPLOYED, detail);
	}

	private boolean changeHostingStatus(Long eventId, HostingStatus from, HostingStatus to, String detail) {
		String tenantId = routedTenant();
		if (registries.updateHostingStatus(eventId, from, to) == 0) {
			return false;
		}
		requireOneRow(events.updateHostingStatus(eventId, tenantId, from, to), eventId);
		statusLogs.save(EventStatusLog.of(eventId, tenantId, StatusType.HOSTING, from, to, Kst.now(clock),
				truncate(detail), null));
		notifications.hostingChanged(tenantId, eventId, from, to, truncate(detail));
		return true;
	}

	private static String routedTenant() {
		String tenantId = TenantContext.current();
		if (tenantId == null) {
			throw new IllegalStateException("테넌트 라우팅 없이 이벤트 상태를 바꿀 수 없습니다.");
		}
		return tenantId;
	}

	/** registry는 바뀌었는데 event가 안 바뀌면 둘이 어긋난 것. 예외로 트랜잭션을 되돌린다. */
	private static void requireOneRow(int updated, Long eventId) {
		if (updated != 1) {
			throw mismatch(eventId);
		}
	}

	private static IllegalStateException mismatch(Long eventId) {
		return new IllegalStateException("event_registry와 event의 상태가 어긋납니다: eventId=" + eventId);
	}

	static String truncate(String detail) {
		if (detail == null || detail.length() <= DETAIL_MAX) {
			return detail;
		}
		return detail.substring(0, DETAIL_MAX);
	}

}
