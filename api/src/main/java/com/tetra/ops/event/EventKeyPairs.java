package com.tetra.ops.event;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import org.springframework.stereotype.Component;

/** 발급 API JWT(RS256)용 RSA 2048 키 쌍. 공개키는 PEM으로 저장하고, 개인키는 메모리에만 두고 메일로 보낸다. */
@Component
public class EventKeyPairs {

	private static final Base64.Encoder PEM_BASE64 = Base64.getMimeEncoder(64,
			"\n".getBytes(StandardCharsets.US_ASCII));

	/** 개인키(PKCS#8 PEM)는 로그·예외·응답에 내보내지 않는다. */
	public record KeyMaterial(String publicKeyPem, String privateKeyPem) {

		@Override
		public String toString() {
			return "KeyMaterial[publicKeyPem=" + publicKeyPem.length() + " chars, privateKeyPem=****]";
		}

	}

	public KeyMaterial generate() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			KeyPair pair = generator.generateKeyPair();
			// Java의 RSA 공개키 인코딩은 X.509 SubjectPublicKeyInfo, 개인키 인코딩은 PKCS#8
			return new KeyMaterial(pem("PUBLIC KEY", pair.getPublic().getEncoded()),
					pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("RSA 키 쌍 생성 실패", ex);
		}
	}

	private static String pem(String type, byte[] der) {
		return "-----BEGIN " + type + "-----\n" + PEM_BASE64.encodeToString(der) + "\n-----END " + type + "-----\n";
	}

}
