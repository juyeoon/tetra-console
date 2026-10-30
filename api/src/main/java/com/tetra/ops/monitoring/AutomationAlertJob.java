package com.tetra.ops.monitoring;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.tetra.ops.common.DbErrors;
import com.tetra.ops.common.time.Kst;
import com.tetra.ops.config.TetraProperties;
import com.tetra.ops.domain.platform.EventRegistry;
import com.tetra.ops.domain.platform.EventRegistryRepository;
import com.tetra.ops.domain.platform.OperatorNotificationRepository;
import com.tetra.ops.domain.platform.Tenant;
import com.tetra.ops.domain.platform.TenantRepository;
import com.tetra.ops.monitoring.TrafficRules.Delay;
import com.tetra.ops.monitoring.TrafficRules.DelayTarget;
import com.tetra.ops.notification.NotificationService;
import com.tetra.ops.tenancy.TenantIds;
import com.tetra.ops.tenancy.TenantRouter;

/**
 * 1분 주기 자동화 확인(D5). 상태 전이로는 잡히지 않는 문제를 운영자 알림으로 남긴다.
 * - DLQ: MONITORING_DLQ_NAMES 큐마다 SQS ApproximateNumberOfMessages가 0보다 크면 SYSTEM/URGENT(같은 큐 · 같은 날(KST) 1건).
 *   종류는 이름 접미사({@link DlqQueue}), 맞지 않는 이름은 기동 때 WARN 한 줄만 남기고 보지 않는다.
 * - 단계 지연 5종({@link TrafficRules#delays}, OPS-05 자동화 칸과 같은 규칙): DELAY/URGENT · WARNING(이벤트 · 단계마다 1건).
 * 여러 태스크면 {@link AlertLock}(GET_LOCK)을 잡은 하나만 돈다. 중복 방지 키(dedupe_key)가 2중 안전장치 — 이미 있으면 남기지 않고,
 * 동시에 넣어 1062가 나면 그 알림만 건너뛴다(D4 ①: 알림 저장은 NotificationService의 자체 트랜잭션, 1062는 그 밖에서 잡는다).
 * 조회: registry 한 번(상태 · 호스팅 시작 시각으로 거른 후보) + 종료 단계를 볼 이벤트가 있을 때만 그 테넌트들 이름과 테넌트마다 event 한 번.
 * Step Functions(WF2-2 · WF4 실행 여부)는 판정 시각에 걸린 이벤트가 있고 아직 알림을 남기지 않았을 때만 EventExecutionService 캐시로 본다.
 * AWS 실패 · 시간 상한 초과는 그 부분만 이번 회차에서 건너뛰고 WARN 한 줄. 정상 회차는 로그를 남기지 않는다(알림을 남겼거나 오류일 때만).
 */
@Component
class AutomationAlertJob {

	private static final Logger log = LoggerFactory.getLogger(AutomationAlertJob.class);

	static final String DEDUPE_INDEX = "uk_operator_notif_dedupe";

	/**
	 * 한 회차의 AWS 호출 시간 상한. 넘으면 남은 AWS 확인은 다음 회차로 넘긴다. 호출 하나는 5초(시도 2초), 실행 목록 갱신은 10초 상한이라
	 * 마지막 호출이 상한 직전에 시작해도 회차가 1분을 넘지 않는다.
	 */
	static final Duration AWS_BUDGET = Duration.ofSeconds(40);

	private final AlertLock lock;

	private final AwsMonitoringGateway aws;

	private final EventExecutionService executions;

	private final EventRegistryRepository registries;

	private final TenantRepository tenants;

	private final TenantRouter router;

	private final TrafficEventReader reader;

	private final OperatorNotificationRepository notificationRows;

	private final NotificationService notifications;

	private final List<DlqQueue> queues;

	private final Duration delayWindow;

	private final Clock clock;

