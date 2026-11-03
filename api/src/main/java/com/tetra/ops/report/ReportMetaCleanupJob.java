package com.tetra.ops.report;

import java.time.Clock;
import java.time.LocalDateTime;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.tetra.ops.common.DbLock;
import com.tetra.ops.common.time.Kst;
import com.tetra.ops.domain.ProvisioningStatus;
import com.tetra.ops.domain.platform.OperatorReportMetaRepository;
import com.tetra.ops.domain.platform.Tenant;
import com.tetra.ops.domain.platform.TenantRepository;
import com.tetra.ops.domain.tenant.TenantReportMetaRepository;
import com.tetra.ops.tenancy.TenantRouter;

/**
 * 리포트 메타 보관 2년(11-02 결정): 하루 1번(04:30 KST) created_at이 731일보다 오래된 tenant_report_meta(READY 테넌트 스키마마다)와
 * operator_report_meta 행을 지운다. S3 객체는 건드리지 않는다 — 버킷 수명 주기(reports/ 731일)가 지운다.
 * 여러 태스크면 {@link DbLock}(GET_LOCK tetra:report-meta-cleanup)을 잡은 하나만 돈다. 테넌트 하나가 실패하면 WARN 한 줄을 남기고
 * 다음 테넌트로 간다(내일 다시). 지운 행이 없고 오류도 없으면 로그를 남기지 않는다.
 * 스위치: tetra.report.meta-cleanup.enabled(REPORT_META_CLEANUP_ENABLED, 기본 true)가 false면 이 빈이 없다(테스트 프로필 —
 * run()을 직접 부른다). 다른 작업과 스위치 · 잠금이 따로다(SchedulingConfig).
 */
@Component
@ConditionalOnProperty(prefix = "tetra.report.meta-cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
class ReportMetaCleanupJob {

	private static final Logger log = LoggerFactory.getLogger(ReportMetaCleanupJob.class);

	static final String LOCK_NAME = "tetra:report-meta-cleanup";

	/** 보관 일수 — 버킷 수명 주기(reports/ 731일)와 같다. created_at이 지금 − 731일보다 이른 행을 지운다(같으면 남김) */
	static final int RETENTION_DAYS = 731;

	/** 매일 04:30 KST */
	static final String CRON = "0 30 4 * * *";

	private final DbLock lock;

	private final TenantRepository tenants;

	private final TenantRouter router;

	private final TenantReportMetaRepository tenantReports;

	private final OperatorReportMetaRepository operatorReports;

	private final Clock clock;

	@Autowired
	ReportMetaCleanupJob(DataSource dataSource, TenantRepository tenants, TenantRouter router,
			TenantReportMetaRepository tenantReports, OperatorReportMetaRepository operatorReports, Clock clock) {
		this(new DbLock(dataSource, LOCK_NAME), tenants, router, tenantReports, operatorReports, clock);
	}

	/** 테스트용: 잠금 · 시계를 직접 준다 */
	ReportMetaCleanupJob(DbLock lock, TenantRepository tenants, TenantRouter router,
			TenantReportMetaRepository tenantReports, OperatorReportMetaRepository operatorReports, Clock clock) {
		this.lock = lock;
		this.tenants = tenants;
		this.router = router;
		this.tenantReports = tenantReports;
		this.operatorReports = operatorReports;
		this.clock = clock;
	}

	@Scheduled(cron = CRON, zone = "Asia/Seoul")
	void scheduled() {
		run();
	}

	/** 한 회차. 잠금을 못 잡으면(다른 태스크가 도는 중) 아무것도 하지 않는다. 예외는 여기서 끝낸다(다음 날 다시) */
	void run() {
		try {
			lock.runIfAcquired(this::round);
		}
		catch (RuntimeException ex) {
			log.error("리포트 메타 정리 중 오류, 다음 회차에 다시 시도합니다", ex);
		}
	}

	private void round() {
		LocalDateTime cutoff = Kst.now(clock).minusDays(RETENTION_DAYS);
		int tenantRows = 0;
		int failed = 0;
		for (Tenant tenant : tenants.findAllByProvisioningStatusOrderByTenantId(ProvisioningStatus.READY)) {
			try {
				tenantRows += router.inTenant(tenant,
						() -> tenantReports.deleteCreatedBefore(tenant.getTenantId(), cutoff));
			}
			catch (RuntimeException ex) {
				failed++;
				log.warn("리포트 메타 정리: 테넌트 {} 실패, 다음 회차에 다시 합니다: {}", tenant.getTenantId(),
						ex.getClass().getSimpleName());
			}
		}
		int operatorRows = operatorReports.deleteCreatedBefore(cutoff);
		if (tenantRows > 0 || operatorRows > 0 || failed > 0) {
			log.info("리포트 메타 정리: created_at < {} 삭제 — 테넌트 {}행 · 운영자 {}행, 실패 테넌트 {}", cutoff, tenantRows,
					operatorRows, failed);
		}
	}

}
