package com.tetra.ops.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * MySQL 8.4 컨테이너 하나를 모든 통합 테스트가 공유한다.
 * db/migration의 DDL(db-schema V1 사본)로 공용 스키마 1개 + 테넌트 스키마 2개를 만들고, 앱은 DML 권한만 있는 계정으로 붙는다.
 */
public final class MySqlTestDatabase {

	public static final String TENANT_A = "aaaaaaaaaaaa";

	public static final String TENANT_B = "bbbbbbbbbbbb";

	public static final String APP_USER = "ops_app";

	public static final String APP_PASSWORD = "ops_app_pw";

	/**
	 * 테스트 DDL = db-schema 저장소 V1 사본(api/db/migration, 커밋됨 — 새 clone · CI에서도 돈다).
	 * 시드 V2는 넣지 않는다 — 이 테스트는 seed/platform_base.sql로 요금제 · 템플릿을 직접 넣는다.
	 */
	private static final Path DDL_DIR = Path.of("db", "migration");

	private static final MySQLContainer CONTAINER = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
		.withDatabaseName("bootstrap")
		.withCommand("--default-time-zone=+09:00");

	private static boolean initialized;

	private MySqlTestDatabase() {
	}

	public static synchronized MySQLContainer start() {
		if (!initialized) {
			CONTAINER.start();
			createSchemas();
			initialized = true;
		}
		return CONTAINER;
	}

	/** 앱이 쓰는 JDBC URL. 기본 스키마는 tetra_platform (DB_URL과 같은 형식). */
	public static String appJdbcUrl(String schema) {
		return "jdbc:mysql://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(3306) + "/" + schema
				+ "?connectionTimeZone=Asia/Seoul&forceConnectionTimeZoneToSession=true";
	}

	public static Connection rootConnection() throws SQLException {
		return DriverManager.getConnection(appJdbcUrl("tetra_platform"), "root", CONTAINER.getPassword());
	}

	private static void createSchemas() {
		try (Connection root = DriverManager.getConnection(appJdbcUrl("bootstrap"), "root", CONTAINER.getPassword());
				Statement st = root.createStatement()) {
			st.execute("CREATE SCHEMA tetra_platform DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
			root.setCatalog("tetra_platform");
			run(root, ddl("platform/V1__init_platform_schema.sql"));
			for (String tenantId : new String[] { TENANT_A, TENANT_B }) {
				String schema = "t_" + tenantId;
				st.execute("CREATE SCHEMA " + schema + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
				root.setCatalog(schema);
				run(root, ddl("tenant/V1__init_tenant_schema.sql"));
			}
			// 앱 런타임 계정: tetra_platform.*와 t\_%.*에 DML만 (격리 결정 문서의 계정과 권한)
			st.execute("CREATE USER '" + APP_USER + "'@'%' IDENTIFIED BY '" + APP_PASSWORD + "'");
			st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON tetra_platform.* TO '" + APP_USER + "'@'%'");
			st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON `t\\_%`.* TO '" + APP_USER + "'@'%'");
			root.setCatalog("tetra_platform");
			run(root, new ClassPathResource("seed/platform_base.sql"));
		}
		catch (SQLException ex) {
			throw new IllegalStateException("테스트 스키마 생성 실패", ex);
		}
	}

	private static Resource ddl(String fileName) {
		Path path = DDL_DIR.resolve(fileName);
		if (!Files.exists(path)) {
			throw new IllegalStateException("DDL 파일이 없습니다: " + path.toAbsolutePath());
		}
		return new FileSystemResource(path);
	}

	private static void run(Connection connection, Resource script) {
		ScriptUtils.executeSqlScript(connection, script);
	}

}
