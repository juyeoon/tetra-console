package com.tetra.ops.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import com.tetra.ops.config.TetraProperties;

/**
 * 운영자 JWT 발급·검증.
 * TODO(미정): 운영자 인증 방식. 임시로 JWT(HS256, OPS_JWT_SECRET)를 HttpOnly·Secure 쿠키로 내려주고 만료 8시간.
 * 서버 세션을 쓰지 않으므로 로그아웃은 쿠키만 지운다(토큰 자체는 만료 전까지 유효).
 * 토큰 값은 로그·예외 메시지에 넣지 않는다.
 */
@Component
public class JwtTokenService {

	public static final String COOKIE_NAME = "TETRA_OPS_TOKEN";

	static final Duration TTL = Duration.ofHours(8);

	private static final String ISSUER = "tetra-ops-api";

	private final JwtEncoder encoder;

	private final JwtDecoder decoder;

	private final Clock clock;

	public JwtTokenService(TetraProperties properties, Clock clock) {
		SecretKey key = new SecretKeySpec(properties.auth().jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
		this.encoder = new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(key));
		this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
		this.clock = clock;
	}

	public JwtDecoder decoder() {
		return decoder;
	}

	public String issue(Long operatorId) {
		Instant now = clock.instant();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(ISSUER)
			.subject(String.valueOf(operatorId))
			.issuedAt(now)
			.expiresAt(now.plus(TTL))
			.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

	public ResponseCookie loginCookie(String token) {
		return baseCookie(token).maxAge(TTL).build();
	}

	public ResponseCookie logoutCookie() {
		return baseCookie("").maxAge(Duration.ZERO).build();
	}

	private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
		return ResponseCookie.from(COOKIE_NAME, value).httpOnly(true).secure(true).sameSite("Strict").path("/api/ops");
	}

}
