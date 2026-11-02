package com.tetra.ops.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Duration;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.tetra.ops.config.TetraProperties;
import com.tetra.ops.domain.platform.OperatorNotificationRepository;

import tools.jackson.databind.json.JsonMapper;

/** 스위치: tetra.slack.enabled가 true일 때만 작업 빈이 있다(꺼져 있으면 DB · HTTP 없음). 1분 주기 자동화 확인 스위치와 무관하다. */
class SlackNotifyJobSwitchTest {

	private final DataSource dataSource = mock(DataSource.class);

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(SlackNotifyJob.class)
		.withBean(DataSource.class, () -> dataSource)
		.withBean(OperatorNotificationRepository.class, () -> mock(OperatorNotificationRepository.class))
		.withBean(TetraProperties.class, SlackNotifyJobSwitchTest::properties)
		.withBean(JsonMapper.class, () -> JsonMapper.builder().build())
		.withBean(Clock.class, Clock::systemUTC);

	private static TetraProperties properties() {
		var slack = new TetraProperties.Slack(true, "xoxb-t", new TetraProperties.SlackChannels("CU", "CW", "CI"),
				Duration.ofSeconds(10), Duration.ofMinutes(10), Duration.ofSeconds(2), Duration.ofSeconds(5),
				"https://slack.com/api");
		return new TetraProperties("test", null, null, null, null, null, new TetraProperties.Console(""), null, null, null,
				null, slack, null, null, null, "tetraops.cloud");
	}

	@Test
	void runsOnlyWhenSwitchedOnIndependentlyOfAutomationAlerts() {
		runner.withPropertyValues("tetra.slack.enabled=true", "tetra.alerts.enabled=false").run(context -> {
			assertThat(context).hasSingleBean(SlackNotifyJob.class);
			// 만들기만 해서는 DB에 붙지 않는다
			verifyNoInteractions(dataSource);
		});
		runner.withPropertyValues("tetra.slack.enabled=false", "tetra.alerts.enabled=true")
			.run(context -> assertThat(context).doesNotHaveBean(SlackNotifyJob.class));
		runner.run(context -> assertThat(context).doesNotHaveBean(SlackNotifyJob.class));
	}

}
