package com.tetra.ops;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import com.tetra.ops.support.IntegrationTest;
import com.tetra.ops.support.TestData;

/** OPS-03-2 · 04-2 신청 내용(신청 요약). 원본 값만, 표시 문자열은 화면이 만든다. */
class ApplicationSummaryApiTest extends IntegrationTest {

	private void coupon(String schemaTenantId, String rowTenantId, long eventId, String name) {
		data.jdbc().update("INSERT INTO t_%s.coupon (event_id, tenant_id, name, description) VALUES (?, ?, ?, '설명')"
			.formatted(schemaTenantId), eventId, rowTenantId, name);
	}

	private ResultActions summary(long eventId) throws Exception {
		return mvc.perform(get("/api/ops/events/{id}/application-summary", eventId).cookie(operatorCookie()));
	}

	@Test
	void returnsRawValuesWithCouponsInOrderAndLayoutObject() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(7101, A).name("가을 맞이 선착순 쿠폰").startAt(TestData.FUTURE).approved("PENDING", "SCHEDULED", "sum00001").insert();
		coupon(A, A, 7101, "10% 할인 쿠폰");
		coupon(A, A, 7101, "무료배송 쿠폰");
		coupon(A, A, 7101, "1+1 쿠폰");
		String start = TestData.FUTURE.toString();

		summary(7101).andExpect(status().isOk())
			.andExpect(jsonPath("$.eventId").value(7101))
			.andExpect(jsonPath("$.eventType").value("COUPON"))
			.andExpect(jsonPath("$.name").value("가을 맞이 선착순 쿠폰"))
			.andExpect(jsonPath("$.startAt").value(Matchers.startsWith(start.substring(0, 16))))
			.andExpect(jsonPath("$.startAt").value(Matchers.endsWith("+09:00")))
			.andExpect(jsonPath("$.endAt").value(Matchers.endsWith("+09:00")))
			.andExpect(jsonPath("$.hostingStartAt").value(Matchers.endsWith("+09:00")))
			.andExpect(jsonPath("$.hostingEndAt").value(Matchers.endsWith("+09:00")))
			.andExpect(jsonPath("$.trafficPlan.code").value("M"))
			.andExpect(jsonPath("$.trafficPlan.maxCapacity").value(30000))
			.andExpect(jsonPath("$.applicationStatus").value("APPROVED"))
			.andExpect(jsonPath("$.bannerFileName").value("banner.png"))
			.andExpect(jsonPath("$.endpointUrl").value("https://shop.example.com/event"))
			.andExpect(jsonPath("$.coupons[*].name").value(Matchers.contains("10% 할인 쿠폰", "무료배송 쿠폰", "1+1 쿠폰")))
			.andExpect(jsonPath("$.coupons[0].couponId").isNumber())
			.andExpect(jsonPath("$.stockCount").value(10000))
			// 문자열이 아니라 객체
			.andExpect(jsonPath("$.layoutConfig.title.h").value("CENTER"))
			.andExpect(jsonPath("$.layoutConfig.title.v").value("TOP"))
			.andExpect(jsonPath("$.layoutConfig.couponList.v").value("MIDDLE"))
			.andExpect(jsonPath("$.layoutConfig.issueButton.v").value("BOTTOM"))
			.andExpect(jsonPath("$.layoutConfig.theme").value("WHITE"))
			// 개요 탭 담당 값은 넣지 않는다
			.andExpect(jsonPath("$.slug").doesNotExist())
			.andExpect(jsonPath("$.rejectReason").doesNotExist());
	}

	@Test
	void couponIdsFollowInsertOrderEvenIfNamesDiffer() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(7102, A).applied().insert();
		coupon(A, A, 7102, "나중 이름");
		coupon(A, A, 7102, "가나다");
		summary(7102).andExpect(status().isOk())
			.andExpect(jsonPath("$.coupons[*].name").value(Matchers.contains("나중 이름", "가나다")));
	}

	@Test
	void nonCouponEventHasEmptyCoupons() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(7103, A).applied().insert();
		data.jdbc().update("UPDATE t_%s.event SET event_type = 'TIMESALE' WHERE event_id = 7103".formatted(A));
		coupon(A, A, 7103, "남아 있는 쿠폰 행");

		summary(7103).andExpect(status().isOk())
			.andExpect(jsonPath("$.eventType").value("TIMESALE"))
			.andExpect(jsonPath("$.coupons").isArray())
			.andExpect(jsonPath("$.coupons.length()").value(0));
	}

	@Test
	void applyingEventWithoutBannerReturnsNullFileName() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(7104, A).status("APPLYING").insert();

		summary(7104).andExpect(status().isOk())
			.andExpect(jsonPath("$.applicationStatus").value("APPLYING"))
			.andExpect(jsonPath("$.bannerFileName").value(Matchers.nullValue()));
	}

	@Test
	void doesNotMixOtherTenantSchema() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.tenant(B, "B사", "ACTIVE", "READY");
		data.event(7105, A).name("A 이벤트").applied().insert();
		coupon(A, A, 7105, "A 쿠폰");
		// B 스키마에 같은 event_id의 행과 쿠폰이 있어도 A로 라우팅한 응답에 섞이지 않는다
		data.jdbc().update("INSERT INTO t_%s.event SELECT * FROM t_%s.event WHERE event_id = 7105".formatted(B, A));
		data.jdbc().update("UPDATE t_%s.event SET tenant_id = ?, name = 'B 이벤트' WHERE event_id = 7105".formatted(B), B);
		coupon(B, B, 7105, "B 쿠폰");

		summary(7105).andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("A 이벤트"))
			.andExpect(jsonPath("$.coupons[*].name").value(Matchers.contains("A 쿠폰")));
	}

	@Test
	void foreignTenantRowsAreRejected() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		// A 스키마의 쿠폰 행에 다른 tenant_id → 이중 확인으로 500(내용 노출 없음)
		data.event(7106, A).applied().insert();
		coupon(A, B, 7106, "섞인 쿠폰");
		summary(7106).andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
			.andExpect(jsonPath("$.name").doesNotExist());

		// event 행 자체의 tenant_id가 다르면 500
		data.event(7107, A).rowTenantId(B).insert();
		summary(7107).andExpect(status().isInternalServerError());
	}

	@Test
	void unknownEventIs404AndNotReadyTenantIs409() throws Exception {
		summary(999999).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));

		data.tenant(A, "A사", "INACTIVE", "PROVISIONING");
		data.event(7108, A).applied().insert();
		summary(7108).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TENANT_NOT_READY"));

		mvc.perform(get("/api/ops/events/{id}/application-summary", "abc").cookie(operatorCookie()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
	}

	@Test
	void requiresLogin() throws Exception {
		mvc.perform(get("/api/ops/events/{id}/application-summary", 1)).andExpect(status().isUnauthorized());
	}

}
