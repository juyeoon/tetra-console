package com.tetra.ops.event;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.tetra.ops.common.DbErrors;
import com.tetra.ops.common.RandomIds;
import com.tetra.ops.common.error.ApiException;
import com.tetra.ops.common.error.ErrorCode;
import com.tetra.ops.common.time.Kst;
import com.tetra.ops.config.TetraProperties;
import com.tetra.ops.domain.ApplicationStatus;
import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.HostingStatus;
import com.tetra.ops.domain.platform.EventRegistryRepository;
import com.tetra.ops.domain.platform.TemplateRepository;
import com.tetra.ops.domain.tenant.Event;
import com.tetra.ops.external.MailGateway;
import com.tetra.ops.external.StepFunctionsGateway;
import com.tetra.ops.notification.NotificationService;
import com.tetra.ops.tenancy.TenantRouter;
import com.tetra.ops.tenancy.TenantRouter.RoutedEvent;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.json.JsonMapper;

/**
 * 승인(WF1-2)·반려(WF1-3) 흐름. 트랜잭션은 EventStatusService가 열고, 외부 호출(Step Functions·SES)은 커밋 후 여기서 한다.
 */
@Service
public class EventCommandService {

	private static final Logger log = LoggerFactory.getLogger(EventCommandService.class);

	static final int SLUG_MAX_ATTEMPTS = 5;

	static final String START_EXECUTION_FAILED = "StartExecution 실패";

	private final TenantRouter router;

	private final EventStatusService statusService;

	private final EventRegistryRepository registries;

	private final TemplateRepository templates;

	private final EventKeyPairs keyPairs;

	private final RandomIds randomIds;

	private final StepFunctionsGateway stepFunctions;

	private final MailGateway mailGateway;

	private final PrivateKeyMailComposer privateKeyMail;

	private final NotificationService notifications;

	private final TetraProperties properties;

	private final JsonMapper jsonMapper;

	public EventCommandService(TenantRouter router, EventStatusService statusService,
			EventRegistryRepository registries, TemplateRepository templates, EventKeyPairs keyPairs,
			RandomIds randomIds, StepFunctionsGateway stepFunctions, MailGateway mailGateway,
			PrivateKeyMailComposer privateKeyMail, NotificationService notifications, TetraProperties properties,
			JsonMapper jsonMapper) {
		this.notifications = notifications;
		this.router = router;
		this.statusService = statusService;
		this.registries = registries;
		this.templates = templates;
		this.keyPairs = keyPairs;
		this.randomIds = randomIds;
		this.stepFunctions = stepFunctions;
		this.mailGateway = mailGateway;
		this.privateKeyMail = privateKeyMail;
		this.properties = properties;
		this.jsonMapper = jsonMapper;
	}

	public record ApproveResponse(@Schema(example = "1043") Long eventId, ApplicationStatus applicationStatus,
			@Schema(description = "PENDING. 배포 시작(StartExecution)에 실패했으면 DEPLOY_FAILED") HostingStatus hostingStatus,
			EventStatus eventStatus,
			@Schema(description = "개인키 메일 발송 요청 성공 여부. false면 \"개인키 메일 발송 실패\" 상태 화면") boolean privateKeyMailSent) {
	}

