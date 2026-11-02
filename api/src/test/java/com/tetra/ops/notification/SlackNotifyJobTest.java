package com.tetra.ops.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.tetra.ops.common.DbLock;
import com.tetra.ops.common.time.Kst;
import com.tetra.ops.config.TetraProperties;
import com.tetra.ops.domain.platform.OperatorNotificationRepository;
import com.tetra.ops.monitoring.DlqQueue;
import com.tetra.ops.support.FakeSlack;
import com.tetra.ops.support.IntegrationTest;
import com.tetra.ops.support.MySqlTestDatabase;

import tools.jackson.databind.json.JsonMapper;

/**
 * Slack 발송 작업. 실제 DB(V5 사본) + FakeSlack(실제 HTTP), 시계는 고정. 스케줄은 테스트 프로필에서 꺼져 있어(빈 없음) run()을 직접 부른다.
 * 잠금은 목(통합 테스트 풀이 1개) — 두 태스크 동시 실행만 실제 GET_LOCK(커넥션을 따로 주는 DataSource)을 쓴다.
 */
@ExtendWith(OutputCaptureExtension.class)
class SlackNotifyJobTest extends IntegrationTest {

	/** 2026-11-02 15:00 KST */
	static final Instant NOW = Instant.parse("2026-11-02T06:00:00Z");

	static final LocalDateTime NOW_KST = LocalDateTime.of(2026, 11, 2, 15, 0);

	static final String TOKEN = "xoxb-job-test-token-9876543210";

	static final String URGENT_CH = "CURGENT01";

	static final String WARNING_CH = "CWARNING01";

	static final String INFO_CH = "CINFO01";

	static final String BASE = "https://console.example.test:8444";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final FakeSlack fake = new FakeSlack();

	@AfterAll
	static void stop() {
		fake.close();
	}

	@Autowired
	OperatorNotificationRepository rows;

	@Autowired
	NotificationService notifications;

	@Autowired
	ApplicationContext context;

	private final DbLock lock = mock(DbLock.class);

	@BeforeEach
	void setUp() {
		fake.reset();
		when(lock.runIfAcquired(any())).thenAnswer(inv -> {
			inv.<Runnable>getArgument(0).run();
			return true;
		});
	}

	private static TetraProperties.Slack settings(Duration readTimeout) {
		return new TetraProperties.Slack(true, TOKEN, new TetraProperties.SlackChannels(URGENT_CH, WARNING_CH, INFO_CH),
				Duration.ofSeconds(10), Duration.ofMinutes(10), Duration.ofSeconds(2), readTimeout, fake.url());
	}

	private SlackNotifyJob job(Instant at) {
		return job(at, Duration.ofSeconds(2), Duration.ZERO, lock);
	}

	private SlackNotifyJob job(Instant at, Duration readTimeout, Duration channelGap, DbLock lock) {
		TetraProperties.Slack settings = settings(readTimeout);
		return new SlackNotifyJob(lock, rows, new SlackClient(settings, JSON), settings, BASE, channelGap,
				Clock.fixed(at, Kst.ZONE));
	}

