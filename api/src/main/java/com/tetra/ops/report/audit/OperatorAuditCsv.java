package com.tetra.ops.report.audit;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tetra.ops.common.CsvWriter;
import com.tetra.ops.common.audit.AuditActions;
import com.tetra.ops.common.audit.AuditEvent;

/**
 * 운영자 접속기록 CSV(UTF-8 BOM · CRLF · 수식 가림 켬, 머리글 한글). 한 줄 = 감사 줄 하나, 일시 오름차순(읽는 쪽이 정렬).
 * 줄이 없으면 머리글만. user_agent · 이메일 원문은 넣지 않는다(운영자 이름은 운영자 표의 이름, 시도 계정은 감사 줄에서 이미 가린 값).
 * 마지막 열 '다운로드 사유(점검자 기입)'는 발급 이력 CSV · 리포트 다운로드 줄만 빈 칸(점검자가 적음), 나머지는 "-".
 */
final class OperatorAuditCsv {

	static final List<String> HEADER = List.of("일시(KST)", "운영자 ID", "운영자 이름", "행위", "행위 코드", "대상 종류", "대상 ID",
			"테넌트", "이벤트", "결과", "사유 코드", "접속 IP", "경로", "변경 내용", "시도 계정(가림)", "요청 ID", "다운로드 사유(점검자 기입)");

	/** 점검자가 다운로드 사유를 적는 행위 */
	static final Set<String> DOWNLOADS = Set.of("ISSUANCE_CSV_DOWNLOAD", "REPORT_DOWNLOAD");

	private static final CsvWriter.Literal NOT_APPLICABLE = new CsvWriter.Literal("-");

	private OperatorAuditCsv() {
	}

	/** @param operatorNames 운영자 id(문자열) → 이름. 없으면 빈 칸 */
	static byte[] write(List<OperatorAuditRecord> records, Map<String, String> operatorNames) {
		CsvWriter csv = CsvWriter.withFormulaGuard(HEADER.toArray(String[]::new));
		for (OperatorAuditRecord r : records) {
			String operatorId = r.operatorId();
			csv.row(r.time(), operatorId, operatorId == null ? null : operatorNames.get(operatorId),
					AuditActions.name(r.action()), r.action(), r.targetType(), r.targetId(), r.tenantId(), r.eventId(),
					result(r.result()), r.reason(), r.ip(), AuditActions.API_REQUEST.equals(r.action()) ? r.path() : null,
					r.changes(), r.attempt(), r.requestId(), DOWNLOADS.contains(r.action()) ? null : NOT_APPLICABLE);
		}
		return csv.toBytes();
	}

	/** SUCCESS → 성공, FAILURE → 실패, 그 밖은 그대로 */
	static String result(String code) {
		if (AuditEvent.SUCCESS.equals(code)) {
			return "성공";
		}
		return AuditEvent.FAILURE.equals(code) ? "실패" : code;
	}

}
