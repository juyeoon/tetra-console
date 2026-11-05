package com.tetra.ops.report.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.Optional;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.tetra.ops.common.DbLock;
import com.tetra.ops.common.error.ApiException;
import com.tetra.ops.common.error.ErrorCode;
import com.tetra.ops.config.TetraProperties;

/**
 * 감사 버킷 이름: 설정(REPORT_AUDIT_BUCKET)이 있으면 그것, 없으면 운영자 리포트 버킷 이름의 -operator-logs- → -audit-. 규칙 밖 이름이면
 * 기동 때 WARN 한 줄, 생성 요청은 409 AUDIT_BUCKET_NOT_CONFIGURED(잠금 · S3 없이).
 */
@ExtendWith(OutputCaptureExtension.class)
class OperatorAuditBucketsTest {

	@Test
	void settingWinsOverDerivedName() {
		assertThat(OperatorAuditBuckets.resolve(" my-audit-bucket ", "tetra-dev-operator-logs-000000000000"))
			.contains("my-audit-bucket");
	}

	@Test
	void derivesFromOperatorBucketWhenSettingIsEmpty() {
		assertThat(OperatorAuditBuckets.resolve("", "tetra-dev-operator-logs-000000000000")).contains("tetra-dev-audit-000000000000");
		assertThat(OperatorAuditBuckets.resolve(null, "tetra-dev-operator-logs-000000000000"))
			.contains("tetra-dev-audit-000000000000");
		// 기본 이름 규칙 tetra-operator-logs-{env}
		assertThat(OperatorAuditBuckets.resolve("  ", "tetra-operator-logs-test")).contains("tetra-audit-test");
	}

	@Test
	void outOfRuleNameIsEmpty() {
		assertThat(OperatorAuditBuckets.resolve("", "company-reports")).isEmpty();
		assertThat(OperatorAuditBuckets.resolve("", "tetra-operator-logs")).isEmpty(); // 뒤에 - 없음
	}

	@Test
	void outOfRuleNameWarnsOnceAtStartupAndGenerateIs409WithoutLock(CapturedOutput output) {
		var report = new TetraProperties.Report("company-reports", new TetraProperties.MetaCleanup(true),
				new TetraProperties.EventResult(true, 3), "");
		var properties = new TetraProperties("dev", null, null, null, null, null, null, null, null, null, null, null, report,
				null, null, null, "tetraops.cloud");

		new OperatorAuditReportGenerator(mock(DataSource.class), null, null, null, null, properties);

		assertThat(output).containsOnlyOnce("운영자 접속기록 리포트: 감사 버킷 이름을 정할 수 없습니다")
			.contains("AUDIT_BUCKET_NOT_CONFIGURED")
			.doesNotContain("company-reports");

		DbLock lock = mock(DbLock.class);
		var generator = new OperatorAuditReportGenerator(lock, null, null, null, null, Optional.empty());
		assertThatThrownBy(() -> generator.generate(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 5)))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.code()).isEqualTo(ErrorCode.AUDIT_BUCKET_NOT_CONFIGURED));
		verify(lock, never()).runIfAcquired(any());
	}

	@Test
	void derivableNameDoesNotWarn(CapturedOutput output) {
		var report = new TetraProperties.Report("tetra-dev-operator-logs-000000000000", new TetraProperties.MetaCleanup(true),
				new TetraProperties.EventResult(true, 3), "");
		var properties = new TetraProperties("dev", null, null, null, null, null, null, null, null, null, null, null, report,
				null, null, null, "tetraops.cloud");

		new OperatorAuditReportGenerator(mock(DataSource.class), null, null, null, null, properties);

		assertThat(output).doesNotContain("감사 버킷 이름을 정할 수 없습니다");
	}

}
