package com.tetra.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.tetra.ops.auth.JwtTokenService;
import com.tetra.ops.support.IntegrationTest;
import com.tetra.ops.support.TestData;

import jakarta.servlet.http.Cookie;

/** OPS-01 로그인, OPS-10 로그아웃·계정 정보 (§7.1). */
@ExtendWith(OutputCaptureExtension.class)
class AuthApiTest extends IntegrationTest {

	@Test
	void loginSetsHttpOnlySecureCookieAndMeWorks(CapturedOutput output) throws Exception {
		var result = mvc.perform(post("/api/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(TestData.OPERATOR_EMAIL,
					TestData.OPERATOR_PASSWORD)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value(TestData.OPERATOR_EMAIL))
			.andExpect(jsonPath("$.name").value("홍길동"))
			.andReturn();
		String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
		assertThat(setCookie).startsWith(JwtTokenService.COOKIE_NAME + "=")
			.contains("HttpOnly", "Secure", "SameSite=Strict", "Max-Age=28800", "Path=/api/ops");
		String token = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
		assertThat(result.getResponse().getContentAsString()).doesNotContain(token);

		mvc.perform(get("/api/ops/me").cookie(new Cookie(JwtTokenService.COOKIE_NAME, token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value(TestData.OPERATOR_EMAIL))
			.andExpect(jsonPath("$.name").value("홍길동"));

		assertThat(output.getAll()).doesNotContain(token).doesNotContain(TestData.OPERATOR_PASSWORD);
	}

	@Test
	void wrongPasswordAndUnknownEmailGiveTheSame401() throws Exception {
		String wrongPassword = mvc
			.perform(post("/api/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"nope\"}".formatted(TestData.OPERATOR_EMAIL)))
			.andExpect(status().isUnauthorized())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String unknownEmail = mvc
			.perform(post("/api/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"nobody@x.com\",\"password\":\"nope\"}"))
			.andExpect(status().isUnauthorized())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(wrongPassword).isEqualTo(unknownEmail).contains("LOGIN_FAILED");
	}

	@Test
	void everyApiExceptLoginNeedsAuthentication() throws Exception {
		for (String path : new String[] { "/api/ops/me", "/api/ops/dashboard", "/api/ops/applications",
				"/api/ops/tenants", "/api/ops/notifications" }) {
			mvc.perform(get(path))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		}
		mvc.perform(post("/api/ops/auth/logout")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/ops/me").cookie(new Cookie(JwtTokenService.COOKIE_NAME, "garbage")))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void logoutClearsCookie() throws Exception {
		String setCookie = mvc.perform(post("/api/ops/auth/logout").cookie(operatorCookie()))
			.andExpect(status().isNoContent())
			.andReturn()
			.getResponse()
			.getHeader(HttpHeaders.SET_COOKIE);
		assertThat(setCookie).startsWith(JwtTokenService.COOKIE_NAME + "=;").contains("Max-Age=0");
	}

	@Test
	void healthIsOpenAndOtherActuatorEndpointsAreNot() throws Exception {
		mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
		mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
		mvc.perform(get("/actuator/env").cookie(operatorCookie())).andExpect(status().isNotFound());
		mvc.perform(get("/actuator/configprops").cookie(operatorCookie())).andExpect(status().isNotFound());
	}

}
