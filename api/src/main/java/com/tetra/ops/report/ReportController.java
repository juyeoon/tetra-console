package com.tetra.ops.report;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.tetra.ops.auth.CurrentOperator;
import com.tetra.ops.common.error.ApiErrorCodes;
import com.tetra.ops.common.error.ErrorCode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/** OPS-09 리포트. Tetra Portal 리포트(UI-TP-12)와 같은 화면. 행은 리포트 작업(ReportStore)이 넣는다(없으면 빈 목록). */
@Tag(name = "리포트", description = "OPS-09 리포트 목록 · 다운로드 · 이메일 전송")
@RestController
@RequestMapping("/api/ops/reports")
public class ReportController {

	private final ReportService service;

	public ReportController(ReportService service) {
		this.service = service;
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

}
