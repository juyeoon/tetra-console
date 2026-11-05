package com.tetra.ops.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.tetra.ops.common.CsvWriter;
import com.tetra.ops.domain.platform.Tenant;
import com.tetra.ops.external.DownloadUrlSigner.SignedUrl;
import com.tetra.ops.report.ReportStore.OperatorReport;
import com.tetra.ops.report.ReportStore.TenantReport;
import com.tetra.ops.support.IntegrationTest;
import com.tetra.ops.tenancy.TenantRouter;

import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * 리포트 저장(멱등): S3 Put(같은 키 덮어쓰기) → 한 트랜잭션에서 메타 확인 · INSERT + REPORT 알림. 같은 리포트를 두 번 저장하면
 * Put은 두 번, 메타 · 알림은 한 번. S3는 목(IntegrationTest), DB는 실제 MySQL(공용 + 테넌트 스키마 A · B).
 */
class ReportStoreTest extends IntegrationTest {

	static final long EVENT_ID = 5501;

	static final YearMonth OCTOBER = YearMonth.of(2026, 10);

	static final LocalDateTime START = LocalDateTime.of(2026, 10, 1, 0, 0);

	static final LocalDateTime END = LocalDateTime.of(2026, 10, 31, 23, 59, 59);

	/** 운영자 접속기록 기간(KST 날짜, 양끝 포함) = START ~ END의 날짜 */
	static final LocalDate FROM = START.toLocalDate();

	static final LocalDate TO = END.toLocalDate();

	static final byte[] ZIP = ReportZip.zip(List.of(new ReportZip.Entry("summary.pdf", "pdf".getBytes(StandardCharsets.UTF_8))));

	static final byte[] CSV = new CsvWriter("a").row("1").toBytes();

	@Autowired
	ReportStore store;

	@Autowired
	TenantRouter router;

	@BeforeEach
	void setUp() {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.tenant(B, "B사", "ACTIVE", "READY");
		data.event(EVENT_ID, A).name("가을 한정 쿠폰").approved("ENDED", "EXPIRED", "rp000001").insert();
	}

	private Tenant tenant(String tenantId) {
		return router.findTenant(tenantId);
	}

	private static TenantReport eventReport() {
		return new TenantReport(ReportCategory.EVENT_RESULT, EVENT_ID, "가을 한정 쿠폰 결과 리포트", START, END,
				ReportKeys.eventResult(OCTOBER, EVENT_ID), ZIP);
	}

	private static OperatorReport auditReport() {
		return new OperatorReport(ReportCategory.OPERATOR_AUDIT, "운영자 접속기록 2026-10-01 ~ 2026-10-31", START, END,
				ReportKeys.operatorAudit(FROM, TO), CSV);
	}

	private List<Map<String, Object>> tenantMeta(String tenantId) {
		return data.jdbc().queryForList("SELECT * FROM t_" + tenantId + ".tenant_report_meta ORDER BY report_id");
	}

	private List<Map<String, Object>> tenantNotifications(String tenantId) {
		return data.jdbc()
			.queryForList("SELECT * FROM t_" + tenantId + ".tenant_notification WHERE category = 'REPORT' ORDER BY notification_id");
	}

	private List<Map<String, Object>> operatorMeta() {
		return data.jdbc().queryForList("SELECT * FROM tetra_platform.operator_report_meta ORDER BY report_id");
	}

	private List<Map<String, Object>> operatorNotifications() {
		return data.jdbc()
			.queryForList("SELECT * FROM tetra_platform.operator_notification WHERE category = 'REPORT' ORDER BY notification_id");
	}