	@Autowired
	AutomationAlertJob(AlertLock lock, AwsMonitoringGateway aws, EventExecutionService executions,
			EventRegistryRepository registries, TenantRepository tenants, TenantRouter router, TrafficEventReader reader,
			OperatorNotificationRepository notificationRows, NotificationService notifications, TetraProperties properties,
			Clock clock) {
		this(lock, aws, executions, registries, tenants, router, reader, notificationRows, notifications,
				properties.monitoring().aws().dlqNameList(), properties.alerts().delayWindow(), clock);
	}

	/** 테스트용: 큐 이름 · 지연 기간 · 시계를 직접 준다 */
	AutomationAlertJob(AlertLock lock, AwsMonitoringGateway aws, EventExecutionService executions,
			EventRegistryRepository registries, TenantRepository tenants, TenantRouter router, TrafficEventReader reader,
			OperatorNotificationRepository notificationRows, NotificationService notifications, List<String> dlqNames,
			Duration delayWindow, Clock clock) {
		this.lock = lock;
		this.aws = aws;
		this.executions = executions;
		this.registries = registries;
		this.tenants = tenants;
		this.router = router;
		this.reader = reader;
		this.notificationRows = notificationRows;
		this.notifications = notifications;
		this.delayWindow = delayWindow;
		this.clock = clock;
		List<DlqQueue> classified = new ArrayList<>();
		for (String name : dlqNames) {
			Optional<DlqQueue> queue = DlqQueue.classify(name);
			if (queue.isPresent()) {
				classified.add(queue.get());
			}
			else {
				log.warn("MONITORING_DLQ_NAMES의 {}는 DLQ 이름 규칙(-result-dlq · -wf-events-dlq · -scheduler-dlq)에 맞지 않아 "
						+ "자동화 확인 알림을 남기지 않습니다", name);
			}
		}
		this.queues = List.copyOf(classified);
	}

	@Scheduled(initialDelayString = "${tetra.alerts.interval:PT1M}", fixedDelayString = "${tetra.alerts.interval:PT1M}")
	void scheduled() {
		run();
	}

	/** 한 회차. 잠금을 못 잡으면(다른 태스크가 도는 중) 아무것도 하지 않는다. 예외는 여기서 끝낸다(다음 회차는 그대로 돈다) */
	void run() {
		try {
			lock.runIfAcquired(this::check);
		}
		catch (RuntimeException ex) {
			log.error("자동화 확인 중 오류, 이번 회차를 건너뜁니다", ex);
		}
	}

	private void check() {
		long deadline = System.nanoTime() + AWS_BUDGET.toNanos();
		LocalDateTime now = Kst.now(clock);
		checkDlq(now.toLocalDate(), deadline);
		checkDelays(now, deadline);
	}

	// ---------------------------------------------------------------- DLQ

	private void checkDlq(LocalDate today, long deadline) {
		for (DlqQueue queue : queues) {
			if (System.nanoTime() > deadline) {
				log.warn("자동화 확인: AWS 시간 상한({}초)을 넘어 남은 DLQ 확인을 다음 회차로 넘깁니다", AWS_BUDGET.toSeconds());
				return;
			}
			int messages;
			try {
				messages = aws.queueDepth(queue.name());
			}
			catch (AwsMonitoringException ex) {
				log.warn("자동화 확인: DLQ 메시지 수를 읽지 못해 이번 회차 DLQ 확인을 건너뜁니다({})", ex.getMessage());
				return;
			}
			if (messages > 0 && record(() -> notifications.dlqDetected(queue, messages, today))) {
				log.info("자동화 확인 알림: DLQ {} 메시지 {}건", queue.name(), messages);
			}
		}
	}

	// ---------------------------------------------------------------- 단계 지연

	private void checkDelays(LocalDateTime now, long deadline) {
		List<EventRegistry> rows = registries.findDelayCandidates(now);
		if (rows.isEmpty()) {
			return;
		}
		Map<Long, LocalDateTime> hostingEnds = hostingEnds(rows, now);
		List<DelayTarget> targets = rows.stream()
			.map(r -> new DelayTarget(r.getEventId(), r.getTenantId(), r.getProcessedAt(), r.getHostingStartAt(),
					r.getStartAt(), hostingEnds.get(r.getEventId()), r.getEventStatus(), r.getHostingStatus()))
			.toList();
		for (Delay d : TrafficRules.delays(targets, now, delayWindow, new ExecutionLookup(deadline))) {
			DelayTarget t = d.target();
			if (record(() -> notifications.stepDelayed(t.tenantId(), t.eventId(), d.step(), d.planned()))) {
				log.info("자동화 확인 알림: {}번 {} 지연({}분)", t.eventId(), d.step(), d.minutes());
			}
		}
	}

