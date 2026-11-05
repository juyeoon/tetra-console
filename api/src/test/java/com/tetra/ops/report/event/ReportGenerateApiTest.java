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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.tetra.ops.common.CsvWriter;
import com.tetra.ops.common.DbLock;
import com.tetra.ops.common.time.Kst;
import com.tetra.ops.domain.platform.OperatorReportMetaRepository;
import com.tetra.ops.domain.platform.OperatorRepository;
import com.tetra.ops.report.ReportStore;
import com.tetra.ops.report.audit.OperatorAuditReader;
import com.tetra.ops.report.audit.OperatorAuditReportGenerator;
import com.tetra.ops.support.FakeObjectReader;
import com.tetra.ops.support.IntegrationTest;
import com.tetra.ops.support.MySqlTestDatabase;
import com.tetra.ops.tenancy.TenantRouter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 리포트 수동 생성 API(POST /api/ops/reports/generate, ⑬-보조 · ⑮-간이).
 * <ul>
 * <li>EVENT_RESULT: 정상 생성 · 다시 부르면 덮어쓰기(created false, 알림 한 번) · 보충 기간과 무관 · 결과 없음 409 · 없는 이벤트 404 ·
 * 잠금 점유 409 REPORT_BUSY · 본문 오류 400(from · to 포함)</li>
 * <li>OPERATOR_AUDIT: 기간 CSV 정상(200 · created · rows) · 같은 기간 두 번(created false · 알림 1건) · 기간 검사 400 · 잠금 점유 409 ·
 * 감사 줄 대상 REPORT</li>
 * <li>MONTHLY 501 · 로그인 필요</li>
 * </ul>
 * 통합 테스트 풀이 1개라 실제 잠금(전용 커넥션 + 작업 커넥션)으로는 만들 수 없다 — 잠금은 실제 DbLock의 spy로 바꿔 work를 바로
 * 돌리고, 잠금 점유 테스트만 실제 GET_LOCK을 쓴다(다른 커넥션이 같은 이름을 쥠). S3 쓰기는 목, 감사 버킷 읽기는 메모리 S3.
 */
@ExtendWith(OutputCaptureExtension.class)
class ReportGenerateApiTest extends IntegrationTest {

	/** 테스트 운영자 리포트 버킷(tetra-operator-logs-test)에서 만든 감사 버킷 이름 */
	static final String AUDIT_BUCKET = "tetra-audit-test";

	@TestConfiguration
	static class LockConfig {

		@Bean
		DbLock reportGenerateTestLock(DataSource dataSource) {
			return spy(new DbLock(dataSource, EventResultReportJob.LOCK_NAME));
		}

		@Bean
		@Primary
		EventResultReportGenerator testEventResultReportGenerator(
				@Qualifier("reportGenerateTestLock") DbLock reportGenerateTestLock, TenantRouter router,
				EventResultReportService reports) {
			return new EventResultReportGenerator(reportGenerateTestLock, router, reports);
		}

		@Bean
		DbLock operatorAuditTestLock(DataSource dataSource) {
			return spy(new DbLock(dataSource, OperatorAuditReportGenerator.LOCK_NAME));
		}

		/** 메모리 S3는 빈으로 두지 않는다(ObjectReader 빈이 둘이 되어 실제 OperatorAuditReader 주입이 모호해짐) */
		@Bean
		@Primary
		OperatorAuditReportGenerator testOperatorAuditReportGenerator(
				@Qualifier("operatorAuditTestLock") DbLock operatorAuditTestLock, ReportStore store,
				OperatorReportMetaRepository metas, OperatorRepository operators) {
			return new OperatorAuditReportGenerator(operatorAuditTestLock, new OperatorAuditReader(AUDIT_OBJECTS), store,
					metas, operators, Optional.of(AUDIT_BUCKET));
		}

	}

	/** 감사 버킷(메모리 S3) — 테스트마다 비운다 */
	static final FakeObjectReader AUDIT_OBJECTS = new FakeObjectReader();

	static final String URL = "/api/ops/reports/generate";

	/** 오픈 2026-10-15 10:00 KST → 키의 달 2026/10 */
	static final LocalDateTime START = LocalDateTime.of(2026, 10, 15, 10, 0);

	@Autowired
	@Qualifier("reportGenerateTestLock")
	DbLock lock;

	@Autowired
	@Qualifier("operatorAuditTestLock")
	DbLock auditLock;

