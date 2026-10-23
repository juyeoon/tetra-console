package com.tetra.ops.event;

import java.time.OffsetDateTime;
import java.util.List;

import com.tetra.ops.domain.ApplicationStatus;
import com.tetra.ops.domain.EventType;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * OPS-03-2 · 04-2 신청 내용. Tetra Portal 이벤트 신청 4단계(UI-TP-10)의 「신청 요약」과 같은 항목의 원본 값.
 * 표시 문자열(기간 · 플랜 · 화면 설정 요약 등)은 화면이 만든다.
 */
public record ApplicationSummaryResponse(Long eventId, EventType eventType, String name, OffsetDateTime startAt,
		OffsetDateTime endAt, OffsetDateTime hostingStartAt, OffsetDateTime hostingEndAt, TrafficPlan trafficPlan,
		@Schema(description = "배너 문구 분기용(APPLYING · APPLY_FAILED). 화면에 상태로 보이지 않는다") ApplicationStatus applicationStatus,
		@Schema(description = "banner_image_path의 파일명(마지막 / 뒤). 원본 파일명이 아니다. 경로가 없으면 null") String bannerFileName,
		String endpointUrl,
		@Schema(description = "coupon_id 오름차순. 쿠폰 타입이 아니면 빈 배열") List<Coupon> coupons, Long stockCount,
		LayoutConfig layoutConfig) {

	@Schema(name = "ApplicationSummaryTrafficPlan")
	public record TrafficPlan(String code, Long maxCapacity) {
	}

	@Schema(name = "ApplicationSummaryCoupon")
	public record Coupon(Long couponId, String name) {
	}

	/** event.layout_config(JSON). 형식은 DB CHECK(chk_event_layout_config)가 보장한다. */
	@Schema(name = "LayoutConfig")
	public record LayoutConfig(Position title, Position banner, Position couponList, Position issueButton,
			@Schema(allowableValues = { "WHITE", "WARM_ORANGE", "DARK", "BLUE" }) String theme) {
	}

	@Schema(name = "LayoutPosition")
	public record Position(@Schema(allowableValues = { "LEFT", "CENTER", "RIGHT" }) String h,
			@Schema(allowableValues = { "TOP", "MIDDLE", "BOTTOM" }) String v) {
	}

}
