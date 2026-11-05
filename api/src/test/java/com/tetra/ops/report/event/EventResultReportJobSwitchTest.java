package com.tetra.ops.report.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.util.Optional;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.tetra.ops.config.TetraProperties;
import com.tetra.ops.domain.platform.TenantRepository;
import com.tetra.ops.event.EventStatusService;
import com.tetra.ops.event.IssuanceHistoryService;
import com.tetra.ops.listener.LifecycleResultHandler;
import com.tetra.ops.tenancy.TenantRouter;

import tools.jackson.databind.json.JsonMapper;

/**
 * 스위치: tetra.report.event-result.enabled가 true(기본)일 때만 작업 빈이 있고 WF4 리스너가 그 빈을 받는다. false면 빈이 없고 리스너는
 * 리포트를 시도하지 않는다(Optional 비어 있음). 만들기만 해서는 DB에 붙지 않는다.
 */
class EventResultReportJobSwitchTest {

	private final DataSource dataSource = mock(DataSource.class);

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(EventResultReportJob.class, LifecycleResultHandler.class)
		.withBean(DataSource.class, () -> dataSource)
		.withBean(TenantRepository.class, () -> mock(TenantRepository.class))
		.withBean(TenantRouter.class, () -> mock(TenantRouter.class))
		.withBean(EventResultReportReader.class, () -> mock(EventResultReportReader.class))
		.withBean(EventResultReportService.class, () -> mock(EventResultReportService.class))
		.withBean(EventStatusService.class, () -> mock(EventStatusService.class))
		.withBean(IssuanceHistoryService.class, () -> mock(IssuanceHistoryService.class))
		.withBean(TetraProperties.class, EventResultReportJobSwitchTest::properties)
		.withBean(JsonMapper.class, () -> JsonMapper.builder().build())
		.withBean(Clock.class, Clock::systemUTC);

	private static TetraProperties properties() {
		var sfn = new TetraProperties.Sfn("arn:a", "arn:t", new TetraProperties.Lifecycle("tetra-test-hosting-start",
				"tetra-test-event-open", "tetra-test-event-end"));
		var report = new TetraProperties.Report("tetra-operator-logs-test", new TetraProperties.MetaCleanup(true),
				new TetraProperties.EventResult(true, 3), "");
		return new TetraProperties("test", null, sfn, null, null, null, null, null, null, null, null, null, report, null,
				null, null, "tetraops.cloud");
	}

	@SuppressWarnings("unchecked")
	private static Optional<Object> listenerReports(LifecycleResultHandler handler) {
		return (Optional<Object>) ReflectionTestUtils.getField(handler, "eventResultReports");
	}

	@Test
	void jobAndListenerAttemptExistOnlyWhenSwitchedOn() {
		runner.run(context -> {
			assertThat(context).hasSingleBean(EventResultReportJob.class);
			assertThat(listenerReports(context.getBean(LifecycleResultHandler.class)))
				.containsSame(context.getBean(EventResultReportJob.class));
			verifyNoInteractions(dataSource);
		});
		runner.withPropertyValues("tetra.report.event-result.enabled=true")
			.run(context -> assertThat(context).hasSingleBean(EventResultReportJob.class));
		runner.withPropertyValues("tetra.report.event-result.enabled=false").run(context -> {
			assertThat(context).doesNotHaveBean(EventResultReportJob.class);
			assertThat(listenerReports(context.getBean(LifecycleResultHandler.class))).isEmpty();
		});
	}

}