	final FakeObjectReader auditObjects = AUDIT_OBJECTS;

	@BeforeEach
	void setUp() {
		for (DbLock spied : List.of(lock, auditLock)) {
			reset(spied);
			doAnswer(inv -> {
				inv.<Runnable>getArgument(0).run();
				return true;
			}).when(spied).runIfAcquired(any());
		}
		auditObjects.clear();
		auditObjects.bucket(AUDIT_BUCKET);
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

	// ---------------------------------------------------------------- 감사 로그

	/** 감사 줄(REPORT_GENERATE): 대상 · event_id = 본문 eventId, tenant_id = 처리에서 찾은 테넌트. 없는 이벤트면 tenant_id null. */
	@Test
	void auditLineCarriesBodyEventAndResolvedTenant(CapturedOutput output) throws Exception {
		endedEvent(B, 7601);
		summary(B, 7601, LocalDateTime.now().minusHours(1));

		generateEvent(7601).andExpect(status().isOk());
		generateEvent(999999).andExpect(status().isNotFound());

		List<JsonNode> lines = output.getOut()
			.lines()
			.filter(line -> line.startsWith("{\"log_type\":\"AUDIT\"") && line.contains("REPORT_GENERATE"))
			.map(JsonMapper.builder().build()::readTree)
			.toList();
		assertThat(lines).hasSize(2);
		JsonNode created = lines.get(0);
		assertThat(created.path("action").asString()).isEqualTo("REPORT_GENERATE");
		assertThat(created.path("result").asString()).isEqualTo("SUCCESS");
		assertThat(created.path("actor").path("type").asString()).isEqualTo("OPERATOR");
		assertThat(created.path("actor").path("id").asString()).isEqualTo("1");
		assertThat(created.path("target").path("type").asString()).isEqualTo("EVENT");
		assertThat(created.path("target").path("id").asString()).isEqualTo("7601");
		assertThat(created.path("event_id").asLong()).isEqualTo(7601);
		assertThat(created.path("tenant_id").asString()).isEqualTo(B);
		JsonNode missing = lines.get(1);
		assertThat(missing.path("result").asString()).isEqualTo("FAILURE");
		assertThat(missing.path("reason").asString()).isEqualTo("EVENT_NOT_FOUND");
		assertThat(missing.path("event_id").asLong()).isEqualTo(999999);
		assertThat(missing.path("tenant_id").isNull()).isTrue();
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
		String today = LocalDate.now(Kst.ZONE).toString();
		mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
			.content("{\"category\":\"OPERATOR_AUDIT\",\"from\":\"" + today + "\",\"to\":\"" + today + "\"}"))
			.andExpect(status().isUnauthorized());
		verify(lock, never()).runIfAcquired(any());
		verify(auditLock, never()).runIfAcquired(any());
	}

