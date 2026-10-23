package com.tetra.ops.domain.tenant;

import com.tetra.ops.tenancy.TenantScopedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 쿠폰. 신청 내용(OPS-03-2 · 04-2)에서 읽기만 한다. 쓰는 쪽은 Portal 신청. */
@Entity
@Table(name = "coupon")
public class Coupon extends TenantScopedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "coupon_id", nullable = false, columnDefinition = "int unsigned")
	private Long couponId;

	@Column(name = "event_id", nullable = false, columnDefinition = "int unsigned")
	private Long eventId;

	@Column(name = "name", length = 100, nullable = false)
	private String name;

	@Column(name = "description", length = 500, nullable = false)
	private String description;

	protected Coupon() {
	}

	public Long getCouponId() {
		return couponId;
	}

	public Long getEventId() {
		return eventId;
	}

	public String getName() {
		return name;
	}

}
