package com.tetra.ops.domain.tenant;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.tetra.ops.domain.ApplicationStatus;
import com.tetra.ops.domain.HostingStatus;

/** 테넌트 스키마의 event. 라우팅(TenantContext) 안에서만 쓴다. */
public interface EventRepository extends JpaRepository<Event, Long> {

	List<Event> findByApplicationStatusOrderByAppliedAtDescEventIdDesc(ApplicationStatus applicationStatus);

	// ---- 상태 변경: EventStatusService에서만 부른다. CHECK 제약상 같이 바뀌어야 하는 컬럼은 한 UPDATE에서 바꾼다.
	//      tenant_id도 WHERE에 넣어, 라우팅한 테넌트가 아닌 행은 바꾸지 않는다(0행 → 호출 쪽이 롤백). ----

	/** chk_event_approved: APPROVED와 hosting_status·event_status를 함께 채운다. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update Event e
			   set e.applicationStatus = com.tetra.ops.domain.ApplicationStatus.APPROVED,
			       e.processedBy = :operatorId,
			       e.processedAt = :processedAt,
			       e.slug = :slug,
			       e.hostingStatus = com.tetra.ops.domain.HostingStatus.PENDING,
			       e.eventStatus = com.tetra.ops.domain.EventStatus.SCHEDULED
			 where e.eventId = :eventId and e.tenantId = :tenantId
			   and e.applicationStatus = com.tetra.ops.domain.ApplicationStatus.APPLIED""")
	int approve(@Param("eventId") Long eventId, @Param("tenantId") String tenantId, @Param("operatorId") Long operatorId,
			@Param("processedAt") LocalDateTime processedAt, @Param("slug") String slug);

	/** chk_event_reject: REJECTED와 reject_reason을 함께 채운다. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update Event e
			   set e.applicationStatus = com.tetra.ops.domain.ApplicationStatus.REJECTED,
			       e.processedBy = :operatorId,
			       e.processedAt = :processedAt,
			       e.rejectReason = :reason
			 where e.eventId = :eventId and e.tenantId = :tenantId
			   and e.applicationStatus = com.tetra.ops.domain.ApplicationStatus.APPLIED""")
	int reject(@Param("eventId") Long eventId, @Param("tenantId") String tenantId, @Param("operatorId") Long operatorId,
			@Param("processedAt") LocalDateTime processedAt, @Param("reason") String reason);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update Event e set e.hostingStatus = :to
			 where e.eventId = :eventId and e.tenantId = :tenantId and e.hostingStatus = :from""")
	int updateHostingStatus(@Param("eventId") Long eventId, @Param("tenantId") String tenantId,
			@Param("from") HostingStatus from,
			@Param("to") HostingStatus to);

}
