package com.tetra.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.tetra.ops.common.RandomIds;
import com.tetra.ops.support.IntegrationTest;

/**
 * 등록(WF5) 중 테넌트 스키마 생성 실패: provisioning_status FAILED, Step Functions · 비밀번호 설정 메일 없음, 201(mailSent false).
 * 테이블 생성 중 실패를 만들려고 Flyway 위치를 중간에 깨지는 스크립트로 바꾼다.
 */
@TestPropertySource(properties = "tetra.db-migration.locations=classpath:db/migration/tenant-broken")
class TenantSchemaCreationFailureTest extends IntegrationTest {

	private static final String VALID = """
			{"name":"E사","email":"minho.park@e-corp.com","rootDomain":"e-corp.com","contactName":"박민호"}""";

	@MockitoBean
	RandomIds randomIds;

	private void register() throws Exception {
		mvc.perform(post("/api/ops/tenants").cookie(operatorCookie())
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.mailSent").value(false));
	}

	private long schemaCount(String schema) {
		return data.count("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?", schema);
	}

	/** 스키마 생성 실패 알림만 1건 (StartExecution 실패 · 메일 실패 알림과 문구로 구분). */
	private void assertOnlySchemaFailureNotified(String tenantId) {
		assertThat(data.count("SELECT COUNT(*) FROM tetra_platform.operator_notification WHERE tenant_id = ?"
				+ " AND category = 'PROVISIONING' AND priority = 'URGENT' AND content LIKE '%스키마 생성 실패%'", tenantId))
			.isEqualTo(1);
		assertThat(data.count("SELECT COUNT(*) FROM tetra_platform.operator_notification WHERE tenant_id = ?"
				+ " AND category = 'MAIL'", tenantId)).isZero();
	}

	@Test
	void tableFailureDropsSchemaMarksFailedAndSkipsStepFunctionsAndMail() throws Exception {
		String tenantId = "cccccccccccc";
		when(randomIds.tenantId()).thenReturn(tenantId);

		register();

		assertThat(data.tenantRow(tenantId)).containsEntry("provisioning_status", "FAILED");
		assertThat(schemaCount("t_" + tenantId)).isZero();
		assertOnlySchemaFailureNotified(tenantId);
		verify(stepFunctions, never()).startExecution(anyString(), anyString(), anyString());
		verify(mail, never()).send(any());
	}

	@Test
	void existingSchemaIsNotDropped() throws Exception {
		String tenantId = "dddddddddddd";
		when(randomIds.tenantId()).thenReturn(tenantId);
		data.jdbc().execute("CREATE SCHEMA t_" + tenantId);
		data.jdbc().execute("CREATE TABLE t_" + tenantId + ".keep_me (id INT PRIMARY KEY)");

		register();

		assertThat(data.tenantRow(tenantId)).containsEntry("provisioning_status", "FAILED");
		assertThat(data.count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ?"
				+ " AND table_name = 'keep_me'", "t_" + tenantId)).isEqualTo(1);
		assertOnlySchemaFailureNotified(tenantId);
		verify(stepFunctions, never()).startExecution(anyString(), anyString(), anyString());
		verify(mail, never()).send(any());
	}

}
