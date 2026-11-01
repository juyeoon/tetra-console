package com.tetra.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.tetra.ops.support.IntegrationTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 프런트용 API 문서(springdoc). 테스트 프로필에서는 켜져 있다. */
class ApiDocsTest extends IntegrationTest {

	private JsonNode apiDocs() throws Exception {
		String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		// 서버를 띄우지 않고도 볼 수 있게 build/openapi.json으로 남긴다(빌드 산출물, 커밋하지 않음)
		Files.writeString(Path.of("build", "openapi.json"), body, StandardCharsets.UTF_8);
		return JsonMapper.builder().build().readTree(body);
	}

	@Test
	void documentsEveryApiPathOnly() throws Exception {
		JsonNode paths = apiDocs().path("paths");
		for (String path : new String[] { "/api/ops/auth/login", "/api/ops/auth/logout", "/api/ops/me",
				"/api/ops/dashboard", "/api/ops/applications", "/api/ops/events/{eventId}",
				"/api/ops/events/{eventId}/approve", "/api/ops/events/{eventId}/reject",
				"/api/ops/events/{eventId}/application-summary", "/api/ops/live-events",
				"/api/ops/events/{eventId}/result", "/api/ops/events/{eventId}/result/history-csv", "/api/ops/tenants",
				"/api/ops/tenants/{tenantId}", "/api/ops/tenants/{tenantId}/events",
				"/api/ops/tenants/{tenantId}/resources", "/api/ops/tenants/email-availability",
				"/api/ops/notifications", "/api/ops/notifications/unread-count",
				"/api/ops/notifications/{notificationId}/read", "/api/ops/notifications/read-all", "/api/ops/reports",
				"/api/ops/reports/{reportId}/download", "/api/ops/reports/{reportId}/email" }) {
			assertThat(paths.has(path)).as(path).isTrue();
		}
		paths.propertyNames().forEach(p -> assertThat(p).startsWith("/api/"));
	}

	@Test
	void documentsCookieAuthAndErrorCodes() throws Exception {
		JsonNode docs = apiDocs();
		assertThat(docs.path("components").path("securitySchemes").path("cookieAuth").path("name").asString())
			.isEqualTo("TETRA_OPS_TOKEN");
		assertThat(docs.path("components").path("schemas").has("ErrorResponse")).isTrue();

		JsonNode approve = docs.path("paths").path("/api/ops/events/{eventId}/approve").path("post");
		assertThat(approve.path("responses").path("409").path("description").asString())
			.contains("APPLICATION_NOT_APPLIED", "HOSTING_PERIOD_ENDED");
		assertThat(approve.path("responses").path("404").path("description").asString()).contains("EVENT_NOT_FOUND");
		assertThat(approve.path("responses").path("401").path("description").asString()).contains("UNAUTHENTICATED");
		assertThat(approve.path("responses").has("500")).isTrue();

		// 알림센터는 501이 아니고, 읽음은 없는 id면 404
		assertThat(docs.path("paths").path("/api/ops/notifications").path("get").path("responses").has("501")).isFalse();
		JsonNode markRead = docs.path("paths").path("/api/ops/notifications/{notificationId}/read").path("patch");
		assertThat(markRead.path("responses").path("404").path("description").asString()).contains("NOTIFICATION_NOT_FOUND");
		assertThat(markRead.path("responses").has("204")).isTrue();

		// 리포트는 501이 아니고, 이메일 전송은 404 · 503을 문서에 둔다
		assertThat(docs.path("paths").path("/api/ops/reports").path("get").path("responses").has("501")).isFalse();
		JsonNode email = docs.path("paths").path("/api/ops/reports/{reportId}/email").path("post");
		assertThat(email.path("responses").path("404").path("description").asString()).contains("REPORT_NOT_FOUND");
		assertThat(email.path("responses").path("503").path("description").asString()).contains("MAIL_SEND_FAILED");
		assertThat(email.path("responses").has("204")).isTrue();
		JsonNode download = docs.path("paths").path("/api/ops/reports/{reportId}/download").path("get");
		assertThat(download.path("responses").path("404").path("description").asString()).contains("REPORT_NOT_FOUND");

		// 신청 내용은 501이 아니고 404 · 409를 문서에 둔다. 이제 501을 돌려주는 API는 없다
		JsonNode summary = docs.path("paths").path("/api/ops/events/{eventId}/application-summary").path("get");
		assertThat(summary.path("responses").has("501")).isFalse();
		assertThat(summary.path("responses").path("404").path("description").asString()).contains("EVENT_NOT_FOUND");
		assertThat(summary.path("responses").path("409").path("description").asString()).contains("TENANT_NOT_READY");
		docs.path("paths").forEach(path -> path.forEach(op -> assertThat(op.path("responses").has("501")).isFalse()));
		// layoutConfig는 문자열이 아니라 객체 스키마
		assertThat(docs.path("components").path("schemas").path("ApplicationSummaryResponse").path("properties")
			.path("layoutConfig").path("$ref").asString()).endsWith("/LayoutConfig");

		// 로그인은 인증 없이 호출, 401은 LOGIN_FAILED만
		JsonNode login = docs.path("paths").path("/api/ops/auth/login").path("post");
		assertThat(login.path("security").isArray() && login.path("security").isEmpty()).isTrue();
		assertThat(login.path("responses").path("401").path("description").asString())
			.contains("LOGIN_FAILED")
			.doesNotContain("UNAUTHENTICATED");
	}

	@Test
	void nestedRecordsHaveDistinctSchemaNames() throws Exception {
		JsonNode schemas = apiDocs().path("components").path("schemas");
		// 이벤트 상세 basic과 테넌트 개요 basic이 같은 이름 "Basic"으로 합쳐지지 않는다
		assertThat(schemas.has("Basic")).isFalse();
		assertThat(schemas.path("EventDetailResponse").path("properties").path("basic").path("$ref").asString())
			.endsWith("/EventDetailBasic");
		assertThat(schemas.path("EventDetailBasic").path("properties").has("eventId")).isTrue();
		for (String name : new String[] { "EventDetailStatus", "EventDetailResources", "EventDetailStatusLog", "EventDetailActor" }) {
			assertThat(schemas.has(name)).as(name).isTrue();
		}
		assertThat(schemas.path("TenantOverviewResponse").path("properties").path("basic").path("$ref").asString())
			.endsWith("/TenantBasic");
		assertThat(schemas.path("TenantBasic").path("properties").has("provisioningStatus")).isTrue();
		assertThat(schemas.path("TenantItem").path("properties").has("provisioningStatus")).isTrue();
		assertThat(schemas.path("TenantItem").path("properties").has("createdAt")).isTrue();
		assertThat(schemas.path("RegisterResponse").path("properties").has("provisioningStatus")).isTrue();
		// 비밀번호 설정 메일은 환경 준비 뒤 결과 리스너가 보내므로 등록 응답에 메일 결과가 없다
		assertThat(schemas.path("RegisterResponse").path("properties").has("mailSent")).isFalse();
		assertThat(schemas.path("TenantBasic").path("properties").has("passwordSet")).isTrue();
	}

	@Test
	void swaggerUiIsServed() throws Exception {
		mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
	}

}
