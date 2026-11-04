package com.tetra.ops.report;

import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.tetra.ops.common.audit.AuditContext;
import com.tetra.ops.common.error.ApiException;
import com.tetra.ops.common.error.ErrorCode;
import com.tetra.ops.report.event.EventResultReportGenerator;
import com.tetra.ops.report.event.EventResultReportGenerator.Generated;

/**
 * 리포트 수동 생성(POST /api/ops/reports/generate — 시험 · 재생성용). 분류별로 나눈다. 지금은 EVENT_RESULT만 만들고
 * MONTHLY · OPERATOR_AUDIT는 501 NOT_IMPLEMENTED(⑭ · ⑮에서 채움). 저장은 ReportStore가 멱등이라 여러 번 불러도 메타 · 알림은 한 번.
 * 트랜잭션 밖에서 부른다(리포트 작업 잠금 · S3 Put).
 */
@Service
public class ReportGenerateService {

	private static final Logger log = LoggerFactory.getLogger(ReportGenerateService.class);

	private final EventResultReportGenerator eventResults;

	public ReportGenerateService(EventResultReportGenerator eventResults) {
		this.eventResults = eventResults;
	}

	/**
	 * @param filePath 리포트 파일 키(테넌트 리포트는 테넌트 로그 버킷 안). 버킷 이름 · presigned URL은 넣지 않는다
	 * @param created 메타 · REPORT 알림을 이번에 새로 남겼으면 true, 이미 있어 파일만 덮어썼으면 false
	 */
	public record ReportGenerateResponse(ReportCategory category, Long eventId, String tenantId, String filePath,
			boolean created) {
	}

	/**
	 * @param eventId EVENT_RESULT에 필수
	 * @param month MONTHLY · OPERATOR_AUDIT용(⑭ · ⑮). EVENT_RESULT에 오면 무시하지 않고 400
	 */
	public ReportGenerateResponse generate(ReportCategory category, Long eventId, YearMonth month, Long operatorId) {
		if (category != ReportCategory.EVENT_RESULT) {
			throw new ApiException(ErrorCode.NOT_IMPLEMENTED);
		}
		Map<String, String> invalid = new LinkedHashMap<>();
		if (eventId == null) {
			invalid.put("eventId", "필수 입력입니다.");
		}
		if (month != null) {
			invalid.put("month", "EVENT_RESULT에는 쓰지 않습니다.");
		}
		if (!invalid.isEmpty()) {
			throw new ApiException(ErrorCode.INVALID_INPUT, invalid);
		}
		// 감사 줄(REPORT_GENERATE): 대상 · event_id = 본문 eventId, tenant_id = 생성기가 event_registry에서 찾은 값
		AuditContext.current().event(eventId);
		Generated generated = eventResults.generate(eventId);
		log.info("리포트 수동 생성: category={}, event={}, tenant={}, created={}, operator={}", category, eventId,
				generated.tenantId(), generated.created(), operatorId);
		return new ReportGenerateResponse(category, eventId, generated.tenantId(), generated.filePath(),
				generated.created());
	}

}
