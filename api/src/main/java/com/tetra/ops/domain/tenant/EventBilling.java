package com.tetra.ops.domain.tenant;

import java.math.BigDecimal;

import com.tetra.ops.tenancy.TenantScopedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 이벤트 비용(1:1). 운영 콘솔 화면에서는 쓰지 않아 매핑만 둔다. */
@Entity
@Table(name = "event_billing")
public class EventBilling extends TenantScopedEntity {

	@Id
	@Column(name = "event_id", nullable = false, columnDefinition = "int unsigned")
	private Long eventId;

	@Column(name = "applied_hourly_price", precision = 15, scale = 2, nullable = false)
	private BigDecimal appliedHourlyPrice;

	@Column(name = "applied_hosting_hourly_price", precision = 15, scale = 2, nullable = false)
	private BigDecimal appliedHostingHourlyPrice;

	@Column(name = "estimated_cost", precision = 15, scale = 2, nullable = false)
	private BigDecimal estimatedCost;

	@Column(name = "actual_cost", precision = 15, scale = 2)
	private BigDecimal actualCost;

	@Column(name = "budget_alert_threshold", precision = 15, scale = 2)
	private BigDecimal budgetAlertThreshold;

	@Column(name = "extracharge_amount", precision = 15, scale = 2)
	private BigDecimal extrachargeAmount;

	protected EventBilling() {
	}

}
