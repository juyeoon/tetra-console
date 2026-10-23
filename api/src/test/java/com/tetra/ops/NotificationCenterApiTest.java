package com.tetra.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import com.tetra.ops.support.IntegrationTest;
import com.tetra.ops.support.TestData;

/** OPS-08 알림센터: 목록(최신순 · 테넌트명) · 읽음 · 모두 읽음. */
class NotificationCenterApiTest extends IntegrationTest {

	private long insert(String tenantId, Long eventId, String priority, LocalDateTime occurredAt, String content,
			boolean read) {
		data.jdbc().update("""
				INSERT INTO tetra_platform.operator_notification
				    (tenant_id, event_id, category, priority, occurred_at, content, is_read)
				VALUES (?, ?, 'HOSTING', ?, ?, ?, ?)""", tenantId, eventId, priority, occurredAt, content, read);
		return data.jdbc().queryForObject("SELECT MAX(notification_id) FROM tetra_platform.operator_notification", Long.class);
	}

	private List<Boolean> readFlags() {
		return data.jdbc()
			.queryForList("SELECT is_read FROM tetra_platform.operator_notification ORDER BY notification_id", Boolean.class);
	}

	@Test
	void listsAllNewestFirstWithTenantName() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(7001, A).approved("HOSTING", "OPEN", "noc00001").insert();
		var t = TestData.BASE;
		long first = insert(A, 7001L, "URGENT", t, "배포 실패", false);
		long system = insert(null, null, "INFO", t.plusMinutes(2), "테넌트 무관 알림", true);
		// 같은 시각이면 나중에 남은 알림이 먼저
		long sameTime = insert(A, null, "WARNING", t, "같은 시각", false);

		mvc.perform(get("/api/ops/notifications").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(3))
			.andExpect(jsonPath("$.items[*].notificationId").value(Matchers.contains((int) system, (int) sameTime, (int) first)))
			.andExpect(jsonPath("$.items[0].tenantId").doesNotExist())
			.andExpect(jsonPath("$.items[0].tenantName").doesNotExist())
			.andExpect(jsonPath("$.items[0].read").value(true))
			.andExpect(jsonPath("$.items[2].tenantId").value(A))
			.andExpect(jsonPath("$.items[2].tenantName").value("A사"))
			.andExpect(jsonPath("$.items[2].eventId").value(7001))
			.andExpect(jsonPath("$.items[2].category").value("HOSTING"))
			.andExpect(jsonPath("$.items[2].priority").value("URGENT"))
			.andExpect(jsonPath("$.items[2].occurredAt").value("2026-10-01T10:00:00+09:00"))
			.andExpect(jsonPath("$.items[2].content").value("배포 실패"))
			.andExpect(jsonPath("$.items[2].read").value(false));
	}

	@Test
	void emptyListIsItemsArray() throws Exception {
		mvc.perform(get("/api/ops/notifications").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isArray())
			.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void markReadIsIdempotentAndOnlyTouchesThatNotification() throws Exception {
		long target = insert(null, null, "INFO", TestData.BASE, "읽을 알림", false);
		insert(null, null, "INFO", TestData.BASE, "다른 알림", false);

		mvc.perform(patch("/api/ops/notifications/{id}/read", target).cookie(operatorCookie()))
			.andExpect(status().isNoContent());
		mvc.perform(patch("/api/ops/notifications/{id}/read", target).cookie(operatorCookie()))
			.andExpect(status().isNoContent());

		assertThat(readFlags()).containsExactly(true, false);
	}

	@Test
	void markReadUnknownOrMalformedId() throws Exception {
		mvc.perform(patch("/api/ops/notifications/{id}/read", 999999).cookie(operatorCookie()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
		mvc.perform(patch("/api/ops/notifications/{id}/read", "abc").cookie(operatorCookie()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
	}

	@Test
	void markAllReadUpdatesOnlyUnreadAndIsIdempotent() throws Exception {
		insert(null, null, "URGENT", TestData.BASE, "안읽음 1", false);
		insert(null, null, "INFO", TestData.BASE, "이미 읽음", true);
		insert(null, null, "WARNING", TestData.BASE, "안읽음 2", false);

		mvc.perform(patch("/api/ops/notifications/read-all").cookie(operatorCookie())).andExpect(status().isNoContent());
		assertThat(readFlags()).containsOnly(true);

		mvc.perform(patch("/api/ops/notifications/read-all").cookie(operatorCookie())).andExpect(status().isNoContent());
		assertThat(readFlags()).containsExactly(true, true, true);
	}

	@Test
	void requiresLogin() throws Exception {
		mvc.perform(get("/api/ops/notifications")).andExpect(status().isUnauthorized());
		mvc.perform(patch("/api/ops/notifications/read-all")).andExpect(status().isUnauthorized());
		mvc.perform(patch("/api/ops/notifications/{id}/read", 1)).andExpect(status().isUnauthorized());
	}

}
