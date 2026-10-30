package com.tetra.ops.domain.platform;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.tetra.ops.domain.AccountStatus;
import com.tetra.ops.domain.ApplicationStatus;
import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.HostingStatus;
import com.tetra.ops.domain.ProvisioningStatus;

public interface OperatorNotificationRepository extends JpaRepository<OperatorNotification, Long> {

	/** 알림과 테넌트명. 테넌트 무관 알림(tenant_id NULL)은 tenantName이 null. */
	record WithTenantName(OperatorNotification notification, String tenantName) {
	}

	/** OPS-02 최근 알림 5건 (idx_operator_notif_occurred). */
	List<OperatorNotification> findTop5ByOrderByOccurredAtDescNotificationIdDesc();

	/** 1분 주기 확인: 같은 중복 방지 키 알림이 이미 있는지 (uk_operator_notif_dedupe). */
	boolean existsByDedupeKey(String dedupeKey);

	/**
	 * OPS-08 알림센터 한 줄: 알림 + 테넌트(이름 · 환경 준비 · 계정 · 비밀번호 설정 여부) + 이벤트(registry 상태 3개)의 현재 값.
	 * 대상이 없으면(tenant_id · event_id NULL, 행 없음) 그쪽 값은 null이고 passwordSet은 false. tenantName이 null이면 테넌트 행이 없다
	 * (tenant.name NOT NULL). password는 읽지 않고 IS NOT NULL만 본다.
	 */
	record CenterRow(OperatorNotification notification, String tenantName, ProvisioningStatus provisioningStatus,
			AccountStatus accountStatus, boolean passwordSet, ApplicationStatus applicationStatus,
			HostingStatus hostingStatus, EventStatus eventStatus) {
	}

	/** OPS-08 전체, 최신순 (idx_operator_notif_occurred). 페이지네이션 없음. */
	@Query("""
			select new com.tetra.ops.domain.platform.OperatorNotificationRepository$CenterRow(n, t.name,
			       t.provisioningStatus, t.accountStatus, case when t.password is not null then true else false end,
			       r.applicationStatus, r.hostingStatus, r.eventStatus)
			  from OperatorNotification n
			  left join Tenant t on t.tenantId = n.tenantId
			  left join EventRegistry r on r.eventId = n.eventId
			 order by n.occurredAt desc, n.notificationId desc""")
	List<CenterRow> findAllForCenter();

	/** OPS-05 최근 자동화 실패 · 긴급 알림: since 이후 URGENT, 최신순 (idx_operator_notif_priority) */
	@Query("""
			select new com.tetra.ops.domain.platform.OperatorNotificationRepository$WithTenantName(n, t.name)
			  from OperatorNotification n left join Tenant t on t.tenantId = n.tenantId
			 where n.priority = 'URGENT' and n.occurredAt >= :since
			 order by n.occurredAt desc, n.notificationId desc""")
	List<WithTenantName> findUrgentSince(@Param("since") LocalDateTime since);

	/** 읽음은 운영자 공통(is_read). 이미 읽은 알림이면 0행. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update OperatorNotification n set n.read = true where n.notificationId = :id and n.read = false")
	int markRead(@Param("id") Long notificationId);

	/** 안읽음만 읽음으로 (idx_operator_notif_read). */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update OperatorNotification n set n.read = true where n.read = false")
	int markAllRead();

}
