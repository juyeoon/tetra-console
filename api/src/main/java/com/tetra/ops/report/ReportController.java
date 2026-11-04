package com.tetra.ops.report;

import java.time.YearMonth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.tetra.ops.auth.CurrentOperator;
import com.tetra.ops.common.error.ApiErrorCodes;
import com.tetra.ops.common.error.ErrorCode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * OPS-09 리포트. Tetra Portal 리포트(UI-TP-12)와 같은 화면. 행은 리포트 작업(ReportStore)이 넣는다(없으면 빈 목록).
 * 수동 생성(POST /generate)은 화면 없는 시험 · 재생성용.
 */
@Tag(name = "리포트", description = "OPS-09 리포트 목록 · 다운로드 · 이메일 전송 · 수동 생성(시험 · 재생성용)")
@RestController
@RequestMapping("/api/ops/reports")
public class ReportController {

	private final ReportService service;

	private final ReportGenerateService generateService;

	public ReportController(ReportService service, ReportGenerateService generateService) {
		this.service = service;
		this.generateService = generateService;
	}

	public record ReportGenerateRequest(
			@Schema(description = "리포트 분류. 지금은 EVENT_RESULT만 만든다(MONTHLY · OPERATOR_AUDIT는 501)", example = "EVENT_RESULT",
					requiredMode = Schema.RequiredMode.REQUIRED) @NotNull(message = "필수 입력입니다.") ReportCategory category,
			@Schema(description = "EVENT_RESULT에 필수", example = "123") Long eventId,
			@Schema(description = "MONTHLY · OPERATOR_AUDIT용 대상 달(yyyy-MM, 다음 MR). EVENT_RESULT에 보내면 400", type = "string",
					example = "2026-10") YearMonth month) {
	}

	@Operation(summary = "OPS-09 리포트 목록", description = "최근 생성순(createdAt · reportId 내림차순). 페이지네이션 없음")
	@GetMapping
	public ReportService.ReportListResponse list() {
		return service.list();
	}

	@Operation(summary = "OPS-09 리포트 다운로드 링크",
			description = "요청할 때마다 S3 presigned GET URL을 새로 만든다(만료 expiresAt, 기본 5분). 파일 이름 report-{reportId}.{확장자}")
	@ApiErrorCodes({ ErrorCode.INVALID_INPUT, ErrorCode.REPORT_NOT_FOUND })
	@GetMapping("/{reportId}/download")
	public ReportService.ReportDownloadResponse download(@Parameter(example = "1") @PathVariable Long reportId) {
		return service.download(reportId);
	}

	@Operation(summary = "OPS-09 리포트 이메일 전송",
			description = "본문 없음. 로그인한 운영자 이메일로 리포트 안내 메일(파일 첨부 · 다운로드 링크 없이 콘솔 화면 안내)을 보낸다. "
					+ "SES가 받지 않으면 503 MAIL_SEND_FAILED")
	@ApiErrorCodes({ ErrorCode.INVALID_INPUT, ErrorCode.REPORT_NOT_FOUND, ErrorCode.MAIL_SEND_FAILED })
	@PostMapping("/{reportId}/email")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void email(@Parameter(example = "1") @PathVariable Long reportId,
			@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
		service.email(reportId, CurrentOperator.id(jwt));
	}

	@Operation(summary = "리포트 수동 생성(시험 · 재생성용)",
			description = "EVENT_RESULT: 이벤트 하나의 결과 리포트를 바로 만든다(자동 생성의 보충 기간 조건 없음). 리포트 작업과 같은 잠금을 "
					+ "기다리지 않고 잡고, 못 잡으면 409 REPORT_BUSY. 결과 탭이 READY가 아니면 409 RESULT_NOT_READY. 같은 리포트를 다시 "
					+ "만들면 파일만 덮어쓰고 created=false(메타 · 테넌트 알림은 처음 한 번). filePath는 테넌트 로그 버킷 안의 키. "
					+ "MONTHLY · OPERATOR_AUDIT는 501 NOT_IMPLEMENTED, EVENT_RESULT에 month를 보내면 400")
	@ApiErrorCodes({ ErrorCode.INVALID_INPUT, ErrorCode.EVENT_NOT_FOUND, ErrorCode.RESULT_NOT_READY,
			ErrorCode.TENANT_NOT_READY, ErrorCode.REPORT_BUSY, ErrorCode.NOT_IMPLEMENTED })
	@PostMapping("/generate")
	public ReportGenerateService.ReportGenerateResponse generate(@Valid @RequestBody ReportGenerateRequest request,
			@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
		return generateService.generate(request.category(), request.eventId(), request.month(), CurrentOperator.id(jwt));
	}

}
