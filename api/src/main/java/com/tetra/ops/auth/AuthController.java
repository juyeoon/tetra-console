package com.tetra.ops.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tetra.ops.common.error.ApiErrorCodes;
import com.tetra.ops.common.error.ErrorCode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/** OPS-01 로그인, OPS-10 로그아웃·계정 정보, 헤더의 운영자 이름. */
@Tag(name = "인증", description = "OPS-01 로그인 · OPS-10 로그아웃·계정 정보 · 헤더 운영자 이름")
@RestController
@RequestMapping("/api/ops")
public class AuthController {

	private final AuthService authService;

	private final JwtTokenService tokens;

	public AuthController(AuthService authService, JwtTokenService tokens) {
		this.authService = authService;
		this.tokens = tokens;
	}

	public record LoginRequest(
			@Schema(example = "operator@tetra-operation.com", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank(message = "필수 입력입니다.") String email,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank(message = "필수 입력입니다.") String password) {

		@Override
		public String toString() {
			return "LoginRequest[email=" + email + ", password=****]";
		}

	}

	public record MeResponse(@Schema(description = "계정 이메일") String email,
			@Schema(description = "담당자 이름 (헤더의 \"홍길동님\")") String name) {
	}

	@Operation(summary = "OPS-01 운영자 로그인",
			description = "성공하면 HttpOnly·Secure 쿠키 TETRA_OPS_TOKEN(8시간, Path=/api/ops)을 내려준다. "
					+ "이메일이 없든 비밀번호가 틀리든 같은 401 LOGIN_FAILED")
	@SecurityRequirements
	@ApiErrorCodes({ ErrorCode.INVALID_INPUT, ErrorCode.LOGIN_FAILED })
	@PostMapping("/auth/login")
	public ResponseEntity<MeResponse> login(@Valid @RequestBody LoginRequest request) {
		AuthService.LoginResult result = authService.login(request.email(), request.password());
		return ResponseEntity.ok()
			.header(HttpHeaders.SET_COOKIE, tokens.loginCookie(result.token()).toString())
			.body(new MeResponse(result.operator().getEmail(), result.operator().getContactName()));
	}

	@Operation(summary = "OPS-10 로그아웃", description = "쿠키를 지운다(204)")
	@PostMapping("/auth/logout")
	public ResponseEntity<Void> logout() {
		return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, tokens.logoutCookie().toString()).build();
	}

	@Operation(summary = "로그인한 운영자 (헤더 · OPS-10 계정 정보)")
	@GetMapping("/me")
	public MeResponse me(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
		var operator = authService.currentOperator(CurrentOperator.id(jwt));
		return new MeResponse(operator.getEmail(), operator.getContactName());
	}

}
