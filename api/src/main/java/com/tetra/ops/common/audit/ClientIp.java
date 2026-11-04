package com.tetra.ops.common.audit;

import java.util.Enumeration;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 감사 줄의 ip(Portal과 같은 규칙). 앞단은 내부 ALB 한 단계뿐이고, ALB는 받은 X-Forwarded-For 끝에 자기가 본 클라이언트 IP를 붙인다
 * (append, 기본) → 원본 헤더의 <b>맨 오른쪽</b> 값만 믿는다. 왼쪽 값은 클라이언트가 보낸 것이라 바꿀 수 있다.
 * 헤더가 없으면(로컬 · 시험) getRemoteAddr().
 * Console은 운영자가 SSM 포트 포워딩으로 들어오므로 이 값은 중계 지점의 사설 IP다(실제 접속자는 CloudTrail StartSession).
 * 이 규칙은 server.forward-headers-strategy: none 전제다 — framework면 ForwardedHeaderFilter가 헤더를 지우고 맨 왼쪽 값을 쓴다.
 * 앞단이 바뀌면 같이 바꾼다.
 */
public final class ClientIp {

	static final String HEADER = "X-Forwarded-For";

	static final int MAX_LENGTH = 64;

	private ClientIp() {
	}

	public static String resolve(HttpServletRequest request) {
		String last = null;
		// 같은 헤더가 여러 줄이면 마지막 줄이 ALB가 붙인 쪽
		for (Enumeration<String> headers = request.getHeaders(HEADER); headers != null && headers.hasMoreElements();) {
			String header = headers.nextElement();
			if (header != null && !header.isBlank()) {
				last = header;
			}
		}
		if (last != null) {
			String rightMost = last.substring(last.lastIndexOf(',') + 1).strip();
			if (!rightMost.isEmpty()) {
				return AuditContext.limit(rightMost, MAX_LENGTH);
			}
		}
		return AuditContext.limit(request.getRemoteAddr(), MAX_LENGTH);
	}

}
