package com.tetra.ops;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import com.tetra.ops.auth.JwtTokenService;
import com.tetra.ops.support.IntegrationTest;

import jakarta.servlet.http.Cookie;

/**
 * 화면과 API를 한 이미지로 서빙 (CLAUDE.md §3). 화면 파일은 인증 없이, /api/**만 인증.
 * 테스트용 화면 파일은 src/test/resources/static/에 있다(실제로는 web/dist가 들어간다).
 */
class SpaRoutingTest extends IntegrationTest {

	static final String INDEX_MARK = "tetra-console-test-index";

	@Test
	void indexAndAssetsAreServedWithoutLogin() throws Exception {
		// "/"는 Spring Boot 환영 페이지가 index.html로 forward한다(실제 서버는 forward를 따라감, MockMvc는 대상만 기록)
		mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
		mvc.perform(get("/index.html")).andExpect(status().isOk()).andExpect(content().string(containsString(INDEX_MARK)));
		mvc.perform(get("/assets/app.js"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("tetra-console-test-asset")));
	}

	@Test
	void spaRoutesFallBackToIndex() throws Exception {
		for (String path : new String[] { "/login", "/events/1043", "/tenants/k7f2m9qx4b1t/resources",
				"/applications" }) {
			mvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().string(containsString(INDEX_MARK)));
		}
	}

	@Test
	void missingFilesWithExtensionAre404NotIndex() throws Exception {
		mvc.perform(get("/assets/missing.js"))
			.andExpect(status().isNotFound())
			.andExpect(content().string(not(containsString(INDEX_MARK))));
		mvc.perform(get("/favicon.ico")).andExpect(status().isNotFound());
	}

	@Test
	void apiStillRequiresLoginAndNeverFallsBackToIndex() throws Exception {
		mvc.perform(get("/api/ops/me")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/ops/unknown")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/ops/unknown").cookie(operatorCookie()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
	}

	@Test
	void expiredCookieOnScreenRequestDoesNotBreakPage() throws Exception {
		Cookie broken = new Cookie(JwtTokenService.COOKIE_NAME, "expired-or-garbage");
		mvc.perform(get("/events/1043").cookie(broken))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(INDEX_MARK)));
		mvc.perform(get("/api/ops/me").cookie(broken)).andExpect(status().isUnauthorized());
	}

}
