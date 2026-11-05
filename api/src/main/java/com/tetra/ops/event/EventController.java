package com.tetra.ops.event;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tetra.ops.auth.CurrentOperator;
import com.tetra.ops.common.error.ApiErrorCodes;
import com.tetra.ops.common.error.ErrorCode;
import com.tetra.ops.domain.ApplicationStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** OPS-03·04 목록, 이벤트 상세, 승인·반려, 진행 결과. */
@Tag(name = "이벤트", description = "OPS-03 신청 현황 · OPS-04 진행 현황 · 상세 · 승인/반려 · 진행 결과")
@RestController
@RequestMapping("/api/ops")
public class EventController {

	private final EventListService listService;

	private final EventQueryService queryService;

	private final EventCommandService commandService;

	public EventController(EventListService listService, EventQueryService queryService,
			EventCommandService commandService) {
		this.listService = listService;
		this.queryService = queryService;
		this.commandService = commandService;
	}

	public record RejectRequest(@Schema(description = "반려 사유. 공백 불가, 500자 이하", example = "배너 이미지가 정책에 맞지 않습니다.",
			requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank(message = "필수 입력입니다.") @Size(max = 500,
					message = "500자 이하로 입력해 주세요.") String reason) {
	}

	public record RejectResponse(Long eventId, ApplicationStatus applicationStatus) {
	}

	@Operation(summary = "OPS-03 신청 목록 + 신청상태별 건수",
			description = "정렬: APPLIED → APPLYING → 나머지(applied_at 내림차순). counts는 5개 상태 모두(0 포함). "
					+ "processedAt은 비고(처리 일시)용이며 APPLY_FAILED는 null")
	@GetMapping("/applications")
	public EventListService.ApplicationListResponse applications() {
		return listService.applications();
	}

	@Operation(summary = "OPS-04 진행 목록 + 호스팅상태별 건수",
			description = "승인된(APPROVED) 이벤트만, 이벤트 시작일 내림차순. counts는 PENDING·HOSTING·ENDED 3개. "
					+ "entryAt = 이벤트 시작 − 입장 리드(입장 열림, 저장값 아님), endAt = 이벤트 종료(end_at, 상세 basic.endAt과 같음 — "
					+ "OPS-05의 endAt은 호스팅 종료), hostingEndAt = 호스팅 종료(hosting_end_at). "
					+ "endAt·hostingEndAt은 테넌트 스키마에서 읽어, 테넌트가 READY가 아니거나 행이 없거나 그 테넌트 스키마를 읽지 못했을 때 null"
					+ "(목록에서는 빠지지 않고, 다른 테넌트 값 · 목록 · 건수는 정상)")
	@GetMapping("/live-events")
	public EventListService.LiveEventListResponse liveEvents() {
		return listService.liveEvents();
	}

	@Operation(summary = "OPS-03-1 · 04-1 이벤트 상세(개요)",
			description = "승인 전에는 status.hostingStatus·eventStatus가 null. 승인 전이거나 eventStatus가 EXPIRED면 "
					+ "resources.domain·fullDomain·publicKey가 null. statusLogs는 신청 · 호스팅 · 이벤트 상태 처리 기록(오름차순, 처리자 포함)")
	@ApiErrorCodes({ ErrorCode.EVENT_NOT_FOUND, ErrorCode.TENANT_NOT_READY })
	@GetMapping("/events/{eventId}")
	public EventDetailResponse detail(@Parameter(example = "1043") @PathVariable Long eventId) {
		return queryService.detail(eventId);
	}

	@Operation(summary = "OPS-03-M1 승인",
			description = "본문 없음. 승인 대기 중(APPLIED)일 때만 가능. 승인은 항상 유지되고, "
					+ "privateKeyMailSent=false면 개인키 메일 발송 실패 상태 화면을 띄운다. "
					+ "배포 시작에 실패하면 hostingStatus가 DEPLOY_FAILED로 온다. "
					// TEST-LEAD: 서비스 값 100분 · 30분
					// + "승인 전 검사(호스팅 시작 ≤ 이벤트 시작 − 100분, 호스팅 시작 ≥ 지금 + 30분, 이름, 결과 주소 http(s), "
					+ "승인 전 검사(호스팅 시작 ≤ 이벤트 시작 − 35분, 호스팅 시작 ≥ 지금 + 10분, 이름, 결과 주소 http(s), " // TEST-LEAD
					+ "기간이 겹치는 승인 이벤트 ≤ 상한)에 걸리면 409 APPLICATION_DATA_INVALID(fields = 사유 코드별 문구), "
					+ "동시 승인 잠금을 못 잡으면 409 APPROVE_BUSY. 이때는 아무것도 바뀌지 않는다")
	@ApiErrorCodes({ ErrorCode.EVENT_NOT_FOUND, ErrorCode.APPLICATION_NOT_APPLIED, ErrorCode.HOSTING_PERIOD_ENDED,
			ErrorCode.TENANT_NOT_READY, ErrorCode.APPLICATION_DATA_INVALID, ErrorCode.APPROVE_BUSY })
	@PostMapping("/events/{eventId}/approve")
	public EventCommandService.ApproveResponse approve(@Parameter(example = "1043") @PathVariable Long eventId,
			@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
		return commandService.approve(eventId, CurrentOperator.id(jwt));
	}

	@Operation(summary = "OPS-03-M2 반려", description = "승인 대기 중(APPLIED)일 때만 가능")
	@ApiErrorCodes({ ErrorCode.INVALID_INPUT, ErrorCode.EVENT_NOT_FOUND, ErrorCode.APPLICATION_NOT_APPLIED,
			ErrorCode.TENANT_NOT_READY })
	@PostMapping("/events/{eventId}/reject")
	public RejectResponse reject(@Parameter(example = "1043") @PathVariable Long eventId,
			@Valid @RequestBody RejectRequest request, @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
		commandService.reject(eventId, CurrentOperator.id(jwt), request.reason());
		return new RejectResponse(eventId, ApplicationStatus.REJECTED);
	}

	@Operation(summary = "OPS-04-3 이벤트 진행 결과",
			description = "state로 화면을 고른다. END_FAILED → failure, READY → result, AGGREGATING·IN_PROGRESS → 상태 값만 "
					+ "(IN_PROGRESS는 endAt 포함). 비율(selloutRate·capacityUsageRate)은 0~1 소수")
	@ApiErrorCodes({ ErrorCode.EVENT_NOT_FOUND, ErrorCode.TENANT_NOT_READY })
	@GetMapping("/events/{eventId}/result")
	public EventResultResponse result(@Parameter(example = "1043") @PathVariable Long eventId) {
		return queryService.result(eventId);
	}

	@Operation(summary = "OPS-04-3 발급 이력 CSV 다운로드 링크",
			description = "요청할 때마다 S3 presigned GET URL을 새로 만든다(만료 expiresAt). 결과가 READY일 때만")
	@ApiErrorCodes({ ErrorCode.EVENT_NOT_FOUND, ErrorCode.RESULT_NOT_READY, ErrorCode.TENANT_NOT_READY })
	@GetMapping("/events/{eventId}/result/history-csv")
	public EventQueryService.CsvDownloadResponse historyCsv(@Parameter(example = "1043") @PathVariable Long eventId) {
		return queryService.historyCsv(eventId);
	}

	@Operation(summary = "OPS-03-2 · 04-2 신청 내용(신청 요약)",
			description = "Tetra Portal 이벤트 신청 4단계(UI-TP-10) 「신청 요약」과 같은 항목의 원본 값. 표시 문자열은 화면이 만든다. "
					+ "bannerFileName은 저장 경로의 파일명(원본 파일명 아님), 쿠폰 타입이 아니면 coupons는 빈 배열, layoutConfig는 객체")
	@ApiErrorCodes({ ErrorCode.INVALID_INPUT, ErrorCode.EVENT_NOT_FOUND, ErrorCode.TENANT_NOT_READY })
	@GetMapping("/events/{eventId}/application-summary")
	public ApplicationSummaryResponse applicationSummary(@Parameter(example = "1043") @PathVariable Long eventId) {
		return queryService.applicationSummary(eventId);
	}

}
