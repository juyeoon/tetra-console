package com.tetra.ops.tenant;

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
import com.tetra.ops.config.TetraProperties;
import com.tetra.ops.domain.platform.Tenant;
import com.tetra.ops.domain.platform.TenantRepository;
import com.tetra.ops.external.MailGateway;
import com.tetra.ops.external.StepFunctionsGateway;
import com.tetra.ops.notification.NotificationService;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.json.JsonMapper;

/** 테넌트 등록(WF5)·정보 수정(OPS-06-M3)·이메일 중복 확인. */
@Service
public class TenantCommandService {

	private static final Logger log = LoggerFactory.getLogger(TenantCommandService.class);

	static final int TENANT_ID_MAX_ATTEMPTS = 5;

	static final String START_EXECUTION_FAILED = "StartExecution 실패";

	private final TenantRepository tenants;

	private final TenantWriter writer;

	private final RandomIds randomIds;

	private final StepFunctionsGateway stepFunctions;

	private final MailGateway mailGateway;

	private final PasswordLinkSigner linkSigner;

	private final TenantRegistrationMailComposer mailComposer;

	private final NotificationService notifications;

	private final TetraProperties properties;

	private final JsonMapper jsonMapper;

	public TenantCommandService(TenantRepository tenants, TenantWriter writer, RandomIds randomIds,
			StepFunctionsGateway stepFunctions, MailGateway mailGateway, PasswordLinkSigner linkSigner,
			TenantRegistrationMailComposer mailComposer, NotificationService notifications, TetraProperties properties,
			JsonMapper jsonMapper) {
		this.notifications = notifications;
		this.tenants = tenants;
		this.writer = writer;
		this.randomIds = randomIds;
		this.stepFunctions = stepFunctions;
		this.mailGateway = mailGateway;
		this.linkSigner = linkSigner;
		this.mailComposer = mailComposer;
		this.properties = properties;
		this.jsonMapper = jsonMapper;
	}

	public record RegisterRequest(
			@Schema(description = "테넌트명, 100자 이하", example = "E사", requiredMode = Schema.RequiredMode.REQUIRED) String name,
			@Schema(description = "계정ID(로그인 이메일), 255자 이하", example = "minho.park@e-corp.com",
					requiredMode = Schema.RequiredMode.REQUIRED) String email,
			@Schema(description = "회사 root 도메인, 253자 이하", example = "e-corp.com",
					requiredMode = Schema.RequiredMode.REQUIRED) String rootDomain,
			@Schema(description = "담당자 이름, 100자 이하", example = "박민호",
					requiredMode = Schema.RequiredMode.REQUIRED) String contactName) {
	}

	public record RegisterResponse(@Schema(example = "k7f2m9qx4b1t") String tenantId,
			@Schema(description = "비밀번호 설정 메일 발송 요청 성공 여부. false면 \"메일 발송에 실패했습니다\" 상태 화면") boolean mailSent) {
	}

	@Schema(description = "name · contactName · email 중 정확히 하나만 보낸다")
	public record UpdateRequest(@Schema(description = "테넌트명, 100자 이하") String name,
			@Schema(description = "담당자 이름, 100자 이하") String contactName,
			@Schema(description = "계정ID(이메일), 255자 이하. 이미 등록된 이메일이면 409") String email) {
	}

	/** OPS-07 이메일 중복 확인. 형식이 틀리면 400. */
	public boolean isEmailAvailable(String email) {
		Map<String, String> fields = new LinkedHashMap<>();
		String normalized = TenantInputRules.email("email", email, fields);
		TenantInputRules.throwIfInvalid(fields);
		return !tenants.existsByEmail(normalized);
	}

