package com.tetra.ops.domain.platform;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "traffic_plan", catalog = "tetra_platform")
public class TrafficPlan {

	@Id
	@Column(name = "plan_code", length = 10, nullable = false)
	private String planCode;

	@Column(name = "max_capacity", nullable = false, columnDefinition = "int unsigned")
	private Long maxCapacity;

	@Column(name = "hourly_price", precision = 15, scale = 2, nullable = false)
	private BigDecimal hourlyPrice;

	@Column(name = "hosting_hourly_price", precision = 15, scale = 2, nullable = false)
	private BigDecimal hostingHourlyPrice;

	@Column(name = "is_visible", nullable = false)
	private boolean visible;

	protected TrafficPlan() {
	}

	public String getPlanCode() {
		return planCode;
	}

	public Long getMaxCapacity() {
		return maxCapacity;
	}

}
