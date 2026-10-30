package com.tetra.ops.monitoring;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OPS-05 AWS 읽기 호출(명세 5-2). 구현은 {@link SdkAwsMonitoringGateway}, 테스트는 목.
 * 실패는 {@link AwsMonitoringException} 하나로 바꾸고 메시지에 ARN · 응답 본문 · 자격 증명을 넣지 않는다.
 */
public interface AwsMonitoringGateway {

	/** ALB. arnSuffix = CloudWatch LoadBalancer 차원(app/이름/id) */
	record LoadBalancerRef(String arn, String arnSuffix) {
	}

	/** 대상 그룹과 태그. arnSuffix = CloudWatch TargetGroup 차원(targetgroup/이름/id) */
	record TargetGroupRef(String arn, String arnSuffix, Map<String, String> tags) {
	}

	record TargetHealthCount(int healthy, int total) {
	}

	record ServiceCount(String name, int running, int desired) {
	}

	/** GetMetricData 한 줄. dimensions 순서 유지 */
	record MetricQuery(String id, String namespace, String metricName, Map<String, String> dimensions, String stat,
			int periodSeconds) {
	}

	record MetricPoint(long epochMillis, double value) {
	}

	/** ListExecutions 한 줄. status는 RUNNING · SUCCEEDED · FAILED · TIMED_OUT · ABORTED · PENDING_REDRIVE. stopDate는 끝났을 때만 */
	record ExecutionItem(String executionArn, String name, String status, Instant startDate, Instant stopDate,
			int redriveCount) {
	}

	/**
	 * GetExecutionHistory 이벤트 하나(includeExecutionData=false)에서 쓰는 값만. stateName은 *StateEntered · *StateExited만,
	 * error · cause는 실행 실패 · 시간 초과 · 중단(ExecutionFailed · ExecutionTimedOut · ExecutionAborted)만 — Task 입력 · 출력 ·
	 * parameters · 재시도 원인은 꺼내지 않는다
	 */
	record HistoryEvent(long id, long previousEventId, String type, Instant timestamp, String stateName, String error,
			String cause) {
	}

	/** 실행 기록. truncated = 페이지 상한을 넘어 뒤쪽을 받지 않음 */
	record ExecutionHistory(List<HistoryEvent> events, boolean truncated) {
	}

	/** DescribeLoadBalancers(Names). 없으면 empty */
	Optional<LoadBalancerRef> findLoadBalancer(String name);

	/** DescribeTargetGroups(LoadBalancerArn) + DescribeTags */
	List<TargetGroupRef> targetGroups(String loadBalancerArn);

	TargetHealthCount targetHealth(String targetGroupArn);

	List<ServiceCount> services(String cluster, List<String> serviceNames);

	/** DescribeDBInstances(DBInstanceIdentifier) — 전체 조회는 권한이 없다. 없으면 null */
	String dbInstanceStatus(String dbInstanceIdentifier);

	/** 쿼리 id → 시각 오름차순 점. 점이 없는 id는 빈 목록 */
	Map<String, List<MetricPoint>> metricData(List<MetricQuery> queries, Instant start, Instant end);

	// ---- Step Functions(자동화 실행 상세). 클라이언트는 StartExecution과 같은 SfnClient, 시간 상한은 요청마다 덮어쓴다 ----

	/** ListExecutions — 최신순. startDate가 notBefore보다 앞인 실행이 나오거나 maxItems를 채우면 멈춘다 */
	List<ExecutionItem> executions(String stateMachineArn, Instant notBefore, int maxItems);

	/** DescribeExecution의 input(JSON 문자열, 없으면 null). 이벤트를 고르는 데만 쓰고 응답 · 로그에 내보내지 않는다 */
	String executionInput(String executionArn);

	/** GetExecutionHistory(includeExecutionData=false) — 오래된 순, maxEvents까지 */
	ExecutionHistory executionHistory(String executionArn, int maxEvents);

	// ---- SQS(1분 주기 확인의 DLQ 메시지 수) ----

	/** 큐의 ApproximateNumberOfMessages(지금 받을 수 있는 메시지 수). 큐 URL은 이름으로 한 번 찾아 보관한다(GetQueueUrl) */
	int queueDepth(String queueName);

}
