package com.tetra.ops.config;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import com.tetra.ops.auth.CookieBearerTokenResolver;
import com.tetra.ops.auth.JwtTokenService;
import com.tetra.ops.common.error.ErrorCode;
import com.tetra.ops.common.error.ErrorResponse;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder, JsonMapper jsonMapper)
			throws Exception {
		http
			// 서버 세션 없음 (Pod 여러 개). 인증은 JWT 쿠키만.
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			// TODO(미정): CSRF. 내부망 전용이고 쿠키를 SameSite=Strict로 내려 임시로 끈다. 프런트 도메인 확정 후 재검토
			.csrf(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.requestCache(AbstractHttpConfigurer::disable)
			// API(/api/**)만 인증한다. 그 밖은 같은 이미지에 들어 있는 화면 파일(classpath:static, web/dist)과
			// SPA 경로(SpaWebConfig가 index.html로 넘김)라 인증 없이 연다. 로그인 화면도 여기서 받는다.
			.authorizeHttpRequests(auth -> auth
				.requestMatchers(HttpMethod.POST, "/api/ops/auth/login").permitAll()
				.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
				.requestMatchers("/api/**", "/actuator/**").authenticated()
				.anyRequest().permitAll())
			.oauth2ResourceServer(oauth -> oauth
				.bearerTokenResolver(new CookieBearerTokenResolver())
				.jwt(jwt -> jwt.decoder(jwtDecoder))
				.authenticationEntryPoint((request, response, ex) -> write(response, jsonMapper, ErrorCode.UNAUTHENTICATED)))
			.exceptionHandling(ex -> ex
				.authenticationEntryPoint((request, response, e) -> write(response, jsonMapper, ErrorCode.UNAUTHENTICATED))
				.accessDeniedHandler((request, response, e) -> write(response, jsonMapper, ErrorCode.FORBIDDEN)));
		return http.build();
	}

	@Bean
	JwtDecoder jwtDecoder(JwtTokenService tokens) {
		return tokens.decoder();
	}

	// TODO(미정): 운영자 비밀번호 해시 알고리즘. BCrypt로 가정 (운영자는 DB CLI로 등록하므로 CLI와 같은 알고리즘이어야 함)
	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	private static void write(HttpServletResponse response, JsonMapper jsonMapper, ErrorCode code) throws IOException {
		response.setStatus(code.status().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		jsonMapper.writeValue(response.getOutputStream(), ErrorResponse.of(code));
	}

}