	/**
	 * 종료 단계를 볼 수 있는 이벤트(WF4가 도는 호스팅 상태, 시작 + 10분이 지남 — hosting_end_at은 시작보다 뒤)만 테넌트마다
	 * event.hosting_end_at을 한 번에 읽는다. READY가 아니거나 tenant_id 형식이 아닌 테넌트는 읽지 않는다(그 이벤트는 종료 2종을 보지 않음).
	 */
	private Map<Long, LocalDateTime> hostingEnds(List<EventRegistry> rows, LocalDateTime now) {
		LocalDateTime startedBefore = now.minus(TrafficRules.STEP_DELAY);
		Map<String, Set<Long>> byTenant = new LinkedHashMap<>();
		for (EventRegistry r : rows) {
			if (TrafficRules.endStepsApply(r.getHostingStatus()) && r.getStartAt().isBefore(startedBefore)
					&& TenantIds.isValid(r.getTenantId())) {
				byTenant.computeIfAbsent(r.getTenantId(), id -> new LinkedHashSet<>()).add(r.getEventId());
			}
		}
		Map<Long, LocalDateTime> ends = new HashMap<>();
		if (byTenant.isEmpty()) {
			return ends;
		}
		for (Tenant tenant : tenants.findAllById(byTenant.keySet())) {
			if (tenant.isReady()) {
				ends.putAll(router.inTenant(tenant, () -> reader.hostingEndAt(byTenant.get(tenant.getTenantId()))));
			}
		}
		return ends;
	}

	/**
	 * 준비 시작 · 종료 시작의 실행 여부. 이미 알림을 남긴 단계는 묻지 않는다(Step Functions를 부르지 않음).
	 * 조회 실패 · 시간 상한 초과면 이번 회차에 그 2종을 더 보지 않는다(WARN 한 줄).
	 */
	private final class ExecutionLookup implements TrafficRules.ExecutionCheck {

		private final long deadline;

		private boolean skipped;

		ExecutionLookup(long deadline) {
			this.deadline = deadline;
		}

		@Override
		public Boolean started(DelayStep step, DelayTarget target) {
			if (skipped || notificationRows.existsByDedupeKey(NotificationService.delayKey(target.eventId(), step))) {
				return null;
			}
			if (System.nanoTime() > deadline) {
				skipped = true;
				log.warn("자동화 확인: AWS 시간 상한({}초)을 넘어 준비 시작 · 종료 시작 지연은 다음 회차에 봅니다", AWS_BUDGET.toSeconds());
				return null;
			}
			try {
				return executions.startedFor(step, target);
			}
			catch (AwsMonitoringException ex) {
				skipped = true;
				log.warn("자동화 확인: Step Functions 실행을 읽지 못해 이번 회차 준비 시작 · 종료 시작 지연은 보지 않습니다({})",
						ex.getMessage());
				return null;
			}
		}

	}

	// ----------------------------------------------------------------

	/**
	 * 알림 저장(NotificationService의 자체 트랜잭션). 다른 태스크와 같은 키를 동시에 넣어 1062(uk_operator_notif_dedupe)가 나면
	 * 이미 남은 것이므로 false. 다른 무결성 오류는 그대로 던진다.
	 * @return 새로 남겼으면 true
	 */
	boolean record(BooleanSupplier save) {
		try {
			return save.getAsBoolean();
		}
		catch (DataIntegrityViolationException ex) {
			if (DbErrors.isDuplicateKey(ex, DEDUPE_INDEX)) {
				return false;
			}
			throw ex;
		}
	}

}
