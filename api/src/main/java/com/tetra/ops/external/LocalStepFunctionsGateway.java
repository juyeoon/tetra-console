package com.tetra.ops.external;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 로컬 실행 전용(local 프로필). Step Functions를 호출하지 않고 "시작됨"으로 처리한다.
 * 실행 결과는 docker/local/send-sfn-result.ps1로 SQS(ElasticMQ)에 직접 보내 리스너를 확인한다.
 */
@Component
@Profile("local")
class LocalStepFunctionsGateway implements StepFunctionsGateway {

	private static final Logger log = LoggerFactory.getLogger(LocalStepFunctionsGateway.class);

	@Override
	public boolean startExecution(String stateMachineArn, String executionName, String inputJson) {
		// input에는 비밀 값이 없다(tenant_id·event_id·경로·일시)
		log.info("[local] StartExecution 생략: name={}, input={}", executionName, inputJson);
		return true;
	}

}
