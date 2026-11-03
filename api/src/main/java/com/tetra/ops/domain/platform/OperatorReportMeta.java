package com.tetra.ops.domain.platform;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * OPS-09 리포트 메타. 리포트 작업(ReportStore)이 넣고 OPS-09가 읽는다. file_path는 운영자 리포트 버킷 안의 키(같은 리포트 = 같은 키라
 * 다시 만들 때 이 값으로 이미 있는지 본다). 2년(731일) 지난 행은 ReportMetaCleanupJob이 지운다.
 */
@Entity
@Table(name = "operator_report_meta", catalog = "tetra_platform")
public class OperatorReportMeta {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "report_id", nullable = false)
	private Long reportId;

	@Column(name = "name", length = 100, nullable = false)
	private String name;

	@Column(name = "period_start", nullable = false)
	private LocalDateTime periodStart;

	@Column(name = "period_end", nullable = false)
	private LocalDateTime periodEnd;

	@Column(name = "category", length = 30, nullable = false)
	private String category;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "file_path", length = 1024, nullable = false)
	private String filePath;

	protected OperatorReportMeta() {
	}

	public static OperatorReportMeta create(String name, LocalDateTime periodStart, LocalDateTime periodEnd,
			String category, LocalDateTime createdAt, String filePath) {
		OperatorReportMeta meta = new OperatorReportMeta();
		meta.name = name;
		meta.periodStart = periodStart;
		meta.periodEnd = periodEnd;
		meta.category = category;
		meta.createdAt = createdAt;
		meta.filePath = filePath;
		return meta;
	}

	public Long getReportId() {
		return reportId;
	}

	public String getName() {
		return name;
	}

	public LocalDateTime getPeriodStart() {
		return periodStart;
	}

	public LocalDateTime getPeriodEnd() {
		return periodEnd;
	}

	public String getCategory() {
		return category;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public String getFilePath() {
		return filePath;
	}

}
