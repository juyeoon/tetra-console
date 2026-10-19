package com.tetra.ops.tenant;

import java.util.Map;
import java.util.regex.Pattern;

import com.tetra.ops.common.error.ApiException;
import com.tetra.ops.common.error.ErrorCode;

/** 테넌트 입력 검증. 길이는 물리 ERD(이름 100, 이메일 255, 도메인 253). 앞뒤 공백은 잘라서 저장한다. */
final class TenantInputRules {

	static final int NAME_MAX = 100;

	static final int EMAIL_MAX = 255;

	static final int DOMAIN_MAX = 253;

	// TODO(미정): 이메일 형식 규칙. 임시로 local@domain.tld 형태만 확인
	private static final Pattern EMAIL = Pattern
		.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*\\.[A-Za-z]{2,}$");

	// TODO(미정): 도메인 형식 정규식. 임시로 RFC 1123 호스트명(라벨 63자, 점 1개 이상, 영문 TLD)
	private static final Pattern DOMAIN = Pattern
		.compile("^(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}$");

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

	static String domain(String field, String value, Map<String, String> fields) {
		String trimmed = text(field, value, DOMAIN_MAX, fields);
		if (trimmed != null && !DOMAIN.matcher(trimmed).matches()) {
			fields.put(field, "도메인 형식이 올바르지 않습니다.");
			return null;
		}
		return trimmed;
	}

	static void throwIfInvalid(Map<String, String> fields) {
		if (!fields.isEmpty()) {
			throw new ApiException(ErrorCode.INVALID_INPUT, fields);
		}
	}

}
