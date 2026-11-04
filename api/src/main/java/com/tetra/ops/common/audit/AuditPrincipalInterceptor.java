package com.tetra.ops.common.audit;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.HandlerInterceptor;

import com.tetra.ops.auth.CurrentOperator;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 인증된 요청의 JWT sub(operator_id)를 AuditContext에 옮긴다(Portal 복사본). AuditFilter는 보안 체인 바깥이라 끝날 때는 보안
 * 컨텍스트가 이미 비워져 있으므로, 체인 안(MVC)에서 미리 복사해 둔다. 로그인(공개 경로)도 쿠키가 유효하면 여기서 채워진다.
 */
public class AuditPrincipalInterceptor implements HandlerInterceptor {

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof Jwt jwt) {
			try {
				AuditContext.of(request).operator(CurrentOperator.id(jwt));
			}
			catch (NumberFormatException ex) {
				// sub가 숫자가 아니면(우리가 발급한 토큰이 아님) 행위자를 비워 둔다
			}
		}
		return true;
	}

}
