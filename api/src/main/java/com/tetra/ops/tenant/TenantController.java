package com.tetra.ops.tenant;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.tetra.ops.common.error.ApiErrorCodes;
import com.tetra.ops.common.error.ErrorCode;
import com.tetra.ops.common.time.Kst;
import com.tetra.ops.domain.platform.Tenant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/** OPS-06 테넌트 현황, OPS-06-M3 정보 수정, OPS-07 등록. */
@Tag(name = "테넌트", description = "OPS-06 테넌트 현황 · 06-1 개요 · 06-2 이벤트 · 06-3 리소스 · 06-M3 수정 · OPS-07 등록")
@RestController
@RequestMapping("/api/ops/tenants")
public class TenantController {

	private final TenantQueryService queryService;

	private final TenantCommandService commandService;

	public TenantController(TenantQueryService queryService, TenantCommandService commandService) {
		this.queryService = queryService;
		this.commandService = commandService;
	}

	public record EmailAvailabilityResponse(boolean available) {
	}

	@Operation(summary = "OPS-06 테넌트 목록 + 계정상태별 건수", description = "가입일 내림차순")
	@GetMapping
	public TenantQueryService.TenantListResponse list() {
		return queryService.list();
	}

	@Operation(summary = "OPS-07 이메일 중복 확인",
			description = "available=false면 이미 등록된 이메일. 등록 API도 같은 검사를 다시 하므로 등록 시 409가 날 수 있다")
	@ApiErrorCodes(ErrorCode.INVALID_INPUT)
	@GetMapping("/email-availability")
	public EmailAvailabilityResponse emailAvailability(
			@Parameter(example = "minho.park@e-corp.com") @RequestParam String email) {
		return new EmailAvailabilityResponse(commandService.isEmailAvailable(email));
	}

	@Operation(summary = "OPS-07 테넌트 등록",
			description = "4개 필수. 앞뒤 공백은 잘라서 저장. 등록되면 계정은 INACTIVE, 환경 준비는 PROVISIONING으로 시작. "
					+ "비밀번호 설정 메일은 환경 준비가 끝나면(READY) 보낸다(등록 응답에는 메일 결과가 없다). "
					+ "스키마 생성 · StartExecution 실패면 provisioningStatus FAILED(201)")
	@ApiErrorCodes({ ErrorCode.INVALID_INPUT, ErrorCode.EMAIL_DUPLICATED })
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public TenantCommandService.RegisterResponse register(@RequestBody TenantCommandService.RegisterRequest request) {
		return commandService.register(request);
	}

	@Operation(summary = "OPS-06-1 테넌트 개요",
			description = "기본 정보 · S3 버킷 이름 · 계정 상태 로그(최신순, 사유 배지 badge). 환경 준비 상태와 무관하게 동작")
	@ApiErrorCodes(ErrorCode.TENANT_NOT_FOUND)
	@GetMapping("/{tenantId}")
	public TenantQueryService.TenantOverviewResponse overview(
			@Parameter(example = "k7f2m9qx4b1t") @PathVariable String tenantId) {
		return queryService.overview(tenantId);
	}

	@Operation(summary = "OPS-06-M3 테넌트 정보 수정",
			description = "본문에 name · contactName · email 중 정확히 하나만 보낸다. 수정 후 기본 정보를 돌려준다")
	@ApiErrorCodes({ ErrorCode.INVALID_INPUT, ErrorCode.TENANT_NOT_FOUND, ErrorCode.EMAIL_DUPLICATED })
	@PatchMapping("/{tenantId}")
	public TenantQueryService.Basic update(@Parameter(example = "k7f2m9qx4b1t") @PathVariable String tenantId,
			@RequestBody TenantCommandService.UpdateRequest request) {
		Tenant tenant = commandService.update(tenantId, request);
		return new TenantQueryService.Basic(tenant.getTenantId(), tenant.getName(), tenant.getContactName(),
				tenant.getEmail(), tenant.getAccountStatus(), Kst.toOffset(tenant.getCreatedAt()),
				tenant.getProvisioningStatus(), tenant.getPassword() != null);
	}

	@Operation(summary = "OPS-06-2 테넌트 이벤트 목록", description = "신청시각 내림차순. 승인 전 건은 hostingStatus·eventStatus가 null")
	@ApiErrorCodes(ErrorCode.TENANT_NOT_FOUND)
	@GetMapping("/{tenantId}/events")
	public List<TenantQueryService.TenantEventItem> events(
			@Parameter(example = "k7f2m9qx4b1t") @PathVariable String tenantId) {
		return queryService.events(tenantId);
	}

	@Operation(summary = "OPS-06-3 테넌트 리소스",
			description = "승인된 이벤트만. eventStatus가 EXPIRED면 subdomain·publicKey가 null. publicKey는 [공개키 복사]용 PEM 원문. "
					+ "환경 준비가 끝나지 않은 테넌트는 빈 목록")
	@ApiErrorCodes(ErrorCode.TENANT_NOT_FOUND)
	@GetMapping("/{tenantId}/resources")
	public List<TenantQueryService.TenantResourceItem> resources(
			@Parameter(example = "k7f2m9qx4b1t") @PathVariable String tenantId) {
		return queryService.resources(tenantId);
	}

}
