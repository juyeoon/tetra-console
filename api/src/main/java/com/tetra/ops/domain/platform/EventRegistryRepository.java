package com.tetra.ops.domain.platform;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.HostingStatus;

public interface EventRegistryRepository extends JpaRepository<EventRegistry, Long> {

	/** registry 행과 테넌트명. 공용 스키마만 읽는 목록 화면용. */
	record WithTenantName(EventRegistry registry, String tenantName) {
	}

	@Query("""
			select new com.tetra.ops.domain.platform.EventRegistryRepository$WithTenantName(r, t.name)
			  from EventRegistry r join Tenant t on t.tenantId = r.tenantId""")
	List<WithTenantName> findAllWithTenantName();

	/** OPS-04: 승인된 이벤트, 이벤트 시작일 내림차순 (idx_registry_app_start). */
	@Query("""
			select new com.tetra.ops.domain.platform.EventRegistryRepository$WithTenantName(r, t.name)
			  from EventRegistry r join Tenant t on t.tenantId = r.tenantId
			 where r.applicationStatus = com.tetra.ops.domain.ApplicationStatus.APPROVED
			 order by r.startAt desc, r.eventId desc""")
	List<WithTenantName> findApprovedWithTenantName();

	/** 홈 모니터링: 진행 중(OPEN) 이벤트와 테넌트명. 홈 "현재 진행 중인 이벤트"와 같은 기준. */
	@Query("""
			select new com.tetra.ops.domain.platform.EventRegistryRepository$WithTenantName(r, t.name)
			  from EventRegistry r join Tenant t on t.tenantId = r.tenantId
			 where r.eventStatus = com.tetra.ops.domain.EventStatus.OPEN
			 order by r.startAt, r.eventId""")
	List<WithTenantName> findOpenWithTenantName();

	/**
	 * OPS-05 모니터링 대상 후보: 승인된 이벤트 중 호스팅이 아직 안 끝난 것 + 시작이 since 이후인 것(최근 종료 7일 · 실패 목록용).
	 * registry에 hosting_end_at이 없어 종료 시각은 테넌트 스키마 event에서 붙인다.
	 */
	@Query("""
			select new com.tetra.ops.domain.platform.EventRegistryRepository$WithTenantName(r, t.name)
			  from EventRegistry r join Tenant t on t.tenantId = r.tenantId
			 where r.applicationStatus = com.tetra.ops.domain.ApplicationStatus.APPROVED
			   and (r.hostingStatus is null
			        or r.hostingStatus not in (com.tetra.ops.domain.HostingStatus.ENDED,
			                                   com.tetra.ops.domain.HostingStatus.END_FAILED)
			        or r.startAt >= :since)
			 order by r.startAt, r.eventId""")
	List<WithTenantName> findMonitoringCandidates(@Param("since") LocalDateTime since);

	/** OPS-06-2: 테넌트별, 신청시각 내림차순 (idx_registry_tenant_applied). */
	List<EventRegistry> findByTenantIdOrderByAppliedAtDescEventIdDesc(String tenantId);

	List<EventRegistry> findByEventIdIn(Collection<Long> eventIds);

	@Query("select r.applicationStatus, count(r) from EventRegistry r group by r.applicationStatus")
	List<Object[]> countByApplicationStatus();

	@Query("""
			select r.hostingStatus, count(r) from EventRegistry r
			 where r.applicationStatus = com.tetra.ops.domain.ApplicationStatus.APPROVED
			   and r.hostingStatus in :statuses
			 group by r.hostingStatus""")
	List<Object[]> countApprovedByHostingStatus(@Param("statuses") Collection<HostingStatus> statuses);

	long countByEventStatus(EventStatus eventStatus);

	/**
	 * 같은 테넌트의 "남은" 이벤트 수 (테넌트 자동 비활성 판정, 화면설계서 OPS-06 기준).
	 * 승인 대기 중(APPLIED) 또는 오픈 예정·진행 중(APPROVED이고 SCHEDULED·OPEN). 기준 이벤트는 뺀다.
	 * OPEN_FAILED·EXPIRED는 남은 이벤트로 보지 않는다.
	 */
	@Query("""
			select count(r) from EventRegistry r
			 where r.tenantId = :tenantId and r.eventId <> :excludeEventId
			   and (r.applicationStatus = com.tetra.ops.domain.ApplicationStatus.APPLIED
			        or (r.applicationStatus = com.tetra.ops.domain.ApplicationStatus.APPROVED
			            and r.eventStatus in (com.tetra.ops.domain.EventStatus.SCHEDULED,
			                                  com.tetra.ops.domain.EventStatus.OPEN)))""")
	long countRemainingEvents(@Param("tenantId") String tenantId, @Param("excludeEventId") Long excludeEventId);

	// ---- 상태 변경: EventStatusService에서만 부른다. 기대 상태를 WHERE에 넣고 바뀐 행 수로 판단한다. ----

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update EventRegistry r
			   set r.applicationStatus = com.tetra.ops.domain.ApplicationStatus.APPROVED,
			       r.processedAt = :processedAt,
			       r.hostingStatus = com.tetra.ops.domain.HostingStatus.PENDING,
			       r.eventStatus = com.tetra.ops.domain.EventStatus.SCHEDULED
			 where r.eventId = :eventId
			   and r.applicationStatus = com.tetra.ops.domain.ApplicationStatus.APPLIED""")
	int approve(@Param("eventId") Long eventId, @Param("processedAt") LocalDateTime processedAt);

	/** 동시 이벤트 상한 계산 대상(3-13): 모든 테넌트의 승인된 이벤트 중 아직 끝나지 않은 것(실패 상태 포함) */
	@Query("""
			select r from EventRegistry r
			 where r.applicationStatus = com.tetra.ops.domain.ApplicationStatus.APPROVED
			   and (r.eventStatus is null or r.eventStatus <> com.tetra.ops.domain.EventStatus.EXPIRED)""")
	List<EventRegistry> findApprovedNotExpired();

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update EventRegistry r
			   set r.applicationStatus = com.tetra.ops.domain.ApplicationStatus.REJECTED,
			       r.processedAt = :processedAt
			 where r.eventId = :eventId
			   and r.applicationStatus = com.tetra.ops.domain.ApplicationStatus.APPLIED""")
	int reject(@Param("eventId") Long eventId, @Param("processedAt") LocalDateTime processedAt);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update EventRegistry r set r.hostingStatus = :to
			 where r.eventId = :eventId and r.hostingStatus = :from""")
	int updateHostingStatus(@Param("eventId") Long eventId, @Param("from") HostingStatus from,
			@Param("to") HostingStatus to);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update EventRegistry r set r.eventStatus = :to
			 where r.eventId = :eventId and r.eventStatus = :from""")
	int updateEventStatus(@Param("eventId") Long eventId, @Param("from") EventStatus from,
			@Param("to") EventStatus to);

}
