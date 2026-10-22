package com.tetra.ops.listener;

import org.springframework.stereotype.Component;

import io.awspring.cloud.sqs.annotation.SqsListener;

/**
 * Step Functions 실행 종료 이벤트(EventBridge → SQS) 수신. 큐 이름은 설정값.
 * 메서드가 정상 종료하면 ack(삭제)하고, 예외가 나면 ack하지 않아 재전달된다(결국 DLQ).
 */
@Component
public class StepFunctionsResultListener {

	private final StepFunctionsResultHandler handler;

	private final LifecycleResultHandler lifecycleHandler;

	public StepFunctionsResultListener(StepFunctionsResultHandler handler, LifecycleResultHandler lifecycleHandler) {
		this.handler = handler;
		this.lifecycleHandler = lifecycleHandler;
	}

	@SqsListener("${tetra.sqs.approve-result-queue}")
	public void onApproveResult(String body) {
		handler.handleApproveResult(body);
	}

	@SqsListener("${tetra.sqs.tenant-result-queue}")
	public void onTenantResult(String body) {
		handler.handleTenantResult(body);
	}

	/** WF2-1 · WF2-2 · WF4 공용 큐 — stateMachineArn으로 흐름을 나눈다 */
	@SqsListener("${tetra.sqs.event-lifecycle-result-queue}")
	public void onEventLifecycleResult(String body) {
		lifecycleHandler.handle(body);
	}

}