	public RegisterResponse register(RegisterRequest request) {
		Map<String, String> fields = new LinkedHashMap<>();
		String name = TenantInputRules.text("name", request.name(), TenantInputRules.NAME_MAX, fields);
		String email = TenantInputRules.email("email", request.email(), fields);
		String rootDomain = TenantInputRules.domain("rootDomain", request.rootDomain(), fields);
		String contactName = TenantInputRules.text("contactName", request.contactName(), TenantInputRules.NAME_MAX,
				fields);
		TenantInputRules.throwIfInvalid(fields);
		// 중복 확인(OPS-07)과 등록 사이에 다른 등록이 끼어들 수 있어 다시 확인한다. 최종 판정은 uk_tenant_email.
		if (tenants.existsByEmail(email)) {
			throw new ApiException(ErrorCode.EMAIL_DUPLICATED);
		}
		Tenant tenant = insertWithUniqueId(name, rootDomain, email, contactName);
		String tenantId = tenant.getTenantId();

		// ---- 커밋 후 ① Step Functions (테넌트 환경 준비) ----
		boolean started = stepFunctions.startExecution(properties.sfn().tenantStateMachineArn(), "tenant-" + tenantId,
				jsonMapper.writeValueAsString(
						Map.of("tenant_id", tenantId, "root_domain", rootDomain, "env", properties.env())));
		if (!started) {
			writer.finishProvisioning(tenantId, false, START_EXECUTION_FAILED);
		}

		// ---- 커밋 후 ② SES 비밀번호 설정 메일. 실패해도 등록은 유지하고 재발송 API는 없다 ----
		var link = linkSigner.create(tenantId, tenant.getPassword());
		boolean mailSent = mailGateway.send(mailComposer.compose(email, contactName, name, link.url()));
		if (!mailSent) {
			log.warn("비밀번호 설정 메일 발송 실패: tenantId={}", tenantId);
			notifications.passwordSetupMailFailed(tenantId);
		}
		return new RegisterResponse(tenantId, mailSent);
	}

	/** OPS-06-M3. name · contactName · email 중 정확히 하나. 확인 절차·상태 로그 없음. */
	public Tenant update(String tenantId, UpdateRequest request) {
		int provided = (request.name() != null ? 1 : 0) + (request.contactName() != null ? 1 : 0)
				+ (request.email() != null ? 1 : 0);
		if (provided != 1) {
			throw ApiException.invalid("body", "name, contactName, email 중 하나만 보내 주세요.");
		}
		Map<String, String> fields = new LinkedHashMap<>();
		String name = request.name() == null ? null
				: TenantInputRules.text("name", request.name(), TenantInputRules.NAME_MAX, fields);
		String contactName = request.contactName() == null ? null
				: TenantInputRules.text("contactName", request.contactName(), TenantInputRules.NAME_MAX, fields);
		String email = request.email() == null ? null : TenantInputRules.email("email", request.email(), fields);
		TenantInputRules.throwIfInvalid(fields);
		try {
			return writer.update(tenantId, name, contactName, email);
		}
		catch (DataIntegrityViolationException ex) {
			if (DbErrors.isDuplicateKey(ex, "uk_tenant_email")) {
				throw new ApiException(ErrorCode.EMAIL_DUPLICATED);
			}
			throw ex;
		}
	}

	/** SQS 테넌트 환경 준비 결과. @return 바꿨으면 true, 이미 처리됐으면 false */
	public boolean applyProvisioningResult(String tenantId, boolean succeeded, String detail) {
		return writer.finishProvisioning(tenantId, succeeded, detail);
	}

	/** tenant_id는 CSPRNG 12자. PK 충돌이면 다시 만든다. */
	private Tenant insertWithUniqueId(String name, String rootDomain, String email, String contactName) {
		for (int attempt = 1; attempt <= TENANT_ID_MAX_ATTEMPTS; attempt++) {
			String tenantId = randomIds.tenantId();
			try {
				return writer.insert(tenantId, name, rootDomain, email, contactName);
			}
			catch (DataIntegrityViolationException ex) {
				if (DbErrors.isDuplicateKey(ex, "uk_tenant_email")) {
					throw new ApiException(ErrorCode.EMAIL_DUPLICATED);
				}
				if (!DbErrors.isDuplicateKey(ex, "PRIMARY")) {
					throw ex;
				}
				log.warn("tenant_id 충돌, 다시 생성: attempt={}", attempt);
			}
		}
		throw new IllegalStateException("tenant_id를 " + TENANT_ID_MAX_ATTEMPTS + "회 안에 만들지 못했습니다.");
	}

}
