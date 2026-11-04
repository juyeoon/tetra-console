package com.tetra.ops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.tetra.ops.config.TetraProperties;
import com.tetra.ops.monitoring.AwsMonitoringGateway.CapacityReservation;
import com.tetra.ops.monitoring.AwsMonitoringGateway.LoadBalancerRef;
import com.tetra.ops.monitoring.AwsMonitoringGateway.MetricPoint;
import com.tetra.ops.monitoring.AwsMonitoringGateway.MetricQuery;
import com.tetra.ops.monitoring.AwsMonitoringGateway.ServiceCount;
import com.tetra.ops.monitoring.AwsMonitoringGateway.TargetGroupRef;
import com.tetra.ops.monitoring.AwsMonitoringGateway.TargetHealthCount;
import com.tetra.ops.monitoring.AwsMonitoringGateway.ZoneCapacity;
import com.tetra.ops.monitoring.MonitoringOverview.Point;
import com.tetra.ops.monitoring.TrafficPlatformResponse.IssuanceLcu;
import com.tetra.ops.monitoring.TrafficPlatformResponse.LcuState;
import com.tetra.ops.monitoring.TrafficPlatformResponse.LcuZone;

/** AWS 값 다듬기: DLQ 점 없음 = 0, 5xx 합계, ALB · 대상 그룹 조회 캐시와 실패 처리, 발급 ALB LCU 예약 상태. AWS는 목 */
class TrafficAwsCollectorTest {

	private static final Instant NOW = Instant.parse("2026-10-26T06:00:00Z");

	private final AwsMonitoringGateway gateway = mock(AwsMonitoringGateway.class);

	private final MutableClock clock = new MutableClock(NOW);

	private static TetraProperties properties(String dlqNames) {
		return properties(dlqNames, "tetra-dev-issuance");
	}

