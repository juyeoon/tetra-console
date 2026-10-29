package com.tetra.ops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.tetra.ops.config.TetraProperties;
import com.tetra.ops.monitoring.AwsMonitoringGateway.LoadBalancerRef;
import com.tetra.ops.monitoring.AwsMonitoringGateway.MetricPoint;
import com.tetra.ops.monitoring.AwsMonitoringGateway.MetricQuery;
import com.tetra.ops.monitoring.AwsMonitoringGateway.ServiceCount;
import com.tetra.ops.monitoring.AwsMonitoringGateway.TargetGroupRef;
import com.tetra.ops.monitoring.AwsMonitoringGateway.TargetHealthCount;
import com.tetra.ops.monitoring.MonitoringOverview.Point;

/** AWS 값 다듬기: DLQ 점 없음 = 0, 5xx 합계, ALB · 대상 그룹 조회 캐시와 실패 처리. AWS는 목 */
class TrafficAwsCollectorTest {

	private static final Instant NOW = Instant.parse("2026-10-26T06:00:00Z");

	private final AwsMonitoringGateway gateway = mock(AwsMonitoringGateway.class);

	private final MutableClock clock = new MutableClock(NOW);

	private static TetraProperties properties(String dlqNames) {
		var aws = new TetraProperties.MonitoringAws(Duration.ofSeconds(60), Duration.ofSeconds(10), "tetra-dev-issuance",
				"app/console/1", "app/portal/2", "targetgroup/console/3", "targetgroup/portal/4",
				"arn:aws:elasticloadbalancing:ap-northeast-2:000000000000:targetgroup/console/3",
				"arn:aws:elasticloadbalancing:ap-northeast-2:000000000000:targetgroup/portal/4", "tetra-dev-mysql",
				"tetra-dev", "console,portal", dlqNames, Duration.ofSeconds(30));
		var monitoring = new TetraProperties.Monitoring("", Duration.ofSeconds(2), Duration.ofSeconds(3),
				Duration.ofSeconds(15), Duration.ofSeconds(8), aws);
		return new TetraProperties("test", null, null, null, null, null, null, null, null, monitoring, null, null, null,
				"tetraops.cloud");
	}

	private TrafficAwsCollector collector(String dlqNames) {
		return new TrafficAwsCollector(gateway, properties(dlqNames), clock);
	}

	private static MetricPoint point(Instant at, double value) {
		return new MetricPoint(at.toEpochMilli(), value);
	}

	@Test
	@SuppressWarnings("unchecked")
	void platformValues() {
		ArgumentCaptor<List<MetricQuery>> queries = ArgumentCaptor.forClass(List.class);
		Map<String, List<MetricPoint>> points = new HashMap<>();
		points.put("rds_cpu", List.of(point(NOW.minusSeconds(180), 30), point(NOW.minusSeconds(120), 48.04)));
		points.put("rds_conn", List.of(point(NOW.minusSeconds(120), 36)));
		points.put("rds_free", List.of(point(NOW.minusSeconds(120), 1.8e10)));
		// portal: 5분 안 대상 5xx 2 + ALB 5xx 1, 오래된 점은 빼고
		points.put("svc1_tg", List.of(point(NOW.minusSeconds(900), 9), point(NOW.minusSeconds(120), 2)));
		points.put("svc1_elb", List.of(point(NOW.minusSeconds(60), 1)));
		// 빈 DLQ는 점이 없다 → 0
		points.put("dlq1", List.of(point(NOW.minusSeconds(120), 3)));
		when(gateway.metricData(queries.capture(), any(), any())).thenReturn(points);
		when(gateway.dbInstanceStatus("tetra-dev-mysql")).thenReturn("available");
		when(gateway.services("tetra-dev", List.of("console", "portal")))
			.thenReturn(List.of(new ServiceCount("console", 1, 1), new ServiceCount("portal", 1, 1)));
		when(gateway.targetHealth(anyString())).thenReturn(new TargetHealthCount(1, 1));

		TrafficAwsCollector.PlatformAws aws = collector("tetra-dev-approve-result-dlq, tetra-dev-scheduler-dlq").platform();

		assertThat(aws.at()).isEqualTo(NOW);
		assertThat(aws.rds()).isEqualTo(new TrafficPlatformResponse.Rds("available", 48.0, 36.0, 1.8e10));
		assertThat(aws.services()).containsExactly(
				new TrafficPlatformResponse.ServiceStatus("console", 1, 1, 1, 1, 0L),
				new TrafficPlatformResponse.ServiceStatus("portal", 1, 1, 1, 1, 3L));
		assertThat(aws.dlq()).containsExactly(Map.entry("tetra-dev-approve-result-dlq", 0),
				Map.entry("tetra-dev-scheduler-dlq", 3));
		assertThat(queries.getValue()).extracting(MetricQuery::id)
			.containsExactly("rds_cpu", "rds_conn", "rds_free", "svc0_elb", "svc0_tg", "svc1_elb", "svc1_tg", "dlq0", "dlq1");
		assertThat(queries.getValue().get(7)).isEqualTo(new MetricQuery("dlq0", "AWS/SQS",
				"ApproximateNumberOfMessagesVisible", Map.of("QueueName", "tetra-dev-approve-result-dlq"), "Maximum", 60));
	}

