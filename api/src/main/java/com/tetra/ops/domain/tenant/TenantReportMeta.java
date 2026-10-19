package com.tetra.ops.domain.tenant;

import java.time.LocalDateTime;

import com.tetra.ops.tenancy.TenantScopedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Tetra Portal 리포트. 이 앱은 쓰지 않아 매핑만 둔다. */
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

}
