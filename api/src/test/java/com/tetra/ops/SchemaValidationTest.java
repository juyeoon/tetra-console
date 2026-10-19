package com.tetra.ops;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.tetra.ops.support.MySqlTestDatabase;

import jakarta.persistence.EntityManagerFactory;

/**
 * 엔티티 매핑이 DDL(dev-docs/db/init)과 맞는지 ddl-auto=validate로 확인한다 (§12).
 * 공용 엔티티는 catalog(tetra_platform)로, 테넌트 엔티티는 기본 스키마(t_aaaaaaaaaaaa)로 검증된다.
 */
@SpringBootTest
@ActiveProfiles("test")
class SchemaValidationTest {

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		MySqlTestDatabase.start();
		registry.add("spring.datasource.url", () -> MySqlTestDatabase.appJdbcUrl("t_" + MySqlTestDatabase.TENANT_A));
		registry.add("spring.datasource.username", () -> MySqlTestDatabase.APP_USER);
		registry.add("spring.datasource.password", () -> MySqlTestDatabase.APP_PASSWORD);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
	}

	@Autowired
	EntityManagerFactory entityManagerFactory;

	@Test
	void entitiesMatchDdl() {
		// 컨텍스트가 떴다면 validate를 통과한 것
		assertThat(entityManagerFactory.getMetamodel().getEntities()).hasSize(18);
	}

}
