package com.tetra.ops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 최근 자동화 실패 사유: JSON 원문 대신 앞부분 요약(상태 · 오류 · 짧은 원인), 토큰 · 비밀번호 형태 값은 가림 */
class FailureReasonsTest {

	@Test
	void plainReasonStaysAsIs() {
		assertThat(FailureReasons.summarize("동결 확인 시간 초과")).isEqualTo("동결 확인 시간 초과");
		assertThat(FailureReasons.summarize(null)).isNull();
	}

	@Test
	void lambdaErrorJsonBecomesShortMessage() {
		// ExecutionStatusChange.failureDetail: "상태: 오류 / 원인(Lambda 오류 JSON, 스택 트레이스 포함)"
		String raw = "FAILED: States.TaskFailed / {\"errorMessage\":\"Task timed out after 30.00 seconds\","
				+ "\"errorType\":\"Sandbox.Timedout\",\"stackTrace\":[\"  File \\\"/var/task/app.py\\\", line 12\"]}";
		assertThat(FailureReasons.summarize(raw))
			.isEqualTo("FAILED: States.TaskFailed · Task timed out after 30.00 seconds");
	}

	@Test
	void escapedNestedJsonAndEcsStoppedReason() {
		String raw = "FAILED: States.TaskFailed / {\\\"Attachments\\\":[],\\\"StoppedReason\\\":\\\"Essential container in task exited\\\","
				+ "\\\"Containers\\\":[{\\\"ExitCode\\\":1}]}";
		assertThat(FailureReasons.summarize(raw)).isEqualTo("FAILED: States.TaskFailed · Essential container in task exited");
	}

	@Test
	void jsonWithoutMessageKeyDropsPunctuationAndIsCut() {
		String raw = "TIMED_OUT / {\"a\":\"" + "x".repeat(10) + "\",\"b\":[1,2,3],\"c\":\"" + "가".repeat(200) + "\"}";
		String summary = FailureReasons.summarize(raw);
		assertThat(summary).startsWith("TIMED_OUT · a : xxxxxxxxxx, b : 1, 2, 3, c :").doesNotContain("{", "\"");
		assertThat(summary).hasSize(FailureReasons.MAX_LENGTH).endsWith("…");
	}

	@Test
	void secretsAreMasked() {
		String raw = "FAILED: Error / {\"errorMessage\":\"login failed password=hunter2 for "
				+ "mysql://ops:s3cr3t@db.internal:3306 with Bearer abc.def.ghi\",\"token\":\"t0k3n\"}";
		String summary = FailureReasons.summarize(raw);
		assertThat(summary).doesNotContain("hunter2", "s3cr3t", "abc.def.ghi", "t0k3n")
			.contains("password=***", "mysql://ops:***@db.internal", "Bearer ***");

		assertThat(FailureReasons.mask("key AKIAIOSFODNN7EXAMPLE jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl"))
			.isEqualTo("key *** jwt ***");
		assertThat(FailureReasons.mask("\"SecretAccessKey\": \"wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY\""))
			.isEqualTo("\"SecretAccessKey\": ***");
		// 32자 이상 난수는 가리고 UUID · 경로 · ARN은 그대로
		assertThat(FailureReasons.mask("id 0123456789abcdef0123456789abcdef run 3f2c9a1e-7b4d-4e8a-9c1f-2a6b8d0e4f13 "
				+ "/api/issuance/events/12/coupons/claim arn:aws:states:ap-northeast-2:123456789012:execution:wf4:e12"))
			.isEqualTo("id *** run 3f2c9a1e-7b4d-4e8a-9c1f-2a6b8d0e4f13 /api/issuance/events/12/coupons/claim "
					+ "arn:aws:states:ap-northeast-2:123456789012:execution:wf4:e12");
	}

}
