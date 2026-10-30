package com.tetra.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

import com.tetra.ops.external.DownloadUrlSigner.SignedUrl;
import com.tetra.ops.listener.LifecycleResultHandler;
import com.tetra.ops.support.IntegrationTest;

import software.amazon.awssdk.services.s3.model.S3Exception;

/** WF4 성공 시 발급 이력 저장 (3-12): 결과 수 · CSV(테넌트 로그 버킷) · event_result_summary. */
class IssuanceHistoryTest extends IntegrationTest {

	static final long EVENT_ID = 4401;

	static final String BUCKET = "tetra-audit-logs-test-" + A;

	static final String KEY = "issuance-history/4401.csv";

	static final String HEADER = "issuance_id,user_id,ticket_number,result,session_issued_at,queue_entered_at,issued_at";

	@Autowired
	LifecycleResultHandler handler;

	@BeforeEach
	void setUp() {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(EVENT_ID, A).approved("HOSTING", "OPEN", "ih000001").insert();
		data.jdbc().update("UPDATE t_%s.event SET stock_count = 8 WHERE event_id = ?".formatted(A), EVENT_ID);
	}

	private static String succeeded() {
		return LifecycleResultHandlerTest.message(LifecycleResultHandlerTest.EVENT_END, "SUCCEEDED", EVENT_ID, A, null,
				null);
	}

	private void issuance(long issuanceId, String userId, int ticket, String result, String at) {
		data.jdbc().update("""
				INSERT INTO t_%s.issuance_history (issuance_id, event_id, tenant_id, user_id, ticket_number,
				    session_issued_at, queue_entered_at, issued_at, result) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"""
			.formatted(A), issuanceId, EVENT_ID, A, userId, ticket, at, at, at, result);
	}

	/** 집계 Job이 먼저 만든 행(Redis 지표만) */
	private void aggregatedSummary() {
		data.jdbc().update("""
				INSERT INTO t_%s.event_result_summary (event_id, tenant_id, session_count, ticket_count, peak_concurrent,
				    peak_at, metrics_finalized_at) VALUES (?, ?, 20, 10, 7, '2026-11-01 10:00:30', '2026-11-01 23:00:00')"""
			.formatted(A), EVENT_ID, A);
	}

