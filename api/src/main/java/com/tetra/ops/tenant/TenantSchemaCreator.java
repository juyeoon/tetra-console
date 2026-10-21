package com.tetra.ops.tenant;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.tetra.ops.config.TetraProperties;
import com.tetra.ops.config.TetraProperties.DbMigration;
import com.tetra.ops.tenancy.TenantIds;

/**
 * WF5 테넌트 스키마 생성: 마이그레이션 계정(DDL)으로 CREATE SCHEMA t_{tenant_id} 후 Flyway(db/migration/tenant)로 테이블 10개를 만든다.
 * 앱 런타임 계정(DML 전용)과 커넥션 풀은 쓰지 않는다. 호출할 때마다 마이그레이션 계정으로 새로 붙고 끝나면 닫는다.
 * 테이블을 만들다 실패하면 방금 만든 스키마를 지워, 다시 시도할 때 빈 상태에서 시작하게 한다. 자동 재시도는 없다.
 */
@Component
class TenantSchemaCreator {

	private static final Logger log = LoggerFactory.getLogger(TenantSchemaCreator.class);

	private final String url;

	private final DbMigration migration;

	TenantSchemaCreator(@Value("${spring.datasource.url}") String url, TetraProperties properties) {
		this.url = url;
		this.migration = properties.dbMigration();
	}

	/** @return 스키마와 테이블을 모두 만들었으면 true. 실패는 로그만 남기고 false (호출한 쪽이 FAILED 처리) */
	boolean create(String tenantId) {
		String schema = "t_" + TenantIds.requireValid(tenantId);
		// 같은 이름의 스키마가 이미 있으면 여기서 실패한다. 남의 스키마를 지우지 않도록 IF NOT EXISTS를 쓰지 않는다
		if (!execute("CREATE SCHEMA " + schema + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci", schema)) {
			return false;
		}
		try {
			Flyway.configure()
				.dataSource(url, migration.username(), migration.password())
				.schemas(schema)
				.defaultSchema(schema)
				.createSchemas(false)
				.locations(migration.locations())
				.placeholderReplacement(false)
				.load()
				.migrate();
			log.info("테넌트 스키마 생성: schema={}", schema);
			return true;
		}
		catch (FlywayException ex) {
			log.error("테넌트 스키마 테이블 생성 실패, 스키마를 지웁니다: schema={}", schema, ex);
			execute("DROP SCHEMA IF EXISTS " + schema, schema);
			return false;
		}
	}

	private boolean execute(String sql, String schema) {
		try (Connection connection = DriverManager.getConnection(url, migration.username(), migration.password());
				Statement statement = connection.createStatement()) {
			statement.execute(sql);
			return true;
		}
		catch (SQLException ex) {
			log.error("테넌트 스키마 DDL 실패: schema={}, sql={}", schema, sql, ex);
			return false;
		}
	}

}
