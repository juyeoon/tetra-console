package com.tetra.ops.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sfn.SfnClient;

/**
 * AWS SDK v2 클라이언트. 자격 증명은 기본 자격 증명 체인(EKS에서는 IRSA)을 쓴다. 코드·설정에 넣지 않는다.
 */
@Configuration(proxyBeanMethods = false)
public class AwsConfig {

	/** StartExecution 실패 시 SDK에서 3회 재시도 (최초 1회 + 재시도 3회). */
	static final int SFN_MAX_ATTEMPTS = 4;

	@Bean(destroyMethod = "close")
	SfnClient sfnClient(TetraProperties properties) {
		return SfnClient.builder()
			.region(Region.of(properties.aws().region()))
			.overrideConfiguration(ClientOverrideConfiguration.builder()
				.retryStrategy(AwsRetryStrategy.standardRetryStrategy().toBuilder().maxAttempts(SFN_MAX_ATTEMPTS).build())
				.build())
			.build();
	}

	@Bean(destroyMethod = "close")
	SesV2Client sesV2Client(TetraProperties properties) {
		return SesV2Client.builder().region(Region.of(properties.aws().region())).build();
	}

	@Bean(destroyMethod = "close")
	S3Client s3Client(TetraProperties properties) {
		return S3Client.builder().region(Region.of(properties.aws().region())).build();
	}

	@Bean(destroyMethod = "close")
	S3Presigner s3Presigner(TetraProperties properties) {
		return S3Presigner.builder().region(Region.of(properties.aws().region())).build();
	}

	// ---- OPS-05 트래픽 모니터링(읽기 전용). 호출 1회 상한을 두어 갱신 상한(MONITORING_AWS_REFRESH_TIMEOUT) 안에 끝나게 한다 ----

	static final Duration MONITORING_API_CALL_TIMEOUT = Duration.ofSeconds(5);

	static final Duration MONITORING_ATTEMPT_TIMEOUT = Duration.ofSeconds(2);

	private static ClientOverrideConfiguration monitoringOverride() {
		return ClientOverrideConfiguration.builder()
			.apiCallTimeout(MONITORING_API_CALL_TIMEOUT)
			.apiCallAttemptTimeout(MONITORING_ATTEMPT_TIMEOUT)
			.build();
	}

	@Bean(destroyMethod = "close")
	CloudWatchClient cloudWatchClient(TetraProperties properties) {
		return CloudWatchClient.builder()
			.region(Region.of(properties.aws().region()))
			.overrideConfiguration(monitoringOverride())
			.build();
	}

	@Bean(destroyMethod = "close")
	ElasticLoadBalancingV2Client elasticLoadBalancingV2Client(TetraProperties properties) {
		return ElasticLoadBalancingV2Client.builder()
			.region(Region.of(properties.aws().region()))
			.overrideConfiguration(monitoringOverride())
			.build();
	}

	@Bean(destroyMethod = "close")
	RdsClient rdsClient(TetraProperties properties) {
		return RdsClient.builder()
			.region(Region.of(properties.aws().region()))
			.overrideConfiguration(monitoringOverride())
			.build();
	}

	@Bean(destroyMethod = "close")
	EcsClient ecsClient(TetraProperties properties) {
		return EcsClient.builder()
			.region(Region.of(properties.aws().region()))
			.overrideConfiguration(monitoringOverride())
			.build();
	}

}
