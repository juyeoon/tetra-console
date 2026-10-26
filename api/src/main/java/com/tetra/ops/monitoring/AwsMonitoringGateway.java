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

}
