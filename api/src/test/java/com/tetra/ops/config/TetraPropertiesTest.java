package com.tetra.ops.config;

import static org.assertj.core.api.Assertions.assertThat;

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
				"tetra.sqs.tenant-result-queue=q2", "tetra.mail.from=x@y.z", "tetra.portal.base-url=https://p");

	@Test
	void startsWithAllValuesAndHidesSecrets() {
		runner
			.withPropertyValues("tetra.auth.jwt-secret=0123456789abcdef0123456789abcdef",
					"tetra.password-link.hmac-secret=hmac-secret-value")
			.run(context -> {
				assertThat(context).hasNotFailed();
				TetraProperties properties = context.getBean(TetraProperties.class);
				assertThat(properties.csv().presignTtl()).hasMinutes(5);
				assertThat(properties.toString()).doesNotContain("0123456789abcdef").doesNotContain("hmac-secret-value");
			});
	}

	@Test
	void failsWhenJwtSecretMissingOrBlankOrShort() {
		runner.withPropertyValues("tetra.password-link.hmac-secret=h").run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("tetra.auth.jwt-secret=", "tetra.password-link.hmac-secret=h")
			.run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("tetra.auth.jwt-secret=short", "tetra.password-link.hmac-secret=h")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void failsWhenHmacSecretMissing() {
		runner.withPropertyValues("tetra.auth.jwt-secret=0123456789abcdef0123456789abcdef")
			.run(context -> assertThat(context).hasFailed());
	}

}
