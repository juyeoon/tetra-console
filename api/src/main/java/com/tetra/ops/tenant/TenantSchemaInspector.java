package com.tetra.ops.tenant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.tetra.ops.tenancy.TenantIds;

/**
 * 테넌트 스키마 t_{tenant_id}가 있는지 확인한다. 앱 계정은 `t\_%`.*에 권한이 있어 information_schema에서 보인다.
 * 환경 준비 성공 결과를 READY로 반영하기 전에 쓴다(스키마 생성 실패 테넌트가 수동 재실행으로 READY가 되지 않게).
 */
@Component
class TenantSchemaInspector {

	private final JdbcTemplate jdbc;

	TenantSchemaInspector(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	boolean exists(String tenantId) {
		Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?",
				Integer.class, "t_" + TenantIds.requireValid(tenantId));
		return count != null && count > 0;
	}

}
