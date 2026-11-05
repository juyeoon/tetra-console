package com.tetra.ops.report.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.tetra.ops.support.FakeObjectReader;

/**
 * 감사 버킷 읽기: 여러 파티션(tenant_id=none · 12자) · 여러 날짜 폴더, gzip과 평문, 끝 줄바꿈 없는 파일, console · portal 섞임, 자정을 넘어
 * 다음 날 dt 폴더에 든 줄 → 기간 안 console 줄만 시간순. 목록은 늘 audit-logs/로 시작하는 접두어.
 */
class OperatorAuditReaderTest {

	static final String BUCKET = "tetra-audit-test";

	static final String TENANT = "aaaaaaaaaaaa";

	static final LocalDate FROM = LocalDate.of(2026, 11, 1);

	static final LocalDate TO = LocalDate.of(2026, 11, 2);

	private final FakeObjectReader s3 = new FakeObjectReader();

	private final OperatorAuditReader reader = new OperatorAuditReader(s3);

	/** Console이 stdout에 내는 줄과 같은 모양(키 순서 고정, user_agent 포함) */
	static String line(String auditId, String time, String app) {
		return line(auditId, time, app, "\"action\":\"DASHBOARD_VIEW\",\"target\":null,\"tenant_id\":null,\"result\":\"SUCCESS\"");
	}

	static String line(String auditId, String time, String app, String middle) {
		return "{\"log_type\":\"AUDIT\",\"audit_id\":\"" + auditId + "\",\"time\":\"" + time + "\",\"app\":\"" + app
				+ "\",\"actor\":{\"type\":\"OPERATOR\",\"id\":\"1\"},\"ip\":\"10.0.1.5\",\"user_agent\":\"Mozilla/5.0\","
				+ middle + ",\"request_id\":\"req-" + auditId + "\"}";
	}

	private static String none(String day, String file) {
		return "audit-logs/tenant_id=none/dt=" + day + "/" + file;
	}

