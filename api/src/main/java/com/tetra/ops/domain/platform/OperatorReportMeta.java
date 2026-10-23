package com.tetra.ops.domain.platform;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** OPS-09 리포트 메타. 이 앱은 읽기만 한다(리포트 생성은 범위 밖). file_path는 운영자 로그 버킷의 S3 경로. */
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
