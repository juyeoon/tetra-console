package com.tetra.ops.domain.tenant;

import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.tetra.ops.tenancy.TenantGuardListener;
import com.tetra.ops.tenancy.TenantOwned;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 테넌트 Slack 연동 정보. tenant_id가 PK라 공통 부모를 쓰지 않는다. 이 앱은 쓰지 않아 매핑만 둔다. */
@Entity
@Table(name = "tenant_slack_config")
@EntityListeners(TenantGuardListener.class)
public class TenantSlackConfig implements TenantOwned {

	@Id
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "tenant_id", length = 12, nullable = false)
	private String tenantId;

	@Column(name = "workspace_name", length = 100, nullable = false)
	private String workspaceName;

	@Column(name = "bot_token", length = 512, nullable = false)
	private String botToken;

	@Column(name = "bot_user_id", length = 32, nullable = false)
	private String botUserId;

	@Column(name = "authed_user_id", length = 32)
	private String authedUserId;

	@Column(name = "authed_user_email", length = 255)
	private String authedUserEmail;

	@Column(name = "channel_urgent_id", length = 32)
	private String channelUrgentId;

	@Column(name = "channel_urgent_name", length = 80)
	private String channelUrgentName;

	@Column(name = "channel_warning_id", length = 32)
	private String channelWarningId;

	@Column(name = "channel_warning_name", length = 80)
	private String channelWarningName;

	@Column(name = "channel_info_id", length = 32)
	private String channelInfoId;

	@Column(name = "channel_info_name", length = 80)
	private String channelInfoName;

	@Column(name = "connection_status", length = 20, nullable = false)
	private String connectionStatus;

	@Column(name = "connected_at", nullable = false)
	private LocalDateTime connectedAt;

	protected TenantSlackConfig() {
	}

	@Override
	public String getTenantId() {
		return tenantId;
	}

}
