package com.tetra.ops.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.tetra.ops.auth.JwtTokenService;
import com.tetra.ops.external.DownloadUrlSigner;
import com.tetra.ops.external.MailGateway;
import com.tetra.ops.external.StepFunctionsGateway;

import jakarta.servlet.http.Cookie;

/**
 * 통합 테스트 공통. 실제 MySQL 8.4(공용 1 + 테넌트 2 스키마)에 DML 전용 계정으로 붙고, AWS 호출은 목으로 바꾼다.
 * 커넥션 풀을 1개로 두어, 앞 요청이 바꾼 스키마가 다음 요청에 남지 않는지(반납 시 초기화)도 함께 검증된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {

	protected static final String A = MySqlTestDatabase.TENANT_A;

	protected static final String B = MySqlTestDatabase.TENANT_B;

	protected static TestData data;

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		MySqlTestDatabase.start();
		registry.add("spring.datasource.url", () -> MySqlTestDatabase.appJdbcUrl("tetra_platform"));
		registry.add("spring.datasource.username", () -> MySqlTestDatabase.APP_USER);
		registry.add("spring.datasource.password", () -> MySqlTestDatabase.APP_PASSWORD);
		registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
		registry.add("tetra.db-migration.username", () -> MySqlTestDatabase.MIGRATION_USER);
		registry.add("tetra.db-migration.password", () -> MySqlTestDatabase.MIGRATION_PASSWORD);
	}

	@Autowired
	protected MockMvc mvc;

	@Autowired
	protected JwtTokenService tokens;

	@MockitoBean
	protected StepFunctionsGateway stepFunctions;

	@MockitoBean
	protected MailGateway mail;

	@MockitoBean
	protected DownloadUrlSigner urlSigner;

	@BeforeEach
	void resetData() {
		data = TestData.shared();
		data.reset();
		when(stepFunctions.startExecution(anyString(), anyString(), anyString())).thenReturn(true);
		when(mail.send(any())).thenReturn(true);
	}

	/** 운영자(operator_id 1) 로그인 쿠키. */
	protected Cookie operatorCookie() {
		return new Cookie(JwtTokenService.COOKIE_NAME, tokens.issue(TestData.OPERATOR_ID));
	}

}
