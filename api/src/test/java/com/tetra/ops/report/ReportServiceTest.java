package com.tetra.ops.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** file_path → S3 키, 다운로드 파일 확장자. */
class ReportServiceTest {

	static final String BUCKET = "tetra-operator-logs-dev";

	@Test
	void keyOnlyOrS3UriOfOperatorBucket() {
		assertThat(ReportService.keyOf("reports/1.pdf", BUCKET, 1L)).isEqualTo("reports/1.pdf");
		assertThat(ReportService.keyOf(" s3://tetra-operator-logs-dev/reports/1.pdf ", BUCKET, 1L)).isEqualTo("reports/1.pdf");
	}

	@Test
	void otherBucketOrBadPathIsDataError() {
		for (String bad : new String[] { "s3://tetra-audit-logs-dev-k7f2m9qx4b1t/reports/1.pdf", "s3://tetra-operator-logs-dev",
				"s3://tetra-operator-logs-dev/", "/reports/1.pdf", "", "  ", null }) {
			assertThatThrownBy(() -> ReportService.keyOf(bad, BUCKET, 7L)).as(String.valueOf(bad))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("reportId=7");
		}
	}

	@Test
	void extensionIsAsciiLowercaseOrEmpty() {
		assertThat(ReportService.extension("reports/1.PDF")).isEqualTo(".pdf");
		assertThat(ReportService.extension("reports/2026.10/report")).isEmpty();
		assertThat(ReportService.extension("reports/리포트.한글")).isEmpty();
		assertThat(ReportService.extension("reports/a.toolongextension")).isEmpty();
		assertThat(ReportService.extension("reports/a.")).isEmpty();
	}

}
