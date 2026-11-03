package com.tetra.ops.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.tetra.ops.common.DbLock;
import com.tetra.ops.domain.platform.OperatorReportMetaRepository;
import com.tetra.ops.domain.platform.TenantRepository;
import com.tetra.ops.domain.tenant.TenantReportMetaRepository;
import com.tetra.ops.support.IntegrationTest;
import com.tetra.ops.tenancy.TenantRouter;

/**
 * 리포트 메타 보관 2년: created_at이 지금 − 731일보다 이른 행만 지운다(같으면 남김). READY 테넌트 스키마마다 + 공용.
 * 잠금은 목(통합 테스트 풀이 1개), 시계는 2026-11-03 04:30 KST 고정. 스케줄은 테스트 프로필에서 꺼져 있어 run()을 직접 부른다.
 */
class ReportMetaCleanupJobTest extends IntegrationTest {

	/** 2026-11-03 04:30:00 KST */
	static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T19:30:00Z"), ZoneId.of("Asia/Seoul"));

	/** 지금 − 731일 */
	static final LocalDateTime CUTOFF = LocalDateTime.of(2024, 11, 2, 4, 30);

	static final String C = "cccccccccccc";

	@Autowired
	TenantRepository tenants;

	@Autowired
	TenantRouter router;

	@Autowired
	TenantReportMetaRepository tenantReports;

	@Autowired
	OperatorReportMetaRepository operatorReports;

	private final DbLock lock = mock(DbLock.class);

	private ReportMetaCleanupJob job;

	@BeforeEach
	void setUp() {
		when(lock.runIfAcquired(any())).thenAnswer(inv -> {
			inv.<Runnable>getArgument(0).run();
			return true;
		});
		job = new ReportMetaCleanupJob(lock, tenants, router, tenantReports, operatorReports, CLOCK);
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.tenant(B, "B사", "INACTIVE", "READY");
		// 스키마가 없는 테넌트(환경 준비 중)는 건너뛴다
		data.tenant(C, "C사", "INACTIVE", "PROVISIONING");
	}

	private void tenantMeta(String tenantId, String key, LocalDateTime createdAt) {
		data.jdbc().update("""
				INSERT INTO t_%s.tenant_report_meta (tenant_id, name, period_start, period_end, category, created_at, file_path)
				VALUES (?, '리포트', '2024-10-01 00:00:00', '2024-10-31 23:59:59', 'MONTHLY', ?, ?)""".formatted(tenantId),
				tenantId, createdAt, key);
	}

	private void operatorMeta(String key, LocalDateTime createdAt) {
		data.jdbc().update("""
				INSERT INTO tetra_platform.operator_report_meta (name, period_start, period_end, category, created_at, file_path)
				VALUES ('감사', '2024-10-01 00:00:00', '2024-10-31 23:59:59', 'OPERATOR_AUDIT', ?, ?)""", createdAt, key);
	}

	private List<String> tenantKeys(String tenantId) {
		return data.jdbc()
			.queryForList("SELECT file_path FROM t_" + tenantId + ".tenant_report_meta ORDER BY file_path", String.class);
	}

	private List<String> operatorKeys() {
		return data.jdbc()
			.queryForList("SELECT file_path FROM tetra_platform.operator_report_meta ORDER BY file_path", String.class);
	}

	@Test
	void deletesOnlyRowsOlderThan731Days() {
		assertThat(LocalDateTime.now(CLOCK).minusDays(ReportMetaCleanupJob.RETENTION_DAYS)).isEqualTo(CUTOFF);
		tenantMeta(A, "a-1-older", CUTOFF.minusSeconds(1));
		tenantMeta(A, "a-2-boundary", CUTOFF);
		tenantMeta(A, "a-3-newer", CUTOFF.plusDays(1));
		tenantMeta(B, "b-1-old", LocalDateTime.of(2023, 1, 1, 0, 0));
		tenantMeta(B, "b-2-recent", LocalDateTime.of(2026, 10, 31, 0, 0));
		operatorMeta("op-1-older", CUTOFF.minusSeconds(1));
		operatorMeta("op-2-boundary", CUTOFF);

		job.run();

		assertThat(tenantKeys(A)).containsExactly("a-2-boundary", "a-3-newer");
		assertThat(tenantKeys(B)).containsExactly("b-2-recent");
		assertThat(operatorKeys()).containsExactly("op-2-boundary");
		// 다음 날 다시 돌아도 같은 결과(지울 것 없음)
		job.run();
		assertThat(tenantKeys(A)).containsExactly("a-2-boundary", "a-3-newer");
	}

	/** 잠금을 못 잡으면(다른 태스크가 도는 중) 아무것도 지우지 않는다 */
	@Test
	void skipsWhenLockIsHeldElsewhere() {
		doReturn(false).when(lock).runIfAcquired(any());
		tenantMeta(A, "a-old", CUTOFF.minusDays(10));
		operatorMeta("op-old", CUTOFF.minusDays(10));

		job.run();

		assertThat(tenantKeys(A)).containsExactly("a-old");
		assertThat(operatorKeys()).containsExactly("op-old");
	}

	/** 스위치: tetra.report.meta-cleanup.enabled가 false면 빈이 없고, 없거나 true면 있다(만들기만 해서는 DB에 붙지 않음) */
	@Test
	void switchControlsTheBean() {
		DataSource dataSource = mock(DataSource.class);
		ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(ReportMetaCleanupJob.class)
			.withBean(DataSource.class, () -> dataSource)
			.withBean(TenantRepository.class, () -> mock(TenantRepository.class))
			.withBean(TenantRouter.class, () -> mock(TenantRouter.class))
			.withBean(TenantReportMetaRepository.class, () -> mock(TenantReportMetaRepository.class))
			.withBean(OperatorReportMetaRepository.class, () -> mock(OperatorReportMetaRepository.class))
			.withBean(Clock.class, Clock::systemUTC);

		runner.run(context -> assertThat(context).hasSingleBean(ReportMetaCleanupJob.class));
		runner.withPropertyValues("tetra.report.meta-cleanup.enabled=true")
			.run(context -> assertThat(context).hasSingleBean(ReportMetaCleanupJob.class));
		runner.withPropertyValues("tetra.report.meta-cleanup.enabled=false")
			.run(context -> assertThat(context).doesNotHaveBean(ReportMetaCleanupJob.class));
		verifyNoInteractions(dataSource);
	}

}
