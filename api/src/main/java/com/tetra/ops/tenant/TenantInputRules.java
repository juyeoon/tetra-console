package com.tetra.ops.tenant;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.tetra.ops.common.error.ApiException;
import com.tetra.ops.common.error.ErrorCode;

/** 테넌트 입력 검증. 길이는 물리 ERD(이름 100, 이메일 255, 도메인 253). 앞뒤 공백은 잘라서 저장한다(도메인은 소문자로도 바꾼다). */
final class TenantInputRules {

	static final int NAME_MAX = 100;

	static final int EMAIL_MAX = 255;

	static final int DOMAIN_MAX = 253;

	// TODO(미정): 이메일 형식 규칙. 임시로 local@domain.tld 형태만 확인
	private static final Pattern EMAIL = Pattern
		.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*\\.[A-Za-z]{2,}$");

	/**
	 * 회사 root 도메인: RFC 1123 호스트명, 소문자 기준(소문자로 바꾼 뒤 검사 · 저장). 전체 253자 · 라벨 63자,
	 * 점 1개 이상, 영문 TLD 2~63자. http(s)://·경로·포트·공백·밑줄·끝의 점·한글 도메인·퓨니코드 TLD(MVP 제외)는 거부.
	 * web/src/utils/validation.ts의 isDomain과 같은 규칙 — 예시 표(TenantInputRulesTest · validation.test.ts)도 같이 고친다.
	 */
	private static final Pattern DOMAIN = Pattern
		.compile("^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");

	static final String DOMAIN_INVALID = "도메인 형식이 올바르지 않습니다. (예: e-corp.com — http://, 경로, 포트 없이)";

	private TenantInputRules() {
	}

	/** 필수·길이 검사 후 앞뒤 공백을 자른 값을 돌려준다. 실패하면 fields에 사유를 넣고 null. */
	static String text(String field, String value, int max, Map<String, String> fields) {
		if (value == null || value.isBlank()) {
			fields.put(field, "필수 입력입니다.");
			return null;
		}
		String trimmed = value.strip();
		if (trimmed.length() > max) {
			fields.put(field, max + "자 이하로 입력해 주세요.");
			return null;
		}
		return trimmed;
	}

	static String email(String field, String value, Map<String, String> fields) {
		String trimmed = text(field, value, EMAIL_MAX, fields);
		if (trimmed != null && !EMAIL.matcher(trimmed).matches()) {
			fields.put(field, "이메일 형식이 올바르지 않습니다.");
			return null;
		}
		return trimmed;
	}

	/** 앞뒤 공백 제거 → 소문자(Locale.ROOT) → 형식 검사. 소문자로 바꾼 값을 돌려준다(저장 값). */
	static String domain(String field, String value, Map<String, String> fields) {
		String trimmed = text(field, value, DOMAIN_MAX, fields);
		if (trimmed == null) {
			return null;
		}
		String normalized = trimmed.toLowerCase(Locale.ROOT);
		if (!DOMAIN.matcher(normalized).matches()) {
			fields.put(field, DOMAIN_INVALID);
			return null;
		}
		return normalized;
	}

	static void throwIfInvalid(Map<String, String> fields) {
		if (!fields.isEmpty()) {
			throw new ApiException(ErrorCode.INVALID_INPUT, fields);
		}
	}

}
