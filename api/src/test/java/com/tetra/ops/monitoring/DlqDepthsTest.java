package com.tetra.ops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** 알림센터 SYSTEM 행의 DLQ 메시지 수: 큐별 캐시 · 실패는 빠짐 · 보지 않는 큐는 부르지 않음 */
@ExtendWith(OutputCaptureExtension.class)
class DlqDepthsTest {

	private static final String APPROVE = "tetra-test-approve-result-dlq";

	private static final String SCHEDULER = "tetra-test-scheduler-dlq";

	private final AwsMonitoringGateway aws = mock(AwsMonitoringGateway.class);

	private DlqDepths depths;

	private DlqDepths depths(Duration ttl, Duration timeout) {
		depths = new DlqDepths(aws, List.of(APPROVE, SCHEDULER), ttl, timeout);
		return depths;
	}

	@AfterEach
	void close() {
		if (depths != null) {
			depths.close();
		}
	}

	@Test
	void cachesEachQueueWithinTtl() {
		when(aws.queueDepth(APPROVE)).thenReturn(3, 5);
		when(aws.queueDepth(SCHEDULER)).thenReturn(0);
		DlqDepths cache = depths(Duration.ofHours(1), Duration.ofSeconds(5));

		assertThat(cache.depths(List.of(APPROVE, SCHEDULER, APPROVE))).isEqualTo(Map.of(APPROVE, 3, SCHEDULER, 0));
		assertThat(cache.depths(List.of(APPROVE))).isEqualTo(Map.of(APPROVE, 3));
		verify(aws, times(1)).queueDepth(APPROVE);
		verify(aws, times(1)).queueDepth(SCHEDULER);
	}

	@Test
	void readsAgainAfterTtl() {
		when(aws.queueDepth(APPROVE)).thenReturn(3, 5);
		DlqDepths cache = depths(Duration.ZERO, Duration.ofSeconds(5));

		assertThat(cache.depths(List.of(APPROVE))).isEqualTo(Map.of(APPROVE, 3));
		assertThat(cache.depths(List.of(APPROVE))).isEqualTo(Map.of(APPROVE, 5));
	}

	@Test
	void failedQueueIsMissingAndNotRetriedWithinTtl(CapturedOutput output) {
		when(aws.queueDepth(APPROVE)).thenThrow(new AwsMonitoringException("GetQueueAttributes 실패(AccessDenied)"));
		when(aws.queueDepth(SCHEDULER)).thenReturn(2);
		DlqDepths cache = depths(Duration.ofHours(1), Duration.ofSeconds(5));

		assertThat(cache.depths(List.of(APPROVE, SCHEDULER))).isEqualTo(Map.of(SCHEDULER, 2));
		assertThat(cache.depths(List.of(APPROVE, SCHEDULER))).isEqualTo(Map.of(SCHEDULER, 2));
		verify(aws, times(1)).queueDepth(APPROVE);
		assertThat(output.getOut().lines().filter(l -> l.contains("메시지 수를 읽지 못해"))).hasSize(1);
		assertThat(output.getOut()).contains("DLQ " + APPROVE + " 메시지 수를 읽지 못해 현재 상태를 비워 둡니다(GetQueueAttributes 실패(AccessDenied))");
	}

	@Test
	void slowQueueIsMissingAfterTimeout() {
		when(aws.queueDepth(APPROVE)).thenAnswer(inv -> {
			Thread.sleep(2_000);
			return 1;
		});
		DlqDepths cache = depths(Duration.ofHours(1), Duration.ofMillis(200));

		assertThat(cache.depths(List.of(APPROVE))).isEmpty();
	}

	@Test
	void unwatchedQueueIsNotRead() {
		DlqDepths cache = depths(Duration.ofHours(1), Duration.ofSeconds(5));

		assertThat(cache.depths(List.of("tetra-test-removed-dlq"))).isEmpty();
		verify(aws, never()).queueDepth(anyString());
	}

}
