package com.tetra.ops.report;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.tetra.ops.common.audit.AuditContext;
import com.tetra.ops.common.error.ApiException;
import com.tetra.ops.common.error.ErrorCode;
import com.tetra.ops.common.time.Kst;
import com.tetra.ops.report.audit.OperatorAuditReportGenerator;
import com.tetra.ops.report.event.EventResultReportGenerator;
import com.tetra.ops.report.event.EventResultReportGenerator.Generated;

/**
 * 리포트 수동 생성(POST /api/ops/reports/generate — OPS-09 [리포트 만들기] · 시험 · 재생성용). 분류별로 나눈다.
 * <ul>
 * <li>EVENT_RESULT: 이벤트 하나({@link EventResultReportGenerator}). eventId 필수, month · from · to는 400</li>
 * <li>OPERATOR_AUDIT: 기간 지정 운영자 접속기록 CSV({@link OperatorAuditReportGenerator}). from · to(KST 날짜, 양끝 포함) 필수,
 * from ≤ to, to ≤ 오늘(KST), 기간 최대 {@value #AUDIT_MAX_DAYS}일. eventId · month는 400</li>
 * <li>MONTHLY: 501 NOT_IMPLEMENTED(⑭에서 채움)</li>
 * </ul>
 * 저장은 ReportStore가 멱등이라 여러 번 불러도 메타 · 알림은 한 번. 트랜잭션 밖에서 부른다(리포트 작업 잠금 · S3).
 */
@Service
public class ReportGenerateService {

	private static final Logger log = LoggerFactory.getLogger(ReportGenerateService.class);

	/** 운영자 접속기록 기간 상한(양끝 포함 일수) */
	static final int AUDIT_MAX_DAYS = 31;

	static final String REQUIRED = "필수 입력입니다.";

	private final EventResultReportGenerator eventResults;

	private final OperatorAuditReportGenerator operatorAudits;

	private final Clock clock;

	public ReportGenerateService(EventResultReportGenerator eventResults, OperatorAuditReportGenerator operatorAudits,
			Clock clock) {
		this.eventResults = eventResults;
		this.operatorAudits = operatorAudits;
		this.clock = clock;
	}

	/**
	 * 요청 값(본문 그대로).
	 * @param eventId EVENT_RESULT에 필수
	 * @param month MONTHLY용(⑭). 다른 분류에 오면 무시하지 않고 400
	 * @param from OPERATOR_AUDIT 시작일(KST, 포함)
	 * @param to OPERATOR_AUDIT 종료일(KST, 포함)
	 */
	public record Command(ReportCategory category, Long eventId, YearMonth month, LocalDate from, LocalDate to) {
	}

	/**
	 * @param eventId EVENT_RESULT의 이벤트(OPERATOR_AUDIT는 null)
	 * @param tenantId EVENT_RESULT의 테넌트(OPERATOR_AUDIT는 null)
	 * @param filePath 리포트 파일 키(테넌트 리포트는 테넌트 로그 버킷, 운영자 리포트는 운영자 리포트 버킷 안). 버킷 이름 · presigned URL은
	 * 넣지 않는다
	 * @param created 메타 · REPORT 알림을 이번에 새로 남겼으면 true, 이미 있어 파일만 덮어썼으면 false
	 * @param from OPERATOR_AUDIT 기간 시작(EVENT_RESULT는 null)
	 * @param to OPERATOR_AUDIT 기간 끝(EVENT_RESULT는 null)
	 * @param rows OPERATOR_AUDIT CSV 데이터 줄 수(EVENT_RESULT는 null)
	 */
	public record ReportGenerateResponse(ReportCategory category, Long eventId, String tenantId, String filePath,
			boolean created, LocalDate from, LocalDate to, Integer rows) {
	}

	public ReportGenerateResponse generate(Command command, Long operatorId) {
		return switch (command.category()) {
			case EVENT_RESULT -> eventResult(command, operatorId);
			case OPERATOR_AUDIT -> operatorAudit(command, operatorId);
			case MONTHLY -> throw new ApiException(ErrorCode.NOT_IMPLEMENTED);
		};
	}

	private ReportGenerateResponse eventResult(Command command, Long operatorId) {
		Map<String, String> invalid = new LinkedHashMap<>();
		if (command.eventId() == null) {
			invalid.put("eventId", REQUIRED);
		}
		unused(invalid, command.category(), "month", command.month());
		unused(invalid, command.category(), "from", command.from());
		unused(invalid, command.category(), "to", command.to());
		reject(invalid);
		long eventId = command.eventId();
		// 감사 줄(REPORT_GENERATE): 대상 · event_id = 본문 eventId, tenant_id = 생성기가 event_registry에서 찾은 값
		AuditContext.current().event(eventId);
		Generated generated = eventResults.generate(eventId);
		log.info("리포트 수동 생성: category={}, event={}, tenant={}, created={}, operator={}", command.category(), eventId,
				generated.tenantId(), generated.created(), operatorId);
		return new ReportGenerateResponse(command.category(), eventId, generated.tenantId(), generated.filePath(),
				generated.created(), null, null, null);
	}

	private ReportGenerateResponse operatorAudit(Command command, Long operatorId) {
		Map<String, String> invalid = new LinkedHashMap<>();
		LocalDate from = command.from();
		LocalDate to = command.to();
		if (from == null) {
			invalid.put("from", REQUIRED);
		}
		if (to == null) {
			invalid.put("to", REQUIRED);
		}
		if (from != null && to != null) {
			LocalDate today = Kst.now(clock).toLocalDate();
			if (from.isAfter(to)) {
				invalid.put("to", "종료일은 시작일과 같거나 뒤여야 합니다.");
			}
			else if (to.isAfter(today)) {
				invalid.put("to", "종료일은 오늘(" + today + ")까지 고를 수 있습니다.");
			}
			else if (ChronoUnit.DAYS.between(from, to) + 1 > AUDIT_MAX_DAYS) {
				invalid.put("to", "기간은 최대 " + AUDIT_MAX_DAYS + "일입니다.");
			}
		}
		unused(invalid, command.category(), "eventId", command.eventId());
		unused(invalid, command.category(), "month", command.month());
		reject(invalid);
		OperatorAuditReportGenerator.Generated generated = operatorAudits.generate(from, to);
		// 감사 줄(REPORT_GENERATE): 대상 = 만든(또는 이미 있던) 운영자 리포트 메타. 테넌트 · 이벤트 없음
		AuditContext.current().report(generated.reportId());
		log.info("리포트 수동 생성: category={}, from={}, to={}, rows={}, report={}, created={}, operator={}",
				command.category(), from, to, generated.rows(), generated.reportId(), generated.created(), operatorId);
		return new ReportGenerateResponse(command.category(), null, null, generated.filePath(), generated.created(), from,
				to, generated.rows());
	}

	/** 그 분류에 쓰지 않는 값이 오면 무시하지 않고 400 */
	private static void unused(Map<String, String> invalid, ReportCategory category, String field, Object value) {
		if (value != null) {
			invalid.put(field, category + "에는 쓰지 않습니다.");
		}
	}

	private static void reject(Map<String, String> invalid) {
		if (!invalid.isEmpty()) {
			throw new ApiException(ErrorCode.INVALID_INPUT, invalid);
		}
	}

}
