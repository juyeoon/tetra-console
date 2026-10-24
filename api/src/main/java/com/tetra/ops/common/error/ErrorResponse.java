package com.tetra.ops.common.error;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/** 모든 오류 응답의 형식 (§9). fields는 400(입력 항목별 사유)과 409 APPLICATION_DATA_INVALID(사유 코드별 문구)일 때만 담는다. */
@Schema(name = "ErrorResponse", description = "모든 오류 응답의 형식")
public record ErrorResponse(
		@Schema(description = "오류 코드. 화면은 이 값으로 분기한다", example = "APPLICATION_NOT_APPLIED") String code,
		@Schema(description = "화면에 그대로 보여 줘도 되는 문구", example = "이미 처리된 신청입니다.") String message,
		@Schema(description = "400이면 입력 항목별 사유, 409 APPLICATION_DATA_INVALID면 사유 코드별 문구(HOSTING_START_TOO_LATE · "
				+ "HOSTING_START_TOO_SOON · NAME_BLANK · ENDPOINT_URL_INVALID · CONCURRENT_EVENT_LIMIT). 그 밖에는 없음",
				example = "{\"reason\": \"필수 입력입니다.\"}",
				nullable = true) @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, String> fields) {

	public static ErrorResponse of(ErrorCode code) {
		return new ErrorResponse(code.name(), code.message(), Map.of());
	}

	public static ErrorResponse of(ErrorCode code, Map<String, String> fields) {
		return new ErrorResponse(code.name(), code.message(), fields);
	}

}
