package com.tetra.ops.domain.tenant;

import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.tetra.ops.tenancy.TenantScopedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 발급 이력. 이 앱은 분당 집계(OPS-04-3)와 발급 이력 CSV(3-12)를 네이티브 쿼리로 읽기만 한다. */
@Entity
@Table(name = "issuance_history")
public class IssuanceHistory extends TenantScopedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "issuance_id", nullable = false)
	private Long issuanceId;

	@Column(name = "event_id", nullable = false, columnDefinition = "int unsigned")
	private Long eventId;

	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "user_id", length = 128, nullable = false)
	private String userId;

	@Column(name = "ticket_number", nullable = false, columnDefinition = "int unsigned")
	private Long ticketNumber;

	@Column(name = "session_issued_at", nullable = false)
	private LocalDateTime sessionIssuedAt;

	@Column(name = "queue_entered_at", nullable = false)
	private LocalDateTime queueEnteredAt;

	@Column(name = "issued_at", nullable = false)
	private LocalDateTime issuedAt;

	@Column(name = "result", length = 20, nullable = false)
	private String result;

	protected IssuanceHistory() {
	}

}
