package com.tetra.ops.monitoring;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.tetra.ops.config.AwsConfig;

import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataResponse;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;
import software.amazon.awssdk.services.cloudwatch.model.MetricStat;
import software.amazon.awssdk.services.cloudwatch.model.ScanBy;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.DescribeServicesResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerNotFoundException;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TagDescription;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroup;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetHealthDescription;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetHealthStateEnum;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DbInstanceNotFoundException;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.ExecutionListItem;
import software.amazon.awssdk.services.sfn.model.GetExecutionHistoryResponse;
import software.amazon.awssdk.services.sfn.model.ListExecutionsResponse;

/**
 * AWS SDK v2 구현. 클라이언트 상한(호출 5초 · 시도 2초)은 AwsConfig. Step Functions는 StartExecution과 같은 SfnClient를 쓰고
 * 같은 상한을 요청마다 덮어쓴다(클라이언트 Bean을 하나 더 두면 StartExecution 쪽 주입이 모호해진다).
 */
@Component
class SdkAwsMonitoringGateway implements AwsMonitoringGateway {

	/** DescribeTags 한 번에 넣을 수 있는 리소스 수 */
	static final int TAGS_BATCH = 20;

	/** ListExecutions 한 페이지 */
	static final int EXECUTIONS_PAGE = 100;

	/** GetExecutionHistory 한 페이지(API 최대) */
	static final int HISTORY_PAGE = 1000;

	private final CloudWatchClient cloudWatch;

	private final ElasticLoadBalancingV2Client elb;

	private final RdsClient rds;

	private final EcsClient ecs;

	private final SfnClient sfn;

	SdkAwsMonitoringGateway(CloudWatchClient cloudWatch, ElasticLoadBalancingV2Client elb, RdsClient rds, EcsClient ecs,
			SfnClient sfn) {
		this.cloudWatch = cloudWatch;
		this.elb = elb;
		this.rds = rds;
		this.ecs = ecs;
		this.sfn = sfn;
	}

	@Override
	public Optional<LoadBalancerRef> findLoadBalancer(String name) {
		return call("DescribeLoadBalancers", () -> {
			try {
				return elb.describeLoadBalancers(r -> r.names(name))
					.loadBalancers()
					.stream()
					.findFirst()
					.map(lb -> new LoadBalancerRef(lb.loadBalancerArn(), loadBalancerSuffix(lb.loadBalancerArn())));
			}
			catch (LoadBalancerNotFoundException ex) {
				return Optional.empty();
			}
		});
	}

	@Override
	public List<TargetGroupRef> targetGroups(String loadBalancerArn) {
		List<TargetGroup> groups = call("DescribeTargetGroups", () -> elb
			.describeTargetGroupsPaginator(r -> r.loadBalancerArn(loadBalancerArn))
			.targetGroups()
			.stream()
			.toList());
		Map<String, Map<String, String>> tags = new HashMap<>();
		for (int i = 0; i < groups.size(); i += TAGS_BATCH) {
			List<String> arns = groups.subList(i, Math.min(groups.size(), i + TAGS_BATCH))
				.stream()
				.map(TargetGroup::targetGroupArn)
				.toList();
			List<TagDescription> descriptions = call("DescribeTags",
					() -> elb.describeTags(r -> r.resourceArns(arns)).tagDescriptions());
			for (TagDescription d : descriptions) {
				Map<String, String> values = new LinkedHashMap<>();
				d.tags().forEach(t -> values.put(t.key(), t.value()));
				tags.put(d.resourceArn(), values);
			}
		}
		return groups.stream()
			.map(g -> new TargetGroupRef(g.targetGroupArn(), targetGroupSuffix(g.targetGroupArn()),
					tags.getOrDefault(g.targetGroupArn(), Map.of())))
			.toList();
	}

	@Override
	public TargetHealthCount targetHealth(String targetGroupArn) {
		List<TargetHealthDescription> targets = call("DescribeTargetHealth",
				() -> elb.describeTargetHealth(r -> r.targetGroupArn(targetGroupArn)).targetHealthDescriptions());
		int healthy = (int) targets.stream()
			.filter(t -> t.targetHealth() != null && t.targetHealth().state() == TargetHealthStateEnum.HEALTHY)
			.count();
		return new TargetHealthCount(healthy, targets.size());
	}