	/** Portal처럼 V5 열을 넣지 않는 INSERT(8열). slack_* 는 DB 기본값(PENDING · 0 · NULL) */
	private long insert(String category, String priority, LocalDateTime occurredAt, String content) {
		data.jdbc().update("""
				INSERT INTO tetra_platform.operator_notification (tenant_id, event_id, category, priority, occurred_at, content, is_read)
				VALUES (NULL, NULL, ?, ?, ?, ?, 0)""", category, priority, occurredAt, content);
		return data.jdbc().queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long insert(String priority, String content) {
		return insert("SYSTEM", priority, NOW_KST.minusMinutes(1), content);
	}

	/** 보내는 중에 멈춘 행을 흉내 낸다 */
	private void sending(long id, LocalDateTime lease, String channel) {
		data.jdbc().update("""
				UPDATE tetra_platform.operator_notification
				   SET slack_status = 'SENDING', slack_attempts = 1, slack_next_at = ?, slack_channel = ?
				 WHERE notification_id = ?""", lease, channel, id);
	}

	record Slack(String status, int attempts, LocalDateTime nextAt, LocalDateTime sentAt, String error, String channel,
			String ts) {
	}

	private Slack slack(long id) {
		return data.jdbc().queryForObject("""
				SELECT slack_status, slack_attempts, slack_next_at, slack_sent_at, slack_error, slack_channel, slack_ts
				  FROM tetra_platform.operator_notification WHERE notification_id = ?""",
				(rs, n) -> new Slack(rs.getString(1), rs.getInt(2), rs.getObject(3, LocalDateTime.class),
						rs.getObject(4, LocalDateTime.class), rs.getString(5), rs.getString(6), rs.getString(7)),
				id);
	}

	// ---------------------------------------------------------------- 발송

	/** Console(JPA)이 넣은 행과 Portal(8열 INSERT)이 넣은 행 모두 기본값 PENDING → 우선순위 채널로 SENT */
	@Test
	void sendsConsoleAndPortalRowsToPriorityChannels(CapturedOutput output) {
		assertThat(notifications.dlqDetected(new DlqQueue("tetra-test-scheduler-dlq", DlqQueue.Kind.SCHEDULER, null), 2,
				NOW_KST.toLocalDate())).isTrue();
		long console = rows.findAll().getFirst().getNotificationId();
		long applied = insert("APPLICATION", "WARNING", NOW_KST.minusMinutes(1),
				"[테넌트A] [7] 이벤트 신청이 접수되었습니다. 승인 검토가 필요합니다.");
		long rejected = insert("APPLICATION", "INFO", NOW_KST.minusMinutes(1),
				"[테넌트A] [7:봄 쿠폰] 이벤트 신청을 반려했습니다. 반려 사유: 담당자 010-0000-0000");
		assertThat(slack(applied).status()).isEqualTo("PENDING");

		job(NOW).run();

		assertThat(fake.requests()).extracting(FakeSlack.Request::channel).containsExactly(URGENT_CH, WARNING_CH, INFO_CH);
		assertThat(fake.requests()).allSatisfy(r -> assertThat(r.authorization()).isEqualTo("Bearer " + TOKEN));
		assertThat(fake.requests().get(0).text()).startsWith("*긴급* · 시스템 · ")
			.contains("스케줄러 DLQ에 메시지 2건")
			.endsWith("<" + BASE + "/logs/notifications?priority=URGENT|알림센터>");
		assertThat(fake.requests().get(1).text()).isEqualTo("*주의* · 신청 · 14:59\n"
				+ "[테넌트A] [7] 이벤트 신청이 접수되었습니다. 승인 검토가 필요합니다.\n"
				+ "<" + BASE + "/logs/notifications?priority=WARNING|알림센터>");
		assertThat(fake.requests().get(2).text()).contains("이벤트 신청을 반려했습니다.").doesNotContain("반려 사유")
			.doesNotContain("010-0000-0000");
		for (long id : new long[] { console, applied, rejected }) {
			Slack s = slack(id);
			assertThat(s.status()).isEqualTo("SENT");
			assertThat(s.attempts()).isEqualTo(1);
			assertThat(s.sentAt()).isEqualTo(NOW_KST);
			assertThat(s.nextAt()).isNull();
			assertThat(s.error()).isNull();
			assertThat(s.ts()).startsWith("1762070400.");
		}
		assertThat(slack(console).channel()).isEqualTo(URGENT_CH);
		assertThat(slack(rejected).channel()).isEqualTo(INFO_CH);
		assertThat(output).contains("Slack 발송: 알림 #" + applied + "(WARNING) → " + WARNING_CH + " SENT")
			.doesNotContain(TOKEN)
			.doesNotContain("010-0000-0000");

		// 다시 돌아도 보낼 것이 없다
		job(NOW.plus(Duration.ofMinutes(1))).run();
		assertThat(fake.requests()).hasSize(3);
	}

	/** 확실한 실패(503): 시도 수 +1, 30초 → 1분 → 2분 → 5분 뒤, 5번째 실패면 FAILED */
	@Test
	void retriesWithBackoffThenFails() {
		long id = insert("INFO", "문구");
		for (int i = 0; i < 5; i++) {
			fake.then(503, "{}");
		}
		Instant at = NOW;
		List<Duration> waits = List.of(Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(2),
				Duration.ofMinutes(5));
		for (int attempt = 1; attempt <= 4; attempt++) {
			job(at).run();
			Duration wait = waits.get(attempt - 1);
			Slack s = slack(id);
			assertThat(s.status()).as("시도 %d", attempt).isEqualTo("PENDING");
			assertThat(s.attempts()).isEqualTo(attempt);
			assertThat(s.error()).isEqualTo("HTTP_503");
			assertThat(s.nextAt()).isEqualTo(LocalDateTime.ofInstant(at, Kst.ZONE).plus(wait));
			// 다음 시도 시각 전에는 보내지 않는다
			job(at.plus(wait).minusSeconds(1)).run();
			assertThat(fake.requests()).hasSize(attempt);
			at = at.plus(wait);
		}

		job(at).run();

		Slack failed = slack(id);
		assertThat(failed.status()).isEqualTo("FAILED");
		assertThat(failed.attempts()).isEqualTo(5);
		assertThat(failed.nextAt()).isNull();
		assertThat(failed.error()).isEqualTo("HTTP_503");
		job(at.plus(Duration.ofHours(1))).run();
		assertThat(fake.requests()).hasSize(5);
	}

	/** 429: PENDING · Retry-After 뒤, 시도로 세지 않고, 이번 회차에 그 채널은 더 보내지 않는다(다른 채널은 보냄) */
	@Test
	void rateLimitDefersWithoutCountingAndPausesThatChannel() {
		long info1 = insert("INFO", "첫째");
		long info2 = insert("INFO", "둘째");
		long warning = insert("WARNING", "셋째");
		fake.thenRateLimited(7);

		job(NOW).run();

		assertThat(fake.requests()).extracting(FakeSlack.Request::channel).containsExactly(INFO_CH, WARNING_CH);
		assertThat(slack(info1)).extracting(Slack::status, Slack::attempts, Slack::nextAt, Slack::error)
			.containsExactly("PENDING", 0, NOW_KST.plusSeconds(7), "HTTP_429");
		assertThat(slack(info2)).extracting(Slack::status, Slack::attempts, Slack::nextAt)
			.containsExactly("PENDING", 0, null);
		assertThat(slack(warning).status()).isEqualTo("SENT");

		job(NOW.plusSeconds(7)).run();

		assertThat(fake.requests()).hasSize(4);
		assertThat(slack(info1)).extracting(Slack::status, Slack::attempts).containsExactly("SENT", 1);
		assertThat(slack(info2)).extracting(Slack::status, Slack::attempts).containsExactly("SENT", 1);
	}

	@Test
	void permanentErrorFailsAtOnce() {
		long id = insert("INFO", "문구");
		fake.thenError("not_in_channel");

		job(NOW).run();
		job(NOW.plus(Duration.ofMinutes(10))).run();

		assertThat(slack(id)).extracting(Slack::status, Slack::attempts, Slack::error)
			.containsExactly("FAILED", 1, "not_in_channel");
		assertThat(fake.requests()).hasSize(1);
	}

	// ---------------------------------------------------------------- 보내지 않을 행

	/** 한 번도 시도하지 않고 10분 넘은 PENDING은 SKIPPED(STALE). V5 적용 때 SKIPPED된 행 · 재시도 대기 행은 그대로 */
	@Test
	void staleRowsAreSkippedAndOldRowsAreNotSent(CapturedOutput output) {
		long stale = insert("SYSTEM", "INFO", NOW_KST.minusMinutes(11), "오래됨");
		long fresh = insert("SYSTEM", "INFO", NOW_KST.minusMinutes(9), "새것");
		long beforeV5 = insert("INFO", "V5 전");
		data.jdbc().update("UPDATE tetra_platform.operator_notification SET slack_status = 'SKIPPED', slack_error = 'BEFORE_V5'"
				+ " WHERE notification_id = ?", beforeV5);
		long retrying = insert("SYSTEM", "INFO", NOW_KST.minusMinutes(20), "재시도 대기");
		data.jdbc().update("UPDATE tetra_platform.operator_notification SET slack_attempts = 1, slack_next_at = ?"
				+ " WHERE notification_id = ?", NOW_KST.minusSeconds(1), retrying);

		job(NOW).run();

		assertThat(slack(stale)).extracting(Slack::status, Slack::error).containsExactly("SKIPPED", "STALE");
		assertThat(slack(beforeV5)).extracting(Slack::status, Slack::error).containsExactly("SKIPPED", "BEFORE_V5");
		assertThat(slack(fresh).status()).isEqualTo("SENT");
		assertThat(slack(retrying)).extracting(Slack::status, Slack::attempts).containsExactly("SENT", 2);
		assertThat(fake.requests()).extracting(FakeSlack.Request::text)
			.noneMatch(t -> t.contains("오래됨") || t.contains("V5 전"));
		assertThat(output).contains("알림 1건은 보내지 않습니다(SKIPPED · STALE)");
	}

	// ---------------------------------------------------------------- 보냈는지 모름

	/** 선점 만료(보내는 중에 멈춤) → 모름. INFO는 UNKNOWN, URGENT는 한 번만 '[재전송]', 재전송 중 멈췄던 URGENT는 UNKNOWN */
	@Test
	void expiredLeaseIsUnknownAndUrgentIsResentOnce() {
		long info = insert("INFO", "멈춘 정보");
		long urgent = insert("URGENT", "멈춘 긴급");
		long urgentResent = insert("URGENT", "재전송 중 멈춘 긴급");
		long inFlight = insert("INFO", "아직 보내는 중");
		sending(info, NOW_KST.minusSeconds(1), null);
		sending(urgent, NOW_KST.minusSeconds(1), null);
		sending(urgentResent, NOW_KST.minusSeconds(1), URGENT_CH);
		sending(inFlight, NOW_KST.plusSeconds(60), null);

		job(NOW).run();

		assertThat(fake.requests()).isEmpty();
		assertThat(slack(info)).extracting(Slack::status, Slack::channel, Slack::error, Slack::nextAt)
			.containsExactly("UNKNOWN", INFO_CH, "LEASE_EXPIRED", null);
		assertThat(slack(urgent)).extracting(Slack::status, Slack::channel, Slack::error, Slack::nextAt)
			.containsExactly("PENDING", URGENT_CH, "LEASE_EXPIRED", NOW_KST.plusSeconds(30));
		assertThat(slack(urgentResent)).extracting(Slack::status, Slack::channel).containsExactly("UNKNOWN", URGENT_CH);
		assertThat(slack(inFlight)).extracting(Slack::status, Slack::nextAt)
			.containsExactly("SENDING", NOW_KST.plusSeconds(60));

		job(NOW.plusSeconds(30)).run();

		assertThat(fake.requests()).singleElement().satisfies(r -> {
			assertThat(r.channel()).isEqualTo(URGENT_CH);
			assertThat(r.text()).startsWith("[재전송] *긴급* · 시스템 · ").contains("멈춘 긴급");
		});
		assertThat(slack(urgent)).extracting(Slack::status, Slack::attempts, Slack::error)
			.containsExactly("SENT", 2, "LEASE_EXPIRED");
	}

	/** 읽기 시간 초과: WARNING은 UNKNOWN으로 끝, URGENT는 한 번 더 '[재전송]' — 재전송도 모르면 UNKNOWN으로 끝 */
	@Test
	void readTimeoutUrgentIsResentOnceThenGivesUp() {
		long urgent = insert("URGENT", "긴급 문구");
		long warning = insert("WARNING", "주의 문구");
		fake.thenSlowOk(1500).thenSlowOk(1500);
		Duration readTimeout = Duration.ofMillis(300);

		job(NOW, readTimeout, Duration.ZERO, lock).run();

		assertThat(slack(urgent)).extracting(Slack::status, Slack::attempts, Slack::channel, Slack::error, Slack::nextAt)
			.containsExactly("PENDING", 1, URGENT_CH, "READ_TIMEOUT", NOW_KST.plusSeconds(30));
		assertThat(slack(warning)).extracting(Slack::status, Slack::attempts, Slack::channel, Slack::error, Slack::nextAt)
			.containsExactly("UNKNOWN", 1, WARNING_CH, "READ_TIMEOUT", null);

		fake.thenSlowOk(1500);
		job(NOW.plusSeconds(30), readTimeout, Duration.ZERO, lock).run();

		assertThat(fake.requests()).hasSize(3);
		assertThat(fake.requests().get(2).text()).startsWith("[재전송] *긴급*");
		assertThat(slack(urgent)).extracting(Slack::status, Slack::attempts, Slack::channel, Slack::error)
			.containsExactly("UNKNOWN", 2, URGENT_CH, "READ_TIMEOUT");

		job(NOW.plus(Duration.ofMinutes(5)), readTimeout, Duration.ZERO, lock).run();
		assertThat(fake.requests()).hasSize(3);
	}

	@Test
	void urgentResendCanSucceed() {
		long urgent = insert("URGENT", "긴급 문구");
		fake.thenSlowOk(1500);
		Duration readTimeout = Duration.ofMillis(300);

		job(NOW, readTimeout, Duration.ZERO, lock).run();
		job(NOW.plusSeconds(30), readTimeout, Duration.ZERO, lock).run();

		assertThat(fake.requests()).extracting(FakeSlack.Request::text)
			.satisfiesExactly(first -> assertThat(first).startsWith("*긴급*"),
					second -> assertThat(second).startsWith("[재전송] *긴급*"));
		Slack s = slack(urgent);
		assertThat(s).extracting(Slack::status, Slack::attempts, Slack::channel, Slack::error)
			.containsExactly("SENT", 2, URGENT_CH, "READ_TIMEOUT");
		assertThat(s.ts()).isNotNull();
	}

	// ---------------------------------------------------------------- 간격 · 동시 실행 · 스위치

	/** 같은 채널은 간격을 두고 보낸다 */
	@Test
	void sameChannelIsPaced() {
		for (int i = 0; i < 3; i++) {
			insert("INFO", "정보 " + i);
		}

		job(NOW, Duration.ofSeconds(2), Duration.ofMillis(300), lock).run();

		List<FakeSlack.Request> requests = fake.requests();
		assertThat(requests).hasSize(3);
		for (int i = 1; i < requests.size(); i++) {
			assertThat(Duration.ofNanos(requests.get(i).receivedNanos() - requests.get(i - 1).receivedNanos()))
				.isGreaterThanOrEqualTo(Duration.ofMillis(280));
		}
	}

	/** 태스크 둘이 동시에 돌아도 알림마다 한 번만 — 실제 GET_LOCK(하나만 돈다)과, 잠금이 없어도 행 선점(조건부 UPDATE)으로 */
	@Test
	void twoTasksSendEachNotificationOnce() throws Exception {
		fake.delay(150);
		for (int i = 0; i < 4; i++) {
			insert("INFO", "동시 " + i);
		}
		DbLock lockA = new DbLock(lockDataSource(), SlackNotifyJob.LOCK_NAME);
		DbLock lockB = new DbLock(lockDataSource(), SlackNotifyJob.LOCK_NAME);
		runTogether(job(NOW, Duration.ofSeconds(2), Duration.ZERO, lockA), job(NOW, Duration.ofSeconds(2), Duration.ZERO, lockB));

		assertThat(sentCountByText()).hasSize(4).allSatisfy((text, count) -> assertThat(count).isEqualTo(1L));

		// 잠금이 둘 다 통과해도(선점 만료 뒤 겹침 등) 선점이 막는다
		fake.reset();
		fake.delay(150);
		for (int i = 0; i < 4; i++) {
			insert("WARNING", "선점 " + i);
		}
		runTogether(job(NOW), job(NOW));

		assertThat(sentCountByText()).hasSize(4).allSatisfy((text, count) -> assertThat(count).isEqualTo(1L));
		assertThat(data.jdbc().queryForObject("SELECT COUNT(*) FROM tetra_platform.operator_notification"
				+ " WHERE slack_status <> 'SENT'", Integer.class)).isZero();
	}

	private static DriverManagerDataSource lockDataSource() {
		return new DriverManagerDataSource(MySqlTestDatabase.appJdbcUrl("tetra_platform"), MySqlTestDatabase.APP_USER,
				MySqlTestDatabase.APP_PASSWORD);
	}

	private static void runTogether(SlackNotifyJob a, SlackNotifyJob b) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<Future<?>> futures = List.of(executor.submit(() -> {
				start.await();
				a.run();
				return null;
			}), executor.submit(() -> {
				start.await();
				b.run();
				return null;
			}));
			start.countDown();
			for (Future<?> f : futures) {
				f.get();
			}
		}
		finally {
			executor.shutdownNow();
		}
	}

	private static Map<String, Long> sentCountByText() {
		return fake.requests()
			.stream()
			.collect(Collectors.groupingBy(r -> r.text().split("\n")[1], Collectors.counting()));
	}

	/** 테스트 프로필은 두 작업 모두 꺼짐(빈 없음). 스케줄러 스레드는 작업마다 하나 이상 */
	@Test
	void jobsAreOffInTestProfileAndSchedulerHasThreadPerJob() {
		assertThat(context.getBeansOfType(SlackNotifyJob.class)).isEmpty();
		assertThat(context.getBean(ThreadPoolTaskScheduler.class).getScheduledThreadPoolExecutor().getCorePoolSize())
			.isGreaterThanOrEqualTo(2);
	}

}
