package com.tetra.ops.common;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

/** CSPRNG로 만드는 [a-z0-9] 식별자. slug 8자, tenant_id 12자. */
@Component
public class RandomIds {

	private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

	private final SecureRandom random = new SecureRandom();

	public String slug() {
		return next(8);
	}

	public String tenantId() {
		return next(12);
	}

	private String next(int length) {
		char[] chars = new char[length];
		for (int i = 0; i < length; i++) {
			chars[i] = ALPHABET[random.nextInt(ALPHABET.length)];
		}
		return new String(chars);
	}

}