	@Test
	void readsConsoleLinesInPeriodAcrossPartitionsAndDaysInTimeOrder() {
		s3.putGzipLines(BUCKET, none("2026-11-01", "f1.jsonl.gz"),
				line("c1", "2026-11-01T09:00:00.000+09:00", "console"),
				line("p1", "2026-11-01T09:00:01.000+09:00", "portal"),
				"",
				// 전날 23:58 줄이 11-01 폴더에 들어옴 → 기간 밖
				line("c0", "2026-10-31T23:58:00.000+09:00", "console"),
				"not json",
				// 마지막 줄 끝 줄바꿈 없음
				line("c2", "2026-11-01T08:00:00.000+09:00", "console"));
		s3.putGzipLines(BUCKET, "audit-logs/tenant_id=" + TENANT + "/dt=2026-11-02/f2.jsonl.gz",
				line("c3", "2026-11-02T12:00:00.000+09:00", "console",
						"\"action\":\"EVENT_REJECT\",\"target\":{\"type\":\"EVENT\",\"id\":\"7101\"},\"tenant_id\":\"" + TENANT
								+ "\",\"event_id\":7101,\"result\":\"SUCCESS\",\"changes\":{\"application_status\":"
								+ "{\"before\":\"APPLIED\",\"after\":\"REJECTED\"},\"reject_reason\":{\"before\":null,"
								+ "\"after\":\"\\uc0ac\\uc720\"}}"),
				// UTC로 적힌 시각도 KST 날짜로(15:30Z = 11-02 00:30 KST)
				line("c4", "2026-11-01T15:30:00Z", "console"),
				line("p2", "2026-11-02T12:00:00.000+09:00", "portal"));
		// 자정을 넘어 to+1일 폴더에 든 to의 줄(23:59:50)은 넣고, 그 폴더의 to+1일 줄은 뺀다. 평문 파일 · CRLF도 읽는다
		s3.put(BUCKET, none("2026-11-03", "f3.jsonl"),
				(line("c5", "2026-11-02T23:59:50.000+09:00", "console") + "\r\n"
						+ line("c6", "2026-11-03T00:00:10.000+09:00", "console")).getBytes(StandardCharsets.UTF_8));
		// 읽지 않는 폴더: to+2일 · from 전날 · tenant_id= 아닌 폴더 · audit-logs/ 밖
		s3.putGzipLines(BUCKET, none("2026-11-04", "f4.jsonl.gz"), line("x1", "2026-11-02T10:00:00.000+09:00", "console"));
		s3.putGzipLines(BUCKET, none("2026-10-31", "f0.jsonl.gz"), line("x2", "2026-11-01T00:00:01.000+09:00", "console"));
		s3.putGzipLines(BUCKET, "audit-logs/other/dt=2026-11-01/x.jsonl.gz", line("x3", "2026-11-01T10:00:00.000+09:00", "console"));
		s3.putGzipLines(BUCKET, "errors/2026/11/01/x.gz", line("x4", "2026-11-01T10:00:00.000+09:00", "console"));

		OperatorAuditReader.Result result = reader.read(BUCKET, FROM, TO);

		assertThat(result.records()).extracting(OperatorAuditRecord::auditId).containsExactly("c2", "c1", "c4", "c3", "c5");
		assertThat(result.records()).extracting(OperatorAuditRecord::time)
			.containsExactly(LocalDateTime.of(2026, 11, 1, 8, 0), LocalDateTime.of(2026, 11, 1, 9, 0),
					LocalDateTime.of(2026, 11, 2, 0, 30), LocalDateTime.of(2026, 11, 2, 12, 0),
					LocalDateTime.of(2026, 11, 2, 23, 59, 50));
		assertThat(result.files()).isEqualTo(3);
		assertThat(result.skipped()).isEqualTo(1);
		assertThat(s3.listedPrefixes).isNotEmpty().allSatisfy(prefix -> assertThat(prefix).startsWith("audit-logs/"));
		// 읽은 날짜 폴더 = from ~ to+1
		assertThat(s3.listedPrefixes).contains(none("2026-11-01", ""), none("2026-11-03", ""))
			.doesNotContain(none("2026-11-04", ""), none("2026-10-31", ""));

		OperatorAuditRecord rejected = result.records().get(3);
		assertThat(rejected.action()).isEqualTo("EVENT_REJECT");
		assertThat(rejected.operatorId()).isEqualTo("1");
		assertThat(rejected.targetType()).isEqualTo("EVENT");
		assertThat(rejected.targetId()).isEqualTo("7101");
		assertThat(rejected.tenantId()).isEqualTo(TENANT);
		assertThat(rejected.eventId()).isEqualTo(7101L);
		assertThat(rejected.result()).isEqualTo("SUCCESS");
		assertThat(rejected.ip()).isEqualTo("10.0.1.5");
		assertThat(rejected.requestId()).isEqualTo("req-c3");
		// changes는 JSON 원문(\\uXXXX는 글자로)
		assertThat(rejected.changes()).isEqualTo("{\"application_status\":{\"before\":\"APPLIED\",\"after\":\"REJECTED\"},"
				+ "\"reject_reason\":{\"before\":null,\"after\":\"사유\"}}");
	}

	/** 로그인 실패: actor null → 운영자 id 없음, 시도 계정(가림) · 사유 코드 */
	@Test
	void loginFailureHasNoOperatorButMaskedAttempt() {
		s3.putGzipLines(BUCKET, none("2026-11-01", "f.jsonl.gz"),
				"{\"log_type\":\"AUDIT\",\"audit_id\":\"l1\",\"time\":\"2026-11-01T10:00:00.000+09:00\",\"app\":\"console\","
						+ "\"actor\":null,\"ip\":\"10.0.1.5\",\"action\":\"AUTH_LOGIN\",\"target\":null,\"tenant_id\":null,"
						+ "\"result\":\"FAILURE\",\"reason\":\"LOGIN_FAILED\",\"attempt\":\"ki***@ex***.com\",\"request_id\":\"r\"}");

		OperatorAuditRecord record = reader.read(BUCKET, FROM, TO).records().get(0);

		assertThat(record.operatorId()).isNull();
		assertThat(record.result()).isEqualTo("FAILURE");
		assertThat(record.reason()).isEqualTo("LOGIN_FAILED");
		assertThat(record.attempt()).isEqualTo("ki***@ex***.com");
	}

	@Test
	void emptyBucketGivesNoRows() {
		s3.bucket(BUCKET);

		OperatorAuditReader.Result result = reader.read(BUCKET, FROM, TO);

		assertThat(result.records()).isEmpty();
		assertThat(result.files()).isZero();
		assertThat(s3.listedPrefixes).containsExactly("audit-logs/");
	}

}
