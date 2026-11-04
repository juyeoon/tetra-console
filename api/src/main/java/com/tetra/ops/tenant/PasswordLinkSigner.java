package com.tetra.ops.tenant;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.tetra.ops.config.TetraProperties;

/**
 * 비밀번호 설정 링크 서명 (비밀번호 설정 링크 방식 결정 문서).
 * sig = HMAC-SHA256(PASSWORD_LINK_HMAC_SECRET, tenant_id + exp + 현재 password 값(NULL이면 빈 문자열)), base64url.
 * Portal 백엔드가 같은 규칙으로 검증한다.
 * TODO(미정): 연결 형식·인코딩은 Portal 담당과 맞춤. 임시로 구분자 없이 이어 붙인 UTF-8 문자열, exp는 10진 epoch초,
 * 결과는 패딩 없는 base64url.
 * 값은 쿼리(?)가 아니라 프래그먼트(#t=…&exp=…&sig=…)로 붙인다 — 프래그먼트는 브라우저가 서버로 보내지 않아 ALB 접근 로그 ·
 * 감사 로그 · Referer에 서명이 남지 않는다(2026-11-05, Portal 화면은 둘 다 읽게 이미 배포됨). 서명 계산 · 만료는 그대로.
 * 링크는 DB·로그·응답에 남기지 않는다.
 */
@Component
public class PasswordLinkSigner {

	static final Duration TTL = Duration.ofHours(24);

	private final byte[] secret;

	private final String portalBaseUrl;

	private final Clock clock;

	public PasswordLinkSigner(TetraProperties properties, Clock clock) {
		this.secret = properties.passwordLink().hmacSecret().getBytes(StandardCharsets.UTF_8);
		String base = properties.portal().baseUrl();
		this.portalBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
		this.clock = clock;
	}

	/** 링크와 만료 시각. toString에 링크를 내보내지 않는다. */
	public record SignedLink(String url, Instant expiresAt) {

		@Override
		public String toString() {
			return "SignedLink[url=****, expiresAt=" + expiresAt + "]";
		}

	}

	public SignedLink create(String tenantId, String currentPassword) {
		Instant expiresAt = clock.instant().plus(TTL);
		long exp = expiresAt.getEpochSecond();
		String url = portalBaseUrl + "/password/setup#t=" + tenantId + "&exp=" + exp + "&sig="
				+ sign(tenantId, exp, currentPassword);
		return new SignedLink(url, Instant.ofEpochSecond(exp));
	}

	String sign(String tenantId, long exp, String currentPassword) {
		String message = tenantId + exp + (currentPassword == null ? "" : currentPassword);
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret, "HmacSHA256"));
			byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HMAC 계산 실패");
		}
	}

}
