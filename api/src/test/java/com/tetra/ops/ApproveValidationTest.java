package com.tetra.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;

import com.tetra.ops.event.EventKeyPairs;
import com.tetra.ops.support.IntegrationTest;
import com.tetra.ops.support.MySqlTestDatabase;
import com.tetra.ops.support.TestData;

/**
 * 승인 전 검사(3-13). 걸리면 409 APPLICATION_DATA_INVALID(fields = 사유 코드별 문구)이고,
 * 키 생성 · DB 변경 · StartExecution · 메일이 모두 일어나지 않는다.
 */
class ApproveValidationTest extends IntegrationTest {

	static final long EVENT_ID = 1301;

	/** TestData 기본: hosting_start = start − 2시간, hosting_end = start + 5시간 */
	static final LocalDateTime START = TestData.FUTURE;

	@MockitoSpyBean
	EventKeyPairs keyPairs;

	@BeforeEach
	void setUp() {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.tenant(B, "B사", "ACTIVE", "READY");
		data.event(EVENT_ID, A).applied().insert();
	}

	private ResultActions approve() throws Exception {
		return mvc.perform(post("/api/ops/events/{id}/approve", EVENT_ID).cookie(operatorCookie()));
	}

	private void updateEvent(String set, Object... args) {
		data.jdbc().update("UPDATE t_%s.event SET %s WHERE event_id = %d".formatted(A, set, EVENT_ID), args);
	}

	private void assertNothingChanged() {
		assertThat(data.registry(EVENT_ID)).containsEntry("application_status", "APPLIED");
		assertThat(data.eventRow(A, EVENT_ID)).containsEntry("application_status", "APPLIED");
		assertThat(data.authKeys(A, EVENT_ID)).isEmpty();
		assertThat(data.statusLogs(A, EVENT_ID)).isEmpty();
		assertThat(data.count("SELECT COUNT(*) FROM tetra_platform.operator_notification WHERE event_id = ?", EVENT_ID))
			.isZero();
		verify(keyPairs, never()).generate();
		verify(stepFunctions, never()).startExecution(anyString(), anyString(), anyString());
		verify(mail, never()).send(any());
	}