	@Test
	void keysAreDeterministicPerTargetPeriod() {
		assertThat(ReportKeys.eventResult(OCTOBER, 1043)).isEqualTo("reports/2026/10/event-1043.zip");
		assertThat(ReportKeys.monthly(YearMonth.of(2026, 1))).isEqualTo("reports/2026/01/monthly-202601.zip");
		assertThat(ReportKeys.operatorAudit(FROM, TO)).isEqualTo("reports/2026/10/operator-audit-20261001-20261031.csv");
		// 달을 넘는 기간은 시작일의 달 폴더
		assertThat(ReportKeys.operatorAudit(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 11, 5)))
			.isEqualTo("reports/2026/10/operator-audit-20261028-20261105.csv");
	}

	/** 이벤트 결과: 테넌트 로그 버킷에 zip Put 두 번(덮어쓰기), 메타 · 테넌트 알림은 한 번 */
	@Test
	void tenantReportTwiceWritesMetaAndNotificationOnce() {
		assertThat(store.saveTenantReport(tenant(A), eventReport())).isTrue();
		assertThat(store.saveTenantReport(tenant(A), eventReport())).isFalse();

		verify(storage, times(2)).put("tetra-audit-logs-test-" + A, "reports/2026/10/event-5501.zip", ZIP, "application/zip");
		assertThat(tenantMeta(A)).singleElement()
			.satisfies(row -> assertThat(row).containsEntry("tenant_id", A)
				.containsEntry("name", "가을 한정 쿠폰 결과 리포트")
				.containsEntry("category", "EVENT_RESULT")
				.containsEntry("file_path", "reports/2026/10/event-5501.zip")
				.containsEntry("period_start", START)
				.containsEntry("period_end", END));
		assertThat(tenantNotifications(A)).singleElement()
			.satisfies(row -> assertThat(row).containsEntry("event_id", EVENT_ID)
				.containsEntry("priority", "INFO")
				.containsEntry("is_read", false)
				.containsEntry("content",
						"[5501:가을 한정 쿠폰] 이벤트 결과 리포트가 생성되었습니다. 리포트 화면에서 내려받을 수 있습니다."));
		// 다른 테넌트 스키마 · 운영자 쪽에는 쓰지 않는다
		assertThat(tenantMeta(B)).isEmpty();
		assertThat(tenantNotifications(B)).isEmpty();
		assertThat(operatorMeta()).isEmpty();
		assertThat(operatorNotifications()).isEmpty();
	}

	/** 월간: 이벤트 없는 테넌트 알림, 같은 달 리포트는 테넌트마다 따로 */
	@Test
	void monthlyReportPerTenant() {
		TenantReport monthly = new TenantReport(ReportCategory.MONTHLY, null, "2026년 10월 월간 리포트", START, END,
				ReportKeys.monthly(OCTOBER), ZIP);

		assertThat(store.saveTenantReport(tenant(A), monthly)).isTrue();
		assertThat(store.saveTenantReport(tenant(B), monthly)).isTrue();
		assertThat(store.saveTenantReport(tenant(B), monthly)).isFalse();

		verify(storage).put("tetra-audit-logs-test-" + A, "reports/2026/10/monthly-202610.zip", ZIP, "application/zip");
		verify(storage, times(2)).put("tetra-audit-logs-test-" + B, "reports/2026/10/monthly-202610.zip", ZIP,
				"application/zip");
		for (String tenantId : List.of(A, B)) {
			assertThat(tenantMeta(tenantId)).singleElement()
				.satisfies(row -> assertThat(row).containsEntry("tenant_id", tenantId).containsEntry("category", "MONTHLY"));
			assertThat(tenantNotifications(tenantId)).singleElement()
				.satisfies(row -> assertThat(row).containsEntry("event_id", null)
					.containsEntry("content", "[2026년 10월] 월간 리포트가 생성되었습니다. 리포트 화면에서 내려받을 수 있습니다."));
		}
	}

	/**
	 * 운영자 접속기록: 운영자 리포트 버킷에 CSV Put 두 번, operator_report_meta · 운영자 알림(dedupe_key, 기간 기준)은 한 번. OPS-09 목록 · 다운로드에
	 * 그대로 나온다(키만 저장 → 같은 버킷 presign, 파일 이름 report-{id}.csv)
	 */
	@Test
	void operatorReportTwiceWritesMetaAndNotificationOnceAndShowsInOps09() throws Exception {
		assertThat(store.saveOperatorReport(auditReport())).isTrue();
		assertThat(store.saveOperatorReport(auditReport())).isFalse();

		verify(storage, times(2)).put("tetra-operator-logs-test", "reports/2026/10/operator-audit-20261001-20261031.csv", CSV,
				"text/csv; charset=utf-8");
		Map<String, Object> meta = operatorMeta().get(0);
		assertThat(operatorMeta()).hasSize(1);
		assertThat(meta).containsEntry("category", "OPERATOR_AUDIT")
			.containsEntry("file_path", "reports/2026/10/operator-audit-20261001-20261031.csv");
		assertThat(operatorNotifications()).singleElement()
			.satisfies(row -> assertThat(row).containsEntry("tenant_id", null)
				.containsEntry("event_id", null)
				.containsEntry("priority", "INFO")
				.containsEntry("dedupe_key", "REPORT:OPERATOR_AUDIT:2026-10-01~2026-10-31")
				.containsEntry("slack_status", "PENDING")
				.containsEntry("content", "[2026-10-01 ~ 2026-10-31] 운영자 접속기록 리포트가 생성되었습니다. 리포트 화면에서 내려받을 수 있습니다."));

		long reportId = ((Number) meta.get("report_id")).longValue();
		mvc.perform(get("/api/ops/reports").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].name").value("운영자 접속기록 2026-10-01 ~ 2026-10-31"))
			.andExpect(jsonPath("$.items[0].category").value("OPERATOR_AUDIT"))
			.andExpect(jsonPath("$.items[0].periodEnd").value("2026-10-31T23:59:59+09:00"));
		when(urlSigner.presignGet(any(), any(), any(), any()))
			.thenReturn(new SignedUrl("https://signed.example/report", Instant.parse("2026-11-02T00:00:00Z")));
		mvc.perform(get("/api/ops/reports/{id}/download", reportId).cookie(operatorCookie())).andExpect(status().isOk());
		verify(urlSigner).presignGet(eq("tetra-operator-logs-test"), eq("reports/2026/10/operator-audit-20261001-20261031.csv"),
				eq(Duration.ofMinutes(5)), eq("report-" + reportId + ".csv"));
		// 알림센터 칩 · 이동 경로는 리포트
		mvc.perform(get("/api/ops/notifications").param("chip", "REPORT").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].chip").value("REPORT"))
			.andExpect(jsonPath("$.items[0].link").value("/logs/reports"));
	}

	/** 메타만 지워진 뒤(보관 정리 등) 다시 만들면 메타는 다시 넣고, 운영자 알림은 dedupe_key가 있어 다시 남기지 않는다(1062 없이) */
	@Test
	void operatorReportAfterMetaDeletedDoesNotDuplicateNotification() {
		store.saveOperatorReport(auditReport());
		data.jdbc().update("DELETE FROM tetra_platform.operator_report_meta");

		assertThat(store.saveOperatorReport(auditReport())).isTrue();

		assertThat(operatorMeta()).hasSize(1);
		assertThat(operatorNotifications()).hasSize(1);
	}

	/** S3가 실패하면 DB에는 아무것도 쓰지 않고 예외(다시 돌리면 처음부터) */
	@Test
	void s3FailureWritesNothing() {
		doThrow(S3Exception.builder().message("AccessDenied").build()).when(storage).put(any(), any(), any(), any());

		assertThatThrownBy(() -> store.saveTenantReport(tenant(A), eventReport())).isInstanceOf(S3Exception.class);
		assertThatThrownBy(() -> store.saveOperatorReport(auditReport())).isInstanceOf(S3Exception.class);

		assertThat(tenantMeta(A)).isEmpty();
		assertThat(tenantNotifications(A)).isEmpty();
		assertThat(operatorMeta()).isEmpty();
		assertThat(operatorNotifications()).isEmpty();
	}

	/** name은 100자(코드 포인트)로 자른다 — 이모지(서로게이트 쌍)를 가르지 않는다 */
	@Test
	void longNameIsCutAtHundredCharacters() {
		String name = "가".repeat(99) + "😀" + "나머지";
		TenantReport report = new TenantReport(ReportCategory.EVENT_RESULT, EVENT_ID, name, START, END,
				ReportKeys.eventResult(OCTOBER, EVENT_ID), ZIP);

		store.saveTenantReport(tenant(A), report);

		assertThat(tenantMeta(A).get(0).get("name")).isEqualTo("가".repeat(99) + "😀");
		assertThat(ReportStore.fitName("  짧은 이름 ")).isEqualTo("짧은 이름");
	}

	/** 잘못된 입력은 S3에 올리기 전에 거절한다 */
	@Test
	void invalidReportsAreRejectedBeforeUpload() {
		assertThatThrownBy(() -> new TenantReport(ReportCategory.OPERATOR_AUDIT, null, "x", START, END,
				ReportKeys.operatorAudit(FROM, TO), ZIP)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TenantReport(ReportCategory.EVENT_RESULT, null, "x", START, END,
				ReportKeys.eventResult(OCTOBER, 1), ZIP)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TenantReport(ReportCategory.MONTHLY, EVENT_ID, "x", START, END,
				ReportKeys.monthly(OCTOBER), ZIP)).isInstanceOf(IllegalArgumentException.class);
		// 수명 주기(reports/)가 걸리지 않는 키
		assertThatThrownBy(() -> new OperatorReport(ReportCategory.OPERATOR_AUDIT, "x", START, END, "audit/202610.csv", CSV))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OperatorReport(ReportCategory.MONTHLY, "x", START, END,
				ReportKeys.operatorAudit(FROM, TO), CSV)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OperatorReport(ReportCategory.OPERATOR_AUDIT, "x", END, START,
				ReportKeys.operatorAudit(FROM, TO), CSV)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OperatorReport(ReportCategory.OPERATOR_AUDIT, " ", START, END,
				ReportKeys.operatorAudit(FROM, TO), CSV)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OperatorReport(ReportCategory.OPERATOR_AUDIT, "x", START, END,
				ReportKeys.operatorAudit(FROM, TO), new byte[0])).isInstanceOf(IllegalArgumentException.class);

		// READY가 아닌 테넌트
		data.tenant("cccccccccccc", "C사", "INACTIVE", "PROVISIONING");
		assertThatThrownBy(() -> store.saveTenantReport(tenant("cccccccccccc"), new TenantReport(ReportCategory.MONTHLY,
				null, "x", START, END, ReportKeys.monthly(OCTOBER), ZIP)))
			.isInstanceOf(IllegalStateException.class);
		verify(storage, never()).put(any(), any(), any(), any());
	}

}
