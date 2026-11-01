package com.tetra.ops.domain.platform;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.tetra.ops.domain.AccountStatus;
import com.tetra.ops.domain.ApplicationStatus;
import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.HostingStatus;
import com.tetra.ops.domain.ProvisioningStatus;

/** tetra_platform.operator_notification. OPS-08 목록(조건 · 페이지)은 Specification으로 만든다(NotificationCenterService). */
public interface OperatorNotificationRepository
		extends JpaRepository<OperatorNotification, Long>, JpaSpecificationExecutor<OperatorNotification> {

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

	/**
	 * OPS-08 한 페이지의 알림(ids)에 테넌트 · 이벤트 현재 값을 붙인다(PK 범위 + PK 조회). 순서는 보장하지 않는다 — 부르는 쪽이 페이지 순서로.
	 * 페이지 고르기(조건 · 정렬 · OFFSET)는 조인 없이 Specification으로 하고, 조인은 고른 행에만(OFFSET으로 버릴 행까지 조인하지 않게).
	 */
	@Query("""
			select new com.tetra.ops.domain.platform.OperatorNotificationRepository$CenterRow(n, t.name,
			       t.provisioningStatus, t.accountStatus, case when t.password is not null then true else false end,
			       r.applicationStatus, r.hostingStatus, r.eventStatus)
			  from OperatorNotification n
			  left join Tenant t on t.tenantId = n.tenantId
			  left join EventRegistry r on r.eventId = n.eventId
			 where n.notificationId in :ids""")
	List<CenterRow> findCenterFacts(@Param("ids") Collection<Long> ids);

	/** OPS-08 건수 재료 — 분류 값 · 우선순위 · 읽음별 건수와 그중 이벤트가 있는 건수(칩 판정은 NotificationRules, Portal과 같은 모양) */
	@Query("""
			select n.category as category, n.priority as priority, n.read as read,
			       count(n) as total, count(n.eventId) as withEvent
			  from OperatorNotification n
			 group by n.category, n.priority, n.read""")
	List<CountGroup> countGroups();

	interface CountGroup {

		String getCategory();

		String getPriority();

		Boolean getRead();

		Long getTotal();

		/** total 중 event_id가 있는 건수 */
		Long getWithEvent();

	}

	/** 안 읽은 알림 수 — is_read = 0 비교라 인덱스(idx_operator_notif_read)만 읽는다 */
	@Query("select count(n) from OperatorNotification n where n.read = false")
	long countUnread();

	/** 안 읽은 알림 중 그 우선순위가 있는지 — 첫 행에서 멈춘다 */
	boolean existsByReadFalseAndPriority(String priority);

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
