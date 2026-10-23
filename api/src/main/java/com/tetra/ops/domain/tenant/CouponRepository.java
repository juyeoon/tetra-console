package com.tetra.ops.domain.tenant;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

	/** 신청 내용의 발급 쿠폰 — 등록 순서(coupon_id 오름차순). */
	List<Coupon> findByEventIdOrderByCouponIdAsc(Long eventId);

}
