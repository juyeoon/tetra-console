package com.tetra.ops.domain.platform;

import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.tetra.ops.domain.AccountStatus;
import com.tetra.ops.domain.ProvisioningStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "tenant", catalog = "tetra_platform")
public class Tenant {

	@Id
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "tenant_id", length = 12, nullable = false, updatable = false)
	private String tenantId;

	@Column(name = "name", length = 100, nullable = false)
	private String name;

	@Column(name = "root_domain", length = 253, nullable = false)
	private String rootDomain;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "email", length = 255, nullable = false)
	private String email;

	@Column(name = "password", length = 255)
	private String password;

	@Column(name = "contact_name", length = 100, nullable = false)
	private String contactName;

	@Column(name = "slack_team_id", length = 32)
	private String slackTeamId;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "account_status", length = 20, nullable = false)
	private AccountStatus accountStatus;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "provisioning_status", length = 20, nullable = false)
	private ProvisioningStatus provisioningStatus;

	@Column(name = "email_notification_enabled", nullable = false)
	private boolean emailNotificationEnabled;

	protected Tenant() {
	}

	/** 등록(WF5): password NULL, INACTIVE, PROVISIONING. */
	public static Tenant register(String tenantId, String name, String rootDomain, String email, String contactName,
			LocalDateTime now) {
		Tenant tenant = new Tenant();
		tenant.tenantId = tenantId;
		tenant.name = name;
		tenant.rootDomain = rootDomain;
		tenant.email = email;
		tenant.contactName = contactName;
		tenant.createdAt = now;
		tenant.password = null;
		tenant.accountStatus = AccountStatus.INACTIVE;
		tenant.provisioningStatus = ProvisioningStatus.PROVISIONING;
		tenant.emailNotificationEnabled = false;
		return tenant;
	}

	public String getTenantId() {
		return tenantId;
	}

	public String getName() {
		return name;
	}

	public String getRootDomain() {
		return rootDomain;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public String getEmail() {
		return email;
	}

	/** 비밀번호 해시. 로그·응답에 내보내지 않는다. */
	public String getPassword() {
		return password;
	}

	public String getContactName() {
		return contactName;
	}

	public AccountStatus getAccountStatus() {
		return accountStatus;
	}

	public ProvisioningStatus getProvisioningStatus() {
		return provisioningStatus;
	}

	public boolean isReady() {
		return provisioningStatus == ProvisioningStatus.READY;
	}

	public void changeName(String name) {
		this.name = name;
	}

	public void changeContactName(String contactName) {
		this.contactName = contactName;
	}

	public void changeEmail(String email) {
		this.email = email;
	}

}