	private Map<String, Object> summary() {
		List<Map<String, Object>> rows = data.jdbc()
			.queryForList("SELECT * FROM t_%s.event_result_summary WHERE event_id = ?".formatted(A), EVENT_ID);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/** 테넌트 집계 완료 알림(EVENT/INFO) 건수 */
	private long aggregatedNotifications() {
		return data.count("SELECT COUNT(*) FROM t_%s.tenant_notification WHERE event_id = ? AND category = 'EVENT'"
				.formatted(A) + " AND priority = 'INFO' AND content LIKE '%이벤트 결과 집계가 완료되었습니다%'", EVENT_ID);
	}

	private byte[] uploadedCsv() {
		ArgumentCaptor<byte[]> csv = ArgumentCaptor.forClass(byte[].class);
		verify(storage).put(eq(BUCKET), eq(KEY), csv.capture(), eq("text/csv; charset=utf-8"));
		return csv.getValue();
	}

	private static String withoutBom(byte[] csv) {
		assertThat(Arrays.copyOf(csv, 3)).containsExactly(0xEF, 0xBB, 0xBF);
		return new String(csv, 3, csv.length - 3, StandardCharsets.UTF_8);
	}

	@Test
	void savesCountsCsvAndKeepsAggregateMetrics() throws Exception {
		aggregatedSummary();
		// issuance_id 순서와 넣은 순서를 섞는다. user_id에 쉼표 · 큰따옴표 · 한글
		issuance(30, "user-c", 3, "FAILED_SOLDOUT", "2026-11-01 10:02:00.5");
		issuance(10, "user-a", 1, "SUCCESS", "2026-11-01 10:00:00.123");
		issuance(20, "김,\"b\"", 2, "SUCCESS", "2026-11-01 10:01:59.999");
		issuance(40, "user-d", 4, "SUCCESS", "2026-11-01 10:03:00");

		handler.handle(succeeded());

		assertThat(withoutBom(uploadedCsv())).isEqualTo(HEADER + "\r\n"
				+ "10,user-a,1,SUCCESS,2026-11-01T10:00:00.123+09:00,2026-11-01T10:00:00.123+09:00,2026-11-01T10:00:00.123+09:00\r\n"
				+ "20,\"김,\"\"b\"\"\",2,SUCCESS,2026-11-01T10:01:59.999+09:00,2026-11-01T10:01:59.999+09:00,2026-11-01T10:01:59.999+09:00\r\n"
				+ "30,user-c,3,FAILED_SOLDOUT,2026-11-01T10:02:00.500+09:00,2026-11-01T10:02:00.500+09:00,2026-11-01T10:02:00.500+09:00\r\n"
				+ "40,user-d,4,SUCCESS,2026-11-01T10:03:00.000+09:00,2026-11-01T10:03:00.000+09:00,2026-11-01T10:03:00.000+09:00\r\n");
		Map<String, Object> row = summary();
		// 집계 Job 지표는 그대로
		assertThat(row).containsEntry("session_count", 20L)
			.containsEntry("ticket_count", 10L)
			.containsEntry("peak_concurrent", 7L)
			.containsEntry("total_stock", 8L)
			.containsEntry("success_count", 3L)
			.containsEntry("soldout_count", 1L)
			.containsEntry("history_file_path", KEY);
		assertThat((BigDecimal) row.get("sellout_rate")).isEqualByComparingTo("0.3750");
		assertThat(row.get("metrics_finalized_at").toString().replace('T', ' ')).startsWith("2026-11-01 23:00");
		assertThat(row.get("history_generated_at")).isNotNull();
		// 집계 Job 행이 먼저 있어도 history_generated_at을 처음 채운 것이므로 집계 완료 알림 1건
		assertThat(aggregatedNotifications()).isEqualTo(1);
		assertThat(data.jdbc().queryForObject("SELECT content FROM t_%s.tenant_notification WHERE category = 'EVENT'"
				.formatted(A), String.class))
			.isEqualTo("[4401:이벤트 4401] 이벤트 결과 집계가 완료되었습니다. 결과 화면에서 발급 내역을 확인하고 CSV를 내려받을 수 있습니다.");

		// 결과 탭 READY, CSV 다운로드는 테넌트 로그 버킷의 같은 키
		mvc.perform(get("/api/ops/events/{id}/result", EVENT_ID).cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("READY"))
			.andExpect(jsonPath("$.result.metrics.successCount").value(3))
			.andExpect(jsonPath("$.result.metrics.soldoutCount").value(1))
			.andExpect(jsonPath("$.result.metrics.selloutRate").value(0.375));
		org.mockito.Mockito.when(urlSigner.presignGet(any(), any(), any(), any()))
			.thenReturn(new SignedUrl("https://signed.example/csv", Instant.parse("2026-11-02T00:00:00Z")));
		mvc.perform(get("/api/ops/events/{id}/result/history-csv", EVENT_ID).cookie(operatorCookie()))
			.andExpect(status().isOk());
		verify(urlSigner).presignGet(eq(BUCKET), eq(KEY), any(), eq("issuance-history-4401.csv"));
	}

	/** 열린 적 없는 이벤트: 집계 Job을 건너뛰어 summary 행이 없고 이력도 0건 → 머리글만, 지표 0으로 행을 만든다 */
	@Test
	void noRowsAndNoSummaryInsertsZeroMetricsWithHeaderOnlyCsv() {
		handler.handle(succeeded());

		assertThat(withoutBom(uploadedCsv())).isEqualTo(HEADER + "\r\n");
		Map<String, Object> row = summary();
		assertThat(row).containsEntry("tenant_id", A)
			.containsEntry("session_count", 0L)
			.containsEntry("ticket_count", 0L)
			.containsEntry("peak_concurrent", 0L)
			.containsEntry("peak_at", null)
			.containsEntry("total_stock", 8L)
			.containsEntry("success_count", 0L)
			.containsEntry("soldout_count", 0L)
			.containsEntry("history_file_path", KEY);
		assertThat((BigDecimal) row.get("sellout_rate")).isEqualByComparingTo("0");
		assertThat(row.get("metrics_finalized_at")).isNotNull();
		assertThat(row.get("history_generated_at")).isNotNull();
		assertThat(aggregatedNotifications()).isEqualTo(1);
	}

	/** S3 실패: DB에 쓰지 않고 예외(ack 안 함). 재전달되면 상태는 이미 처리됨 → 저장만 다시 */
	@Test
	void s3FailureWritesNothingAndRedeliveryRetriesOnlyTheHistory() {
		aggregatedSummary();
		issuance(1, "user-a", 1, "SUCCESS", "2026-11-01 10:00:00");
		doThrow(S3Exception.builder().message("AccessDenied").build()).when(storage).put(any(), any(), any(), any());

		assertThatThrownBy(() -> handler.handle(succeeded())).isInstanceOf(S3Exception.class);

		// 상태 전이(3-11)는 이미 커밋, 발급 이력 쪽은 그대로
		assertThat(data.registry(EVENT_ID)).containsEntry("hosting_status", "ENDED").containsEntry("event_status", "EXPIRED");
		assertThat(summary()).containsEntry("success_count", null).containsEntry("history_file_path", null)
			.containsEntry("history_generated_at", null);
		// 집계가 끝나지 않았으므로 집계 완료 알림 없음
		assertThat(aggregatedNotifications()).isZero();

		reset(storage);
		handler.handle(succeeded());

		verify(storage, times(1)).put(eq(BUCKET), eq(KEY), any(), any());
		assertThat(summary()).containsEntry("success_count", 1L).containsEntry("history_file_path", KEY);
		// 상태 로그 · 종료 알림은 첫 처리 때 한 번씩뿐, 집계 완료는 저장이 성공한 재시도 때 1건
		assertThat(data.statusLogs(A, EVENT_ID)).hasSize(2);
		assertThat(data.count("SELECT COUNT(*) FROM tetra_platform.operator_notification WHERE event_id = ?"
				+ " AND category = 'HOSTING'", EVENT_ID))
			.isEqualTo(1);
		assertThat(aggregatedNotifications()).isEqualTo(1);
	}

	/** 같은 성공 결과가 다시 와도 같은 키 · 같은 행에 같은 내용으로 덮어쓴다 */
	@Test
	void redeliveredSuccessOverwritesWithSameResult() {
		issuance(1, "user-a", 1, "SUCCESS", "2026-11-01 10:00:00");
		issuance(2, "user-b", 2, "FAILED_SOLDOUT", "2026-11-01 10:00:01");

		handler.handle(succeeded());
		Map<String, Object> first = summary();
		handler.handle(succeeded());
		Map<String, Object> second = summary();

		ArgumentCaptor<byte[]> csv = ArgumentCaptor.forClass(byte[].class);
		verify(storage, times(2)).put(eq(BUCKET), eq(KEY), csv.capture(), any());
		assertThat(csv.getAllValues().get(1)).isEqualTo(csv.getAllValues().get(0));
		for (String column : List.of("total_stock", "success_count", "soldout_count", "sellout_rate", "history_file_path",
				"session_count", "ticket_count", "peak_concurrent")) {
			assertThat(second.get(column)).as(column).isEqualTo(first.get(column));
		}
		assertThat(data.count("SELECT COUNT(*) FROM t_%s.event_result_summary WHERE event_id = ?".formatted(A), EVENT_ID))
			.isEqualTo(1);
		assertThat(aggregatedNotifications()).isEqualTo(1);
	}

	/**
	 * history_generated_at = 처음 저장 시각. 다시 저장해도(재전달 · -rN 재실행) 그대로 두고, 결과 수 · 키는 덮어쓴다.
	 * 집계 완료 알림은 처음 저장 때 한 번뿐.
	 */
	@Test
	void resaveKeepsFirstSaveTimeAndOverwritesCounts() {
		issuance(1, "user-a", 1, "SUCCESS", "2026-11-01 10:00:00");
		handler.handle(succeeded());
		assertThat(summary()).containsEntry("success_count", 1L);
		// 처음 저장 시각을 과거로 돌려 두면, 다시 저장했을 때 덮어쓰는지 구분할 수 있다
		data.jdbc().update("UPDATE t_%s.event_result_summary SET history_generated_at = '2026-11-01 23:30:00' WHERE event_id = ?"
			.formatted(A), EVENT_ID);
		issuance(2, "user-b", 2, "SUCCESS", "2026-11-01 10:00:01");

		handler.handle(succeeded());

		Map<String, Object> row = summary();
		assertThat(row.get("history_generated_at").toString().replace('T', ' ')).startsWith("2026-11-01 23:30");
		assertThat(row).containsEntry("success_count", 2L).containsEntry("history_file_path", KEY);
		assertThat(aggregatedNotifications()).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(strings = { "FAILED", "TIMED_OUT", "ABORTED" })
	void failedResultDoesNotSaveHistory(String status) {
		issuance(1, "user-a", 1, "SUCCESS", "2026-11-01 10:00:00");

		handler.handle(LifecycleResultHandlerTest.message(LifecycleResultHandlerTest.EVENT_END, status, EVENT_ID, A,
				"States.TaskFailed", "aggregate failed"));

		verify(storage, never()).put(any(), any(), any(), any());
		assertThat(summary()).isNull();
	}

	/** 다른 tenant_id 행이 섞여 있으면 올리지도 쓰지도 않는다 */
	@Test
	void foreignIssuanceRowStopsBeforeUpload() {
		data.jdbc().update("""
				INSERT INTO t_%s.issuance_history (event_id, tenant_id, user_id, ticket_number, session_issued_at,
				    queue_entered_at, issued_at, result) VALUES (?, ?, 'x', 1, NOW(), NOW(), NOW(), 'SUCCESS')"""
			.formatted(A), EVENT_ID, B);

		assertThatThrownBy(() -> handler.handle(succeeded())).isNotNull();

		verify(storage, never()).put(any(), any(), any(), any());
		assertThat(summary()).isNull();
	}

}
