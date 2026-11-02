package com.tetra.ops.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/** 비밀 값이 비어 있으면 기동이 실패해야 한다 (§10). 값은 로그·toString에 나오지 않는다. */
class TetraPropertiesTest {

	@Configuration
	@EnableConfigurationProperties(TetraProperties.class)
	static class Config {

	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
		.withUserConfiguration(Config.class)
		.withPropertyValues("tetra.env=test", "tetra.aws.region=ap-northeast-2", "tetra.sfn.approve-state-machine-arn=a",
				"tetra.sfn.tenant-state-machine-arn=b", "tetra.sqs.approve-result-queue=q1",
				"tetra.sqs.tenant-result-queue=q2", "tetra.sqs.event-lifecycle-result-queue=q3",
				"tetra.sfn.lifecycle.hosting-start-name=tetra-test-hosting-start",
				"tetra.sfn.lifecycle.event-open-name=tetra-test-event-open",
				"tetra.sfn.lifecycle.event-end-name=tetra-test-event-end", "tetra.mail.from=x@y.z", "tetra.portal.base-url=https://p",
				"tetra.db-migration.username=tetra_migrator", "tetra.platform-domain=tetraops.cloud");

	private static final String MIGRATOR_PASSWORD = "tetra.db-migration.password=migrator-pw-value";

	@Test
	void startsWithAllValuesAndHidesSecrets() {
		runner
			.withPropertyValues("tetra.auth.jwt-secret=0123456789abcdef0123456789abcdef",
					"tetra.password-link.hmac-secret=hmac-secret-value", MIGRATOR_PASSWORD)
			.run(context -> {
				assertThat(context).hasNotFailed();
				TetraProperties properties = context.getBean(TetraProperties.class);
				assertThat(properties.csv().presignTtl()).hasMinutes(5);
				// PROMETHEUS_URL이 없어도 기동한다(모니터링만 stale)
				assertThat(properties.monitoring().configured()).isFalse();
				assertThat(properties.monitoring().connectTimeout()).hasSeconds(2);
				assertThat(properties.monitoring().readTimeout()).hasSeconds(3);
				assertThat(properties.monitoring().cacheTtl()).hasSeconds(15);
				assertThat(properties.monitoring().refreshTimeout()).hasSeconds(8);
				assertThat(properties.dbMigration().username()).isEqualTo("tetra_migrator");
				assertThat(properties.dbMigration().locations()).isEqualTo("classpath:db/migration/tenant");
				assertThat(properties.toString()).doesNotContain("0123456789abcdef")
					.doesNotContain("hmac-secret-value")
					.doesNotContain("migrator-pw-value");
			});
	}

	@Test
	void failsWhenJwtSecretMissingOrBlankOrShort() {
		runner.withPropertyValues("tetra.password-link.hmac-secret=h", MIGRATOR_PASSWORD)
			.run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("tetra.auth.jwt-secret=", "tetra.password-link.hmac-secret=h", MIGRATOR_PASSWORD)
			.run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("tetra.auth.jwt-secret=short", "tetra.password-link.hmac-secret=h", MIGRATOR_PASSWORD)
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void failsWhenHmacSecretMissing() {
		runner.withPropertyValues("tetra.auth.jwt-secret=0123456789abcdef0123456789abcdef", MIGRATOR_PASSWORD)
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void failsWhenMigratorPasswordMissing() {
		runner.withPropertyValues("tetra.auth.jwt-secret=0123456789abcdef0123456789abcdef",
				"tetra.password-link.hmac-secret=h")
			.run(context -> assertThat(context).hasFailed());
	}

	private static final String[] SECRETS = { "tetra.auth.jwt-secret=0123456789abcdef0123456789abcdef",
			"tetra.password-link.hmac-secret=h", MIGRATOR_PASSWORD };

	/** Slack 발송은 기본 꺼짐 — 토큰 · 채널 없이 기동한다 */
	@Test
	void slackIsOffByDefaultAndStartsWithoutToken() {
		runner.withPropertyValues(SECRETS).run(context -> {
			assertThat(context).hasNotFailed();
			TetraProperties.Slack slack = context.getBean(TetraProperties.class).slack();
			assertThat(slack.enabled()).isFalse();
			assertThat(slack.botToken()).isEmpty();
			assertThat(slack.interval()).hasSeconds(10);
			assertThat(slack.maxAge()).hasMinutes(10);
			assertThat(slack.connectTimeout()).hasSeconds(2);
			assertThat(slack.readTimeout()).hasSeconds(5);
			assertThat(slack.apiUrl()).isEqualTo("https://slack.com/api");
		});
	}

	private static final String SLACK_TOKEN = "xoxb-secret-token-value";

	private static final String[] SLACK_ON = { "tetra.slack.enabled=true", "tetra.slack.bot-token=" + SLACK_TOKEN,
			"tetra.slack.channels.urgent=C0URGENT1", "tetra.slack.channels.warning=C0WARNING1",
			"tetra.slack.channels.info=C0INFO1" };

	/** 켜져 있으면 토큰 · 채널 3개(채널 ID 형식)가 모두 있어야 기동한다. 실패 메시지 · toString에 토큰은 없다 */
	@Test
	void slackEnabledNeedsTokenAndAllThreeChannels() {
		runner.withPropertyValues(SECRETS).withPropertyValues(SLACK_ON).run(context -> {
			assertThat(context).hasNotFailed();
			TetraProperties properties = context.getBean(TetraProperties.class);
			assertThat(properties.slack().channels().forPriority("WARNING")).isEqualTo("C0WARNING1");
			assertThat(properties.slack().channels().forPriority("LOW")).isNull();
			assertThat(properties.toString()).doesNotContain(SLACK_TOKEN);
		});
		for (String missing : new String[] { "tetra.slack.bot-token=", "tetra.slack.channels.urgent=",
				"tetra.slack.channels.warning=", "tetra.slack.channels.info= ", "tetra.slack.channels.info=#ops-info" }) {
			runner.withPropertyValues(SECRETS).withPropertyValues(SLACK_ON).withPropertyValues(missing).run(context -> {
				assertThat(context).as(missing).hasFailed();
				StringWriter trace = new StringWriter();
				context.getStartupFailure().printStackTrace(new PrintWriter(trace));
				assertThat(trace.toString()).doesNotContain(SLACK_TOKEN);
			});
		}
	}

	/** 기본값(tetraops.cloud)은 application.yml에 있다. 값을 덮어쓸 수 있고, 비우면 기동하지 않는다 */
	@Test
	void platformDomainCanBeOverriddenAndMustNotBeBlank() {
		String[] secrets = { "tetra.auth.jwt-secret=0123456789abcdef0123456789abcdef", "tetra.password-link.hmac-secret=h",
				MIGRATOR_PASSWORD };
		runner.withPropertyValues(secrets)
			.withPropertyValues("tetra.platform-domain=stage.tetraops.cloud")
			.run(context -> assertThat(context.getBean(TetraProperties.class).platformDomain())
				.isEqualTo("stage.tetraops.cloud"));
		runner.withPropertyValues(secrets)
			.withPropertyValues("tetra.platform-domain=")
			.run(context -> assertThat(context).hasFailed());
	}

}
