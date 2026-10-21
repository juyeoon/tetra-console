package com.tetra.ops.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 회사 root 도메인 규칙. 예시 표는 web/src/utils/validation.test.ts와 같다 — 두 쪽 규칙이 어긋나지 않게 같이 고친다.
 */
class TenantInputRulesTest {

	private static final String LABEL_63 = "a".repeat(63);

	private static String domain(String value, Map<String, String> fields) {
		return TenantInputRules.domain("rootDomain", value, fields);
	}

	@ParameterizedTest(name = "[{0}] → {1}")
	@CsvSource(delimiter = '|', ignoreLeadingAndTrailingWhitespace = false, value = {
			"e-corp.com|e-corp.com",
			"E-Corp.COM|e-corp.com",
			"shop.e-corp.co.kr|shop.e-corp.co.kr",
			"a.io|a.io",
			"  e-corp.com  |e-corp.com" })
	void acceptsAndNormalizesToLowerCase(String input, String normalized) {
		Map<String, String> fields = new HashMap<>();
		assertThat(domain(input, fields)).isEqualTo(normalized);
		assertThat(fields).isEmpty();
	}

	@Test
	void labelOf63AndTotalOf253AreBoundaries() {
		Map<String, String> fields = new HashMap<>();
		assertThat(domain(LABEL_63 + ".com", fields)).isEqualTo(LABEL_63 + ".com");
		String max = LABEL_63 + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(57) + ".com";
		assertThat(max).hasSize(253);
		assertThat(domain(max, fields)).isEqualTo(max);
		assertThat(fields).isEmpty();
	}

	@ParameterizedTest(name = "[{0}]")
	@ValueSource(strings = { "https://e-corp.com", "http://e-corp.com", "e-corp.com/shop", "e-corp.com:8080", "e corp.com",
			"e_corp.com", "e-corp.com.", "-e.com", "e-.com", "e.123", "회사.한국", "e-corp.xn--3e0b707e", "localhost" })
	void rejectsSchemePathPortSpaceUnderscoreTrailingDotLabelRulesNumericTldKoreanPunycode(String input) {
		Map<String, String> fields = new HashMap<>();
		assertThat(domain(input, fields)).isNull();
		assertThat(fields).containsEntry("rootDomain", TenantInputRules.DOMAIN_INVALID);
	}

	@Test
	void rejectsLabelOver63AndTotalOver253AndBlank() {
		Map<String, String> label = new HashMap<>();
		assertThat(domain("a".repeat(64) + ".com", label)).isNull();
		assertThat(label).containsEntry("rootDomain", TenantInputRules.DOMAIN_INVALID);

		String over = LABEL_63 + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(58) + ".com";
		assertThat(over).hasSize(254);
		Map<String, String> length = new HashMap<>();
		assertThat(domain(over, length)).isNull();
		assertThat(length).containsEntry("rootDomain", "253자 이하로 입력해 주세요.");

		Map<String, String> blank = new HashMap<>();
		assertThat(domain("", blank)).isNull();
		assertThat(blank).containsEntry("rootDomain", "필수 입력입니다.");
	}

}
