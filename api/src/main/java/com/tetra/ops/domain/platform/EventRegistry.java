package com.tetra.ops.domain.platform;

import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.tetra.ops.domain.ApplicationStatus;
import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.EventType;
import com.tetra.ops.domain.HostingStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 전역 event_id 발급·라우팅·운영 콘솔 목록용 복제 컬럼.
 * 이 앱은 행을 만들지 않고, 상태는 EventStatusService의 조건부 UPDATE로만 바꾼다.
 */
@Entity
@Table(name = "event_registry", catalog = "tetra_platform")
public class EventRegistry {

	// TODO(미정): event_id Java 타입. 임시로 Long(INT UNSIGNED 범위를 담음)으로 통일
	@Id
	@Column(name = "event_id", nullable = false, columnDefinition = "int unsigned")
	private Long eventId;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "tenant_id", length = 12, nullable = false)
	private String tenantId;

	@Column(name = "name", length = 100, nullable = false)
	private String name;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "event_type", length = 20, nullable = false)
	private EventType eventType;

	@Column(name = "traffic_plan_code", length = 10, nullable = false)
	private String trafficPlanCode;

	@Column(name = "hosting_start_at", nullable = false)
	private LocalDateTime hostingStartAt;

	@Column(name = "start_at", nullable = false)
	private LocalDateTime startAt;

	@Column(name = "applied_at", nullable = false)
	private LocalDateTime appliedAt;

	@Column(name = "processed_at")
	private LocalDateTime processedAt;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "application_status", length = 20, nullable = false)
	private ApplicationStatus applicationStatus;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "event_status", length = 20)
	private EventStatus eventStatus;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "hosting_status", length = 20)
	private HostingStatus hostingStatus;

	protected EventRegistry() {
	}

	public Long getEventId() {
		return eventId;
	}

	public String getTenantId() {
		return tenantId;
	}

	public String getName() {
		return name;
	}

	public EventType getEventType() {
		return eventType;
	}

	public String getTrafficPlanCode() {
		return trafficPlanCode;
	}

	public LocalDateTime getHostingStartAt() {
		return hostingStartAt;
	}

	public LocalDateTime getStartAt() {
		return startAt;
	}

	public LocalDateTime getAppliedAt() {
		return appliedAt;
	}

	public LocalDateTime getProcessedAt() {
		return processedAt;
	}

	public ApplicationStatus getApplicationStatus() {
		return applicationStatus;
	}

	public EventStatus getEventStatus() {
		return eventStatus;
	}

	public HostingStatus getHostingStatus() {
		return hostingStatus;
	}

}
