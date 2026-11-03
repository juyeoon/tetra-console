package com.tetra.ops.domain.tenant;

import java.time.LocalDateTime;

import com.tetra.ops.tenancy.TenantScopedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Tetra Portal 리포트(UI-TP-12). 리포트 작업(ReportStore)이 넣고 Portal이 읽는다. file_path는 테넌트 로그 버킷 안의 키(같은 리포트 =
 * 같은 키라 다시 만들 때 이 값으로 이미 있는지 본다). 2년(731일) 지난 행은 ReportMetaCleanupJob이 지운다.
 */
@Entity
@Table(name = "tenant_report_meta")
public class TenantReportMeta extends TenantScopedEntity {

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

	protected TenantReportMeta() {
	}

	public static TenantReportMeta create(String tenantId, String name, LocalDateTime periodStart,
			LocalDateTime periodEnd, String category, LocalDateTime createdAt, String filePath) {
		TenantReportMeta meta = new TenantReportMeta();
		meta.tenantId = tenantId;
		meta.name = name;
		meta.periodStart = periodStart;
		meta.periodEnd = periodEnd;
		meta.category = category;
		meta.createdAt = createdAt;
		meta.filePath = filePath;
		return meta;
	}

}