	private static TetraProperties properties(String dlqNames, String issuanceAlbName) {
		var aws = new TetraProperties.MonitoringAws(Duration.ofSeconds(60), Duration.ofSeconds(10), issuanceAlbName,
				"app/console/1", "app/portal/2", "targetgroup/console/3", "targetgroup/portal/4",
				"arn:aws:elasticloadbalancing:ap-northeast-2:000000000000:targetgroup/console/3",
				"arn:aws:elasticloadbalancing:ap-northeast-2:000000000000:targetgroup/portal/4", "tetra-dev-mysql",
				"tetra-dev", "console,portal", dlqNames, Duration.ofSeconds(30), Duration.ofSeconds(30));
		var monitoring = new TetraProperties.Monitoring("", Duration.ofSeconds(2), Duration.ofSeconds(3),
				Duration.ofSeconds(15), Duration.ofSeconds(8), aws, "");
		return new TetraProperties("test", null, null, null, null, null, null, null, null, monitoring, null, null, null, null, null, null,
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

	// ---------------------------------------------------------------- 발급 ALB LCU 예약

	private static ZoneCapacity zone(String az, String state, Double effective) {
		return new ZoneCapacity(az, state, effective, null);
	}

	@Test
	void lcuProvisionedSumsZonesAndUsesKst() {
		// 15:26 실측과 같은 모양: 100 LCU, 두 AZ 모두 provisioned 50, 마지막 변경 12:42:23 KST, 오늘 줄이기 1회 남음
		IssuanceLcu lcu = TrafficAwsCollector.lcu(new CapacityReservation(100,
				List.of(zone("ap-northeast-2c", "provisioned", 50.0), zone("ap-northeast-2a", "provisioned", 50.0)),
				Instant.parse("2026-11-04T03:42:23.889Z"), 1));
		assertThat(lcu.reservedUnits()).isEqualTo(100);
		assertThat(lcu.state()).isEqualTo(LcuState.PROVISIONED);
		assertThat(lcu.effectiveUnits()).isEqualTo(100.0);
		// AZ 이름 순, 상태는 대문자
		assertThat(lcu.zones()).containsExactly(new LcuZone("ap-northeast-2a", "PROVISIONED", 50.0, null),
				new LcuZone("ap-northeast-2c", "PROVISIONED", 50.0, null));
		assertThat(lcu.lastModifiedAt()).isEqualTo(OffsetDateTime.parse("2026-11-04T12:42:23.889+09:00"));
		assertThat(lcu.decreaseRequestsRemaining()).isEqualTo(1);
	}

	@Test
	void lcuStatesWorstWinsAndNoReservation() {
		// 해제 직후(09:12:11 실측): 예약 0 + 두 AZ pending · 실효 없음 → 반영 중
		IssuanceLcu releasing = TrafficAwsCollector.lcu(new CapacityReservation(0,
				List.of(zone("ap-northeast-2a", "pending", null), zone("ap-northeast-2c", "pending", null)), null, 1));
		assertThat(releasing.state()).isEqualTo(LcuState.PENDING);
		assertThat(releasing.reservedUnits()).isZero();
		assertThat(releasing.effectiveUnits()).isNull();
		assertThat(releasing.lastModifiedAt()).isNull();
		// 해제 끝(09:12:35 실측): 예약 필드도 AZ도 없음 → 예약 없음
		IssuanceLcu none = TrafficAwsCollector.lcu(new CapacityReservation(null, List.of(), null, 1));
		assertThat(none.state()).isEqualTo(LcuState.NONE);
		assertThat(none.reservedUnits()).isNull();
		assertThat(none.zones()).isEmpty();
		// 예약은 있는데 AZ가 아직 없으면 반영 중
		assertThat(TrafficAwsCollector.lcu(new CapacityReservation(200, List.of(), null, 2)).state())
			.isEqualTo(LcuState.PENDING);
		// 나쁜 것이 이긴다: FAILED > PENDING > REBALANCING > PROVISIONED. 실패 사유는 AZ에 남는다
		IssuanceLcu failed = TrafficAwsCollector.lcu(new CapacityReservation(200,
				List.of(zone("ap-northeast-2a", "pending", null),
						new ZoneCapacity("ap-northeast-2c", "failed", 50.0, "Insufficient capacity")),
				null, 2));
		assertThat(failed.state()).isEqualTo(LcuState.FAILED);
		assertThat(failed.zones().get(1).reason()).isEqualTo("Insufficient capacity");
		assertThat(failed.effectiveUnits()).isEqualTo(50.0);
		assertThat(TrafficAwsCollector.lcu(new CapacityReservation(200,
				List.of(zone("ap-northeast-2a", "rebalancing", 100.0), zone("ap-northeast-2c", "pending", 100.0)), null, 2))
			.state()).isEqualTo(LcuState.PENDING);
		assertThat(TrafficAwsCollector.lcu(new CapacityReservation(200,
				List.of(zone("ap-northeast-2a", "rebalancing", 100.0), zone("ap-northeast-2c", "provisioned", 100.0)), null,
				2))
			.state()).isEqualTo(LcuState.REBALANCING);
		// SDK보다 새 코드는 그대로 남기고 상태는 반영 전으로 본다
		IssuanceLcu unknown = TrafficAwsCollector.lcu(new CapacityReservation(200,
				List.of(zone("ap-northeast-2a", "provisioned", 100.0), zone("ap-northeast-2c", "warming", null)), null, 2));
		assertThat(unknown.state()).isEqualTo(LcuState.PENDING);
		assertThat(unknown.zones().get(1).state()).isEqualTo("WARMING");
	}

	@Test
	void lcuUsesIssuanceAlbArnCache() {
		LoadBalancerRef alb = new LoadBalancerRef("arn:alb", "app/tetra-dev-issuance/9");
		when(gateway.findLoadBalancer("tetra-dev-issuance")).thenReturn(Optional.of(alb));
		when(gateway.capacityReservation("arn:alb")).thenReturn(Optional.of(new CapacityReservation(100,
				List.of(zone("ap-northeast-2a", "provisioned", 50.0), zone("ap-northeast-2c", "provisioned", 50.0)), null, 1)));
		TrafficAwsCollector collector = collector("");

		assertThat(collector.lcuConfigured()).isTrue();
		assertThat(collector.issuanceLcu().state()).isEqualTo(LcuState.PROVISIONED);
		collector.issuanceLcu();
		// ARN은 DescribeLoadBalancers 10분 캐시 그대로(예약 조회만 매번 — 30초 캐시는 TrafficMonitoringService)
		verify(gateway, times(1)).findLoadBalancer(anyString());
		verify(gateway, times(2)).capacityReservation("arn:alb");
	}

	@Test
	void lcuFailuresThrowSoOnlyLcuIsStale() {
		LoadBalancerRef alb = new LoadBalancerRef("arn:alb", "app/tetra-dev-issuance/9");
		when(gateway.findLoadBalancer("tetra-dev-issuance")).thenReturn(Optional.of(alb));
		// 권한 반영 전: AccessDenied → 예외(캐시가 값 없음 + stale → 화면 '확인 불가')
		when(gateway.capacityReservation("arn:alb"))
			.thenThrow(new AwsMonitoringException("DescribeCapacityReservation AccessDenied"));
		assertThatThrownBy(() -> collector("").issuanceLcu()).isInstanceOf(AwsMonitoringException.class)
			.hasMessage("DescribeCapacityReservation AccessDenied");

		// ALB를 못 찾음(이름 조회 결과 없음)
		when(gateway.findLoadBalancer("tetra-dev-issuance")).thenReturn(Optional.empty());
		assertThatThrownBy(() -> collector("").issuanceLcu()).isInstanceOf(AwsMonitoringException.class)
			.hasMessage("발급 ALB 없음");

		// 발급 ALB 이름이 없으면 조회하지 않는다
		TrafficAwsCollector unnamed = new TrafficAwsCollector(gateway, properties("", ""), clock);
		assertThat(unnamed.lcuConfigured()).isFalse();
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
