package com.tetra.ops.listener;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.tetra.ops.common.error.ApiException;
import com.tetra.ops.common.error.ErrorCode;
import com.tetra.ops.config.TetraProperties;
import com.tetra.ops.event.EventStatusService;
import com.tetra.ops.tenancy.TenantRouter;
import com.tetra.ops.tenancy.TenantRouter.RoutedEvent;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 이벤트 수명 주기 결과(WF2-1 호스팅 시작 · WF2-2 이벤트 준비 · WF4 이벤트 종료) 처리 (§8).
 * 세 흐름이 큐 하나를 쓰고, 실행 이름은 스케줄러가 정하므로 detail.stateMachineArn의 이름으로 흐름을,
 * detail.input의 event_id로 이벤트를 고른다.
 * - 모르는 상태 머신 · 형식 오류 · 없는 이벤트 · tenant_id 불일치: WARN 후 정상 종료(ack)
 * - 조건부 UPDATE 0행: "이미 처리됨"으로 정상 종료(ack)
 * - 그 밖의 예외: 그대로 던져 ack하지 않는다(재전달 → 결국 DLQ)
 */
@Component
public class LifecycleResultHandler {

	private static final Logger log = LoggerFactory.getLogger(LifecycleResultHandler.class);

	enum Flow {

		/** WF2-1 호스팅 시작 */
		HOSTING_START,
		/** WF2-2 이벤트 준비(입장 열기) */
		EVENT_OPEN,
		/** WF4 이벤트 종료 */
		EVENT_END

	}

	private final JsonMapper jsonMapper;

	private final TenantRouter router;

	private final EventStatusService eventStatusService;

	private final TetraProperties.Lifecycle names;

	public LifecycleResultHandler(JsonMapper jsonMapper, TenantRouter router, EventStatusService eventStatusService,
			TetraProperties properties) {
		this.jsonMapper = jsonMapper;
		this.router = router;
		this.eventStatusService = eventStatusService;
		this.names = properties.sfn().lifecycle();
	}

	public void handle(String body) {
		Optional<ExecutionStatusChange> parsed = ExecutionStatusChange.parse(body, jsonMapper);
		if (parsed.isEmpty()) {
			log.warn("수명 주기 결과 메시지 형식 오류, 삭제합니다.");
			return;
		}
		ExecutionStatusChange change = parsed.get();
		String stateMachine = stateMachineName(change.stateMachineArn());
		Flow flow = flowOf(stateMachine);
		if (flow == null) {
			log.warn("수명 주기 결과 메시지의 상태 머신을 알 수 없습니다, 삭제합니다: stateMachine={}", stateMachine);
			return;
		}
		Input input = parseInput(change.input());
		if (input == null) {
			log.warn("수명 주기 결과 메시지의 input에 event_id가 없습니다, 삭제합니다: flow={}, name={}", flow, change.name());
			return;
		}
		RoutedEvent routed;
		try {
			routed = router.findEvent(input.eventId());
		}
		catch (ApiException ex) {
			if (ex.code() != ErrorCode.EVENT_NOT_FOUND) {
				throw ex;
			}
			log.warn("수명 주기 결과 메시지의 이벤트가 없습니다, 삭제합니다: flow={}, eventId={}", flow, input.eventId());
			return;
		}
		if (input.tenantId() != null && !input.tenantId().equals(routed.tenantId())) {
			// 다른 테넌트 이벤트를 바꾸지 않는다
			log.warn("수명 주기 결과 메시지의 tenant_id가 이벤트와 다릅니다, 삭제합니다: flow={}, eventId={}, tenantId={}", flow,
					input.eventId(), input.tenantId());
			return;
		}
		Long eventId = input.eventId();
		boolean succeeded = change.succeeded();
		String detail = succeeded ? null : change.failureDetail();
		boolean changed = router.inTenant(routed, () -> switch (flow) {
			case HOSTING_START -> eventStatusService.applyHostingStart(eventId, succeeded, detail);
			case EVENT_OPEN -> eventStatusService.applyEventOpen(eventId, succeeded, detail);
			case EVENT_END -> eventStatusService.applyEventEnd(eventId, succeeded, detail);
		});
		if (changed) {
			log.info("수명 주기 결과 반영: flow={}, eventId={}, status={}", flow, eventId, change.status());
		}
		else {
			log.info("수명 주기 결과 이미 처리됨: flow={}, eventId={}, status={}", flow, eventId, change.status());
		}
		if (flow == Flow.EVENT_END && succeeded && changed) {
			// TODO(3-12): 발급 이력 저장(이벤트 종료 집계 결과 · CSV 경로) 연결 지점. 상태 트랜잭션이 커밋된 뒤 여기서 부른다
		}
	}

	Flow flowOf(String stateMachineName) {
		if (stateMachineName == null) {
			return null;
		}
		if (stateMachineName.equals(names.hostingStartName())) {
			return Flow.HOSTING_START;
		}
		if (stateMachineName.equals(names.eventOpenName())) {
			return Flow.EVENT_OPEN;
		}
		if (stateMachineName.equals(names.eventEndName())) {
			return Flow.EVENT_END;
		}
		return null;
	}

	/** arn:aws:states:{region}:{account}:stateMachine:{name}[:{version|alias}] → name */
	static String stateMachineName(String stateMachineArn) {
		if (stateMachineArn == null) {
			return null;
		}
		int at = stateMachineArn.indexOf(":stateMachine:");
		if (at < 0) {
			return null;
		}
		String rest = stateMachineArn.substring(at + ":stateMachine:".length());
		int qualifier = rest.indexOf(':');
		return qualifier < 0 ? rest : rest.substring(0, qualifier);
	}

	record Input(Long eventId, String tenantId) {
	}

	/** detail.input(JSON 문자열)의 event_id(필수, 숫자 · 숫자 문자열)와 tenant_id(선택). 없거나 틀리면 null */
	Input parseInput(String input) {
		if (input == null) {
			return null;
		}
		JsonNode root;
		try {
			root = jsonMapper.readTree(input);
		}
		catch (JacksonException ex) {
			return null;
		}
		JsonNode id = root.path("event_id");
		Long eventId = null;
		if (id.isIntegralNumber() && id.asLong() > 0) {
			eventId = id.asLong();
		}
		else if (id.isString() && id.asString().matches("\\d{1,10}")) {
			eventId = Long.valueOf(id.asString());
		}
		if (eventId == null) {
			return null;
		}
		JsonNode tenant = root.path("tenant_id");
		return new Input(eventId, tenant.isString() ? tenant.asString() : null);
	}

}
