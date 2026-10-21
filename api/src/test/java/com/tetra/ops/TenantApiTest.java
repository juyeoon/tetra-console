package com.tetra.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.tetra.ops.external.MailGateway;
import com.tetra.ops.support.IntegrationTest;
import com.tetra.ops.support.MySqlTestDatabase;
import com.tetra.ops.support.TestData;

/** OPS-06·06-1·06-2·06-3·06-M3·07 (§7.9~7.12, §12 외부 호출 실패). */
@ExtendWith(OutputCaptureExtension.class)
class TenantApiTest extends IntegrationTest {

	private ResultActions register(String json) throws Exception {
		return mvc.perform(post("/api/ops/tenants").cookie(operatorCookie())
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	private static final String VALID = """
			{"name":"E사","email":"minho.park@e-corp.com","rootDomain":"e-corp.com","contactName":"박민호"}""";

	// ------------------------------------------------------------ 등록 (WF5)

	@Test
	void registerCreatesInactiveProvisioningTenantAndSendsSignedLink(CapturedOutput output) throws Exception {
		String body = register(VALID).andExpect(status().isCreated())
			.andExpect(jsonPath("$.mailSent").value(true))
			.andExpect(jsonPath("$.provisioningStatus").value("PROVISIONING"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String tenantId = body.replaceAll(".*\"tenantId\":\"([a-z0-9]{12})\".*", "$1");
		assertThat(tenantId).matches("^[a-z0-9]{12}$");

		Map<String, Object> tenant = data.tenantRow(tenantId);
		assertThat(tenant).containsEntry("name", "E사")
			.containsEntry("email", "minho.park@e-corp.com")
			.containsEntry("root_domain", "e-corp.com")
			.containsEntry("contact_name", "박민호")
			.containsEntry("password", null)
			.containsEntry("account_status", "INACTIVE")
			.containsEntry("provisioning_status", "PROVISIONING");
		List<Map<String, Object>> logs = data.tenantStatusLogs(tenantId);
		assertThat(logs).hasSize(1);
		assertThat(logs.get(0)).containsEntry("prev_status", null)
			.containsEntry("new_status", "INACTIVE")
			.containsEntry("reason", "테넌트 등록");

		ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
		verify(stepFunctions).startExecution(eq("arn:aws:states:ap-northeast-2:000000000000:stateMachine:tenant"),
				eq("tenant-" + tenantId), input.capture());
		assertThat(input.getValue()).contains("\"tenant_id\":\"" + tenantId + "\"", "\"root_domain\":\"e-corp.com\"",
				"\"env\":\"test\"");

		ArgumentCaptor<MailGateway.Mail> mailCaptor = ArgumentCaptor.forClass(MailGateway.Mail.class);
		verify(mail).send(mailCaptor.capture());
		MailGateway.Mail sent = mailCaptor.getValue();
		assertThat(sent.to()).isEqualTo("minho.park@e-corp.com");
		assertThat(sent.subject()).isEqualTo("[Tetra] 테넌트 계정이 등록되었습니다");
		assertThat(sent.textBody()).contains("안녕하세요, 박민호님.", "Tetra에 E사 테넌트 계정이 등록되었습니다.", "24시간");

		// 링크: {portal}/password/setup?t=&exp=&sig=, sig = base64url(HMAC-SHA256(secret, t + exp + ""))
		String link = sent.textBody().replaceAll("(?s).*(https://portal\\.example\\.test/password/setup\\?\\S+).*", "$1");
		Map<String, String> query = query(link);
		assertThat(query.get("t")).isEqualTo(tenantId);
		long exp = Long.parseLong(query.get("exp"));
		assertThat(exp - System.currentTimeMillis() / 1000).isBetween(24 * 3600L - 60, 24 * 3600L + 60);
		assertThat(query.get("sig")).isEqualTo(hmac("test-hmac-secret", tenantId + exp));

		// 링크는 응답·로그·DB에 남지 않는다
		assertThat(body).doesNotContain("sig=").doesNotContain("password/setup");
		assertThat(output.getAll()).doesNotContain(query.get("sig")).doesNotContain("password/setup");
		assertThat(sent.toString()).doesNotContain("sig=");
	}

	/** 테넌트 스키마의 테이블 수 (Flyway 이력 테이블 제외). */
	private long tenantTableCount(String schema) {
		return data.count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ?"
				+ " AND table_name <> 'flyway_schema_history'", schema);
	}

	@Test
	void registerCreatesTenantSchemaWithFlywayBeforeStartExecutionAndMail() throws Exception {
		// StartExecution이 불릴 때 이미 스키마가 다 만들어져 있어야 한다
		AtomicLong tablesAtStartExecution = new AtomicLong(-1);
		when(stepFunctions.startExecution(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
			String executionName = invocation.getArgument(1);
			tablesAtStartExecution.set(tenantTableCount("t_" + executionName.substring("tenant-".length())));
			return true;
		});

		String body = register(VALID).andExpect(status().isCreated())
			.andExpect(jsonPath("$.mailSent").value(true))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String schema = "t_" + body.replaceAll(".*\"tenantId\":\"([a-z0-9]{12})\".*", "$1");

		assertThat(tablesAtStartExecution).hasValue(10);
		assertThat(tenantTableCount(schema)).isEqualTo(10);
		assertThat(data.count("SELECT COUNT(*) FROM " + schema + ".flyway_schema_history"
				+ " WHERE version = '1' AND success = 1")).isEqualTo(1);
		InOrder order = inOrder(stepFunctions, mail);
		order.verify(stepFunctions).startExecution(anyString(), anyString(), anyString());
		order.verify(mail).send(any());

		// 앱 런타임 계정(DML 전용)은 t\_% 와일드카드 권한으로 새 스키마를 바로 쓸 수 있다
		try (Connection app = DriverManager.getConnection(MySqlTestDatabase.appJdbcUrl(schema),
				MySqlTestDatabase.APP_USER, MySqlTestDatabase.APP_PASSWORD);
				Statement statement = app.createStatement();
				ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM tenant_notification")) {
			rows.next();
			assertThat(rows.getLong(1)).isZero();
		}
	}

	@Test
	void startExecutionFailureMarksProvisioningFailedButKeepsTenant() throws Exception {
		when(stepFunctions.startExecution(anyString(), anyString(), anyString())).thenReturn(false);

		String body = register(VALID).andExpect(status().isCreated())
			.andExpect(jsonPath("$.mailSent").value(true))
			.andExpect(jsonPath("$.provisioningStatus").value("FAILED"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String tenantId = body.replaceAll(".*\"tenantId\":\"([a-z0-9]{12})\".*", "$1");
		assertThat(data.tenantRow(tenantId)).containsEntry("provisioning_status", "FAILED");
		// 스키마는 남기고(수동 재실행으로 복구) 메일도 보낸다. 알림 문구로 스키마 생성 실패와 구분
		assertThat(tenantTableCount("t_" + tenantId)).isEqualTo(10);
		verify(mail).send(any());
		assertThat(data.count("SELECT COUNT(*) FROM tetra_platform.operator_notification WHERE tenant_id = ?"
				+ " AND category = 'PROVISIONING' AND priority = 'URGENT' AND content LIKE '%StartExecution 실패%수동 재실행%'",
				tenantId)).isEqualTo(1);
	}

	@Test
	void mailFailureStillReturns201WithFlag() throws Exception {
		when(mail.send(any())).thenReturn(false);

		// 메일만 실패: 환경 준비는 계속 진행 중 → 화면은 "메일 발송 실패"로 (스키마 생성 실패 FAILED와 구분)
		register(VALID).andExpect(status().isCreated())
			.andExpect(jsonPath("$.mailSent").value(false))
			.andExpect(jsonPath("$.provisioningStatus").value("PROVISIONING"));
		assertThat(data.count("SELECT COUNT(*) FROM tetra_platform.tenant")).isEqualTo(1);
	}

	@Test
	void duplicateEmailIs409() throws Exception {
		data.tenant(A, "A사", "minho.park@e-corp.com", "ACTIVE", "READY", TestData.BASE);

		register(VALID).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_DUPLICATED"));
		// 대소문자만 다른 이메일도 uk_tenant_email(utf8mb4_0900_ai_ci)로 중복
		register(VALID.replace("minho.park", "Minho.Park")).andExpect(status().isConflict());
		verify(stepFunctions, never()).startExecution(anyString(), anyString(), anyString());
	}

	@Test
	void invalidInputIs400WithFields() throws Exception {
		register("{}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
			.andExpect(jsonPath("$.fields.name").exists())
			.andExpect(jsonPath("$.fields.email").exists())
			.andExpect(jsonPath("$.fields.rootDomain").exists())
			.andExpect(jsonPath("$.fields.contactName").exists());
		register("""
				{"name":"%s","email":"not-an-email","rootDomain":"no_tld","contactName":"  "}""".formatted("가".repeat(101)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.name").value("100자 이하로 입력해 주세요."))
			.andExpect(jsonPath("$.fields.email").value("이메일 형식이 올바르지 않습니다."))
			.andExpect(jsonPath("$.fields.rootDomain").value("도메인 형식이 올바르지 않습니다."))
			.andExpect(jsonPath("$.fields.contactName").value("필수 입력입니다."));
	}

	@Test
	void emailAvailability() throws Exception {
		data.tenant(A, "A사", "taken@a.com", "ACTIVE", "READY", TestData.BASE);

		mvc.perform(get("/api/ops/tenants/email-availability").param("email", "taken@a.com").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.available").value(false));
		mvc.perform(get("/api/ops/tenants/email-availability").param("email", "free@a.com").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.available").value(true));
		mvc.perform(get("/api/ops/tenants/email-availability").param("email", "bad").cookie(operatorCookie()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.email").exists());
	}

	// ------------------------------------------------------------ 수정 (OPS-06-M3)

	@Test
	void patchUpdatesExactlyOneField() throws Exception {
		data.tenant(A, "A사", "a@a.com", "ACTIVE", "READY", TestData.BASE);
		data.tenant(B, "B사", "b@b.com", "ACTIVE", "READY", TestData.BASE);

		mvc.perform(patch("/api/ops/tenants/{id}", A).cookie(operatorCookie())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"  A사(변경)  \"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("A사(변경)"))
			.andExpect(jsonPath("$.provisioningStatus").value("READY"));
		assertThat(data.tenantRow(A)).containsEntry("name", "A사(변경)");

		mvc.perform(patch("/api/ops/tenants/{id}", A).cookie(operatorCookie())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"contactName\":\"새 담당자\"}")).andExpect(status().isOk());
		assertThat(data.tenantRow(A)).containsEntry("contact_name", "새 담당자");

		mvc.perform(patch("/api/ops/tenants/{id}", A).cookie(operatorCookie())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"new@a.com\"}")).andExpect(status().isOk());
		assertThat(data.tenantRow(A)).containsEntry("email", "new@a.com");
		assertThat(data.tenantStatusLogs(A)).isEmpty();

		mvc.perform(patch("/api/ops/tenants/{id}", A).cookie(operatorCookie())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"b@b.com\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("EMAIL_DUPLICATED"));
		mvc.perform(patch("/api/ops/tenants/{id}", A).cookie(operatorCookie())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"x\",\"email\":\"x@x.com\"}")).andExpect(status().isBadRequest());
		mvc.perform(patch("/api/ops/tenants/{id}", A).cookie(operatorCookie())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"   \"}")).andExpect(status().isBadRequest());
		mvc.perform(patch("/api/ops/tenants/{id}", A).cookie(operatorCookie())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"bad\"}")).andExpect(status().isBadRequest());
		mvc.perform(patch("/api/ops/tenants/{id}", "zzzzzzzzzzzz").cookie(operatorCookie())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"x\"}")).andExpect(status().isNotFound());
	}

	// ------------------------------------------------------------ 조회 (OPS-06, 06-1, 06-2, 06-3)

	@Test
	void listIsNewestFirstWithCounts() throws Exception {
		data.tenant(A, "A사", "a@a.com", "ACTIVE", "READY", TestData.BASE);
		data.tenant(B, "B사", "b@b.com", "INACTIVE", "FAILED", TestData.BASE.plusDays(1));

		mvc.perform(get("/api/ops/tenants").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.counts.ACTIVE").value(1))
			.andExpect(jsonPath("$.counts.INACTIVE").value(1))
			.andExpect(jsonPath("$.items[0].tenantId").value(B))
			.andExpect(jsonPath("$.items[0].email").value("b@b.com"))
			.andExpect(jsonPath("$.items[0].accountStatus").value("INACTIVE"))
			.andExpect(jsonPath("$.items[0].provisioningStatus").value("FAILED"))
			.andExpect(jsonPath("$.items[0].createdAt").value("2026-10-02T10:00:00+09:00"))
			.andExpect(jsonPath("$.items[1].tenantId").value(A))
			.andExpect(jsonPath("$.items[1].provisioningStatus").value("READY"))
			.andExpect(jsonPath("$.items[1].createdAt").value("2026-10-01T10:00:00+09:00"));
	}

	@Test
	void overviewHasBucketsAndBadgedStatusLogsNewestFirst() throws Exception {
		LocalDateTime t = TestData.BASE;
		data.tenant(A, "A사", "a@a.com", "INACTIVE", "READY", t);
		data.event(5001, A).status("REJECTED").insert();
		data.event(5002, A).approved("ENDED", "EXPIRED", "abcd1234").insert();
		data.tenantStatusLog(A, t, null, "INACTIVE", "테넌트 등록", null);
		data.tenantStatusLog(A, t.plusDays(1), "INACTIVE", "ACTIVE", null, 5001L);
		data.tenantStatusLog(A, t.plusDays(2), "ACTIVE", "INACTIVE", null, 5001L);
		data.tenantStatusLog(A, t.plusDays(3), "INACTIVE", "ACTIVE", null, 5002L);
		data.tenantStatusLog(A, t.plusDays(4), "ACTIVE", "INACTIVE", null, 5002L);

		mvc.perform(get("/api/ops/tenants/{id}", A).cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.basic.contactName").value("A사 담당자"))
			.andExpect(jsonPath("$.basic.createdAt").value("2026-10-01T10:00:00+09:00"))
			.andExpect(jsonPath("$.basic.provisioningStatus").value("READY"))
			.andExpect(jsonPath("$.buckets.content").value("tetra-storage-test-" + A))
			.andExpect(jsonPath("$.buckets.auditLog").value("tetra-audit-logs-test-" + A))
			.andExpect(jsonPath("$.statusLogs[0].badge").value("ENDED"))
			.andExpect(jsonPath("$.statusLogs[0].relatedEvent.eventId").value(5002))
			.andExpect(jsonPath("$.statusLogs[1].badge").value("APPLIED"))
			.andExpect(jsonPath("$.statusLogs[2].badge").value("REJECTED"))
			.andExpect(jsonPath("$.statusLogs[2].relatedEvent.name").value("이벤트 5001"))
			.andExpect(jsonPath("$.statusLogs[3].badge").value("APPLIED"))
			.andExpect(jsonPath("$.statusLogs[4].badge").value("REGISTERED"))
			.andExpect(jsonPath("$.statusLogs[4].prevStatus").doesNotExist());
	}

	@Test
	void eventsAreAppliedAtDescending() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(5101, A).appliedAt(TestData.BASE).applied().insert();
		data.event(5102, A).appliedAt(TestData.BASE.plusHours(1)).approved("HOSTING", "OPEN", "abcd1234").insert();

		mvc.perform(get("/api/ops/tenants/{id}/events", A).cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].eventId").value(5102))
			.andExpect(jsonPath("$[0].hostingStatus").value("HOSTING"))
			.andExpect(jsonPath("$[1].eventId").value(5101))
			.andExpect(jsonPath("$[1].hostingStatus").doesNotExist())
			.andExpect(jsonPath("$[1].trafficPlanCode").value("M"));
	}

	@Test
	void resourcesShowApprovedOnlyAndHideExpired() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(5201, A).applied().insert();
		data.event(5202, A).appliedAt(TestData.BASE.plusHours(2)).approved("HOSTING", "OPEN", "k7f2q9xa").insert();
		data.event(5203, A).appliedAt(TestData.BASE.plusHours(1)).approved("ENDED", "EXPIRED", "zzzz0000").insert();
		data.activeKey(A, 5202, 1, "PEM-v1");
		data.activeKey(A, 5202, 2, "PEM-v2");
		data.activeKey(A, 5203, 1, "PEM-old");

		mvc.perform(get("/api/ops/tenants/{id}/resources", A).cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].eventId").value(5202))
			.andExpect(jsonPath("$[0].subdomain").value("k7f2q9xa." + A))
			.andExpect(jsonPath("$[0].publicKey").value("PEM-v2"))
			.andExpect(jsonPath("$[1].eventId").value(5203))
			.andExpect(jsonPath("$[1].subdomain").doesNotExist())
			.andExpect(jsonPath("$[1].publicKey").doesNotExist());
	}

	private static Map<String, String> query(String url) {
		return List.of(URI.create(url).getRawQuery().split("&"))
			.stream()
			.map(p -> p.split("=", 2))
			.collect(Collectors.toMap(p -> p[0], p -> p[1]));
	}

	private static String hmac(String secret, String message) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
	}

}