	@Override
	public List<ServiceCount> services(String cluster, List<String> serviceNames) {
		DescribeServicesResponse response = call("DescribeServices",
				() -> ecs.describeServices(r -> r.cluster(cluster).services(serviceNames)));
		return response.services()
			.stream()
			.map(s -> new ServiceCount(s.serviceName(), s.runningCount(), s.desiredCount()))
			.toList();
	}

	@Override
	public String dbInstanceStatus(String dbInstanceIdentifier) {
		return call("DescribeDBInstances", () -> {
			try {
				return rds.describeDBInstances(r -> r.dbInstanceIdentifier(dbInstanceIdentifier))
					.dbInstances()
					.stream()
					.findFirst()
					.map(db -> db.dbInstanceStatus())
					.orElse(null);
			}
			catch (DbInstanceNotFoundException ex) {
				return null;
			}
		});
	}

	@Override
	public Map<String, List<MetricPoint>> metricData(List<MetricQuery> queries, Instant start, Instant end) {
		Map<String, List<MetricPoint>> points = new LinkedHashMap<>();
		if (queries.isEmpty()) {
			return points;
		}
		List<MetricDataQuery> dataQueries = new ArrayList<>();
		for (MetricQuery q : queries) {
			points.put(q.id(), new ArrayList<>());
			List<Dimension> dimensions = q.dimensions()
				.entrySet()
				.stream()
				.map(d -> Dimension.builder().name(d.getKey()).value(d.getValue()).build())
				.toList();
			dataQueries.add(MetricDataQuery.builder()
				.id(q.id())
				.returnData(true)
				.metricStat(MetricStat.builder()
					.metric(Metric.builder().namespace(q.namespace()).metricName(q.metricName()).dimensions(dimensions).build())
					.period(q.periodSeconds())
					.stat(q.stat())
					.build())
				.build());
		}
		String nextToken = null;
		do {
			GetMetricDataRequest request = GetMetricDataRequest.builder()
				.metricDataQueries(dataQueries)
				.startTime(start)
				.endTime(end)
				.scanBy(ScanBy.TIMESTAMP_ASCENDING)
				.nextToken(nextToken)
				.build();
			GetMetricDataResponse response = call("GetMetricData", () -> cloudWatch.getMetricData(request));
			for (MetricDataResult result : response.metricDataResults()) {
				List<MetricPoint> target = points.computeIfAbsent(result.id(), id -> new ArrayList<>());
				for (int i = 0; i < Math.min(result.timestamps().size(), result.values().size()); i++) {
					target.add(new MetricPoint(result.timestamps().get(i).toEpochMilli(), result.values().get(i)));
				}
			}
			nextToken = response.nextToken();
		}
		while (nextToken != null);
		points.values().forEach(list -> list.sort(Comparator.comparingLong(MetricPoint::epochMillis)));
		return points;
	}

	@Override
	public List<ExecutionItem> executions(String stateMachineArn, Instant notBefore, int maxItems) {
		List<ExecutionItem> items = new ArrayList<>();
		String nextToken = null;
		do {
			String token = nextToken;
			ListExecutionsResponse response = call("ListExecutions", () -> sfn.listExecutions(r -> r
				.stateMachineArn(stateMachineArn)
				.maxResults(EXECUTIONS_PAGE)
				.nextToken(token)
				.overrideConfiguration(SdkAwsMonitoringGateway::sfnLimits)));
			for (ExecutionListItem e : response.executions()) {
				if (e.startDate() != null && e.startDate().isBefore(notBefore)) {
					// 최신순이라 뒤는 모두 더 오래됐다
					return items;
				}
				items.add(new ExecutionItem(e.executionArn(), e.name(), e.statusAsString(), e.startDate(), e.stopDate(),
						e.redriveCount() == null ? 0 : e.redriveCount()));
				if (items.size() >= maxItems) {
					return items;
				}
			}
			nextToken = response.nextToken();
		}
		while (nextToken != null);
		return items;
	}

