package com.tetra.ops.domain.tenant;

import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.tetra.ops.domain.StatusType;
import com.tetra.ops.tenancy.TenantScopedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 신청·호스팅·이벤트 상태 변경 이력(통합). prev/new는 status_type에 따라 값 목록이 달라 문자열로 둔다. */
@Entity
@Table(name = "event_status_log")
public class EventStatusLog extends TenantScopedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "log_id", nullable = false)
	private Long logId;

	@Column(name = "event_id", nullable = false, columnDefinition = "int unsigned")
	private Long eventId;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "status_type", length = 20, nullable = false)
	private StatusType statusType;

	@Column(name = "prev_status", length = 20)
	private String prevStatus;

	@Column(name = "new_status", length = 20, nullable = false)
	private String newStatus;

	@Column(name = "changed_at", nullable = false)
	private LocalDateTime changedAt;

	@Column(name = "detail", length = 500)
	private String detail;

	@Column(name = "changed_by", columnDefinition = "int unsigned")
	private Long changedBy;

	protected EventStatusLog() {
	}

	public static EventStatusLog of(Long eventId, String tenantId, StatusType statusType, Enum<?> prevStatus,
			Enum<?> newStatus, LocalDateTime changedAt, String detail, Long changedBy) {
		EventStatusLog log = new EventStatusLog();
		log.eventId = eventId;
		log.tenantId = tenantId;
		log.statusType = statusType;
		log.prevStatus = prevStatus == null ? null : prevStatus.name();
		log.newStatus = newStatus.name();
		log.changedAt = changedAt;
		log.detail = detail;
		log.changedBy = changedBy;
		return log;
	}

	public Long getLogId() {
		return logId;
	}

	public Long getEventId() {
		return eventId;
	}

	public StatusType getStatusType() {
		return statusType;
	}

	public String getPrevStatus() {
		return prevStatus;
	}

	public String getNewStatus() {
		return newStatus;
	}

	public LocalDateTime getChangedAt() {
		return changedAt;
	}

	public String getDetail() {
		return detail;
	}

	public Long getChangedBy() {
		return changedBy;
	}

}
