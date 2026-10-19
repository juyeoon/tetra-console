package com.tetra.ops.external;

/** Step Functions StartExecution. 트랜잭션 밖(커밋 후)에서만 부른다. */
public interface StepFunctionsGateway {

	/**
	 * @return 실행이 시작됐으면(또는 같은 이름의 실행이 이미 있으면) true, SDK 재시도 후에도 실패하면 false
	 */
	boolean startExecution(String stateMachineArn, String executionName, String inputJson);

}
