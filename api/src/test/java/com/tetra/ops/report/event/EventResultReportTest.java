package com.tetra.ops.report.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.tetra.ops.common.CsvWriter;
import com.tetra.ops.common.DbLock;
import com.tetra.ops.config.TetraProperties;
import com.tetra.ops.domain.platform.TenantRepository;
import com.tetra.ops.event.EventStatusService;
import com.tetra.ops.event.IssuanceHistoryService;
import com.tetra.ops.listener.LifecycleResultHandler;
import com.tetra.ops.support.IntegrationTest;
import com.tetra.ops.support.TestData;
import com.tetra.ops.tenancy.TenantRouter;

import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 이벤트 결과 리포트(⑬): 결과 탭과 같은 수치 · 키의 달(start_at KST) · 멱등(리스너 시도 + 보충 → 메타 · 알림 한 번) · 리스너 실패 격리 ·
 * 보충 대상 · 수식 가림 · 최종 사용자 값 없음. DB는 실제 MySQL, S3는 목, 잠금은 목(통합 테스트 풀이 1개). 스케줄 · 리스너 연결은 테스트
 * 프로필에서 꺼져 있어(tetra.report.event-result.enabled=false) 작업과 리스너를 직접 만든다.
 */
class EventResultReportTest extends IntegrationTest {

	/** 2026-11-03 12:00:00 KST — 보충 기간 3일 → 2026-10-31 12:00:00부터 */
	static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-03T03:00:00Z"), ZoneId.of("Asia/Seoul"));

