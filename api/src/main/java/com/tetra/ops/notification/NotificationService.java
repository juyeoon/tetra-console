package com.tetra.ops.notification;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.tetra.ops.common.time.Kst;
import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.HostingStatus;
import com.tetra.ops.domain.platform.EventRegistry;
import com.tetra.ops.domain.platform.EventRegistryRepository;
import com.tetra.ops.domain.platform.OperatorNotification;
import com.tetra.ops.domain.platform.OperatorNotificationRepository;
import com.tetra.ops.domain.platform.TenantRepository;
import com.tetra.ops.domain.tenant.TenantNotification;
import com.tetra.ops.domain.tenant.TenantNotificationRepository;
import com.tetra.ops.tenancy.TenantContext;

/**
 * 운영자 알림(operator_notification)·테넌트 알림(tenant_notification)을 남기는 유일한 곳 (CLAUDE.md §7.13).
 * 상태 변경은 상태 로그와 같은 트랜잭션에서 부른다(MANDATORY). 한 사건에 알림은 한 번만 남긴다.
 * 메일 발송 실패처럼 커밋 뒤에 생기는 일은 자체 트랜잭션으로 남긴다.
 * TODO(미정): 알림 문구. category 값은 상태 구분 기준(APPLICATION·HOSTING·EVENT·ACCOUNT·PROVISIONING·MAIL)으로 임시 사용
 */
@Service
public class NotificationService {

	static final int CONTENT_MAX = 1000;

	enum Category {

		APPLICATION, HOSTING, EVENT, ACCOUNT, PROVISIONING, MAIL

	}

	enum Priority {

		URGENT, WARNING, INFO

	}

	private final OperatorNotificationRepository operatorNotifications;

	private final TenantNotificationRepository tenantNotifications;

	private final TenantRepository tenants;

	private final EventRegistryRepository registries;

	private final Clock clock;

	public NotificationService(OperatorNotificationRepository operatorNotifications,
			TenantNotificationRepository tenantNotifications, TenantRepository tenants,
			EventRegistryRepository registries, Clock clock) {
		this.operatorNotifications = operatorNotifications;
		this.tenantNotifications = tenantNotifications;
		this.tenants = tenants;
		this.registries = registries;
		this.clock = clock;
	}

	// ------------------------------------------------------------ 신청 (APPLICATION)

	/** APPLIED → APPROVED (+ 호스팅 PENDING, 이벤트 SCHEDULED). 운영자·테넌트 INFO. 테넌트 스키마 라우팅 안에서 부른다. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void eventApproved(String tenantId, Long eventId, String eventName) {
		operator(tenantId, eventId, Category.APPLICATION, Priority.INFO,
				tenantLabel(tenantId) + " [" + eventName + "] 이벤트 신청을 승인했습니다.");
		tenant(tenantId, eventId, Category.APPLICATION, Priority.INFO,
				"[" + eventName + "] 이벤트 신청이 승인되었습니다.");
	}

	/** APPLIED → REJECTED. 운영자·테넌트 WARNING. 반려 사유를 담는다(이벤트명 100자 + 사유 500자). */
	@Transactional(propagation = Propagation.MANDATORY)
	public void eventRejected(String tenantId, Long eventId, String eventName, String reason) {
		operator(tenantId, eventId, Category.APPLICATION, Priority.WARNING,
				tenantLabel(tenantId) + " [" + eventName + "] 이벤트 신청을 반려했습니다. 반려 사유: " + reason);
		tenant(tenantId, eventId, Category.APPLICATION, Priority.WARNING,
				"[" + eventName + "] 이벤트 신청이 반려되었습니다. 반려 사유: " + reason);
	}

	// ------------------------------------------------------------ 호스팅 (HOSTING)

