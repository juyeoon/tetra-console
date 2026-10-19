package com.tetra.ops.auth;

import org.springframework.security.oauth2.jwt.Jwt;

/** 인증된 요청의 operator_id. 승인·반려의 processed_by, changed_by에 쓴다. */
public final class CurrentOperator {

	private CurrentOperator() {
	}

	public static Long id(Jwt jwt) {
		return Long.valueOf(jwt.getSubject());
	}

}