	static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 12, 0);

	static final LocalDateTime SINCE = NOW.minusDays(3);

	static final String C = "cccccccccccc";

	@Autowired
	TenantRepository tenants;

	@Autowired
	TenantRouter router;

	@Autowired
	EventResultReportReader reader;

	@Autowired
	EventResultReportService reports;

	@Autowired
	JsonMapper jsonMapper;

	@Autowired
	EventStatusService eventStatusService;

	@Autowired
	IssuanceHistoryService issuanceHistory;

	@Autowired
	TetraProperties properties;

	private final DbLock lock = mock(DbLock.class);

	@BeforeEach
	void setUp() {
		when(lock.runIfAcquired(any())).thenAnswer(inv -> {
			inv.<Runnable>getArgument(0).run();
			return true;
		});
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.tenant(B, "B사", "ACTIVE", "READY");
	}

	// ---------------------------------------------------------------- 준비

	private EventResultReportJob job(Clock clock) {
		return new EventResultReportJob(lock, tenants, router, reader, reports, 3, clock);
	}

	private LifecycleResultHandler handler(Optional<EventResultReportJob> job) {
		return new LifecycleResultHandler(jsonMapper, router, eventStatusService, issuanceHistory, properties, job);
	}

	/** WF4(이벤트 종료) 성공 결과 메시지 */
	private static String wf4Succeeded(long eventId, String tenantId) {
		String input = "{\\\"tenant_id\\\":\\\"" + tenantId + "\\\",\\\"event_id\\\":" + eventId + "}";
		return """
				{"version":"0","id":"x","detail-type":"Step Functions Execution Status Change","source":"aws.states",
				 "detail":{"executionArn":"arn:aws:states:ap-northeast-2:0:execution:tetra-test-event-end:sched-%d",
				           "stateMachineArn":"arn:aws:states:ap-northeast-2:0:stateMachine:tetra-test-event-end",
				           "name":"sched-%d","status":"SUCCEEDED","input":"%s","error":null,"cause":null}}"""
			.formatted(eventId, eventId, input);
	}

	private static String slug(long eventId) {
		return "er%06d".formatted(eventId);
	}

	/** 종료된(ENDED · EXPIRED) 이벤트 */
	private void endedEvent(String tenantId, long eventId, LocalDateTime startAt) {
		data.event(eventId, tenantId).startAt(startAt).approved("ENDED", "EXPIRED", slug(eventId)).insert();
	}

	private void stock(String tenantId, long eventId, long stock) {
		data.jdbc().update("UPDATE t_%s.event SET stock_count = ? WHERE event_id = ?".formatted(tenantId), stock, eventId);
	}

	/** 집계 Job 지표 + 발급 이력 저장 결과(historyAt이 null이면 발급 이력 저장 전) */
	private void summary(String tenantId, long eventId, long session, long ticket, long peak, String peakAt, long success,
			long soldout, long stock, String rate, LocalDateTime historyAt) {
		data.jdbc().update("""
				INSERT INTO t_%s.event_result_summary (event_id, tenant_id, session_count, ticket_count, peak_concurrent, peak_at,
				    metrics_finalized_at, total_stock, success_count, soldout_count, sellout_rate, history_file_path,
				    history_generated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".formatted(tenantId), eventId, tenantId, session, ticket, peak,
				peakAt, NOW.minusDays(5), historyAt == null ? null : stock, historyAt == null ? null : success,
				historyAt == null ? null : soldout, historyAt == null ? null : new BigDecimal(rate),
				historyAt == null ? null : "issuance-history/" + eventId + ".csv", historyAt);
	}

	/** 발급 이력 저장까지 끝난 결과(지표 0) */
	private void readySummary(String tenantId, long eventId, LocalDateTime historyAt) {
		summary(tenantId, eventId, 0, 0, 0, null, 0, 0, 10000, "0.0000", historyAt);
	}

	private void issuance(String tenantId, long eventId, long issuanceId, String userId, String result,
			String queueEnteredAt, String issuedAt) {
		data.jdbc().update("""
				INSERT INTO t_%s.issuance_history (issuance_id, event_id, tenant_id, user_id, ticket_number,
				    session_issued_at, queue_entered_at, issued_at, result) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"""
			.formatted(tenantId), issuanceId, eventId, tenantId, userId, issuanceId, queueEnteredAt, queueEnteredAt,
				issuedAt, result);
	}

	private void reportMeta(String tenantId, String key) {
		data.jdbc().update("""
				INSERT INTO t_%s.tenant_report_meta (tenant_id, name, period_start, period_end, category, created_at, file_path)
				VALUES (?, '이미 있음', '2026-10-30 10:00:00', '2026-10-30 13:00:00', 'EVENT_RESULT', ?, ?)""".formatted(tenantId),
				tenantId, NOW.minusHours(1), key);
	}

	// ---------------------------------------------------------------- 확인

	/** 리포트 키에 올린 zip들(순서대로) */
	private List<byte[]> uploads(String tenantId, String key) {
		return mockingDetails(storage).getInvocations()
			.stream()
			.filter(inv -> inv.getMethod().getName().equals("put"))
			.filter(inv -> inv.getArgument(0).equals("tetra-audit-logs-test-" + tenantId) && inv.getArgument(1).equals(key))
			.map(inv -> {
				assertThat(inv.<String>getArgument(3)).isEqualTo("application/zip");
				return inv.<byte[]>getArgument(2);
			})
			.toList();
	}

	/** 리포트 키(reports/…)로 올린 모든 Put의 키 */
	private List<String> reportPutKeys() {
		return mockingDetails(storage).getInvocations()
			.stream()
			.filter(inv -> inv.getMethod().getName().equals("put"))
			.map(inv -> inv.<String>getArgument(1))
			.filter(key -> key.startsWith("reports/"))
			.toList();
	}

	private List<Map<String, Object>> meta(String tenantId) {
		return data.jdbc().queryForList("SELECT * FROM t_" + tenantId + ".tenant_report_meta ORDER BY report_id");
	}

	private List<String> reportNotifications(String tenantId) {
		return data.jdbc()
			.queryForList("SELECT content FROM t_" + tenantId + ".tenant_notification WHERE category = 'REPORT'"
					+ " ORDER BY notification_id", String.class);
	}

	/** event.csv 한 줄 → 열 이름별 값(값에 쉼표 · 따옴표가 없는 데이터만) */
	private static Map<String, String> eventCsv(byte[] csv) {
		String[] lines = EventResultReportFilesTest.csv(csv).split("\r\n");
		assertThat(lines).hasSize(2);
		String[] names = lines[0].split(",", -1);
		String[] values = lines[1].split(",", -1);
		assertThat(values).hasSameSizeAs(names);
		Map<String, String> row = new LinkedHashMap<>();
		for (int i = 0; i < names.length; i++) {
			row.put(names[i], values[i]);
		}
		return row;
	}

	private JsonNode resultTab(long eventId) throws Exception {
		String body = mvc.perform(get("/api/ops/events/{id}/result", eventId).cookie(operatorCookie()))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		return jsonMapper.readTree(body);
	}

	private static String kst(String offsetDateTime) {
		return CsvWriter.kst(OffsetDateTime.parse(offsetDateTime).toLocalDateTime());
	}

	// ---------------------------------------------------------------- 수치

	/**
	 * 고정 데이터: 재고 4, 성공 4(마지막 성공 10:02:03.250) · 품절 2, 입장 10 · 번호표 8. 결과 탭 API와 같은 값 + 소진까지 걸린 시간(오픈
	 * 10:00 → 123.250초) · 대기 시간(번호표 → 결과: 121 · 32.5 · 10 · 63.25 · 35 · 26초 → 평균 47.958, 최대 121). user_id는 zip 어디에도 없다.
	 */
	@Test
	void numbersMatchResultTabAndNoUserIdLeaks() throws Exception {
		long eventId = 6101;
		LocalDateTime start = LocalDateTime.of(2026, 11, 1, 10, 0);
		endedEvent(A, eventId, start);
		stock(A, eventId, 4);
		issuance(A, eventId, 1, "secret-user-001", "SUCCESS", "2026-11-01 09:58:00", "2026-11-01 10:00:01");
		issuance(A, eventId, 2, "secret-user-002", "SUCCESS", "2026-11-01 09:59:30", "2026-11-01 10:00:02.500");
		issuance(A, eventId, 3, "secret-user-003", "SUCCESS", "2026-11-01 10:00:10", "2026-11-01 10:00:20");
		issuance(A, eventId, 4, "secret-user-004", "SUCCESS", "2026-11-01 10:01:00", "2026-11-01 10:02:03.250");
		issuance(A, eventId, 5, "secret-user-005", "FAILED_SOLDOUT", "2026-11-01 10:01:30", "2026-11-01 10:02:05");
		issuance(A, eventId, 6, "secret-user-006", "FAILED_SOLDOUT", "2026-11-01 10:01:40", "2026-11-01 10:02:06");
		summary(A, eventId, 10, 8, 6, "2026-11-01 10:00:30", 4, 2, 4, "1.0000", LocalDateTime.of(2026, 11, 2, 9, 0));
		data.statusLog(A, eventId, "APPLICATION", "APPLIED", "APPROVED", LocalDateTime.of(2026, 10, 20, 9, 0), null,
				TestData.OPERATOR_ID);
		data.statusLog(A, eventId, "HOSTING", "PENDING", "DEPLOY_FAILED", LocalDateTime.of(2026, 10, 20, 9, 5),
				"TIMED_OUT: internal-cause-xyz");
		data.statusLog(A, eventId, "HOSTING", "DEPLOY_FAILED", "DEPLOYED", LocalDateTime.of(2026, 10, 20, 9, 30), null);

		job(CLOCK).run();

		String key = "reports/2026/11/event-6101.zip";
		Map<String, byte[]> zip = EventResultReportFilesTest.unzip(uploads(A, key).get(0));
		Map<String, String> csv = eventCsv(zip.get("event.csv"));
		JsonNode tab = resultTab(eventId);
		JsonNode metrics = tab.path("result").path("metrics");
		assertThat(tab.path("state").asString()).isEqualTo("READY");
		// 결과 탭과 같은 값
		assertThat(csv.get("success_count")).isEqualTo(metrics.path("successCount").asString()).isEqualTo("4");
		assertThat(csv.get("total_stock")).isEqualTo(metrics.path("totalStock").asString()).isEqualTo("4");
		assertThat(new BigDecimal(csv.get("sellout_rate"))).isEqualByComparingTo(metrics.path("selloutRate").decimalValue())
			.isEqualByComparingTo("1");
		assertThat(csv.get("soldout_count")).isEqualTo(metrics.path("soldoutCount").asString()).isEqualTo("2");
		assertThat(csv.get("peak_concurrent")).isEqualTo(metrics.path("peakConcurrent").asString()).isEqualTo("6");
		assertThat(csv.get("max_capacity")).isEqualTo(metrics.path("maxCapacity").asString()).isEqualTo("30000");
		assertThat(new BigDecimal(csv.get("capacity_usage_rate")))
			.isEqualByComparingTo(metrics.path("capacityUsageRate").decimalValue())
			.isEqualByComparingTo("0.0002");
		assertThat(csv.get("peak_at")).isEqualTo(kst(metrics.path("peakAt").asString()))
			.isEqualTo("2026-11-01T10:00:30.000+09:00");
		assertThat(csv.get("abandoned_count"))
			.isEqualTo(tab.path("result").path("composition").path("abandonedCount").asString())
			.isEqualTo("4");
		assertThat(csv.get("start_at")).isEqualTo(kst(tab.path("eventOpenAt").asString()));
		assertThat(csv.get("aggregated_at")).isEqualTo(kst(tab.path("aggregatedAt").asString()))
			.isEqualTo("2026-11-02T09:00:00.000+09:00");
		// 결과 탭에 없는 값: 입장 · 번호표, 비율(÷ 성공 + 품절 + 이탈 = 10), 시간
		assertThat(csv).containsEntry("event_name", "이벤트 6101")
			.containsEntry("tenant_name", "A사")
			.containsEntry("traffic_plan", "M")
			.containsEntry("end_at", "2026-11-01T13:00:00.000+09:00")
			.containsEntry("session_count", "10")
			.containsEntry("ticket_count", "8")
			.containsEntry("session_ratio", "1.0000")
			.containsEntry("ticket_ratio", "0.8000")
			.containsEntry("success_ratio", "0.4000")
			.containsEntry("soldout_ratio", "0.2000")
			.containsEntry("abandoned_ratio", "0.4000")
			.containsEntry("sellout_duration_seconds", "123.250")
			.containsEntry("wait_avg_seconds", "47.958")
			.containsEntry("wait_max_seconds", "121.000");
		// 분당 = 결과 탭 perMinute(대기열 진입 분)
		JsonNode perMinute = tab.path("result").path("perMinute");
		StringBuilder expectedMinutes = new StringBuilder("minute,success,soldout\r\n");
		for (JsonNode m : perMinute) {
			expectedMinutes.append(kst(m.path("minute").asString()))
				.append(',')
				.append(m.path("successCount").asLong())
				.append(',')
				.append(m.path("soldoutCount").asLong())
				.append("\r\n");
		}
		assertThat(EventResultReportFilesTest.csv(zip.get("minutes.csv"))).isEqualTo(expectedMinutes.toString())
			.isEqualTo("minute,success,soldout\r\n" + "2026-11-01T09:58:00.000+09:00,1,0\r\n"
					+ "2026-11-01T09:59:00.000+09:00,1,0\r\n" + "2026-11-01T10:00:00.000+09:00,1,0\r\n"
					+ "2026-11-01T10:01:00.000+09:00,1,2\r\n");
		// 운영 이력: 상태만(detail 원문 없음)
		assertThat(EventResultReportFilesTest.csv(zip.get("history.csv")))
			.isEqualTo("changed_at,status_type,prev_status,new_status,reject_reason\r\n"
					+ "2026-10-20T09:00:00.000+09:00,APPLICATION,APPLIED,APPROVED,\r\n"
					+ "2026-10-20T09:05:00.000+09:00,HOSTING,PENDING,DEPLOY_FAILED,\r\n"
					+ "2026-10-20T09:30:00.000+09:00,HOSTING,DEPLOY_FAILED,DEPLOYED,\r\n");
		// PDF: 이벤트명 · 핵심 지표
		String pdf = EventResultReportFilesTest.pdfText(zip.get("summary.pdf"));
		assertThat(pdf).contains("[6101] 이벤트 6101", "A사", "4 / 4 (소진율 100.0%)", "2명", "6 / 30,000 (사용률 0.0%)",
				"2분 3초", "48.0초", "2분 1초", "배포 대기 → 배포 실패", "배포 실패 → 배포 완료");
		// 최종 사용자 값 · 내부 원인은 zip 어디에도 없다
		for (Map.Entry<String, byte[]> entry : zip.entrySet()) {
			String text = entry.getKey().endsWith(".pdf") ? EventResultReportFilesTest.pdfText(entry.getValue())
					: new String(entry.getValue(), StandardCharsets.UTF_8);
			assertThat(text).as(entry.getKey()).doesNotContain("secret-user", "internal-cause");
		}
		// 메타 · 알림
		assertThat(meta(A)).singleElement()
			.satisfies(row -> assertThat(row).containsEntry("name", "이벤트 결과 — 이벤트 6101")
				.containsEntry("category", "EVENT_RESULT")
				.containsEntry("period_start", start)
				.containsEntry("period_end", start.plusHours(3))
				.containsEntry("file_path", key));
		assertThat(reportNotifications(A))
			.containsExactly("[6101:이벤트 6101] 이벤트 결과 리포트가 생성되었습니다. 리포트 화면에서 내려받을 수 있습니다.");
	}

	/** 발급 0(이력 없음): 시간 · 분당은 빈 값. 재고를 다 못 쓴 경우: 소진까지 걸린 시간은 빈 값, 대기 시간은 있음 */
	@Test
	void noIssuanceAndStockNotUsedUp() throws Exception {
		LocalDateTime start = LocalDateTime.of(2026, 11, 1, 10, 0);
		endedEvent(A, 6111, start);
		summary(A, 6111, 5, 0, 2, "2026-11-01 09:30:00", 0, 0, 10000, "0.0000", NOW.minusHours(2));
		endedEvent(A, 6112, start);
		stock(A, 6112, 10);
		issuance(A, 6112, 11, "u-1", "SUCCESS", "2026-11-01 09:59:00", "2026-11-01 10:00:01");
		issuance(A, 6112, 12, "u-2", "SUCCESS", "2026-11-01 09:59:10", "2026-11-01 10:00:02");
		summary(A, 6112, 2, 2, 2, "2026-11-01 10:00:00", 2, 0, 10, "0.2000", NOW.minusHours(2));

		job(CLOCK).run();

		Map<String, byte[]> none = EventResultReportFilesTest.unzip(uploads(A, "reports/2026/11/event-6111.zip").get(0));
		assertThat(eventCsv(none.get("event.csv"))).containsEntry("success_count", "0")
			.containsEntry("sellout_rate", "0.0000")
			.containsEntry("abandoned_count", "5")
			.containsEntry("session_ratio", "1.0000")
			.containsEntry("abandoned_ratio", "1.0000")
			.containsEntry("sellout_duration_seconds", "")
			.containsEntry("wait_avg_seconds", "")
			.containsEntry("wait_max_seconds", "");
		assertThat(EventResultReportFilesTest.csv(none.get("minutes.csv"))).isEqualTo("minute,success,soldout\r\n");
		assertThat(EventResultReportFilesTest.pdfText(none.get("summary.pdf"))).contains("0 / 10,000 (소진율 0.0%)",
				"발급 기록이 없습니다.", "- (재고를 다 쓰지 않음)");

		Map<String, byte[]> partial = EventResultReportFilesTest.unzip(uploads(A, "reports/2026/11/event-6112.zip").get(0));
		assertThat(eventCsv(partial.get("event.csv"))).containsEntry("success_count", "2")
			.containsEntry("total_stock", "10")
			.containsEntry("sellout_rate", "0.2000")
			.containsEntry("sellout_duration_seconds", "")
			.containsEntry("wait_avg_seconds", "56.500")
			.containsEntry("wait_max_seconds", "61.000");
		assertThat(meta(A)).hasSize(2);
		assertThat(reportNotifications(A)).hasSize(2);
	}

	// ---------------------------------------------------------------- 키

	/** yyyy/mm = 이벤트 오픈(start_at, KST)의 달: 10-31 23:30 → 2026/10, 11-01 00:10 → 2026/11 */
	@Test
	void keyMonthFollowsStartAtInKst() {
		endedEvent(A, 6201, LocalDateTime.of(2026, 10, 31, 23, 30));
		readySummary(A, 6201, NOW.minusHours(1));
		endedEvent(A, 6202, LocalDateTime.of(2026, 11, 1, 0, 10));
		readySummary(A, 6202, NOW.minusHours(1));

		job(CLOCK).run();

		assertThat(reportPutKeys()).containsExactly("reports/2026/10/event-6201.zip", "reports/2026/11/event-6202.zip");
		assertThat(meta(A)).extracting(row -> row.get("file_path"), row -> row.get("period_start"))
			.containsExactly(org.assertj.core.groups.Tuple.tuple("reports/2026/10/event-6201.zip",
					LocalDateTime.of(2026, 10, 31, 23, 30)),
					org.assertj.core.groups.Tuple.tuple("reports/2026/11/event-6202.zip",
							LocalDateTime.of(2026, 11, 1, 0, 10)));
	}

	// ---------------------------------------------------------------- 리스너 · 멱등

	/**
	 * WF4 성공 → 발급 이력 저장 뒤 리스너가 만든다. 같은 메시지가 다시 와도(재전달) 파일만 덮어쓰고, 보충은 메타가 있어 건너뛴다 → 메타 · 알림 한 번.
	 */
	@Test
	void listenerAttemptThenRedeliveryThenBackfillWritesMetaAndNotificationOnce() {
		long eventId = 6301;
		data.event(eventId, A).approved("HOSTING", "OPEN", slug(eventId)).insert();
		issuance(A, eventId, 21, "u-1", "SUCCESS", "2026-11-01 09:59:00", "2026-11-01 10:00:01");
		EventResultReportJob job = job(Clock.system(ZoneId.of("Asia/Seoul")));
		LifecycleResultHandler handler = handler(Optional.of(job));
		String key = EventResultReportService.key(eventId, TestData.FUTURE);

		handler.handle(wf4Succeeded(eventId, A));

		assertThat(uploads(A, key)).hasSize(1);
		assertThat(meta(A)).hasSize(1);
		assertThat(reportNotifications(A)).hasSize(1);

		handler.handle(wf4Succeeded(eventId, A));
		job.run();

		assertThat(uploads(A, key)).hasSize(2);
		assertThat(meta(A)).hasSize(1);
		assertThat(reportNotifications(A)).hasSize(1);
		// WF4 처리 결과도 그대로(상태 로그 2줄 · 집계 완료 알림 1건)
		assertThat(data.statusLogs(A, eventId)).hasSize(2);
		assertThat(data.count("SELECT COUNT(*) FROM t_%s.tenant_notification WHERE category = 'EVENT'".formatted(A)))
			.isEqualTo(1);
	}

	/** 보충이 먼저 만들고 리스너가 나중에 와도(스위치를 늦게 켬 · 재전달) 메타 · 알림 한 번 */
	@Test
	void backfillThenListenerWritesMetaAndNotificationOnce() {
		long eventId = 6302;
		data.event(eventId, A).approved("HOSTING", "OPEN", slug(eventId)).insert();
		EventResultReportJob job = job(Clock.system(ZoneId.of("Asia/Seoul")));
		handler(Optional.empty()).handle(wf4Succeeded(eventId, A));
		assertThat(meta(A)).isEmpty();

		job.run();
		handler(Optional.of(job)).handle(wf4Succeeded(eventId, A));

		assertThat(uploads(A, EventResultReportService.key(eventId, TestData.FUTURE))).hasSize(2);
		assertThat(meta(A)).hasSize(1);
		assertThat(reportNotifications(A)).hasSize(1);
	}

	/** 리포트 저장(S3)이 실패해도 WF4 처리(상태 전이 · 발급 이력 저장)는 그대로이고 예외가 밖으로 나가지 않는다 → 보충이 만든다 */
	@Test
	void listenerSwallowsReportFailureAndKeepsWf4Result() {
		long eventId = 6303;
		data.event(eventId, A).approved("HOSTING", "OPEN", slug(eventId)).insert();
		doThrow(S3Exception.builder().message("AccessDenied").build()).when(storage)
			.put(any(), startsWith("reports/"), any(), any());
		EventResultReportJob job = job(Clock.system(ZoneId.of("Asia/Seoul")));

		assertThatCode(() -> handler(Optional.of(job)).handle(wf4Succeeded(eventId, A))).doesNotThrowAnyException();

		assertThat(data.registry(eventId)).containsEntry("hosting_status", "ENDED").containsEntry("event_status", "EXPIRED");
		Map<String, Object> summary = data.jdbc()
			.queryForMap("SELECT * FROM t_%s.event_result_summary WHERE event_id = ?".formatted(A), eventId);
		assertThat(summary.get("history_generated_at")).isNotNull();
		assertThat(summary).containsEntry("history_file_path", "issuance-history/6303.csv");
		assertThat(meta(A)).isEmpty();
		assertThat(reportNotifications(A)).isEmpty();

		reset(storage);
		job.run();

		assertThat(meta(A)).hasSize(1);
		assertThat(reportNotifications(A)).hasSize(1);
	}

	/** 리포트 쪽 어떤 예외(PDF · 읽기 등)도 리스너 밖으로 나가지 않는다 */
	@Test
	void listenerSwallowsAnyReportError() {
		long eventId = 6304;
		data.event(eventId, A).approved("HOSTING", "OPEN", slug(eventId)).insert();
		EventResultReportService broken = mock(EventResultReportService.class);
		when(broken.create(any(), anyLong())).thenThrow(new IllegalStateException("PDF 실패"));
		EventResultReportJob job = new EventResultReportJob(lock, tenants, router, reader, broken, 3, CLOCK);

		assertThatCode(() -> handler(Optional.of(job)).handle(wf4Succeeded(eventId, A))).doesNotThrowAnyException();

		assertThat(data.registry(eventId)).containsEntry("hosting_status", "ENDED");
		assertThat(data.count("SELECT COUNT(*) FROM t_%s.event_result_summary WHERE event_id = ? AND history_generated_at"
				.formatted(A) + " IS NOT NULL", eventId)).isEqualTo(1);
	}

	/** 잠금을 바로 못 잡으면(보충 · 다른 태스크가 만드는 중) 리스너는 건너뛴다 → 보충이 만든다 */
	@Test
	void listenerSkipsWhenLockIsBusy() {
		long eventId = 6305;
		data.event(eventId, A).approved("HOSTING", "OPEN", slug(eventId)).insert();
		EventResultReportJob job = job(Clock.system(ZoneId.of("Asia/Seoul")));
		doReturn(false).when(lock).runIfAcquired(any());

		handler(Optional.of(job)).handle(wf4Succeeded(eventId, A));

		assertThat(reportPutKeys()).isEmpty();
		assertThat(meta(A)).isEmpty();

		when(lock.runIfAcquired(any())).thenAnswer(inv -> {
			inv.<Runnable>getArgument(0).run();
			return true;
		});
		job.run();

		assertThat(meta(A)).hasSize(1);
		assertThat(reportNotifications(A)).hasSize(1);
	}

	// ---------------------------------------------------------------- 보충 대상

	/**
	 * 보충 대상 = READY 테넌트마다 발급 이력 저장 시각이 지금 − 3일 이후(같으면 포함)이고 리포트 메타가 없는 이벤트 중 결과 탭 READY.
	 * 경계 1초 전 · 이미 있는 리포트 · 발급 이력 저장 전 · 종료 실패 · 결과 행 없음은 건너뛴다. 다시 돌아도 새로 만들지 않는다.
	 */
	@Test
	void backfillTargets() {
		LocalDateTime start = LocalDateTime.of(2026, 10, 30, 10, 0);
		data.tenant(C, "C사", "INACTIVE", "PROVISIONING");
		endedEvent(A, 6401, start);
		readySummary(A, 6401, SINCE);
		endedEvent(A, 6402, start);
		readySummary(A, 6402, SINCE.minusSeconds(1));
		endedEvent(A, 6403, start);
		readySummary(A, 6403, NOW.minusHours(1));
		reportMeta(A, "reports/2026/10/event-6403.zip");
		endedEvent(A, 6404, start);
		summary(A, 6404, 3, 3, 3, null, 0, 0, 0, null, null);
		data.event(6405, A).startAt(start).approved("END_FAILED", "EXPIRED", slug(6405)).insert();
		readySummary(A, 6405, NOW.minusHours(1));
		endedEvent(A, 6406, start);
		endedEvent(B, 6407, start);
		readySummary(B, 6407, NOW.minusHours(2));

		job(CLOCK).run();

		assertThat(reportPutKeys()).containsExactly("reports/2026/10/event-6401.zip", "reports/2026/10/event-6407.zip");
		assertThat(meta(A)).extracting(row -> row.get("file_path"))
			.containsExactly("reports/2026/10/event-6403.zip", "reports/2026/10/event-6401.zip");
		assertThat(meta(B)).extracting(row -> row.get("file_path")).containsExactly("reports/2026/10/event-6407.zip");
		assertThat(reportNotifications(A)).hasSize(1);
		assertThat(reportNotifications(B)).hasSize(1);

		job(CLOCK).run();

		assertThat(reportPutKeys()).hasSize(2);
		assertThat(meta(A)).hasSize(2);
	}

	/** 잠금을 못 잡으면(다른 태스크가 도는 중) 보충 회차를 건너뛴다 */
	@Test
	void backfillSkipsWhenLockIsHeldElsewhere() {
		endedEvent(A, 6411, LocalDateTime.of(2026, 10, 30, 10, 0));
		readySummary(A, 6411, NOW.minusHours(1));
		doReturn(false).when(lock).runIfAcquired(any());

		job(CLOCK).run();

		assertThat(reportPutKeys()).isEmpty();
		assertThat(meta(A)).isEmpty();
	}

	// ---------------------------------------------------------------- CSV 수식 가림

	/** 이벤트명이 =로 시작하면 리포트 CSV 칸은 '로 시작(메타 이름 · 알림은 원문 그대로) */
	@Test
	void reportCsvGuardsFormulaLikeNames() throws Exception {
		data.event(6501, A)
			.name("=1+1 쿠폰")
			.startAt(LocalDateTime.of(2026, 11, 1, 10, 0))
			.approved("ENDED", "EXPIRED", slug(6501))
			.insert();
		readySummary(A, 6501, NOW.minusHours(1));

		job(CLOCK).run();

		Map<String, byte[]> zip = EventResultReportFilesTest.unzip(uploads(A, "reports/2026/11/event-6501.zip").get(0));
		assertThat(eventCsv(zip.get("event.csv"))).containsEntry("event_name", "'=1+1 쿠폰");
		assertThat(EventResultReportFilesTest.pdfText(zip.get("summary.pdf"))).contains("[6501] =1+1 쿠폰");
		assertThat(meta(A)).singleElement().satisfies(row -> assertThat(row).containsEntry("name", "이벤트 결과 — =1+1 쿠폰"));
	}

}
