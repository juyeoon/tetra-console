package com.tetra.ops.external;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.ExecutionAlreadyExistsException;

/** 실제 Step Functions 호출. local 프로필에서는 LocalStepFunctionsGateway가 대신한다. */
@Component
@Profile("!local")
class AwsStepFunctionsGateway implements StepFunctionsGateway {

	private static final Logger log = LoggerFactory.getLogger(AwsStepFunctionsGateway.class);

	private final SfnClient sfn;

	AwsStepFunctionsGateway(SfnClient sfn) {
		this.sfn = sfn;
	}

	@Override
	public boolean startExecution(String stateMachineArn, String executionName, String inputJson) {
		try {
			sfn.startExecution(b -> b.stateMachineArn(stateMachineArn).name(executionName).input(inputJson));
			log.info("StartExecution 성공: name={}", executionName);
			return true;
		}
		catch (ExecutionAlreadyExistsException ex) {
			// 실행 이름을 고정해 중복 실행을 막는 구조. 같은 이름이 이미 있으면 시작된 것으로 본다.
			log.warn("같은 이름의 실행이 이미 있습니다: name={}", executionName);
			return true;
		}
		catch (SdkException ex) {
			log.error("StartExecution 실패(재시도 후): name={}, error={}", executionName, ex.getClass().getSimpleName(), ex);
			return false;
		}
	}

}