	public ApproveResponse approve(Long eventId, Long operatorId) {
		RoutedEvent routed = router.findEvent(eventId);
		// 키를 만들기 전에 먼저 걸러 둔다. 동시에 들어온 요청은 조건부 UPDATE가 막는다.
		if (routed.registry().getApplicationStatus() != ApplicationStatus.APPLIED) {
			throw new ApiException(ErrorCode.APPLICATION_NOT_APPLIED);
		}
		EventKeyPairs.KeyMaterial keys = keyPairs.generate();
		Event approved = router.inTenant(routed, () -> commitApproval(eventId, operatorId, keys.publicKeyPem()));

		// ---- 커밋 후 ① Step Functions ----
		HostingStatus hostingStatus = HostingStatus.PENDING;
		boolean started = stepFunctions.startExecution(properties.sfn().approveStateMachineArn(), "approve-" + eventId,
				approveInput(routed, approved));
		if (!started) {
			boolean changed = router.inTenant(routed,
					() -> statusService.applyDeployResult(eventId, false, START_EXECUTION_FAILED));
			hostingStatus = changed ? HostingStatus.DEPLOY_FAILED : currentHostingStatus(eventId);
		}

		// ---- 커밋 후 ② SES 개인키 메일. 실패해도 승인은 유지하고, 개인키는 다시 만들 수 없으므로 재시도하지 않는다 ----
		boolean mailSent = mailGateway.send(privateKeyMail.compose(routed.tenant().getEmail(), approved,
				keys.privateKeyPem(), 1L, approved.getHostingEndAt()));
		if (!mailSent) {
			log.warn("개인키 메일 발송 실패: eventId={}", eventId);
			notifications.privateKeyMailFailed(routed.tenantId(), eventId);
		}
		return new ApproveResponse(eventId, ApplicationStatus.APPROVED, hostingStatus, EventStatus.SCHEDULED, mailSent);
	}

	public void reject(Long eventId, Long operatorId, String reason) {
		RoutedEvent routed = router.findEvent(eventId);
		if (routed.registry().getApplicationStatus() != ApplicationStatus.APPLIED) {
			throw new ApiException(ErrorCode.APPLICATION_NOT_APPLIED);
		}
		router.inTenant(routed, () -> statusService.reject(eventId, operatorId, reason));
	}

	/** slug가 테넌트 안에서 겹치면(uk_event_slug, 1062) 새 slug로 트랜잭션을 다시 실행한다. 최대 5회. */
	private Event commitApproval(Long eventId, Long operatorId, String publicKeyPem) {
		for (int attempt = 1; attempt <= SLUG_MAX_ATTEMPTS; attempt++) {
			try {
				return statusService.approve(eventId, operatorId, randomIds.slug(), publicKeyPem);
			}
			catch (DataIntegrityViolationException ex) {
				if (!DbErrors.isDuplicateKey(ex, "uk_event_slug")) {
					throw ex;
				}
				log.warn("slug 중복, 다시 생성: eventId={}, attempt={}", eventId, attempt);
			}
		}
		throw new IllegalStateException("slug를 " + SLUG_MAX_ATTEMPTS + "회 안에 만들지 못했습니다: eventId=" + eventId);
	}

	/**
	 * Step Functions input. TODO(미정): 키 이름과 일시 형식은 승인 등록 state machine 담당과 맞춤.
	 * 임시로 snake_case 키, 일시는 +09:00이 붙은 ISO-8601.
	 */
	private String approveInput(RoutedEvent routed, Event event) {
		String templateFilePath = templates.findById(event.getTemplateId())
			.orElseThrow(() -> new IllegalStateException("템플릿이 없습니다: templateId=" + event.getTemplateId()))
			.getFilePath();
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("tenant_id", routed.tenantId());
		input.put("event_id", event.getEventId());
		input.put("slug", event.getSlug());
		input.put("template_file_path", templateFilePath);
		// 화면설정은 문자열이 아니라 JSON 객체로 넘긴다(event.json layout으로 그대로 전달, 물리 ERD event.layout_config)
		input.put("layout_config", jsonMapper.readTree(event.getLayoutConfig()));
		input.put("banner_image_path", event.getBannerImagePath());
		input.put("traffic_plan_code", event.getTrafficPlanCode());
		input.put("hosting_start_at", Kst.toOffset(event.getHostingStartAt()).toString());
		input.put("start_at", Kst.toOffset(event.getStartAt()).toString());
		input.put("end_at", Kst.toOffset(event.getEndAt()).toString());
		input.put("hosting_end_at", Kst.toOffset(event.getHostingEndAt()).toString());
		return jsonMapper.writeValueAsString(input);
	}

	/** StartExecution 실패를 반영하려 했는데 이미 PENDING이 아니면(리스너가 먼저 처리) 현재 값을 돌려준다. */
	private HostingStatus currentHostingStatus(Long eventId) {
		return registries.findById(eventId).map(r -> r.getHostingStatus()).orElse(HostingStatus.PENDING);
	}

}
