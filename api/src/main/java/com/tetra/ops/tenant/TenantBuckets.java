package com.tetra.ops.tenant;

import org.springframework.stereotype.Component;

import com.tetra.ops.config.TetraProperties;

/** 테넌트 S3 버킷 이름. 저장하지 않고 계산한다. */
@Component
public class TenantBuckets {

	private final String env;

	public TenantBuckets(TetraProperties properties) {
		this.env = properties.env();
	}

	public String content(String tenantId) {
		return "tetra-storage-" + env + "-" + tenantId;
	}

	public String auditLog(String tenantId) {
		return "tetra-audit-logs-" + env + "-" + tenantId;
	}

}
