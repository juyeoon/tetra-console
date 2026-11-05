package com.tetra.ops.report.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 운영자 접속기록 CSV: BOM · CRLF · 한글 머리글 17열, 수식 가림(= 로 시작하는 값), 다운로드 줄만 사유 칸 빈 칸(나머지 "-"),
 * 운영자 이름은 표에서, 결과 성공/실패, 경로는 API_REQUEST만, 줄이 없으면 머리글만.
 */
class OperatorAuditCsvTest {

	static final String HEADER = "일시(KST),운영자 ID,운영자 이름,행위,행위 코드,대상 종류,대상 ID,테넌트,이벤트,결과,사유 코드,접속 IP,경로,"
			+ "변경 내용,시도 계정(가림),요청 ID,다운로드 사유(점검자 기입)";

	private static String body(byte[] csv) {
		assertThat(Arrays.copyOf(csv, 3)).containsExactly(0xEF, 0xBB, 0xBF);
		return new String(csv, 3, csv.length - 3, StandardCharsets.UTF_8);
	}

	static OperatorAuditRecord record(LocalDateTime time, String actorId, String action, String path, String targetType,
			String targetId, String tenantId, Long eventId, String result, String reason, String changes, String attempt) {
		return new OperatorAuditRecord(time.toInstant(ZoneOffset.ofHours(9)), time, "id", actorId == null ? null : "OPERATOR",
				actorId, action, path, targetType, targetId, tenantId, eventId, result, reason, "10.0.1.5", changes, attempt,
				"req-1");
	}

	@Test
	void headerOnlyWhenNoRows() {
		assertThat(body(OperatorAuditCsv.write(List.of(), Map.of()))).isEqualTo(HEADER + "\r\n");
		assertThat(OperatorAuditCsv.HEADER).hasSize(17);
	}

	@Test
	void rowsWithKoreanActionNamesFormulaGuardAndDownloadReasonCell() {
		LocalDateTime t = LocalDateTime.of(2026, 11, 5, 14, 3, 21, 512_000_000);
		List<OperatorAuditRecord> records = List.of(
				record(t, "1", "REPORT_DOWNLOAD", null, "REPORT", "12", null, null, "SUCCESS", null, null, null),
				record(t, "1", "ISSUANCE_CSV_DOWNLOAD", null, "EVENT", "7101", "aaaaaaaaaaaa", 7101L, "SUCCESS", null, null,
						null),
				record(t, "2", "EVENT_APPROVE", null, "EVENT", "7101", "aaaaaaaaaaaa", 7101L, "SUCCESS", null,
						"{\"application_status\":{\"before\":\"APPLIED\",\"after\":\"APPROVED\"}}", null),
				// 경로 변수로 들어온 값이 = 로 시작 → 앞에 '
				record(t, "1", "TENANT_VIEW", null, "TENANT", "=HYPERLINK(\"x\")", null, null, "FAILURE", "TENANT_NOT_FOUND",
						null, null),
				record(t, null, "AUTH_LOGIN", null, null, null, null, null, "FAILURE", "LOGIN_FAILED", null, "ki***@ex***.com"),
				record(t, "9", "API_REQUEST", "/api/ops/unknown", null, null, null, null, "FAILURE", "NOT_FOUND", null, null),
				// 표에 없는 경로가 아니면 path가 있어도 넣지 않는다
				record(t, "1", "DASHBOARD_VIEW", "/api/ops/dashboard", null, null, null, null, "SUCCESS", null, null, null));

		String csv = body(OperatorAuditCsv.write(records, Map.of("1", "김운영", "2", "=이름")));

		assertThat(csv.split("\r\n", -1)).containsExactly(HEADER,
				"2026-11-05T14:03:21.512+09:00,1,김운영,리포트 다운로드,REPORT_DOWNLOAD,REPORT,12,,,성공,,10.0.1.5,,,,req-1,",
				"2026-11-05T14:03:21.512+09:00,1,김운영,발급 이력 CSV 다운로드,ISSUANCE_CSV_DOWNLOAD,EVENT,7101,aaaaaaaaaaaa,7101,성공,,"
						+ "10.0.1.5,,,,req-1,",
				"2026-11-05T14:03:21.512+09:00,2,'=이름,이벤트 승인,EVENT_APPROVE,EVENT,7101,aaaaaaaaaaaa,7101,성공,,10.0.1.5,,"
						+ "\"{\"\"application_status\"\":{\"\"before\"\":\"\"APPLIED\"\",\"\"after\"\":\"\"APPROVED\"\"}}\",,req-1,-",
				"2026-11-05T14:03:21.512+09:00,1,김운영,테넌트 상세 조회,TENANT_VIEW,TENANT,\"'=HYPERLINK(\"\"x\"\")\",,,실패,"
						+ "TENANT_NOT_FOUND,10.0.1.5,,,,req-1,-",
				"2026-11-05T14:03:21.512+09:00,,,로그인,AUTH_LOGIN,,,,,실패,LOGIN_FAILED,10.0.1.5,,,ki***@ex***.com,req-1,-",
				// 운영자 표에 없는 id는 이름 빈 칸
				"2026-11-05T14:03:21.512+09:00,9,,기타 요청,API_REQUEST,,,,,실패,NOT_FOUND,10.0.1.5,/api/ops/unknown,,,req-1,-",
				"2026-11-05T14:03:21.512+09:00,1,김운영,대시보드 조회,DASHBOARD_VIEW,,,,,성공,,10.0.1.5,,,,req-1,-",
				"");
		assertThat(csv).doesNotContain("Mozilla");
	}

}
