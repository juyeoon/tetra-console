package com.tetra.ops.domain.tenant;

import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.tetra.ops.domain.AuthKeyStatus;
import com.tetra.ops.tenancy.TenantScopedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 발급 API JWT 검증용 공개키(PEM). 개인키는 저장하지 않는다. */
@Entity
@Table(name = "event_auth_key")
public class EventAuthKey extends TenantScopedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "key_id", nullable = false, columnDefinition = "int unsigned")
	private Long keyId;

	@Column(name = "event_id", nullable = false, columnDefinition = "int unsigned")
	private Long eventId;

	@Column(name = "public_key", length = 2048, nullable = false)
	private String publicKey;

	@Column(name = "key_version", nullable = false, columnDefinition = "int unsigned")
	private Long keyVersion;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "status", length = 20, nullable = false)
	private AuthKeyStatus status;

	@Column(name = "issued_at", nullable = false)
	private LocalDateTime issuedAt;

	@Column(name = "expires_at", nullable = false)
	private LocalDateTime expiresAt;

	@Column(name = "revoked_at")
	private LocalDateTime revokedAt;

	protected EventAuthKey() {
	}

	/** 승인 시 첫 키: key_version 1, ACTIVE, expires_at = hosting_end_at. */
	public static EventAuthKey firstActive(Long eventId, String tenantId, String publicKeyPem, LocalDateTime issuedAt,
			LocalDateTime expiresAt) {
		EventAuthKey key = new EventAuthKey();
		key.eventId = eventId;
		key.tenantId = tenantId;
		key.publicKey = publicKeyPem;
		key.keyVersion = 1L;
		key.status = AuthKeyStatus.ACTIVE;
		key.issuedAt = issuedAt;
		key.expiresAt = expiresAt;
		return key;
	}

	public Long getKeyId() {
		return keyId;
	}

	public Long getEventId() {
		return eventId;
	}

	public String getPublicKey() {
		return publicKey;
	}

	public Long getKeyVersion() {
		return keyVersion;
	}

	public AuthKeyStatus getStatus() {
		return status;
	}

	public LocalDateTime getExpiresAt() {
		return expiresAt;
	}

}