	@Test
	void dlqSkippedWhenNotConfigured() {
		when(gateway.metricData(anyList(), any(), any())).thenReturn(Map.of());
		assertThat(collector("").platform().dlq()).isNull();
	}

	@Test
	void eventTargetGroupFromTagsAndCachedLookups() {
		LoadBalancerRef alb = new LoadBalancerRef("arn:alb", "app/tetra-dev-issuance/9");
		when(gateway.findLoadBalancer("tetra-dev-issuance")).thenReturn(Optional.of(alb));
		when(gateway.targetGroups("arn:alb")).thenReturn(List.of(
				new TargetGroupRef("arn:tg7", "targetgroup/k8s-tenant-7/a",
						Map.of("ingress.k8s.aws/resource", "tenant-aaaaaaaaaaaa/issuance-7-issuance-7:http")),
				new TargetGroupRef("arn:tg70", "targetgroup/k8s-tenant-70/b",
						Map.of("kubernetes.io/service-name", "tenant-aaaaaaaaaaaa/issuance-70"))));
		Instant t = NOW.minusSeconds(120);
		when(gateway.metricData(anyList(), any(), any())).thenReturn(Map.of("alb_req", List.of(point(t, 1000)),
				"alb_5xx", List.of(point(t, 8)), "tg_req", List.of(point(t, 500), point(NOW.minusSeconds(60), 0)),
				"rds_cpu", List.of(point(t, 48.04))));
		TrafficAwsCollector collector = collector("");
		TrafficWindow window = new TrafficWindow("entry", TrafficWindow.LIVE_OPTIONS, NOW.minusSeconds(600), NOW,
				Duration.ofSeconds(60));

		TrafficAwsCollector.EventAws aws = collector.event(7, window);

		assertThat(aws.albErrorRatio()).containsExactly(new Point(t.toEpochMilli(), 0.008));
		// 요청이 있고 5xx 점만 없으면 0, 요청 0이면 0
		assertThat(aws.eventTargetErrorRatio()).containsExactly(new Point(t.toEpochMilli(), 0.0),
				new Point(NOW.minusSeconds(60).toEpochMilli(), 0.0));
		assertThat(aws.rdsCpu()).containsExactly(new Point(t.toEpochMilli(), 48.0));
		// 두 번째 호출은 캐시(ALB · 대상 그룹 다시 조회 안 함), 다른 이벤트 70도 같은 목록에서 찾음
		collector.event(70, window);
		verify(gateway, times(1)).findLoadBalancer(anyString());
		verify(gateway, times(1)).targetGroups(anyString());
	}

	@Test
	void albLookupFailureLeavesAlbMetricsEmpty() {
		when(gateway.findLoadBalancer(anyString())).thenThrow(new AwsMonitoringException("DescribeLoadBalancers AccessDenied"));
		when(gateway.metricData(anyList(), any(), any())).thenReturn(Map.of());
		TrafficAwsCollector collector = collector("");
		TrafficWindow window = new TrafficWindow("entry", TrafficWindow.LIVE_OPTIONS, NOW.minusSeconds(600), NOW,
				Duration.ofSeconds(60));
		TrafficAwsCollector.EventAws aws = collector.event(7, window);
		assertThat(aws.albErrorRatio()).isEmpty();
		assertThat(aws.eventTargetErrorRatio()).isEmpty();
		verify(gateway, never()).targetGroups(anyString());
		// 1분 안에는 다시 조회하지 않고, 지나면 다시
		collector.event(7, window);
		verify(gateway, times(1)).findLoadBalancer(anyString());
		clock.advance(Duration.ofSeconds(61));
		collector.event(7, window);
		verify(gateway, times(2)).findLoadBalancer(anyString());
	}

	@Test
	void eventIdFromTags() {
		assertThat(TrafficAwsCollector.eventIdOf(Map.of("ingress.k8s.aws/resource",
				"tenant-aaaaaaaaaaaa/issuance-7-issuance-7:http"))).isEqualTo(7L);
		assertThat(TrafficAwsCollector.eventIdOf(Map.of("kubernetes.io/service-name", "tenant-aaaaaaaaaaaa/issuance-123")))
			.isEqualTo(123L);
		assertThat(TrafficAwsCollector.eventIdOf(Map.of("ingress.k8s.aws/resource", "argocd/argocd-server:http"))).isNull();
		assertThat(TrafficAwsCollector.eventIdOf(Map.of())).isNull();
	}

	/** 테스트용 시계 */
	static final class MutableClock extends Clock {

		private Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration d) {
			now = now.plus(d);
		}

		@Override
		public java.time.ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}

	}

}
