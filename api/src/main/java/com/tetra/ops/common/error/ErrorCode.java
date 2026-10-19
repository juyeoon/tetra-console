package com.tetra.ops.common.error;

import org.springframework.http.HttpStatus;

/** 오류 code 값을 모으는 곳 (§9). message는 화면에 그대로 보여도 되는 문구만 둔다. */
public enum ErrorCode {

	// 400
	INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력 값을 확인해 주세요."),

	// 401
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
	LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 일치하지 않습니다."),

	// 403
	FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),

	// 404
	NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."),
	EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "이벤트를 찾을 수 없습니다."),
	TENANT_NOT_FOUND(HttpStatus.NOT_FOUND, "테넌트를 찾을 수 없습니다."),

	// 405
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 메서드입니다."),

	// 409
	APPLICATION_NOT_APPLIED(HttpStatus.CONFLICT, "이미 처리된 신청입니다."),
	TENANT_NOT_READY(HttpStatus.CONFLICT, "테넌트 환경 준비가 끝나지 않았습니다."),
	EMAIL_DUPLICATED(HttpStatus.CONFLICT, "이미 등록된 이메일입니다."),
	RESULT_NOT_READY(HttpStatus.CONFLICT, "이벤트 결과가 아직 없습니다."),
	HOSTING_PERIOD_ENDED(HttpStatus.CONFLICT, "호스팅 기간이 지나 승인할 수 없습니다."),

	// 501
	NOT_IMPLEMENTED(HttpStatus.NOT_IMPLEMENTED, "아직 제공하지 않는 기능입니다."),

	// 500
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "처리 중 오류가 발생했습니다.");

	private final HttpStatus status;

	private final String message;

	ErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	public HttpStatus status() {
		return status;
	}

	public String message() {
		return message;
	}

}
