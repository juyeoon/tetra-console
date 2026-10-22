package com.tetra.ops.listener;

import java.util.Optional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * EventBridge "Step Functions Execution Status Change" 이벤트에서 쓰는 값만 꺼낸다.
 * detail.name(실행 이름), detail.status, 실패면 detail.error·detail.cause. 실행 기록은 따로 조회하지 않는다.
 * 수명 주기 흐름(WF2-1 · WF2-2 · WF4)은 실행 이름을 스케줄러가 정하므로 detail.stateMachineArn과 detail.input(JSON 문자열)도 꺼낸다.
 */
record ExecutionStatusChange(String name, Status status, String error, String cause, String stateMachineArn,
		String input) {

	enum Status {

		SUCCEEDED, FAILED, TIMED_OUT, ABORTED

	}

	boolean succeeded() {
		return status == Status.SUCCEEDED;
	}

	/** 실패 상세: 실행 상태와 error/cause. */
	String failureDetail() {
		StringBuilder detail = new StringBuilder(status.name());
		if (error != null && !error.isBlank()) {
			detail.append(": ").append(error);
		}
		if (cause != null && !cause.isBlank()) {
			detail.append(" / ").append(cause);
		}
		return detail.toString();
	}

	/** 형식이 틀리거나 처리 대상 상태가 아니면 empty. */
	static Optional<ExecutionStatusChange> parse(String body, JsonMapper jsonMapper) {
		JsonNode root;
		try {
			root = jsonMapper.readTree(body);
		}
		catch (JacksonException ex) {
			return Optional.empty();
		}
		JsonNode detail = root.path("detail");
		String name = text(detail, "name");
		String status = text(detail, "status");
		if (name == null || status == null) {
			return Optional.empty();
		}
		Status parsed;
		try {
			parsed = Status.valueOf(status);
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
		return Optional.of(new ExecutionStatusChange(name, parsed, text(detail, "error"), text(detail, "cause"),
				text(detail, "stateMachineArn"), text(detail, "input")));
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isString() ? value.asString() : null;
	}

}