	@Override
	public String executionInput(String executionArn) {
		return call("DescribeExecution", () -> sfn
			.describeExecution(r -> r.executionArn(executionArn).overrideConfiguration(SdkAwsMonitoringGateway::sfnLimits))
			.input());
	}

	@Override
	public ExecutionHistory executionHistory(String executionArn, int maxEvents) {
		List<HistoryEvent> events = new ArrayList<>();
		String nextToken = null;
		do {
			String token = nextToken;
			int page = Math.min(HISTORY_PAGE, maxEvents - events.size());
			GetExecutionHistoryResponse response = call("GetExecutionHistory", () -> sfn.getExecutionHistory(r -> r
				.executionArn(executionArn)
				.maxResults(page)
				.includeExecutionData(false)
				.nextToken(token)
				.overrideConfiguration(SdkAwsMonitoringGateway::sfnLimits)));
			response.events().forEach(e -> events.add(historyEvent(e)));
			nextToken = response.nextToken();
			if (nextToken != null && events.size() >= maxEvents) {
				return new ExecutionHistory(events, true);
			}
		}
		while (nextToken != null);
		return new ExecutionHistory(events, false);
	}

	/** SDK 이벤트에서 상태 이름(진입 · 나옴)과 실행 실패 · 시간 초과 · 중단의 error · cause만 꺼낸다 */
	static HistoryEvent historyEvent(software.amazon.awssdk.services.sfn.model.HistoryEvent e) {
		String name = e.stateEnteredEventDetails() != null ? e.stateEnteredEventDetails().name()
				: e.stateExitedEventDetails() != null ? e.stateExitedEventDetails().name() : null;
		String error = null;
		String cause = null;
		if (e.executionFailedEventDetails() != null) {
			error = e.executionFailedEventDetails().error();
			cause = e.executionFailedEventDetails().cause();
		}
		else if (e.executionTimedOutEventDetails() != null) {
			error = e.executionTimedOutEventDetails().error();
			cause = e.executionTimedOutEventDetails().cause();
		}
		else if (e.executionAbortedEventDetails() != null) {
			error = e.executionAbortedEventDetails().error();
			cause = e.executionAbortedEventDetails().cause();
		}
		return new HistoryEvent(e.id(), e.previousEventId() == null ? 0 : e.previousEventId(), e.typeAsString(),
				e.timestamp(), name, error, cause);
	}

	private static void sfnLimits(AwsRequestOverrideConfiguration.Builder override) {
		override.apiCallTimeout(AwsConfig.MONITORING_API_CALL_TIMEOUT)
			.apiCallAttemptTimeout(AwsConfig.MONITORING_ATTEMPT_TIMEOUT);
	}

	/** arn:aws:elasticloadbalancing:{region}:{account}:loadbalancer/app/{name}/{id} → app/{name}/{id} */
	static String loadBalancerSuffix(String arn) {
		int i = arn == null ? -1 : arn.indexOf(":loadbalancer/");
		return i < 0 ? null : arn.substring(i + ":loadbalancer/".length());
	}

	/** arn:aws:elasticloadbalancing:{region}:{account}:targetgroup/{name}/{id} → targetgroup/{name}/{id} */
	static String targetGroupSuffix(String arn) {
		int i = arn == null ? -1 : arn.lastIndexOf(':');
		return i < 0 ? null : arn.substring(i + 1);
	}

	/** SDK 예외를 짧은 원인으로 바꾼다(서비스 오류 코드 또는 클래스 이름만) */
	private static <T> T call(String operation, Supplier<T> work) {
		try {
			return work.get();
		}
		catch (AwsServiceException ex) {
			String code = ex.awsErrorDetails() == null ? null : ex.awsErrorDetails().errorCode();
			throw new AwsMonitoringException(operation + " " + (code == null ? "HTTP " + ex.statusCode() : code));
		}
		catch (SdkException ex) {
			throw new AwsMonitoringException(operation + " " + ex.getClass().getSimpleName());
		}
	}

}
