package com.tetra.ops.report.event;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.tetra.ops.common.DbLock;
import com.tetra.ops.common.time.Kst;
import com.tetra.ops.config.TetraProperties;
import com.tetra.ops.domain.ProvisioningStatus;
import com.tetra.ops.domain.platform.Tenant;
import com.tetra.ops.domain.platform.TenantRepository;
import com.tetra.ops.report.event.EventResultReportService.Outcome;
import com.tetra.ops.tenancy.TenantRouter;
import com.tetra.ops.tenancy.TenantRouter.RoutedEvent;

/**
 * 이벤트 결과 리포트를 만드는 때(11-03 결정). 둘 다 같은 {@link DbLock}(GET_LOCK tetra:report-event-result) 안에서만 만든다 —
 * 리포트 저장은 확인 뒤 넣기라 한 번에 한 스레드여야 메타 · 알림이 한 번이다.
 * <ol>
 * <li>WF4 성공 리스너가 발급 이력 저장(커밋) 뒤 {@link #afterHistorySaved}. 잠금을 바로 못 잡으면 건너뛰고, 실패해도 예외를 밖으로 던지지
 * 않는다(WARN 한 줄 — 그 WF4 메시지는 정상 처리, 재전달 · DLQ로 가지 않음). 빠진 리포트는 ②가 만든다</li>
 * <li>10분마다 보충({@link #run}): READY 테넌트 스키마마다 발급 이력 저장 시각이 최근 N일(tetra.report.event-result.backfill-days,
 * 기본 3) 안이고 아직 리포트 메타가 없는 이벤트. 기능 이전 이벤트는 기간 밖이라 자동으로 만들지 않는다. 한 이벤트 · 테넌트가 실패해도
 * WARN 한 줄 뒤 다음으로, 다음 회차에 다시 한다. 만든 것도 실패도 없으면 로그를 남기지 않는다</li>
 * </ol>
 * 스위치: tetra.report.event-result.enabled(REPORT_EVENT_RESULT_ENABLED, 기본 true)가 false면 이 빈이 없다 — 리스너도 시도하지 않는다
 * (테스트 프로필: 테스트가 잠금 목으로 직접 만든다). 운영자 수동 생성({@link EventResultReportGenerator}, POST /reports/generate)은
 * 스위치와 상관없이 같은 이름의 잠금을 잡고 만든다.
 */
@Component
@ConditionalOnProperty(prefix = "tetra.report.event-result", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EventResultReportJob {

	private static final Logger log = LoggerFactory.getLogger(EventResultReportJob.class);

	static final String LOCK_NAME = "tetra:report-event-result";

	private final DbLock lock;

	private final TenantRepository tenants;

	private final TenantRouter router;

	private final EventResultReportReader reader;

	private final EventResultReportService reports;

	private final int backfillDays;

	private final Clock clock;

	@Autowired
	EventResultReportJob(DataSource dataSource, TenantRepository tenants, TenantRouter router,
			EventResultReportReader reader, EventResultReportService reports, TetraProperties properties, Clock clock) {
		this(new DbLock(dataSource, LOCK_NAME), tenants, router, reader, reports,
				properties.report().eventResult().backfillDays(), clock);
	}

	/** 테스트용: 잠금 · 보충 일수 · 시계를 직접 준다 */
	EventResultReportJob(DbLock lock, TenantRepository tenants, TenantRouter router, EventResultReportReader reader,
			EventResultReportService reports, int backfillDays, Clock clock) {
		this.lock = lock;
		this.tenants = tenants;
		this.router = router;
		this.reader = reader;
		this.reports = reports;
		this.backfillDays = backfillDays;
		this.clock = clock;
	}

	/**
	 * WF4 성공 리스너: 발급 이력 저장이 커밋된 뒤(트랜잭션 밖) 한 번 시도한다. 예외를 던지지 않는다 — 잠금을 못 잡았거나 실패하면 보충이 만든다.
	 * 이미 리포트가 있으면(재전달) 파일만 새 값으로 덮어쓰고 메타 · 알림은 그대로다.
	 */
	public void afterHistorySaved(RoutedEvent routed) {
		try {
			boolean ran = lock.runIfAcquired(() -> {
				if (reports.create(routed.tenant(), routed.eventId()).outcome() == Outcome.NOT_READY) {
					log.info("이벤트 결과 리포트: 결과가 없어 만들지 않습니다(발급 이력 저장 전 · 종료 실패): eventId={}", routed.eventId());
				}
			});
			if (!ran) {
				log.info("이벤트 결과 리포트: 다른 작업이 만드는 중이라 건너뜁니다(10분 보충이 만듭니다): eventId={}", routed.eventId());
			}
		}
		catch (RuntimeException ex) {
			log.warn("이벤트 결과 리포트를 만들지 못했습니다, 10분 보충이 다시 만듭니다: tenantId={}, eventId={}", routed.tenantId(),
					routed.eventId(), ex);
		}
	}

	@Scheduled(initialDelay = 1, fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
	void scheduled() {
		run();
	}

	/** 보충 한 회차. 잠금을 못 잡으면(다른 태스크 · 리스너가 만드는 중) 아무것도 하지 않는다. 예외는 여기서 끝낸다(다음 회차는 그대로 돈다) */
	void run() {
		try {
			lock.runIfAcquired(this::backfill);
		}
		catch (RuntimeException ex) {
			log.error("이벤트 결과 리포트 보충 중 오류, 다음 회차에 다시 시도합니다", ex);
		}
	}

	private void backfill() {
		LocalDateTime since = Kst.now(clock).minusDays(backfillDays);
		int created = 0;
		int failed = 0;
		for (Tenant tenant : tenants.findAllByProvisioningStatusOrderByTenantId(ProvisioningStatus.READY)) {
			List<Long> eventIds;
			try {
				eventIds = router.inTenant(tenant, () -> reader.missingReports(since));
			}
			catch (RuntimeException ex) {
				failed++;
				log.warn("이벤트 결과 리포트 보충: 테넌트 {} 대상을 읽지 못해 다음 회차에 다시 합니다: {}", tenant.getTenantId(),
						ex.getClass().getSimpleName());
				continue;
			}
			for (Long eventId : eventIds) {
				try {
					if (reports.create(tenant, eventId).outcome() == Outcome.CREATED) {
						created++;
					}
				}
				catch (RuntimeException ex) {
					failed++;
					log.warn("이벤트 결과 리포트 보충: tenantId={}, eventId={} 실패, 다음 회차에 다시 합니다: {}: {}",
							tenant.getTenantId(), eventId, ex.getClass().getSimpleName(), ex.getMessage());
				}
			}
		}
		if (created > 0 || failed > 0) {
			log.info("이벤트 결과 리포트 보충: 발급 이력 저장 {} 이후 — 만듦 {}건, 실패 {}건", since, created, failed);
		}
	}

}