	/**
	 * 호스팅 상태 변화(승인 등록 WF1-2 · 호스팅 시작 WF2-1 · 종료 실패 WF4). 테넌트 알림은 남기지 않는다(내부 인프라 단계).
	 * → DEPLOYED: INFO(DEPLOY_FAILED에서면 복구) / → DEPLOY_FAILED: URGENT
	 * → HOSTING: INFO(HOSTING_FAILED에서면 복구) / → HOSTING_FAILED: URGENT / → END_FAILED: URGENT
	 * 종료 성공(→ ENDED)은 eventEnded 한 건으로 남긴다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void hostingChanged(String tenantId, Long eventId, HostingStatus from, HostingStatus to, String detail) {
		String label = tenantLabel(tenantId) + " [" + eventName(eventId) + "]";
		if (to == HostingStatus.DEPLOY_FAILED) {
			operator(tenantId, eventId, Category.HOSTING, Priority.URGENT,
					label + " 이벤트 배포에 실패했습니다. 확인이 필요합니다." + (detail == null ? "" : " 원인: " + detail));
		}
		else if (to == HostingStatus.DEPLOYED && from == HostingStatus.DEPLOY_FAILED) {
			operator(tenantId, eventId, Category.HOSTING, Priority.INFO,
					label + " 배포 실패로 표시됐던 이벤트의 배포가 완료되었습니다.");
		}
		else if (to == HostingStatus.DEPLOYED) {
			operator(tenantId, eventId, Category.HOSTING, Priority.INFO, label + " 이벤트 배포가 완료되었습니다.");
		}
		else if (to == HostingStatus.HOSTING && from == HostingStatus.HOSTING_FAILED) {
			operator(tenantId, eventId, Category.HOSTING, Priority.INFO,
					label + " 호스팅 시작 실패로 표시됐던 이벤트의 호스팅이 시작되었습니다.");
		}
		else if (to == HostingStatus.HOSTING) {
			operator(tenantId, eventId, Category.HOSTING, Priority.INFO, label + " 호스팅을 시작했습니다(대기방 열림).");
		}
		else if (to == HostingStatus.HOSTING_FAILED) {
			operator(tenantId, eventId, Category.HOSTING, Priority.URGENT,
					label + " 호스팅 시작(대기방 열기)에 실패했습니다. 확인이 필요합니다." + (detail == null ? "" : " 원인: " + detail));
		}
		else if (to == HostingStatus.END_FAILED) {
			operator(tenantId, eventId, Category.HOSTING, Priority.URGENT,
					label + " 이벤트 종료 처리에 실패했습니다. 확인이 필요합니다." + (detail == null ? "" : " 원인: " + detail));
		}
	}

	// ------------------------------------------------------------ 이벤트 (EVENT)

	/** 이벤트 준비 결과(WF2-2). → OPEN: INFO(OPEN_FAILED에서면 복구) / → OPEN_FAILED: URGENT. 만료는 eventEnded. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void eventStatusChanged(String tenantId, Long eventId, EventStatus from, EventStatus to, String detail) {
		String label = tenantLabel(tenantId) + " [" + eventName(eventId) + "]";
		if (to == EventStatus.OPEN && from == EventStatus.OPEN_FAILED) {
			operator(tenantId, eventId, Category.EVENT, Priority.INFO, label + " 이벤트 준비 실패로 표시됐던 이벤트의 입장이 열렸습니다.");
		}
		else if (to == EventStatus.OPEN) {
			operator(tenantId, eventId, Category.EVENT, Priority.INFO, label + " 입장이 열렸습니다(쿠폰 발급은 이벤트 시작 시각부터).");
		}
		else if (to == EventStatus.OPEN_FAILED) {
			operator(tenantId, eventId, Category.EVENT, Priority.URGENT,
					label + " 이벤트 준비(입장 열기)에 실패했습니다. 확인이 필요합니다." + (detail == null ? "" : " 원인: " + detail));
		}
	}

	/** 이벤트 종료 성공(WF4: 호스팅 종료 · 발급 마감). 운영자 INFO 1건. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void eventEnded(String tenantId, Long eventId) {
		operator(tenantId, eventId, Category.HOSTING, Priority.INFO,
				tenantLabel(tenantId) + " [" + eventName(eventId) + "] 이벤트가 종료되었습니다(호스팅 종료 · 발급 마감).");
	}

	// ------------------------------------------------------------ 계정 (ACCOUNT)

	/** 테넌트 등록 (NULL → INACTIVE). 운영자 INFO. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void tenantRegistered(String tenantId, String tenantName) {
		operator(tenantId, null, Category.ACCOUNT, Priority.INFO,
				"[" + tenantName + "] 테넌트가 등록되었습니다. 환경 준비를 시작합니다.");
	}

	/** 자동 비활성 (ACTIVE → INACTIVE). 운영자 INFO. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void tenantDeactivated(String tenantId, Long relatedEventId) {
		operator(tenantId, relatedEventId, Category.ACCOUNT, Priority.INFO,
				tenantLabel(tenantId) + " 진행 중인 이벤트가 없어 계정이 비활성으로 전환되었습니다.");
	}

	// ------------------------------------------------------------ 환경 준비 (PROVISIONING, 상태 로그 없음)

	/** PROVISIONING → READY: INFO, → FAILED: URGENT. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void provisioningFinished(String tenantId, boolean ready, String detail) {
		if (ready) {
			operator(tenantId, null, Category.PROVISIONING, Priority.INFO,
					tenantLabel(tenantId) + " 테넌트 환경 준비가 완료되었습니다.");
		}
		else {
			operator(tenantId, null, Category.PROVISIONING, Priority.URGENT,
					tenantLabel(tenantId) + " 테넌트 환경 준비에 실패했습니다. 확인이 필요합니다."
							+ (detail == null ? "" : " 원인: " + detail));
		}
	}

	// ------------------------------------------------------------ 메일 (커밋 뒤)

	/** 개인키 메일 발송 실패. 키 재발급이 필요하다. 운영자 URGENT. */
	@Transactional
	public void privateKeyMailFailed(String tenantId, Long eventId) {
		operator(tenantId, eventId, Category.MAIL, Priority.URGENT,
				tenantLabel(tenantId) + " [" + eventName(eventId) + "] 개인키 메일 발송에 실패했습니다. 키 재발급이 필요합니다.");
	}

