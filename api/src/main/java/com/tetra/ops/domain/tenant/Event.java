package com.tetra.ops.domain.tenant;

import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.tetra.ops.domain.ApplicationStatus;
import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.EventType;
import com.tetra.ops.domain.HostingStatus;
import com.tetra.ops.tenancy.TenantScopedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 이벤트 본체 (테넌트 스키마). event_id는 event_registry가 발급한 값이다(AUTO_INCREMENT 아님).
 * 상태는 EventStatusService의 조건부 UPDATE로만 바꾼다.
 */
@Entity
@Table(name = "event")
public class Event extends TenantScopedEntity {

	@Id
	@Column(name = "event_id", nullable = false, columnDefinition = "int unsigned")
	private Long eventId;

	@Column(name = "name", length = 100, nullable = false)
	private String name;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "event_type", length = 20, nullable = false)
	private EventType eventType;

	@Column(name = "template_id", nullable = false, columnDefinition = "int unsigned")
	private Long templateId;

	/**
	 * 화면설정(JSON). 요소 4개(title·banner·couponList·issueButton)의 가로(h)·세로(v) 위치 + 배경 테마(theme).
	 * 형식은 DB CHECK(chk_event_layout_config)가 검증한다. 이 앱은 쓰지 않고 읽어서 승인 시 Step Functions input으로 넘긴다.
	 */
	@Column(name = "layout_config", columnDefinition = "json", nullable = false, insertable = false, updatable = false)
	private String layoutConfig;

	@Column(name = "banner_image_path", length = 1024)
	private String bannerImagePath;

	@Column(name = "start_at", nullable = false)
	private LocalDateTime startAt;

	@Column(name = "end_at", nullable = false)
	private LocalDateTime endAt;

	@Column(name = "hosting_start_at", nullable = false)
	private LocalDateTime hostingStartAt;

	@Column(name = "hosting_end_at", nullable = false)
	private LocalDateTime hostingEndAt;

	@Column(name = "endpoint_url", length = 2048, nullable = false)
	private String endpointUrl;

	@Column(name = "traffic_plan_code", length = 10, nullable = false)
	private String trafficPlanCode;

	@Column(name = "stock_count", nullable = false, columnDefinition = "int unsigned")
	private Long stockCount;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "slug", length = 8)
	private String slug;

	@Column(name = "applied_at", nullable = false)
	private LocalDateTime appliedAt;

	@Column(name = "processed_by", columnDefinition = "int unsigned")
	private Long processedBy;

	@Column(name = "processed_at")
	private LocalDateTime processedAt;

	@Column(name = "reject_reason", length = 500)
	private String rejectReason;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "application_status", length = 20, nullable = false)
	private ApplicationStatus applicationStatus;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "hosting_status", length = 20)
	private HostingStatus hostingStatus;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "event_status", length = 20)
	private EventStatus eventStatus;

	protected Event() {
	}

	public Long getEventId() {
		return eventId;
	}

	public String getName() {
		return name;
	}

	public EventType getEventType() {
		return eventType;
	}

	public Long getTemplateId() {
		return templateId;
	}

	/** JSON 원문. */
	public String getLayoutConfig() {
		return layoutConfig;
	}

	public String getBannerImagePath() {
		return bannerImagePath;
	}

	public LocalDateTime getStartAt() {
		return startAt;
	}

	public LocalDateTime getEndAt() {
		return endAt;
	}

	public LocalDateTime getHostingStartAt() {
		return hostingStartAt;
	}

	public LocalDateTime getHostingEndAt() {
		return hostingEndAt;
	}

	public String getEndpointUrl() {
		return endpointUrl;
	}

	public String getTrafficPlanCode() {
		return trafficPlanCode;
	}

	public Long getStockCount() {
		return stockCount;
	}

	public String getSlug() {
		return slug;
	}

	public LocalDateTime getAppliedAt() {
		return appliedAt;
	}

	public Long getProcessedBy() {
		return processedBy;
	}

	public LocalDateTime getProcessedAt() {
		return processedAt;
	}

	public String getRejectReason() {
		return rejectReason;
	}

	public ApplicationStatus getApplicationStatus() {
		return applicationStatus;
	}

	public HostingStatus getHostingStatus() {
		return hostingStatus;
	}

	public EventStatus getEventStatus() {
		return eventStatus;
	}

}
