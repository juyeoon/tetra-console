package com.tetra.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.tetra.ops.listener.StepFunctionsResultHandler;
import com.tetra.ops.support.IntegrationTest;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * 실제 SQS 프로토콜(ElasticMQ)로 @SqsListener가 Boot 4.1 + Spring Cloud AWS 4.2에서 메시지를 받고,
 * 정상 처리면 삭제(ack)하고 예외면 ack하지 않아 재전달되는지 확인한다 (§8).
 */
@TestPropertySource(properties = "spring.cloud.aws.sqs.enabled=true")
class SqsListenerContainerTest extends IntegrationTest {

	static final GenericContainer<?> ELASTICMQ = new GenericContainer<>(
			DockerImageName.parse("softwaremill/elasticmq-native:1.7.1"))
		.withExposedPorts(9324);

	static SqsClient sqs;

	static String approveQueueUrl;

	static String tenantQueueUrl;

	@DynamicPropertySource
	static void sqsEndpoint(DynamicPropertyRegistry registry) {
		ELASTICMQ.start();
		String endpoint = "http://" + ELASTICMQ.getHost() + ":" + ELASTICMQ.getMappedPort(9324);
		sqs = SqsClient.builder()
			.endpointOverride(URI.create(endpoint))
			.region(Region.AP_NORTHEAST_2)
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
			.build();
		// 재전달을 빨리 보려고 가시성 제한 시간을 1초로 둔다
		Map<QueueAttributeName, String> attributes = Map.of(QueueAttributeName.VISIBILITY_TIMEOUT, "1");
		approveQueueUrl = sqs.createQueue(b -> b.queueName("approve-result-it").attributes(attributes)).queueUrl();
		tenantQueueUrl = sqs.createQueue(b -> b.queueName("tenant-result-it").attributes(attributes)).queueUrl();
		registry.add("spring.cloud.aws.sqs.endpoint", () -> endpoint);
		registry.add("tetra.sqs.approve-result-queue", () -> "approve-result-it");
		registry.add("tetra.sqs.tenant-result-queue", () -> "tenant-result-it");
	}

	@MockitoSpyBean
	StepFunctionsResultHandler handler;

	@Test
	void succeededMessageIsConsumedAndDeleted() {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(4101, A).approved("PENDING", "SCHEDULED", "sqs00001").insert();

		sqs.sendMessage(b -> b.queueUrl(approveQueueUrl)
			.messageBody(StepFunctionsResultHandlerTest.message("approve-4101", "SUCCEEDED", null, null)));

		await(() -> "DEPLOYED".equals(data.registry(4101).get("hosting_status")));
		assertThat(data.eventRow(A, 4101)).containsEntry("hosting_status", "DEPLOYED");
		await(() -> inFlightAndVisible() == 0);
	}

	@Test
	void malformedMessageIsDeleted() {
		sqs.sendMessage(b -> b.queueUrl(approveQueueUrl).messageBody("not json"));

		verify(handler, org.mockito.Mockito.timeout(10_000)).handleApproveResult(eq("not json"));
		await(() -> inFlightAndVisible() == 0);
	}

	@Test
	void failingMessageIsNotAcknowledgedAndIsRedelivered() {
		data.tenant(A, "A사", "ACTIVE", "READY");
		// event 행이 없어 처리 중 예외가 난다
		data.event(4102, A).approved("PENDING", "SCHEDULED", "sqs00002").registryOnly().insert();
		String body = StepFunctionsResultHandlerTest.message("approve-4102", "SUCCEEDED", null, null);

		sqs.sendMessage(b -> b.queueUrl(approveQueueUrl).messageBody(body));

		verify(handler, org.mockito.Mockito.timeout(15_000).atLeast(2)).handleApproveResult(eq(body));
		assertThat(data.registry(4102)).containsEntry("hosting_status", "PENDING");
		// 정리: 이미 처리된 상태로 맞춰 두면 다음 재전달에서 "이미 처리됨"으로 삭제된다
		// (재전달 중인 호출이 이 UPDATE 뒤에 registry를 보면 그 호출에서 바로 삭제될 수 있다)
		data.jdbc().update("UPDATE tetra_platform.event_registry SET hosting_status = 'DEPLOYED' WHERE event_id = 4102");
		await(() -> inFlightAndVisible() == 0);
	}

	/** 비밀번호 설정 메일 실패는 알림만 남기고 ack한다 — 가시성 제한 시간이 지나도 다시 처리되지 않는다 */
	@Test
	void tenantReadyWithMailFailureIsAcknowledgedWithoutRedelivery() throws InterruptedException {
		data.tenant(B, "B사", "INACTIVE", "PROVISIONING");
		when(mail.send(any())).thenReturn(false);
		String body = StepFunctionsResultHandlerTest.message("tenant-" + B, "SUCCEEDED", null, null);

		sqs.sendMessage(b -> b.queueUrl(tenantQueueUrl).messageBody(body));

		await(() -> "READY".equals(data.tenantRow(B).get("provisioning_status")));
		// ack하지 않았다면 메시지가 큐에 남아 1초마다 다시 보인다 — 큐가 비면 삭제(ack)된 것
		await(() -> inFlightAndVisible(tenantQueueUrl) == 0);
		Thread.sleep(2_500);
		assertThat(inFlightAndVisible(tenantQueueUrl)).isZero();
		// 첫 처리가 가시성 제한 시간(1초)보다 길면 한 번 더 받을 수 있지만, 이미 READY라 메일은 한 통뿐
		verify(handler, org.mockito.Mockito.atLeastOnce()).handleTenantResult(eq(body));
		verify(mail, times(1)).send(any());
		assertThat(data.count("SELECT COUNT(*) FROM tetra_platform.operator_notification WHERE tenant_id = ?"
				+ " AND category = 'MAIL' AND priority = 'URGENT'", B)).isEqualTo(1);
	}

	private static long inFlightAndVisible() {
		return inFlightAndVisible(approveQueueUrl);
	}

	private static long inFlightAndVisible(String queueUrl) {
		Map<QueueAttributeName, String> attrs = sqs
			.getQueueAttributes(b -> b.queueUrl(queueUrl)
				.attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
						QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE))
			.attributes();
		return Long.parseLong(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
				+ Long.parseLong(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE));
	}

	private static void await(BooleanSupplier condition) {
		long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			try {
				Thread.sleep(200);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(ex);
			}
		}
		throw new AssertionError("20초 안에 조건을 만족하지 못했습니다.");
	}

}
