package com.tetra.ops.report;

/**
 * 리포트 분류(tenant_report_meta · operator_report_meta.category에 이름 그대로). 테넌트 리포트는 zip 하나(summary.pdf + 표마다 CSV),
 * 운영자 감사 리포트는 CSV 하나(11-02 결정). 키는 {@link ReportKeys}.
 */
public enum ReportCategory {

	/** 테넌트 · 이벤트 하나의 결과 리포트(zip) */
	EVENT_RESULT(true),

	/** 테넌트 · 월간 리포트(zip) */
	MONTHLY(true),

	/** 운영자 · 월간 감사 리포트(CSV) */
	OPERATOR_AUDIT(false);

	private final boolean tenant;

	ReportCategory(boolean tenant) {
		this.tenant = tenant;
	}

	/** 테넌트 리포트(테넌트 버킷 · tenant_report_meta)면 true, 운영자 리포트면 false */
	public boolean tenant() {
		return tenant;
	}

}