	/** 비밀번호 설정 메일 발송 실패(환경 준비 완료 뒤 결과 리스너에서 발송). 재처리하지 않는다. 운영자 URGENT. */
	@Transactional
	public void passwordSetupMailFailed(String tenantId) {
		operator(tenantId, null, Category.MAIL, Priority.URGENT,
				"비밀번호 설정 메일 발송에 실패했습니다(테넌트 " + tenantId + "). 담당자 이메일 주소와 SES 상태를 확인해 주세요.");
	}

	// ------------------------------------------------------------

	private void operator(String tenantId, Long eventId, Category category, Priority priority, String content) {
		operatorNotifications.save(OperatorNotification.unread(tenantId, eventId, category.name(), priority.name(),
				Kst.now(clock), fit(content)));
	}

	/** tenant_notification은 테넌트 스키마 테이블이라 라우팅된 테넌트와 같아야 한다(이중 확인은 엔티티 리스너가 한다). */
	private void tenant(String tenantId, Long eventId, Category category, Priority priority, String content) {
		if (!tenantId.equals(TenantContext.current())) {
			throw new IllegalStateException("테넌트 알림은 해당 테넌트 스키마 라우팅 안에서만 남길 수 있습니다.");
		}
		tenantNotifications.save(TenantNotification.unread(tenantId, eventId, category.name(), priority.name(),
				Kst.now(clock), fit(content)));
	}

	private String tenantLabel(String tenantId) {
		return tenants.findById(tenantId).map(t -> "[" + t.getName() + "]").orElse("[" + tenantId + "]");
	}

	private String eventName(Long eventId) {
		return registries.findById(eventId).map(EventRegistry::getName).orElse(String.valueOf(eventId));
	}

	static String fit(String content) {
		return content.length() <= CONTENT_MAX ? content : content.substring(0, CONTENT_MAX);
	}

}
