package com.tetra.ops.monitoring;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.tetra.ops.config.TetraProperties;

import jakarta.annotation.PreDestroy;

/**
 * 알림센터 SYSTEM 행의 현재 DLQ 메시지 수(SQS ApproximateNumberOfMessages, 1분 주기 확인과 같은 값).
 * - 큐마다 MONITORING_AWS_CACHE_TTL(기본 60초) 동안 마지막 조회를 그대로 쓴다. 실패도 같은 시간 동안 다시 부르지 않는다
 *   (AWS 장애 때 목록 요청마다 기다리지 않게). 같은 큐를 동시에 물으면 진행 중인 조회 하나를 같이 기다린다.
 * - 여러 큐는 동시에 조회하고, 한 번에 MONITORING_AWS_REFRESH_TIMEOUT(기본 10초)까지만 기다린다.
 * - 실패 · 상한 초과 · MONITORING_DLQ_NAMES에 없는 큐는 결과에 넣지 않는다(→ 현재 상태 null). 마지막 성공 값은 지금 값이 아니라 쓰지 않는다.
 * - 실패 로그는 조회마다 WARN 한 줄(큐 이름 · 호출 이름 · 오류 코드만).
 */
@Component
public class DlqDepths {

	private static final Logger log = LoggerFactory.getLogger(DlqDepths.class);

	/** 조회 하나와 시작 시각(System.nanoTime) */
	private record Load(CompletableFuture<Integer> messages, long startedNanos) {
	}

	private final AwsMonitoringGateway aws;

	private final Set<String> watched;

	private final long ttlNanos;

	private final Duration timeout;

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	private final Map<String, Load> loads = new ConcurrentHashMap<>();

	@Autowired
	public DlqDepths(AwsMonitoringGateway aws, TetraProperties properties) {
		this(aws, properties.monitoring().aws().dlqNameList(), properties.monitoring().aws().cacheTtl(),
				properties.monitoring().aws().refreshTimeout());
	}

	DlqDepths(AwsMonitoringGateway aws, List<String> watched, Duration ttl, Duration timeout) {
		this.aws = aws;
		this.watched = Set.copyOf(watched);
		this.ttlNanos = ttl.toNanos();
		this.timeout = timeout;
	}

	@PreDestroy
	void close() {
		executor.shutdownNow();
	}

	/** 큐 이름 → 메시지 수. 못 읽었거나 보지 않는 큐는 빠진다 */
	public Map<String, Integer> depths(Collection<String> queueNames) {
		Map<String, CompletableFuture<Integer>> pending = new LinkedHashMap<>();
		queueNames.stream().filter(watched::contains).distinct().forEach(name -> pending.put(name, load(name)));
		Map<String, Integer> depths = new HashMap<>();
		pending.forEach((name, messages) -> {
			try {
				depths.put(name, messages.join());
			}
			catch (CompletionException ex) {
				// 실패는 조회를 시작한 쪽이 한 번 남겼다
			}
		});
		return depths;
	}

	private CompletableFuture<Integer> load(String name) {
		long now = System.nanoTime();
		return loads.compute(name, (key, last) -> last != null && now - last.startedNanos() < ttlNanos ? last
				: new Load(start(key), now)).messages();
	}

	private CompletableFuture<Integer> start(String name) {
		// 로그를 남긴 뒤에 끝나는 future를 준다(기다린 쪽이 로그보다 먼저 깨지 않게)
		return CompletableFuture.supplyAsync(() -> aws.queueDepth(name), executor)
			.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
			.whenComplete((value, error) -> {
				if (error != null) {
					log.warn("알림센터: DLQ {} 메시지 수를 읽지 못해 현재 상태를 비워 둡니다({})", name, reason(error));
				}
			});
	}

	private String reason(Throwable error) {
		Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
		if (cause instanceof TimeoutException) {
			return timeout.toMillis() + "ms 초과";
		}
		return cause instanceof AwsMonitoringException ? cause.getMessage() : cause.getClass().getSimpleName();
	}

}
