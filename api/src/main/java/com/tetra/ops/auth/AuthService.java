package com.tetra.ops.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.tetra.ops.common.audit.AuditContext;
import com.tetra.ops.common.error.ApiException;
import com.tetra.ops.common.error.ErrorCode;
import com.tetra.ops.domain.platform.Operator;
import com.tetra.ops.domain.platform.OperatorRepository;

/** 잠금·MFA 없음. 계정 존재 여부를 구분하지 않고 실패는 401 하나로만 응답한다 (§7.1). */
@Service
public class AuthService {

	/** 없는 이메일에도 해시 비교를 한 번 해서 응답 시간으로 계정 존재 여부가 드러나지 않게 한다. */
	private static final String DUMMY_HASH = "$2a$10$T1EpFVINGQ000xopLK.afuBaAeaA9nmyd86nRTJTlVHRuKD1lx0Re";

	private final OperatorRepository operators;

	private final PasswordEncoder passwordEncoder;

	private final JwtTokenService tokens;

	public AuthService(OperatorRepository operators, PasswordEncoder passwordEncoder, JwtTokenService tokens) {
		this.operators = operators;
		this.passwordEncoder = passwordEncoder;
		this.tokens = tokens;
	}

	public record LoginResult(Operator operator, String token) {

		@Override
		public String toString() {
			return "LoginResult[operatorId=" + operator.getOperatorId() + ", token=****]";
		}

	}

	public LoginResult login(String email, String rawPassword) {
		Operator operator = operators.findByEmail(email).orElse(null);
		String hash = operator == null ? DUMMY_HASH : operator.getPassword();
		boolean matches = passwordEncoder.matches(rawPassword, hash);
		if (operator == null || !matches) {
			// 감사 줄: 시도한 계정은 가려서만(attempt). 원문 · 비밀번호는 남기지 않는다
			AuditContext.current().loginAttempt(email);
			throw new ApiException(ErrorCode.LOGIN_FAILED);
		}
		AuditContext.current().loginSucceeded(operator.getOperatorId());
		return new LoginResult(operator, tokens.issue(operator.getOperatorId()));
	}

	public Operator currentOperator(Long operatorId) {
		return operators.findById(operatorId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
	}

}
