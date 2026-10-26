package com.tetra.ops.domain.platform;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OperatorNotificationRepository extends JpaRepository<OperatorNotification, Long> {

	/** 알림과 테넌트명. 테넌트 무관 알림(tenant_id NULL)은 tenantName이 null. */
	record WithTenantName(OperatorNotification notification, String tenantName) {
	}

	/** OPS-02 최근 알림 5건 (idx_operator_notif_occurred). */
	List<OperatorNotification> findTop5ByOrderByOccurredAtDescNotificationIdDesc();

	/** OPS-08 전체, 최신순 (idx_operator_notif_occurred). 페이지네이션 없음. */
	@Query("""
			select new com.tetra.ops.domain.platform.OperatorNotificationRepository$WithTenantName(n, t.name)
			  from OperatorNotification n left join Tenant t on t.tenantId = n.tenantId
			 order by n.occurredAt desc, n.notificationId desc""")
	List<WithTenantName> findAllWithTenantName();

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
