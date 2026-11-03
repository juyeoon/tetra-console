package com.tetra.ops.report;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.tetra.ops.domain.platform.OperatorReportMeta;
import com.tetra.ops.domain.platform.OperatorReportMetaRepository;
import com.tetra.ops.domain.tenant.TenantReportMeta;
import com.tetra.ops.domain.tenant.TenantReportMetaRepository;
import com.tetra.ops.notification.NotificationService;
import com.tetra.ops.report.ReportStore.OperatorReport;
import com.tetra.ops.report.ReportStore.TenantReport;
import com.tetra.ops.tenancy.TenantContext;

/**
 * 리포트 저장의 DB 부분(ReportStore가 S3 Put 뒤에 부른다). 메서드 하나 = 트랜잭션 하나: 같은 키(file_path)의 메타가 있으면 아무것도 하지
 * 않고, 없으면 메타 INSERT + REPORT 알림 INSERT. 테넌트 메타는 TenantRouter.inTenant 안에서만 부른다.
 */
@Component
class ReportMetaWriter {

	private final TenantReportMetaRepository tenantReports;

	private final OperatorReportMetaRepository operatorReports;

	private final NotificationService notifications;

	ReportMetaWriter(TenantReportMetaRepository tenantReports, OperatorReportMetaRepository operatorReports,
			NotificationService notifications) {
		this.tenantReports = tenantReports;
		this.operatorReports = operatorReports;
		this.notifications = notifications;
	}

	/** @return 새로 남겼으면 true */
	@Transactional
	public boolean tenantMeta(TenantReport report, String name, LocalDateTime now) {
		String tenantId = TenantContext.current();
		if (tenantId == null) {
			throw new IllegalStateException("테넌트 리포트 메타는 테넌트 스키마 라우팅 안에서만 남길 수 있습니다.");
		}
		if (tenantReports.existsByTenantIdAndFilePath(tenantId, report.key())) {
			return false;
		}
		tenantReports.save(TenantReportMeta.create(tenantId, name, report.periodStart(), report.periodEnd(),
				report.category().name(), now, report.key()));
		notifications.tenantReportCreated(tenantId, report.category(), report.eventId(), report.periodStart());
		return true;
	}

	/** @return 새로 남겼으면 true */
	@Transactional
	public boolean operatorMeta(OperatorReport report, String name, LocalDateTime now) {
		if (operatorReports.existsByFilePath(report.key())) {
			return false;
		}
		operatorReports.save(OperatorReportMeta.create(name, report.periodStart(), report.periodEnd(),
				report.category().name(), now, report.key()));
		notifications.operatorReportCreated(report.category(), report.periodStart());
		return true;
	}

}
