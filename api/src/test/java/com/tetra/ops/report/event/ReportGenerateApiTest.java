package com.tetra.ops.report.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.tetra.ops.common.DbLock;
import com.tetra.ops.support.IntegrationTest;
import com.tetra.ops.support.MySqlTestDatabase;
import com.tetra.ops.tenancy.TenantRouter;

/**
 * 리포트 수동 생성 API(POST /api/ops/reports/generate, ⑬-보조): 정상 생성 · 다시 부르면 덮어쓰기(created false, 알림 한 번) · 보충 기간과
 * 무관 · 결과 없음 409 · 없는 이벤트 404 · 잠금 점유 409 REPORT_BUSY · 로그인 필요 · 아직 없는 분류 501 · 본문 오류 400.
 * 통합 테스트 풀이 1개라 실제 잠금(전용 커넥션 + 작업 커넥션)으로는 만들 수 없다 — 잠금은 실제 DbLock의 spy로 바꿔 work를 바로
 * 돌리고, 잠금 점유 테스트만 실제 GET_LOCK을 쓴다(다른 커넥션이 같은 이름을 쥠). S3는 목.
 */
@ExtendWith(OutputCaptureExtension.class)
class ReportGenerateApiTest extends IntegrationTest {

	@TestConfiguration
	static class LockConfig {

		@Bean
		DbLock reportGenerateTestLock(DataSource dataSource) {
			return spy(new DbLock(dataSource, EventResultReportJob.LOCK_NAME));
		}

		@Bean
		@Primary
		EventResultReportGenerator testEventResultReportGenerator(DbLock reportGenerateTestLock, TenantRouter router,
				EventResultReportService reports) {
			return new EventResultReportGenerator(reportGenerateTestLock, router, reports);
		}

	}

	static final String URL = "/api/ops/reports/generate";

	/** 오픈 2026-10-15 10:00 KST → 키의 달 2026/10 */
	static final LocalDateTime START = LocalDateTime.of(2026, 10, 15, 10, 0);

	@Autowired
	DbLock lock;

	@BeforeEach
	void setUp() {
		reset(lock);
		doAnswer(inv -> {
			inv.<Runnable>getArgument(0).run();
			return true;
		}).when(lock).runIfAcquired(any());
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.tenant(B, "B사", "ACTIVE", "READY");
	}

	// ---------------------------------------------------------------- 준비

	private void endedEvent(String tenantId, long eventId) {
		data.event(eventId, tenantId).startAt(START).approved("ENDED", "EXPIRED", "rg%06d".formatted(eventId)).insert();
	}

	/** 집계 지표 + 발급 이력 저장 결과(historyAt이 null이면 발급 이력 저장 전 → 결과 탭 AGGREGATING) */
	private void summary(String tenantId, long eventId, LocalDateTime historyAt) {
		boolean saved = historyAt != null;
		data.jdbc().update("""
				INSERT INTO t_%s.event_result_summary (event_id, tenant_id, session_count, ticket_count, peak_concurrent, peak_at,
				    metrics_finalized_at, total_stock, success_count, soldout_count, sellout_rate, history_file_path,
				    history_generated_at)
				VALUES (?, ?, 0, 0, 0, NULL, ?, ?, ?, ?, ?, ?, ?)""".formatted(tenantId), eventId, tenantId,
				START.plusHours(4), saved ? 10000 : null, saved ? 0 : null, saved ? 0 : null,
				saved ? BigDecimal.ZERO : null, saved ? "issuance-history/" + eventId + ".csv" : null, historyAt);
	}

