package com.tetra.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.filter.ForwardedHeaderFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.tetra.ops.auth.JwtTokenService;
import com.tetra.ops.common.audit.AuditActions;
import com.tetra.ops.common.audit.AuditContext;
import com.tetra.ops.common.audit.AuditFilter;
import com.tetra.ops.external.DownloadUrlSigner.SignedUrl;
import com.tetra.ops.support.IntegrationTest;
import com.tetra.ops.support.TestData;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 운영자 감사 로그(2026-11-05 결정) — /api/ops/** 요청마다 stdout에 JSON 한 줄(조회 포함, 표에 없으면 API_REQUEST + path).
 * 출력 캡처로 실제 나간 줄을 파싱해 본다. 값은 모두 가짜(시험용)다.
 */
@ExtendWith(OutputCaptureExtension.class)
class AuditLogApiTest extends IntegrationTest {

	static final JsonMapper JSON = JsonMapper.builder().build();

	static final List<String> KEY_ORDER = List.of("log_type", "audit_id", "time", "app", "actor", "ip", "user_agent",
			"action", "path", "target", "tenant_id", "event_id", "result", "reason", "changes", "truncated", "attempt",
			"request_id");

	static final Pattern TIME = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\+09:00$");

	static final Pattern UUID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

	static final String OPERATOR = String.valueOf(TestData.OPERATOR_ID);

	static final String TENANT_EMAIL = "audit.owner@a-corp.example";

	@Autowired
	FilterRegistrationBean<AuditFilter> auditFilter;

	@Autowired
	ApplicationContext context;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping handlerMapping;

	// ---------------------------------------------------------------- 도우미

	interface Call {

		void run() throws Exception;

	}

	/** 지금까지 나온 감사 줄(원문). 감사 줄은 늘 줄 맨 앞에서 {"log_type":"AUDIT" 로 시작한다. */
	static List<String> auditLines(CapturedOutput output) {
		return output.getOut().lines().filter(line -> line.startsWith("{\"log_type\":\"AUDIT\"")).toList();
	}

	/** call 동안 새로 나온 감사 줄. */
	static List<JsonNode> during(CapturedOutput output, Call call) throws Exception {
		int before = auditLines(output).size();
		call.run();
		List<String> lines = auditLines(output);
		List<JsonNode> parsed = new ArrayList<>();
		for (String line : lines.subList(before, lines.size())) {
			assertThat(line.chars().allMatch(c -> c >= 0x20 && c < 0x7F)).as("ASCII 한 줄: %s", line).isTrue();
			JsonNode node = JSON.readTree(line);
			assertShape(node);
			parsed.add(node);
		}
		return parsed;
	}

	/** call 동안 감사 줄이 정확히 하나. */
	static JsonNode one(CapturedOutput output, Call call) throws Exception {
		List<JsonNode> lines = during(output, call);
		assertThat(lines).hasSize(1);
		return lines.get(0);
	}

	/** 모든 줄 공통: 키 순서 · 고정 값 · 형식. actor · target · tenant_id는 없어도 null로 있다. path는 API_REQUEST만. */
	static void assertShape(JsonNode line) {
		List<String> keys = List.copyOf(line.propertyNames());
		assertThat(KEY_ORDER).containsSubsequence(keys);
		assertThat(keys).contains("log_type", "audit_id", "time", "app", "actor", "ip", "action", "target", "tenant_id",
				"result", "request_id");
		assertThat(line.path("log_type").asString()).isEqualTo("AUDIT");
		assertThat(line.path("app").asString()).isEqualTo("console");
		assertThat(line.path("audit_id").asString()).matches(UUID);
		assertThat(line.path("time").asString()).matches(TIME);
		assertThat(line.path("result").asString()).isIn("SUCCESS", "FAILURE");
		assertThat(line.has("path")).isEqualTo(line.path("action").asString().equals("API_REQUEST"));
		assertThat(keys).doesNotContain("password", "sig", "token", "cookie", "body", "stack", "q", "email");
	}

	/** 로그인한 운영자(1)의 성공 줄. */
	static void assertOperatorLine(JsonNode line, String action) {
		assertThat(line.path("action").asString()).isEqualTo(action);
		assertThat(line.path("result").asString()).isEqualTo("SUCCESS");
		assertThat(line.has("reason")).isFalse();
		assertThat(line.path("actor").path("type").asString()).isEqualTo("OPERATOR");
		assertThat(line.path("actor").path("id").asString()).isEqualTo(OPERATOR);
	}