	@Test
	void hostingStartLessThan100MinutesBeforeStartIsRejected() throws Exception {
		// TEST-LEAD: 서비스 값 90(100분보다 늦음)
		// updateEvent("hosting_start_at = ?", START.minusMinutes(90));
		updateEvent("hosting_start_at = ?", START.minusMinutes(34)); // TEST-LEAD

		approve().andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("APPLICATION_DATA_INVALID"))
			.andExpect(jsonPath("$.message").value("신청 내용이 승인 조건에 맞지 않아 승인할 수 없습니다."))
			.andExpect(jsonPath("$.fields.HOSTING_START_TOO_LATE").value(org.hamcrest.Matchers.startsWith(
					// TEST-LEAD: 서비스 값 100분
					// "호스팅 시작은 이벤트 시작 100분 전까지여야 합니다.")))
					"호스팅 시작은 이벤트 시작 35분 전까지여야 합니다."))) // TEST-LEAD
			.andExpect(jsonPath("$.fields.length()").value(1));
		assertNothingChanged();
	}

	@Test
	void exactly100MinutesIsApproved() throws Exception {
		// TEST-LEAD: 서비스 값 100
		// updateEvent("hosting_start_at = ?", START.minusMinutes(100));
		updateEvent("hosting_start_at = ?", START.minusMinutes(35)); // TEST-LEAD

		approve().andExpect(status().isOk());
	}

	@Test
	void hostingStartSoonOrPastIsRejected() throws Exception {
		// 지난 이벤트(호스팅 종료도 지남)도 키를 만들기 전에 이 사유로 걸린다
		LocalDateTime start = LocalDateTime.now().minusDays(1).withNano(0);
		updateEvent("start_at = ?, end_at = ?, hosting_start_at = ?, hosting_end_at = ?", start, start.plusHours(3),
				start.minusHours(2), start.plusHours(5));

		approve().andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("APPLICATION_DATA_INVALID"))
			.andExpect(jsonPath("$.fields.HOSTING_START_TOO_SOON").exists());
		assertNothingChanged();
	}

	@Test
	void blankNameAndInvalidUrlAreReportedTogether() throws Exception {
		updateEvent("name = ?, endpoint_url = ?", " ", "ftp://shop.example.com/x");

		approve().andExpect(status().isConflict())
			.andExpect(jsonPath("$.fields.NAME_BLANK").value("이벤트 이름이 비어 있습니다."))
			.andExpect(jsonPath("$.fields.ENDPOINT_URL_INVALID").value("결과를 받을 주소가 비어 있거나 http(s) 주소가 아닙니다."))
			.andExpect(jsonPath("$.fields.length()").value(2));
		assertNothingChanged();
	}

	@Test
	void tenantNotReadyIsRejectedBeforeKeys() throws Exception {
		data.jdbc().update("UPDATE tetra_platform.tenant SET provisioning_status = 'PROVISIONING' WHERE tenant_id = ?", A);

		approve().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TENANT_NOT_READY"));
		assertNothingChanged();
	}

	// ---- 동시 이벤트 상한(테스트 설정 3) ----

	@Test
	void fourthOverlappingEventAcrossTenantsIsRejected() throws Exception {
		// 같은 기간의 승인 이벤트 3건(다른 테넌트 포함, 배포 실패도 센다)
		data.event(1311, A).approved("HOSTING", "OPEN", "cap00001").insert();
		data.event(1312, B).approved("PENDING", "SCHEDULED", "cap00002").insert();
		data.event(1313, B).approved("DEPLOY_FAILED", "SCHEDULED", "cap00003").insert();

		approve().andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("APPLICATION_DATA_INVALID"))
			.andExpect(jsonPath("$.fields.CONCURRENT_EVENT_LIMIT").value(org.hamcrest.Matchers.startsWith(
					"같은 기간에 진행되는 이벤트가 최대 3개를 넘습니다.")));
		assertNothingChanged();
	}

	@Test
	void expiredRejectedAndAppliedEventsAreNotCounted() throws Exception {
		data.event(1311, A).approved("HOSTING", "OPEN", "cap00001").insert();
		data.event(1312, B).approved("PENDING", "SCHEDULED", "cap00002").insert();
		data.event(1313, B).approved("ENDED", "EXPIRED", "cap00003").insert();
		data.event(1314, B).applied().insert();
		data.event(1315, B).status("REJECTED").insert();

		approve().andExpect(status().isOk());
	}

	@Test
	void touchingWindowsCountAsOverlap() throws Exception {
		// 새 이벤트 Redis 구간 = [START − 90분, START + 5시간]. 기존 3건의 hosting_end가 정확히 START − 90분이면 겹친다
		for (long id = 1311; id <= 1313; id++) {
			LocalDateTime start = START.minusHours(7).minusMinutes(90);
			data.event(id, B).approved("HOSTING", "OPEN", "cap0000" + (id - 1310)).startAt(start).insert();
			data.jdbc().update("UPDATE t_%s.event SET hosting_end_at = ? WHERE event_id = ?".formatted(B),
					// TEST-LEAD: 서비스 값 90
					// START.minusMinutes(90), id);
					START.minusMinutes(25), id); // TEST-LEAD
		}
		approve().andExpect(status().isConflict()).andExpect(jsonPath("$.fields.CONCURRENT_EVENT_LIMIT").exists());

		// 1분 앞당기면 겹치지 않는다
		// TEST-LEAD: 서비스 값 91
		// data.jdbc().update("UPDATE t_%s.event SET hosting_end_at = ?".formatted(B), START.minusMinutes(91));
		data.jdbc().update("UPDATE t_%s.event SET hosting_end_at = ?".formatted(B), START.minusMinutes(26)); // TEST-LEAD
		approve().andExpect(status().isOk());
	}

	// ---- 전역 잠금 ----

	private static Connection otherConnection() throws Exception {
		return DriverManager.getConnection(MySqlTestDatabase.appJdbcUrl("tetra_platform"), MySqlTestDatabase.APP_USER,
				MySqlTestDatabase.APP_PASSWORD);
	}

	private static int query(Connection c, String sql) throws Exception {
		try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			rs.next();
			return rs.getInt(1);
		}
	}

	@Test
	void lockHeldElsewhereIsApproveBusyAndChangesNothing() throws Exception {
		try (Connection other = otherConnection()) {
			assertThat(query(other, "select get_lock('tetra:approve-capacity', 0)")).isEqualTo(1);

			approve().andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("APPROVE_BUSY"))
				.andExpect(jsonPath("$.message").value("다른 승인을 처리하고 있습니다. 잠시 후 다시 시도해 주세요."));
			assertThat(data.registry(EVENT_ID)).containsEntry("application_status", "APPLIED");
			assertThat(data.authKeys(A, EVENT_ID)).isEmpty();
			verify(stepFunctions, never()).startExecution(anyString(), anyString(), anyString());

			query(other, "select release_lock('tetra:approve-capacity')");
		}
		approve().andExpect(status().isOk());
	}

	@Test
	void lockIsReleasedAfterApproval() throws Exception {
		approve().andExpect(status().isOk());

		// 앱 커넥션이 잠금을 쥐고 있으면 다른 커넥션에서 0
		try (Connection other = otherConnection()) {
			assertThat(query(other, "select is_free_lock('tetra:approve-capacity')")).isEqualTo(1);
		}
	}

}