	private ResultActions generate(String body) throws Exception {
		return mvc.perform(post(URL).cookie(operatorCookie()).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions generateEvent(long eventId) throws Exception {
		return generate("{\"category\":\"EVENT_RESULT\",\"eventId\":" + eventId + "}");
	}

	private long meta(String tenantId) {
		return data.count("SELECT COUNT(*) FROM t_%s.tenant_report_meta".formatted(tenantId));
	}

	private long reportNotifications(String tenantId) {
		return data.count("SELECT COUNT(*) FROM t_%s.tenant_notification WHERE category = 'REPORT'".formatted(tenantId));
	}

	private void verifyNothingSaved() {
		verify(storage, never()).put(any(), any(), any(), any());
		assertThat(meta(A)).isZero();
		assertThat(reportNotifications(A)).isZero();
	}

	// ---------------------------------------------------------------- 정상 · 멱등

	/**
	 * 발급 이력 저장이 30일 전이라 자동 보충(최근 3일) 대상이 아니어도 만든다. 두 번째 호출은 같은 키에 덮어쓰고 created=false — 메타 ·
	 * 테넌트 알림은 한 번. 응답에 버킷 이름 · 링크가 없다. 로그 INFO 한 줄(운영자 id 포함).
	 */
	@Test
	void createsOnceThenOverwritesWithSingleNotification(CapturedOutput output) throws Exception {
		long eventId = 7101;
		endedEvent(A, eventId);
		summary(A, eventId, LocalDateTime.now().minusDays(30));
		String key = "reports/2026/10/event-7101.zip";

		String body = generateEvent(eventId).andExpect(status().isOk())
			.andExpect(jsonPath("$.category").value("EVENT_RESULT"))
			.andExpect(jsonPath("$.eventId").value(eventId))
			.andExpect(jsonPath("$.tenantId").value(A))
			.andExpect(jsonPath("$.filePath").value(key))
			.andExpect(jsonPath("$.created").value(true))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(body).doesNotContain("tetra-audit-logs", "https://", "X-Amz");
		verify(storage).put(eq("tetra-audit-logs-test-" + A), eq(key), any(), eq("application/zip"));
		assertThat(meta(A)).isEqualTo(1);
		assertThat(reportNotifications(A)).isEqualTo(1);
		assertThat(output).contains("리포트 수동 생성: category=EVENT_RESULT, event=7101, tenant=" + A
				+ ", created=true, operator=1");

		generateEvent(eventId).andExpect(status().isOk())
			.andExpect(jsonPath("$.filePath").value(key))
			.andExpect(jsonPath("$.created").value(false));
		verify(storage, times(2)).put(eq("tetra-audit-logs-test-" + A), eq(key), any(), eq("application/zip"));
		assertThat(meta(A)).isEqualTo(1);
		assertThat(reportNotifications(A)).isEqualTo(1);
		assertThat(output).contains("created=false, operator=1");
		verify(lock, times(2)).runIfAcquired(any());
	}

	// ---------------------------------------------------------------- 오류

	/** 결과 탭이 READY가 아님: 발급 이력 저장 전(AGGREGATING) · 결과 행 없음(진행 중) → 409, 아무것도 저장하지 않음 */
	@Test
	void resultNotReadyIs409() throws Exception {
		endedEvent(A, 7201);
		summary(A, 7201, null);
		data.event(7202, A).approved("HOSTING", "OPEN", "rg007202").insert();

		for (long eventId : new long[] { 7201, 7202 }) {
			generateEvent(eventId).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("RESULT_NOT_READY"));
		}
		verifyNothingSaved();
	}

	@Test
	void unknownEventIs404WithoutLock() throws Exception {
		generateEvent(999999).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
		verify(lock, never()).runIfAcquired(any());
		verifyNothingSaved();
	}

	/** READY가 아닌 테넌트의 이벤트는 S3 전에 409 */
	@Test
	void tenantNotReadyIs409() throws Exception {
		data.jdbc().update("UPDATE tetra_platform.tenant SET provisioning_status = 'PROVISIONING' WHERE tenant_id = ?", B);
		endedEvent(B, 7301);
		summary(B, 7301, LocalDateTime.now().minusHours(1));

		generateEvent(7301).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TENANT_NOT_READY"));
		verify(storage, never()).put(any(), any(), any(), any());
	}

	/** 작업과 같은 이름(tetra:report-event-result)의 GET_LOCK을 다른 커넥션이 쥐고 있으면 409 REPORT_BUSY — 실제 DbLock */
	@Test
	void lockHeldElsewhereIsReportBusy() throws Exception {
		endedEvent(A, 7401);
		summary(A, 7401, LocalDateTime.now().minusHours(1));
		reset(lock); // spy → 실제 GET_LOCK
		try (Connection other = DriverManager.getConnection(MySqlTestDatabase.appJdbcUrl("tetra_platform"),
				MySqlTestDatabase.APP_USER, MySqlTestDatabase.APP_PASSWORD)) {
			assertThat(query(other, "select get_lock('tetra:report-event-result', 0)")).isEqualTo(1);

			generateEvent(7401).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("REPORT_BUSY"))
				.andExpect(jsonPath("$.message").value("다른 리포트를 만들고 있습니다. 잠시 후 다시 시도해 주세요."));

			query(other, "select release_lock('tetra:report-event-result')");
		}
		verifyNothingSaved();
	}

	private static int query(Connection c, String sql) throws Exception {
		try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			rs.next();
			return rs.getInt(1);
		}
	}

	@Test
	void requiresLogin() throws Exception {
		mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"EVENT_RESULT\",\"eventId\":1}"))
			.andExpect(status().isUnauthorized());
		verify(lock, never()).runIfAcquired(any());
	}

	/** 월간 · 운영자 감사는 아직 없다(⑭ · ⑮) */
	@Test
	void otherCategoriesAre501() throws Exception {
		for (String body : new String[] { "{\"category\":\"MONTHLY\",\"month\":\"2026-10\"}",
				"{\"category\":\"OPERATOR_AUDIT\",\"month\":\"2026-10\"}", "{\"category\":\"MONTHLY\"}" }) {
			generate(body).andExpect(status().isNotImplemented()).andExpect(jsonPath("$.code").value("NOT_IMPLEMENTED"));
		}
		verify(lock, never()).runIfAcquired(any());
	}

	@Test
	void invalidBodyIs400() throws Exception {
		endedEvent(A, 7501);
		summary(A, 7501, LocalDateTime.now().minusHours(1));

		generate("{}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
			.andExpect(jsonPath("$.fields.category").value("필수 입력입니다."));
		generate("{\"category\":\"EVENT_RESULT\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.eventId").value("필수 입력입니다."));
		// month는 EVENT_RESULT에 쓰지 않는다 — 무시하지 않고 400
		generate("{\"category\":\"EVENT_RESULT\",\"eventId\":7501,\"month\":\"2026-10\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.month").value("EVENT_RESULT에는 쓰지 않습니다."));
		for (String body : new String[] { "{\"category\":\"WEEKLY\",\"eventId\":7501}",
				"{\"category\":\"EVENT_RESULT\",\"eventId\":\"abc\"}", "{\"category\":\"MONTHLY\",\"month\":\"2026-13\"}",
				"{\"category\":\"EVENT_RESULT\",", "" }) {
			generate(body).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fields.body").value("요청 본문 형식이 올바르지 않습니다."));
		}
		verify(lock, never()).runIfAcquired(any());
		verifyNothingSaved();
	}

}