	/** 테넌트 월간은 아직 없다(⑭) — 본문과 상관없이 501 */
	@Test
	void monthlyIsStill501() throws Exception {
		for (String body : new String[] { "{\"category\":\"MONTHLY\",\"month\":\"2026-10\"}", "{\"category\":\"MONTHLY\"}",
				"{\"category\":\"MONTHLY\",\"from\":\"2026-10-01\",\"to\":\"2026-10-31\"}" }) {
			generate(body).andExpect(status().isNotImplemented()).andExpect(jsonPath("$.code").value("NOT_IMPLEMENTED"));
		}
		verify(lock, never()).runIfAcquired(any());
		verify(auditLock, never()).runIfAcquired(any());
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
		// from · to(운영자 접속기록 기간)도 EVENT_RESULT에는 400
		generate("{\"category\":\"EVENT_RESULT\",\"eventId\":7501,\"from\":\"2026-11-01\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.from").value("EVENT_RESULT에는 쓰지 않습니다."));
		generate("{\"category\":\"EVENT_RESULT\",\"eventId\":7501,\"from\":\"2026-11-01\",\"to\":\"2026-11-02\"}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.from").value("EVENT_RESULT에는 쓰지 않습니다."))
			.andExpect(jsonPath("$.fields.to").value("EVENT_RESULT에는 쓰지 않습니다."));
		for (String body : new String[] { "{\"category\":\"WEEKLY\",\"eventId\":7501}",
				"{\"category\":\"EVENT_RESULT\",\"eventId\":\"abc\"}", "{\"category\":\"MONTHLY\",\"month\":\"2026-13\"}",
				"{\"category\":\"EVENT_RESULT\",", "", "{\"category\":\"OPERATOR_AUDIT\",\"from\":\"2026-11-31\",\"to\":\"2026-12-01\"}",
				"{\"category\":\"OPERATOR_AUDIT\",\"from\":\"20261101\",\"to\":\"2026-11-02\"}" }) {
			generate(body).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fields.body").value("요청 본문 형식이 올바르지 않습니다."));
		}
		verify(lock, never()).runIfAcquired(any());
		verify(auditLock, never()).runIfAcquired(any());
		verifyNothingSaved();
	}

	// ================================================================ OPERATOR_AUDIT (운영자 접속기록, ⑮-간이)

	private static String auditLine(String auditId, LocalDateTime kst, String app, String action) {
		return "{\"log_type\":\"AUDIT\",\"audit_id\":\"" + auditId + "\",\"time\":\"" + CsvWriter.kst(kst) + "\",\"app\":\"" + app
				+ "\",\"actor\":{\"type\":\"OPERATOR\",\"id\":\"1\"},\"ip\":\"10.0.1.5\",\"user_agent\":\"Mozilla/5.0\","
				+ "\"action\":\"" + action + "\",\"target\":null,\"tenant_id\":null,\"result\":\"SUCCESS\",\"request_id\":\"r-"
				+ auditId + "\"}";
	}

	private ResultActions generateAudit(LocalDate from, LocalDate to) throws Exception {
		return generate("{\"category\":\"OPERATOR_AUDIT\",\"from\":\"" + from + "\",\"to\":\"" + to + "\"}");
	}

	private List<Map<String, Object>> operatorMeta() {
		return data.jdbc().queryForList("SELECT * FROM tetra_platform.operator_report_meta ORDER BY report_id");
	}

	private List<Map<String, Object>> operatorReportNotifications() {
		return data.jdbc()
			.queryForList("SELECT * FROM tetra_platform.operator_notification WHERE category = 'REPORT' ORDER BY notification_id");
	}

	/**
	 * 어제 ~ 오늘(KST): console 줄 3개(어제 10:00 · 자정을 넘어 오늘 dt 폴더(테넌트 파티션)에 든 어제 23:59:58 · 오늘 09:00) + portal 줄 ·
	 * 그제 줄(뺌) → 200 · created true · rows 3, 시간순.
	 * 운영자 리포트 버킷에 CSV(BOM · 한글 머리글 · 운영자 이름). 같은 기간을 다시 만들면 파일만 덮어쓰고
	 * created false — 메타 · 운영자 알림(중복 방지 키 = 기간)은 1건. 감사 줄 대상 = REPORT(메타 id), 테넌트 · 이벤트 없음.
	 */
	@Test
	void operatorAuditCreatesOnceThenOverwritesWithSingleNotification(CapturedOutput output) throws Exception {
		LocalDate today = LocalDate.now(Kst.ZONE);
		LocalDate yesterday = today.minusDays(1);
		auditObjects.putGzipLines(AUDIT_BUCKET, "audit-logs/tenant_id=none/dt=" + yesterday + "/a.jsonl.gz",
				auditLine("a1", yesterday.atTime(10, 0), "console", "DASHBOARD_VIEW"),
				auditLine("p1", yesterday.atTime(10, 0, 1), "portal", "DASHBOARD_VIEW"),
				auditLine("a0", yesterday.minusDays(1).atTime(23, 59), "console", "DASHBOARD_VIEW"));
		auditObjects.putGzipLines(AUDIT_BUCKET, "audit-logs/tenant_id=" + A + "/dt=" + today + "/b.jsonl.gz",
				auditLine("a3", today.atTime(9, 0), "console", "REPORT_LIST_VIEW"),
				auditLine("a2", yesterday.atTime(23, 59, 58), "console", "REPORT_DOWNLOAD"));
		String key = "reports/%04d/%02d/operator-audit-%s-%s.csv".formatted(yesterday.getYear(), yesterday.getMonthValue(),
				yesterday.toString().replace("-", ""), today.toString().replace("-", ""));

		String body = generateAudit(yesterday, today).andExpect(status().isOk())
			.andExpect(jsonPath("$.category").value("OPERATOR_AUDIT"))
			.andExpect(jsonPath("$.filePath").value(key))
			.andExpect(jsonPath("$.created").value(true))
			.andExpect(jsonPath("$.from").value(yesterday.toString()))
			.andExpect(jsonPath("$.to").value(today.toString()))
			.andExpect(jsonPath("$.rows").value(3))
			.andExpect(jsonPath("$.eventId").isEmpty())
			.andExpect(jsonPath("$.tenantId").isEmpty())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(body).doesNotContain(AUDIT_BUCKET, "tetra-operator-logs", "https://", "X-Amz");
		ArgumentCaptor<byte[]> csv = ArgumentCaptor.forClass(byte[].class);
		verify(storage).put(eq("tetra-operator-logs-test"), eq(key), csv.capture(), eq("text/csv; charset=utf-8"));
		byte[] bytes = csv.getValue();
		assertThat(Arrays.copyOf(bytes, 3)).containsExactly(0xEF, 0xBB, 0xBF);
		String text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
		String[] csvLines = text.split("\r\n");
		assertThat(csvLines).hasSize(4);
		assertThat(csvLines[0]).startsWith("일시(KST),운영자 ID,운영자 이름,행위,행위 코드,");
		assertThat(csvLines[1]).startsWith(yesterday + "T10:00:00.000+09:00,1,홍길동,대시보드 조회,DASHBOARD_VIEW,").endsWith(",r-a1,-");
		// 다운로드 줄은 점검자 사유 칸이 빈 칸
		assertThat(csvLines[2]).startsWith(yesterday + "T23:59:58.000+09:00,1,홍길동,리포트 다운로드,REPORT_DOWNLOAD,").endsWith(",r-a2,");
		assertThat(csvLines[3]).startsWith(today + "T09:00:00.000+09:00,1,홍길동,리포트 목록 조회,REPORT_LIST_VIEW,");
		assertThat(text).doesNotContain("r-p1", "r-a0", "Mozilla", "operator@");

		assertThat(operatorMeta()).singleElement().satisfies(meta -> assertThat(meta)
			.containsEntry("category", "OPERATOR_AUDIT")
			.containsEntry("name", "운영자 접속기록 " + yesterday + " ~ " + today)
			.containsEntry("file_path", key)
			.containsEntry("period_start", yesterday.atStartOfDay())
			.containsEntry("period_end", today.atTime(23, 59, 59)));
		long reportId = ((Number) operatorMeta().get(0).get("report_id")).longValue();
		assertThat(operatorReportNotifications()).singleElement()
			.satisfies(row -> assertThat(row).containsEntry("dedupe_key", "REPORT:OPERATOR_AUDIT:" + yesterday + "~" + today)
				.containsEntry("content", "[" + yesterday + " ~ " + today
						+ "] 운영자 접속기록 리포트가 생성되었습니다. 리포트 화면에서 내려받을 수 있습니다."));
		assertThat(output).contains("리포트 수동 생성: category=OPERATOR_AUDIT, from=" + yesterday + ", to=" + today
				+ ", rows=3, report=" + reportId + ", created=true, operator=1");

		generateAudit(yesterday, today).andExpect(status().isOk())
			.andExpect(jsonPath("$.filePath").value(key))
			.andExpect(jsonPath("$.created").value(false))
			.andExpect(jsonPath("$.rows").value(3));
		verify(storage, times(2)).put(eq("tetra-operator-logs-test"), eq(key), any(), eq("text/csv; charset=utf-8"));
		assertThat(operatorMeta()).hasSize(1);
		assertThat(operatorReportNotifications()).hasSize(1);
		verify(auditLock, times(2)).runIfAcquired(any());
		verify(lock, never()).runIfAcquired(any());
		// 목록 접두어는 모두 audit-logs/ 아래
		assertThat(auditObjects.listedPrefixes).allSatisfy(prefix -> assertThat(prefix).startsWith("audit-logs/"));

		List<JsonNode> lines = output.getOut()
			.lines()
			.filter(line -> line.startsWith("{\"log_type\":\"AUDIT\"") && line.contains("REPORT_GENERATE"))
			.map(JsonMapper.builder().build()::readTree)
			.toList();
		assertThat(lines).hasSize(2).allSatisfy(line -> {
			assertThat(line.path("result").asString()).isEqualTo("SUCCESS");
			assertThat(line.path("target").path("type").asString()).isEqualTo("REPORT");
			assertThat(line.path("target").path("id").asString()).isEqualTo(Long.toString(reportId));
			assertThat(line.path("tenant_id").isNull()).isTrue();
			assertThat(line.has("event_id")).isFalse();
		});
	}

	/** 기록이 없는 기간은 머리글만(rows 0) — 그래도 리포트는 만든다 */
	@Test
	void operatorAuditWithoutLinesIsHeaderOnly() throws Exception {
		LocalDate today = LocalDate.now(Kst.ZONE);

		generateAudit(today, today).andExpect(status().isOk())
			.andExpect(jsonPath("$.rows").value(0))
			.andExpect(jsonPath("$.created").value(true));
		ArgumentCaptor<byte[]> csv = ArgumentCaptor.forClass(byte[].class);
		verify(storage).put(eq("tetra-operator-logs-test"), any(), csv.capture(), any());
		assertThat(new String(csv.getValue(), 3, csv.getValue().length - 3, StandardCharsets.UTF_8))
			.isEqualTo("일시(KST),운영자 ID,운영자 이름,행위,행위 코드,대상 종류,대상 ID,테넌트,이벤트,결과,사유 코드,접속 IP,경로,변경 내용,"
					+ "시도 계정(가림),요청 ID,다운로드 사유(점검자 기입)\r\n");
	}

	/** 기간 검사: 빈 값 · from > to · 미래 · 32일 → 400(INVALID_INPUT, 칸별 사유), 31일은 된다. 다른 분류 값(eventId · month)도 400 */
	@Test
	void operatorAuditPeriodValidationIs400() throws Exception {
		LocalDate today = LocalDate.now(Kst.ZONE);

		generate("{\"category\":\"OPERATOR_AUDIT\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
			.andExpect(jsonPath("$.fields.from").value("필수 입력입니다."))
			.andExpect(jsonPath("$.fields.to").value("필수 입력입니다."));
		generate("{\"category\":\"OPERATOR_AUDIT\",\"from\":\"" + today + "\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.to").value("필수 입력입니다."));
		generate("{\"category\":\"OPERATOR_AUDIT\",\"from\":null,\"to\":\"" + today + "\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.from").value("필수 입력입니다."));
		generateAudit(today, today.minusDays(1)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.to").value("종료일은 시작일과 같거나 뒤여야 합니다."));
		generateAudit(today, today.plusDays(1)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.to").value("종료일은 오늘(" + today + ")까지 고를 수 있습니다."));
		generateAudit(today.minusDays(31), today).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.to").value("기간은 최대 31일입니다."));
		generate("{\"category\":\"OPERATOR_AUDIT\",\"from\":\"" + today + "\",\"to\":\"" + today + "\",\"eventId\":1,"
				+ "\"month\":\"2026-10\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.eventId").value("OPERATOR_AUDIT에는 쓰지 않습니다."))
			.andExpect(jsonPath("$.fields.month").value("OPERATOR_AUDIT에는 쓰지 않습니다."));
		verify(auditLock, never()).runIfAcquired(any());
		verify(storage, never()).put(any(), any(), any(), any());

		// 31일(양끝 포함)은 된다
		generateAudit(today.minusDays(30), today).andExpect(status().isOk()).andExpect(jsonPath("$.rows").value(0));
	}

	/** 같은 이름(tetra:report-operator-audit)의 GET_LOCK을 다른 커넥션이 쥐고 있으면 409 REPORT_BUSY — 실제 DbLock, 아무것도 저장 안 함 */
	@Test
	void operatorAuditLockHeldElsewhereIsReportBusy() throws Exception {
		LocalDate today = LocalDate.now(Kst.ZONE);
		reset(auditLock); // spy → 실제 GET_LOCK
		try (Connection other = DriverManager.getConnection(MySqlTestDatabase.appJdbcUrl("tetra_platform"),
				MySqlTestDatabase.APP_USER, MySqlTestDatabase.APP_PASSWORD)) {
			assertThat(query(other, "select get_lock('tetra:report-operator-audit', 0)")).isEqualTo(1);

			generateAudit(today, today).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REPORT_BUSY"));

			query(other, "select release_lock('tetra:report-operator-audit')");
		}
		verify(storage, never()).put(any(), any(), any(), any());
		assertThat(operatorMeta()).isEmpty();
		assertThat(operatorReportNotifications()).isEmpty();
		assertThat(auditObjects.listedPrefixes).isEmpty();
	}

}
