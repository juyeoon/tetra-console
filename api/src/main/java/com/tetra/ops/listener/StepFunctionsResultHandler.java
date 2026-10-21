package com.tetra.ops.listener;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.tetra.ops.common.error.ApiException;
import com.tetra.ops.common.error.ErrorCode;
import com.tetra.ops.event.EventStatusService;
import com.tetra.ops.tenancy.TenantRouter;
import com.tetra.ops.tenancy.TenantRouter.RoutedEvent;
import com.tetra.ops.tenant.TenantCommandService;

import tools.jackson.databind.json.JsonMapper;

/**
 * Step Functions 결과 메시지 처리 (§8).
 * - 접두어가 안 맞거나 형식이 틀린 메시지: WARN 후 정상 종료(ack)
 * - 조건부 UPDATE 0행: "이미 처리됨"으로 정상 종료(ack)
 * - 그 밖의 예외: 그대로 던져 ack하지 않는다(재전달 → 결국 DLQ)
 */
@Component
public class StepFunctionsResultHandler {

	private static final Logger log = LoggerFactory.getLogger(StepFunctionsResultHandler.class);

	// 수동 재실행은 실행 이름 끝에 -r1, -r2…가 붙는다(ops-workflows 계약). event_id는 INT UNSIGNED라 10자리 이하
	private static final Pattern APPROVE_NAME = Pattern.compile("^approve-(\\d{1,10})(-r\\d+)?$");

	private static final Pattern TENANT_NAME = Pattern.compile("^tenant-([a-z0-9]{12})(-r\\d+)?$");

	private final JsonMapper jsonMapper;

	private final TenantRouter router;

	private final EventStatusService eventStatusService;

	private final TenantCommandService tenantCommandService;

	public StepFunctionsResultHandler(JsonMapper jsonMapper, TenantRouter router,
			EventStatusService eventStatusService, TenantCommandService tenantCommandService) {
		this.jsonMapper = jsonMapper;
		this.router = router;
		this.eventStatusService = eventStatusService;
		this.tenantCommandService = tenantCommandService;
	}

	/** 승인 등록 결과: hosting_status PENDING → DEPLOYED / DEPLOY_FAILED + HOSTING 로그 1줄. */
	public void handleApproveResult(String body) {
		Optional<ExecutionStatusChange> parsed = ExecutionStatusChange.parse(body, jsonMapper);
		if (parsed.isEmpty()) {
			log.warn("승인 결과 메시지 형식 오류, 삭제합니다.");
			return;
		}
		ExecutionStatusChange change = parsed.get();
		Matcher matcher = APPROVE_NAME.matcher(change.name());
		if (!matcher.matches()) {
			log.warn("승인 결과 메시지의 실행 이름이 approve-{event_id}가 아닙니다, 삭제합니다: name={}", change.name());
			return;
		}
		Long eventId = Long.valueOf(matcher.group(1));
		RoutedEvent routed;
		try {
			routed = router.findEvent(eventId);
		}
		catch (ApiException ex) {
			if (ex.code() != ErrorCode.EVENT_NOT_FOUND) {
				throw ex;
			}
			log.warn("승인 결과 메시지의 이벤트가 없습니다, 삭제합니다: eventId={}", eventId);
			return;
		}
		String detail = change.succeeded() ? null : change.failureDetail();
		boolean changed = router.inTenant(routed,
				() -> eventStatusService.applyDeployResult(eventId, change.succeeded(), detail));
		if (changed) {
			log.info("승인 등록 결과 반영: eventId={}, status={}", eventId, change.status());
		}
		else {
			log.info("승인 등록 결과 이미 처리됨: eventId={}, status={}", eventId, change.status());
		}
	}

	/** 테넌트 환경 준비 결과: provisioning_status PROVISIONING → READY / FAILED. 상태 로그 없음. */
	public void handleTenantResult(String body) {
		Optional<ExecutionStatusChange> parsed = ExecutionStatusChange.parse(body, jsonMapper);
		if (parsed.isEmpty()) {
			log.warn("테넌트 준비 결과 메시지 형식 오류, 삭제합니다.");
			return;
		}
		ExecutionStatusChange change = parsed.get();
		Matcher matcher = TENANT_NAME.matcher(change.name());
		if (!matcher.matches()) {
			log.warn("테넌트 준비 결과 메시지의 실행 이름이 tenant-{tenant_id}가 아닙니다, 삭제합니다: name={}", change.name());
			return;
		}
		String tenantId = matcher.group(1);
		boolean changed = tenantCommandService.applyProvisioningResult(tenantId, change.succeeded(),
				change.succeeded() ? null : change.failureDetail());
		if (changed) {
			log.info("테넌트 환경 준비 결과 반영: tenantId={}, status={}", tenantId, change.status());
		}
		else {
			// 없는 테넌트이거나 이미 PROVISIONING이 아님
			log.info("테넌트 환경 준비 결과 이미 처리됨: tenantId={}, status={}", tenantId, change.status());
		}
	}

}
