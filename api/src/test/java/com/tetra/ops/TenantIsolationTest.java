package com.tetra.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.tetra.ops.support.IntegrationTest;

/** 격리 규칙 (§4, §12 교차 접근·이중 확인). */
@ExtendWith(OutputCaptureExtension.class)
class TenantIsolationTest extends IntegrationTest {

	@Autowired
	DataSource dataSource;

	@Test
	void eventIdOfTenantARoutesOnlyToSchemaA() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.tenant(B, "B사", "ACTIVE", "READY");
		// registry는 A를 가리키지만 event 행은 B 스키마에만 있다
		data.event(3001, A).name("B 스키마에만 있는 비밀 이벤트").inSchemaOf(B).rowTenantId(B).insert();

		String body = mvc.perform(get("/api/ops/events/{id}", 3001).cookie(operatorCookie()))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(body).doesNotContain("비밀 이벤트").doesNotContain("SELECT").doesNotContain("Exception");
	}

	@Test
	void rowWithDifferentTenantIdIsRejectedByDoubleCheck(CapturedOutput output) throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.tenant(B, "B사", "ACTIVE", "READY");
		// A 스키마에 tenant_id가 B인 행이 잘못 들어가 있다
		data.event(3002, A).name("섞인 행").rowTenantId(B).insert();

		mvc.perform(get("/api/ops/events/{id}", 3002).cookie(operatorCookie()))
			.andExpect(status().isInternalServerError());
		assertThat(output.getAll()).contains("ERROR").contains("테넌트 불일치");

		// 상태 변경도 막힌다 (approve는 registry만 바꾸고 event 조회에서 막혀 전체가 롤백)
		data.jdbc().update("UPDATE t_" + A + ".event SET application_status = 'APPLIED' WHERE event_id = 3002");
		mvc.perform(post("/api/ops/events/{id}/approve", 3002).cookie(operatorCookie()))
			.andExpect(status().isInternalServerError());
		assertThat(data.registry(3002)).containsEntry("application_status", "APPLIED");
	}

	@Test
	void notReadyTenantIsNotRoutedButOverviewWorks() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "PROVISIONING");
		data.event(3003, A).applied().registryOnly().insert();

		mvc.perform(get("/api/ops/events/{id}", 3003).cookie(operatorCookie()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TENANT_NOT_READY"));
		mvc.perform(post("/api/ops/events/{id}/approve", 3003).cookie(operatorCookie()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TENANT_NOT_READY"));
		// 공용 테이블만 읽는 화면은 상태와 무관
		mvc.perform(get("/api/ops/tenants/{id}", A).cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.basic.tenantId").value(A));
		mvc.perform(get("/api/ops/tenants/{id}/resources", A).cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void connectionIsResetToPlatformSchemaAfterTenantRequest() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(3004, A).applied().insert();

		mvc.perform(get("/api/ops/events/{id}", 3004).cookie(operatorCookie())).andExpect(status().isOk());

		// 풀 크기 1: 방금 테넌트 요청이 쓴 커넥션을 그대로 다시 받는다
		try (Connection connection = dataSource.getConnection()) {
			assertThat(connection.getCatalog()).isEqualTo("tetra_platform");
		}
	}

	@Test
	void invalidTenantIdFormatIs404() throws Exception {
		mvc.perform(get("/api/ops/tenants/{id}", "short").cookie(operatorCookie())).andExpect(status().isNotFound());
		mvc.perform(get("/api/ops/tenants/{id}/resources", "aaaa-aaaa-aa").cookie(operatorCookie()))
			.andExpect(status().isNotFound());
		mvc.perform(get("/api/ops/tenants/{id}", "ABCDEFGHIJKL").cookie(operatorCookie()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("TENANT_NOT_FOUND"));
	}

}