	static void assertTarget(JsonNode line, String type, Object id) {
		assertThat(line.path("target").path("type").asString()).isEqualTo(type);
		assertThat(line.path("target").path("id").asString()).isEqualTo(String.valueOf(id));
	}

	static void assertChange(JsonNode line, String field, String before, String after) {
		JsonNode change = line.path("changes").path(field);
		if (before == null) {
			assertThat(change.path("before").isNull()).as(field).isTrue();
		}
		else {
			assertThat(change.path("before").asString()).as(field).isEqualTo(before);
		}
		assertThat(change.path("after").asString()).as(field).isEqualTo(after);
	}

	private ResultActions login(String email, String password) throws Exception {
		return mvc.perform(post("/api/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
	}

	private void appliedEvent(long eventId) {
		data.event(eventId, A).applied().insert();
		data.statusLog(A, eventId, "APPLICATION", null, "APPLYING", TestData.BASE, null);
		data.statusLog(A, eventId, "APPLICATION", "APPLYING", "APPLIED", TestData.BASE, null);
	}

	/** 결과가 READY인 이벤트(발급 이력 CSV를 받을 수 있음). */
	private void readyEvent(long eventId) {
		data.event(eventId, A).approved("ENDED", "EXPIRED", "audt" + eventId).insert();
		data.jdbc().update("""
				INSERT INTO t_%s.event_result_summary (event_id, tenant_id, session_count, ticket_count, peak_concurrent,
				    peak_at, metrics_finalized_at, total_stock, success_count, soldout_count, sellout_rate,
				    history_file_path, history_generated_at)
				VALUES (?, ?, 20, 10, 10, '2026-11-01 10:00:30', '2026-11-01 23:00:00', 9000, 3, 1, 0.0003, ?, '2026-11-02 00:12:00')"""
			.formatted(A), eventId, A, "issuance-history/" + eventId + ".csv");
	}

	private long operatorReport() {
		data.jdbc().update("""
				INSERT INTO tetra_platform.operator_report_meta (name, period_start, period_end, category, created_at, file_path)
				VALUES ('9월 운영자 감사', ?, ?, 'OPERATOR_AUDIT', ?, 'reports/2026/09/operator-audit-202609.csv')""",
				TestData.BASE, TestData.BASE, TestData.BASE);
		return data.jdbc().queryForObject("SELECT MAX(report_id) FROM tetra_platform.operator_report_meta", Long.class);
	}

	// ---------------------------------------------------------------- 대표 행위

	@Test
	void representativeActionsWriteOneLineEach(CapturedOutput output) throws Exception {
		data.tenant(A, "A사", TENANT_EMAIL, "ACTIVE", "READY", TestData.BASE);
		appliedEvent(1101);
		appliedEvent(1102);
		readyEvent(1103);
		long report = operatorReport();
		when(urlSigner.presignGet(any(), any(), any(), any()))
			.thenReturn(new SignedUrl("https://signed.example/file", Instant.now().plusSeconds(300)));

		// 로그인 성공 — 행위자 · 대상 = 운영자, tenant_id 없음
		String[] setCookie = new String[1];
		JsonNode login = one(output, () -> setCookie[0] = login(TestData.OPERATOR_EMAIL, TestData.OPERATOR_PASSWORD)
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getHeader(HttpHeaders.SET_COOKIE));
		assertOperatorLine(login, "AUTH_LOGIN");
		assertTarget(login, "OPERATOR", OPERATOR);
		assertThat(login.path("tenant_id").isNull()).isTrue();
		assertThat(login.has("attempt")).isFalse();
		String issuedToken = setCookie[0].substring(setCookie[0].indexOf('=') + 1, setCookie[0].indexOf(';'));
		Cookie cookie = new Cookie(JwtTokenService.COOKIE_NAME, issuedToken);

		JsonNode me = one(output, () -> mvc.perform(get("/api/ops/me").cookie(cookie)).andExpect(status().isOk()));
		assertOperatorLine(me, "ACCOUNT_VIEW");
		assertTarget(me, "OPERATOR", OPERATOR);

		// 목록 조회 — 대상 · tenant_id 없음
		JsonNode applications = one(output, () -> mvc.perform(get("/api/ops/applications").cookie(cookie))
			.andExpect(status().isOk()));
		assertOperatorLine(applications, "APPLICATION_LIST_VIEW");
		assertThat(applications.path("target").isNull()).isTrue();
		assertThat(applications.path("tenant_id").isNull()).isTrue();
		assertThat(applications.has("event_id")).isFalse();

		// 이벤트 조회 — tenant_id는 서비스가 찾은 event_registry 값
		JsonNode detail = one(output, () -> mvc.perform(get("/api/ops/events/{id}", 1101).cookie(cookie))
			.andExpect(status().isOk()));
		assertOperatorLine(detail, "EVENT_VIEW");
		assertTarget(detail, "EVENT", 1101);
		assertThat(detail.path("event_id").asLong()).isEqualTo(1101);
		assertThat(detail.path("tenant_id").asString()).isEqualTo(A);

		JsonNode approve = one(output, () -> mvc.perform(post("/api/ops/events/{id}/approve", 1101).cookie(cookie))
			.andExpect(status().isOk()));
		assertOperatorLine(approve, "EVENT_APPROVE");
		assertTarget(approve, "EVENT", 1101);
		assertThat(approve.path("tenant_id").asString()).isEqualTo(A);
		assertChange(approve, "application_status", "APPLIED", "APPROVED");
		assertThat(List.copyOf(approve.path("changes").propertyNames())).containsExactly("application_status");

		String longReason = "배너 이미지가 정책에 맞지 않습니다. ".repeat(20); // 400자 넘음 → 감사 줄은 200자
		JsonNode reject = one(output, () -> mvc.perform(post("/api/ops/events/{id}/reject", 1102).cookie(cookie)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"%s\"}".formatted(longReason.strip()))).andExpect(status().isOk()));
		assertOperatorLine(reject, "EVENT_REJECT");
		assertTarget(reject, "EVENT", 1102);
		assertThat(reject.path("tenant_id").asString()).isEqualTo(A);
		assertChange(reject, "application_status", "APPLIED", "REJECTED");
		assertChange(reject, "reject_reason", null, longReason.strip().substring(0, 200));

		JsonNode csv = one(output, () -> mvc.perform(get("/api/ops/events/{id}/result/history-csv", 1103).cookie(cookie))
			.andExpect(status().isOk()));
		assertOperatorLine(csv, "ISSUANCE_CSV_DOWNLOAD");
		assertTarget(csv, "EVENT", 1103);
		assertThat(csv.path("event_id").asLong()).isEqualTo(1103);
		assertThat(csv.path("tenant_id").asString()).isEqualTo(A);

		JsonNode download = one(output, () -> mvc.perform(get("/api/ops/reports/{id}/download", report).cookie(cookie))
			.andExpect(status().isOk()));
		assertOperatorLine(download, "REPORT_DOWNLOAD");
		assertTarget(download, "REPORT", report);
		assertThat(download.path("tenant_id").isNull()).isTrue();

		// 테넌트 등록 — 대상 · tenant_id = 새 id, 등록 값(이메일 · 담당자 이름은 가림)
		String[] created = new String[1];
		JsonNode register = one(output, () -> created[0] = mvc.perform(post("/api/ops/tenants").cookie(cookie)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"name":"E사","email":"minho.park@e-corp.example","rootDomain":"e-corp.example","contactName":"박민호"}"""))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString()
			.replaceAll(".*\"tenantId\":\"([a-z0-9]{12})\".*", "$1"));
		assertOperatorLine(register, "TENANT_REGISTER");
		assertTarget(register, "TENANT", created[0]);
		assertThat(register.path("tenant_id").asString()).isEqualTo(created[0]);
		assertThat(List.copyOf(register.path("changes").propertyNames())).containsExactly("name", "email", "root_domain",
				"contact_name");
		assertChange(register, "name", null, "E사");
		assertChange(register, "email", null, "mi***@e-***.example");
		assertChange(register, "root_domain", null, "e-corp.example");
		assertChange(register, "contact_name", null, "박*호");

		// 테넌트 수정 — 바꾸기 전 · 후(가림), tenant_id = 경로
		JsonNode updateEmail = one(output, () -> mvc.perform(patch("/api/ops/tenants/{id}", A).cookie(cookie)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"changed.owner@a-corp.example\"}")).andExpect(status().isOk()));
		assertOperatorLine(updateEmail, "TENANT_UPDATE");
		assertTarget(updateEmail, "TENANT", A);
		assertThat(updateEmail.path("tenant_id").asString()).isEqualTo(A);
		assertChange(updateEmail, "email", "au***@a-***.example", "ch***@a-***.example");
		JsonNode updateContact = one(output, () -> mvc.perform(patch("/api/ops/tenants/{id}", A).cookie(cookie)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"contactName\":\"남궁민수\"}")).andExpect(status().isOk()));
		assertChange(updateContact, "contact_name", "A****자", "남**수"); // 원래 값 "A사 담당자"(TestData)
		JsonNode updateName = one(output, () -> mvc.perform(patch("/api/ops/tenants/{id}", A).cookie(cookie)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"A사(변경)\"}")).andExpect(status().isOk()));
		assertChange(updateName, "name", "A사", "A사(변경)");
		// 같은 값으로 다시 — 행위는 남지만 바뀐 필드가 없으니 changes 없음
		JsonNode unchanged = one(output, () -> mvc.perform(patch("/api/ops/tenants/{id}", A).cookie(cookie)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"A사(변경)\"}")).andExpect(status().isOk()));
		assertThat(unchanged.path("action").asString()).isEqualTo("TENANT_UPDATE");
		assertThat(unchanged.has("changes")).isFalse();

		// 검색어 · 확인한 이메일(쿼리)은 남기지 않는다
		JsonNode notifications = one(output, () -> mvc
			.perform(get("/api/ops/notifications").param("q", "audit-search-word").cookie(cookie))
			.andExpect(status().isOk()));
		assertOperatorLine(notifications, "NOTIFICATION_LIST_VIEW");
		JsonNode emailCheck = one(output, () -> mvc
			.perform(get("/api/ops/tenants/email-availability").param("email", "probe.check@x-corp.example").cookie(cookie))
			.andExpect(status().isOk()));
		assertOperatorLine(emailCheck, "TENANT_EMAIL_CHECK");
		assertThat(emailCheck.path("tenant_id").isNull()).isTrue();

		JsonNode logout = one(output, () -> mvc.perform(post("/api/ops/auth/logout").cookie(cookie))
			.andExpect(status().isNoContent()));
		assertOperatorLine(logout, "AUTH_LOGOUT");
		assertTarget(logout, "OPERATOR", OPERATOR);

		// 비밀 값 · 개인정보 원문은 감사 줄 · 일반 로그 어디에도 없다
		assertThat(output.getAll()).doesNotContain(TestData.OPERATOR_PASSWORD)
			.doesNotContain(TestData.OPERATOR_EMAIL)
			.doesNotContain(issuedToken)
			.doesNotContain(TENANT_EMAIL)
			.doesNotContain("changed.owner@a-corp.example")
			.doesNotContain("minho.park@e-corp.example")
			.doesNotContain("probe.check@x-corp.example")
			.doesNotContain("A사 담당자")
			.doesNotContain("박민호")
			.doesNotContain("남궁민수")
			.doesNotContain("audit-search-word")
			.doesNotContain("signed.example");
		for (String line : auditLines(output)) {
			assertThat(line).doesNotContain("\\uBC15\\uBBFC\\uD638").doesNotContain("\\uB0A8\\uAD81\\uBBFC\\uC218");
		}
	}

	// ---------------------------------------------------------------- 실패

	@Test
	void loginFailureKeepsOnlyMaskedAttempt(CapturedOutput output) throws Exception {
		JsonNode unknown = one(output, () -> login("kim.audit@example.com", "wrong-password-xyz")
			.andExpect(status().isUnauthorized()));
		assertThat(unknown.path("action").asString()).isEqualTo("AUTH_LOGIN");
		assertThat(unknown.path("result").asString()).isEqualTo("FAILURE");
		assertThat(unknown.path("reason").asString()).isEqualTo("LOGIN_FAILED");
		assertThat(unknown.path("attempt").asString()).isEqualTo("ki***@ex***.com");
		assertThat(unknown.path("actor").isNull()).isTrue();
		assertThat(unknown.path("target").isNull()).isTrue();
		assertThat(unknown.path("tenant_id").isNull()).isTrue();

		JsonNode wrongPassword = one(output, () -> login(TestData.OPERATOR_EMAIL, "wrong-password-xyz")
			.andExpect(status().isUnauthorized()));
		assertThat(wrongPassword.path("attempt").asString()).isEqualTo(
				com.tetra.ops.common.audit.Masking.email(TestData.OPERATOR_EMAIL));
		assertThat(wrongPassword.path("target").isNull()).isTrue();

		// 다른 운영자 쿠키를 든 채 실패 — 행위자는 그 쿠키의 운영자, 대상은 없음(시도한 계정은 attempt로만)
		JsonNode withCookie = one(output, () -> mvc.perform(post("/api/ops/auth/login")
			.cookie(new Cookie(JwtTokenService.COOKIE_NAME, tokens.issue(2L)))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"kim.audit@example.com\",\"password\":\"wrong-password-xyz\"}"))
			.andExpect(status().isUnauthorized()));
		assertThat(withCookie.path("actor").path("id").asString()).isEqualTo("2");
		assertThat(withCookie.path("target").isNull()).isTrue();
		assertThat(withCookie.path("attempt").asString()).isEqualTo("ki***@ex***.com");

		// 검사에서 끝난 실패는 계정을 보지 않았으므로 attempt 없음
		JsonNode invalid = one(output, () -> mvc.perform(post("/api/ops/auth/login")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"\"}")).andExpect(status().isBadRequest()));
		assertThat(invalid.path("reason").asString()).isEqualTo("INVALID_INPUT");
		assertThat(invalid.has("attempt")).isFalse();

		assertThat(output.getAll()).doesNotContain("kim.audit@example.com")
			.doesNotContain(TestData.OPERATOR_EMAIL)
			.doesNotContain("wrong-password-xyz");
	}

	/** 패턴이 없는 요청(보안 체인 401 · 403, 405, 없는 경로 404)과 표에 없는 경로는 API_REQUEST + 쿼리 뺀 경로. */
	@Test
	void requestsWithoutTableEntryAreApiRequestWithPath(CapturedOutput output) throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		appliedEvent(1201);

		JsonNode noCookie = one(output, () -> mvc.perform(post("/api/ops/events/{id}/approve", 1201))
			.andExpect(status().isUnauthorized()));
		assertThat(noCookie.path("action").asString()).isEqualTo("API_REQUEST");
		assertThat(noCookie.path("path").asString()).isEqualTo("/api/ops/events/1201/approve");
		assertThat(noCookie.path("result").asString()).isEqualTo("FAILURE");
		assertThat(noCookie.path("reason").asString()).isEqualTo("UNAUTHENTICATED");
		assertThat(noCookie.path("actor").isNull()).isTrue();
		assertThat(noCookie.path("target").isNull()).isTrue();
		assertThat(noCookie.path("tenant_id").isNull()).isTrue();
		assertThat(noCookie.has("event_id")).isFalse();

		// 망가진 쿠키도 보안 체인이 401. 쿼리(검색어)는 경로에 넣지 않는다
		JsonNode broken = one(output, () -> mvc.perform(get("/api/ops/notifications").param("q", "audit-hidden-word")
			.cookie(new Cookie(JwtTokenService.COOKIE_NAME, "garbage-token-value"))).andExpect(status().isUnauthorized()));
		assertThat(broken.path("action").asString()).isEqualTo("API_REQUEST");
		assertThat(broken.path("path").asString()).isEqualTo("/api/ops/notifications");
		assertThat(broken.path("actor").isNull()).isTrue();

		// 403 — 인증 객체는 있으나 인증되지 않음 → accessDeniedHandler
		JsonNode forbidden = one(output, () -> mvc.perform(patch("/api/ops/notifications/read-all")
			.with(authentication(new TestingAuthenticationToken("someone", null)))).andExpect(status().isForbidden()));
		assertThat(forbidden.path("action").asString()).isEqualTo("API_REQUEST");
		assertThat(forbidden.path("path").asString()).isEqualTo("/api/ops/notifications/read-all");
		assertThat(forbidden.path("reason").asString()).isEqualTo("FORBIDDEN");
		assertThat(forbidden.path("actor").isNull()).isTrue();

		// 없는 경로 404 — 화면 파일 처리기("/**")까지 와서 인터셉터가 운영자를 채운다
		JsonNode notFound = one(output, () -> mvc.perform(get("/api/ops/no-such-api/{x}", "x1").cookie(operatorCookie()))
			.andExpect(status().isNotFound()));
		assertThat(notFound.path("action").asString()).isEqualTo("API_REQUEST");
		assertThat(notFound.path("path").asString()).isEqualTo("/api/ops/no-such-api/x1");
		assertThat(notFound.path("reason").asString()).isEqualTo("NOT_FOUND");
		assertThat(notFound.path("actor").path("id").asString()).isEqualTo(OPERATOR);

		// 표에 있는 경로라도 다른 메서드면 패턴이 없다 — 405
		JsonNode wrongMethod = one(output, () -> mvc.perform(get("/api/ops/auth/login").cookie(operatorCookie()))
			.andExpect(status().isMethodNotAllowed()));
		assertThat(wrongMethod.path("action").asString()).isEqualTo("API_REQUEST");
		assertThat(wrongMethod.path("path").asString()).isEqualTo("/api/ops/auth/login");
		assertThat(wrongMethod.path("reason").asString()).isEqualTo("METHOD_NOT_ALLOWED");

		// /api/ops 밖(헬스 체크 · 화면 파일 · SPA 경로)은 남기지 않는다
		List<JsonNode> outside = during(output, () -> {
			mvc.perform(get("/actuator/health")).andExpect(status().isOk());
			mvc.perform(get("/")).andExpect(status().isOk());
			mvc.perform(get("/events/1201")).andExpect(status().isOk());
		});
		assertThat(outside).isEmpty();
		assertThat(output.getAll()).doesNotContain("garbage-token-value").doesNotContain("audit-hidden-word");
	}

	@Test
	void exceptionEscapingTheFilterIsUnhandledFailure(CapturedOutput output) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ops/auth/logout");
		JsonNode line = one(output, () -> assertThatThrownBy(() -> auditFilter.getFilter()
			.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
				throw new IllegalStateException("boom");
			})).isInstanceOf(IllegalStateException.class));
		assertThat(line.path("action").asString()).isEqualTo("API_REQUEST");
		assertThat(line.path("path").asString()).isEqualTo("/api/ops/auth/logout");
		assertThat(line.path("result").asString()).isEqualTo("FAILURE");
		assertThat(line.path("reason").asString()).isEqualTo("UNHANDLED");
	}

	// ---------------------------------------------------------------- tenant_id

	@Test
	void tenantIdComesFromPathOrEventRegistryElseNull(CapturedOutput output) throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.tenant(B, "B사", "ACTIVE", "READY");
		data.event(2101, B).applied().insert();
		Cookie cookie = operatorCookie();

		JsonNode tenantPath = one(output, () -> mvc.perform(get("/api/ops/tenants/{id}/events", B).cookie(cookie))
			.andExpect(status().isOk()));
		assertThat(tenantPath.path("action").asString()).isEqualTo("TENANT_EVENTS_VIEW");
		assertTarget(tenantPath, "TENANT", B);
		assertThat(tenantPath.path("tenant_id").asString()).isEqualTo(B);

		// 서비스가 이벤트를 찾기 전에 끝난 요청(본문 검사 400) — 필터가 event_registry를 한 번 본다
		JsonNode eventPath = one(output, () -> mvc.perform(post("/api/ops/events/{id}/reject", 2101).cookie(cookie)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")).andExpect(status().isBadRequest()));
		assertThat(eventPath.path("action").asString()).isEqualTo("EVENT_REJECT");
		assertThat(eventPath.path("reason").asString()).isEqualTo("INVALID_INPUT");
		assertThat(eventPath.path("event_id").asLong()).isEqualTo(2101);
		assertThat(eventPath.path("tenant_id").asString()).isEqualTo(B);

		JsonNode missingEvent = one(output, () -> mvc.perform(get("/api/ops/events/{id}", 999999).cookie(cookie))
			.andExpect(status().isNotFound()));
		assertThat(missingEvent.path("reason").asString()).isEqualTo("EVENT_NOT_FOUND");
		assertThat(missingEvent.path("event_id").asLong()).isEqualTo(999999);
		assertThat(missingEvent.path("tenant_id").isNull()).isTrue();

		JsonNode badTenant = one(output, () -> mvc.perform(get("/api/ops/tenants/{id}", "NOT-A-TENANT").cookie(cookie))
			.andExpect(status().isNotFound()));
		assertThat(badTenant.path("tenant_id").isNull()).isTrue();

		JsonNode badEvent = one(output, () -> mvc.perform(get("/api/ops/events/{id}", "abc").cookie(cookie))
			.andExpect(status().isBadRequest()));
		assertThat(badEvent.has("event_id")).isFalse();
		assertThat(badEvent.path("tenant_id").isNull()).isTrue();

		// 여러 테넌트 목록은 null
		List<JsonNode> lists = during(output, () -> {
			mvc.perform(get("/api/ops/tenants").cookie(cookie)).andExpect(status().isOk());
			mvc.perform(get("/api/ops/live-events").cookie(cookie)).andExpect(status().isOk());
			mvc.perform(get("/api/ops/dashboard").cookie(cookie)).andExpect(status().isOk());
		});
		assertThat(lists).extracting(l -> l.path("action").asString())
			.containsExactly("TENANT_LIST_VIEW", "LIVE_EVENT_LIST_VIEW", "DASHBOARD_VIEW");
		assertThat(lists).allMatch(l -> l.path("tenant_id").isNull());
	}

	/** 서비스가 이미 찾은 이벤트면 다시 조회하지 않고, 아니면 한 번만. 조회가 실패해도 줄은 남는다(tenant_id만 null). */
	@Test
	void filterLooksUpEventRegistryOnlyWhenServiceDidNot(CapturedOutput output) throws Exception {
		AtomicInteger lookups = new AtomicInteger();
		AuditFilter filter = new AuditFilter(Clock.systemUTC(), eventId -> {
			lookups.incrementAndGet();
			if (eventId == 3) {
				throw new IllegalStateException("db down");
			}
			return B;
		});

		JsonNode reused = one(output, () -> filter.doFilter(eventRequest(1), new MockHttpServletResponse(),
				(req, res) -> AuditContext.of((MockHttpServletRequest) req).eventTenant(1L, A)));
		assertThat(reused.path("tenant_id").asString()).isEqualTo(A);
		assertThat(lookups).hasValue(0);

		JsonNode looked = one(output, () -> filter.doFilter(eventRequest(2), new MockHttpServletResponse(), (req, res) -> {
		}));
		assertThat(looked.path("tenant_id").asString()).isEqualTo(B);
		assertThat(lookups).hasValue(1);

		JsonNode failed = one(output, () -> filter.doFilter(eventRequest(3), new MockHttpServletResponse(), (req, res) -> {
		}));
		assertThat(failed.path("tenant_id").isNull()).isTrue();
		assertThat(failed.path("event_id").asLong()).isEqualTo(3);
		assertThat(lookups).hasValue(2);
		assertThat(output.getAll()).doesNotContain("db down");
	}

	/** MVC가 /events/{eventId}를 고른 것처럼 패턴 · 경로 변수를 넣은 요청(필터 단독 시험용). */
	private static MockHttpServletRequest eventRequest(long eventId) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ops/events/" + eventId);
		request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/ops/events/{eventId}");
		request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("eventId", Long.toString(eventId)));
		return request;
	}

	// ---------------------------------------------------------------- 표 · ip · request_id

	/** 컨트롤러의 /api/ops 매핑과 행위 표가 정확히 같다 — 엔드포인트를 더하면 표에도 코드를 붙인다. */
	@Test
	void everyApiMappingHasAnActionCode() {
		Set<String> mapped = new HashSet<>();
		for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
			for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
				if (pattern.startsWith("/api/ops")) {
					info.getMethodsCondition().getMethods().forEach(method -> mapped.add(method + " " + pattern));
				}
			}
		}
		Set<String> table = new HashSet<>();
		AuditActions.ENTRIES.forEach(entry -> table.add(entry.method() + " " + entry.pattern()));
		assertThat(table).hasSize(AuditActions.ENTRIES.size()).isEqualTo(mapped);
		assertThat(AuditActions.ENTRIES).extracting(AuditActions.Entry::action).doesNotHaveDuplicates()
			.allMatch(action -> action.matches("^[A-Z]+(_[A-Z]+)+$"));
	}

	@Test
	void ipIsRightMostForwardedForValueAddedByAlb(CapturedOutput output) throws Exception {
		Cookie cookie = operatorCookie();
		JsonNode forwarded = one(output, () -> mvc.perform(get("/api/ops/me").cookie(cookie)
			.header("X-Forwarded-For", "1.1.1.1, 10.0.0.5")));
		assertThat(forwarded.path("ip").asString()).isEqualTo("10.0.0.5");

		// 클라이언트가 보낸 값(왼쪽)은 쓰지 않는다 — ALB가 끝에 실제 주소(SSM 중계 지점)를 붙인다
		JsonNode spoofed = one(output, () -> mvc.perform(get("/api/ops/me").cookie(cookie)
			.header("X-Forwarded-For", "6.6.6.6, 7.7.7.7,10.0.2.33")));
		assertThat(spoofed.path("ip").asString()).isEqualTo("10.0.2.33");

		JsonNode direct = one(output, () -> mvc.perform(get("/api/ops/me").cookie(cookie).with(request -> {
			request.setRemoteAddr("192.0.2.10");
			return request;
		})));
		assertThat(direct.path("ip").asString()).isEqualTo("192.0.2.10");

		// framework 전략(ForwardedHeaderFilter)이 꺼져 있다 — 켜지면 맨 왼쪽 값으로 remoteAddr를 바꾸고 헤더를 지운다
		assertThat(context.getEnvironment().getProperty("server.forward-headers-strategy")).isEqualTo("none");
		assertThat(context.getBeansOfType(ForwardedHeaderFilter.class)).isEmpty();
		assertThat(context.getBeansOfType(FilterRegistrationBean.class).values())
			.noneMatch(registration -> registration.getFilter() instanceof ForwardedHeaderFilter);
	}

	@Test
	void requestIdComesFromAlbTraceHeader(CapturedOutput output) throws Exception {
		String root = "1-67891233-abcdef012345678912345678";
		String self = "1-67891234-12456789abcdef0123456789";
		Cookie cookie = operatorCookie();

		JsonNode both = one(output, () -> mvc.perform(get("/api/ops/me").cookie(cookie)
			.header("X-Amzn-Trace-Id", "Self=" + self + ";Root=" + root + ";Sampled=1")));
		assertThat(both.path("request_id").asString()).isEqualTo(self);

		JsonNode rootOnly = one(output, () -> mvc.perform(get("/api/ops/me").cookie(cookie)
			.header("X-Amzn-Trace-Id", "Root=" + root)));
		assertThat(rootOnly.path("request_id").asString()).isEqualTo(root);

		JsonNode malformed = one(output, () -> mvc.perform(get("/api/ops/me").cookie(cookie)
			.header("X-Amzn-Trace-Id", "Root=1-zzzz-not-a-trace")));
		assertThat(malformed.path("request_id").asString()).matches(UUID);

		JsonNode missing = one(output, () -> mvc.perform(get("/api/ops/me").cookie(cookie)));
		assertThat(missing.path("request_id").asString()).matches(UUID);
		assertThat(missing.path("audit_id").asString()).isNotEqualTo(missing.path("request_id").asString());
	}

	// ---------------------------------------------------------------- 한 줄 · 형식

	@Test
	void lineStaysOneAsciiLineWhateverTheValues(CapturedOutput output) throws Exception {
		String userAgent = "Mozilla/5.0 (테스트 브라우저)\n{\"log_type\":\"AUDIT\",\"forged\":true}\r\u0001 끝";
		JsonNode line = one(output, () -> mvc.perform(get("/api/ops/me").cookie(operatorCookie())
			.header(HttpHeaders.USER_AGENT, userAgent)));
		assertThat(line.path("user_agent").asString()).isEqualTo(userAgent);
		// 값 안의 줄바꿈으로 가짜 감사 줄을 만들 수 없다
		assertThat(output.getOut().lines()).noneMatch(l -> l.startsWith("{\"log_type\":\"AUDIT\",\"forged\""));
		String raw = auditLines(output).get(auditLines(output).size() - 1);
		assertThat(raw).contains("\\n").contains("\\r").contains("\\u0001").doesNotContain("테스트");
		assertThat(raw.toLowerCase()).contains("\\ud14c"); // 테

		JsonNode longAgent = one(output, () -> mvc.perform(get("/api/ops/me").cookie(operatorCookie())
			.header(HttpHeaders.USER_AGENT, "a".repeat(5000))));
		assertThat(longAgent.path("user_agent").asString()).hasSize(512);

		JsonNode longPath = one(output, () -> mvc.perform(get("/api/ops/" + "p".repeat(1000)).cookie(operatorCookie())));
		assertThat(longPath.path("path").asString()).hasSize(256);

		// JSON 본문 안의 \n → 이메일 값에 실제 줄바꿈
		JsonNode korean = one(output, () -> login("김\\n철수@예시.한국", "아무 비밀번호").andExpect(status().isUnauthorized()));
		assertThat(korean.path("attempt").asString()).isEqualTo("김\n***@예***.한국");
		assertThat(output.getAll()).doesNotContain("아무 비밀번호");
	}

	/** 일반 로그는 Boot 기본 콘솔 형식 그대로 — MDC request_id도 찍히지 않는다. 감사 줄은 그 형식으로 한 번 더 나가지 않는다. */
	@Test
	void generalLogFormatIsUnchanged(CapturedOutput output) throws Exception {
		long report = operatorReport();
		String root = "1-6789abcd-0123456789abcdef01234567";

		JsonNode line = one(output, () -> mvc.perform(post("/api/ops/reports/{id}/email", report)
			.cookie(operatorCookie())
			.header("X-Amzn-Trace-Id", "Root=" + root)).andExpect(status().isNoContent()));
		assertThat(line.path("action").asString()).isEqualTo("REPORT_MAIL");
		assertTarget(line, "REPORT", report);
		assertThat(line.path("request_id").asString()).isEqualTo(root);

		Pattern bootConsole = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{2}:\\d{2} +INFO \\d+ --- "
				+ "\\[tetra-ops-api\\] \\[.{15}\\] .{40} : 리포트 메일 발송: reportId=\\d+, operatorId=1$");
		List<String> infoLines = output.getOut()
			.lines()
			.map(l -> l.replaceAll("\u001B\\[[;\\d]*m", ""))
			.filter(l -> l.contains("리포트 메일 발송: reportId="))
			.toList();
		assertThat(infoLines).hasSize(1);
		assertThat(infoLines.get(0)).matches(bootConsole).doesNotContain(root);
		assertThat(output.getOut().lines().filter(l -> l.contains("\"log_type\":\"AUDIT\"")))
			.allMatch(l -> l.startsWith("{\"log_type\":\"AUDIT\""));
	}

}
