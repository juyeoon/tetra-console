package com.tetra.ops.notification;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.tetra.ops.common.DbLock;
import com.tetra.ops.common.time.Kst;
import com.tetra.ops.config.TetraProperties;
import com.tetra.ops.domain.platform.OperatorNotification;
import com.tetra.ops.domain.platform.OperatorNotificationRepository;
import com.tetra.ops.notification.SlackClient.Result;

import tools.jackson.databind.json.JsonMapper;

/**
 * 운영자 알림 Slack 발송(결정 11-02). 10초마다(SLACK_NOTIFY_INTERVAL, fixedDelay) operator_notification.slack_status로 보낼 행을 찾아
 * 우선순위 채널(URGENT · WARNING · INFO)로 chat.postMessage를 보낸다. Console이 넣은 행과 Portal이 넣은 행(slack 열을 모르고 넣어
 * DB 기본값 PENDING)을 같이 처리한다. 스위치 tetra.slack.enabled(SLACK_NOTIFY_ENABLED, 기본 false)가 꺼져 있으면 이 빈이 없다.
 * 1분 주기 자동화 확인과는 스위치 · 잠금 · 스케줄러 스레드가 따로다(SchedulingConfig).
 * <p>
 * 중복 방지는 2중: 전용 GET_LOCK(tetra:slack-notify)을 잡은 태스크 하나만 회차를 돌고, 행마다 조건부 UPDATE(PENDING → SENDING)가
 * 1행일 때만 보낸다. SENDING의 slack_next_at은 선점 만료 시각(lease)이고 결과 UPDATE는 그 값이 맞을 때만 바뀐다.
 * HTTP는 트랜잭션 · DB 커넥션 없이 부르고, 선점 · 결과 UPDATE는 각각 짧은 트랜잭션이다(OperatorNotificationRepository).
 * <p>
 * 한 회차: ① 선점이 만료된 SENDING → 보냈는지 모름(⑤) ② 한 번도 시도하지 않고 maxAge(기본 10분)보다 오래된 PENDING → SKIPPED(STALE)
 * ③ 보낼 PENDING(다음 시도 시각이 없거나 지남)을 id 순으로 20건 → 행마다 선점 → 같은 채널은 1초 간격 → 발송 → 결과 반영.
 * 결과({@link SlackClient}):
 * <pre>
 * SENT          SENT · slack_sent_at · slack_channel · slack_ts
 * RATE_LIMITED  PENDING, 지금 + Retry-After, 시도 수 되돌림, 이번 회차에 그 채널은 더 보내지 않음
 * RETRY         시도 5회째면 FAILED, 아니면 PENDING · 30초 → 1분 → 2분 → 5분 뒤
 * FAILED        FAILED(다시 해도 안 될 오류)
 * UNKNOWN(⑤)    UNKNOWN — 다시 보내지 않는다. 단 URGENT는 한 번만 더 '[재전송]'으로 보낸다: 첫 '모름'에서 slack_channel에
 *               보냈을 수 있는 채널을 남기고 PENDING(30초 뒤)으로 돌린다. SENT 전 행에 채널이 있으면 재전송 차례 — 같은 채널로 보내고,
 *               재전송도 모르면 UNKNOWN으로 끝난다(재전송 중 확실한 실패는 위 규칙대로 재시도 · FAILED)
 * </pre>
 * 로그: 행마다 한 줄(알림 번호 · 우선순위 · 채널 · 결과 코드만, 문구 · 토큰 없음). DB가 내려가 회차가 계속 실패하면 첫 오류만 ERROR로 남기고
 * 이어지는 동안은 60회(약 10분)마다 WARN 한 줄, 회복되면 INFO 한 줄.
 */
@Component
@ConditionalOnProperty(prefix = "tetra.slack", name = "enabled", havingValue = "true")
class SlackNotifyJob {

	private static final Logger log = LoggerFactory.getLogger(SlackNotifyJob.class);

	static final String LOCK_NAME = "tetra:slack-notify";

	/** 한 번에 고르는 행 수. 남으면 다음 회차 */
	static final int BATCH = 20;

	/** 확실한 실패를 몇 번째 시도까지 다시 하는지(이 횟수째 실패면 FAILED) */
	static final int MAX_ATTEMPTS = 5;

	/** n번째 시도가 실패하면 BACKOFF[n − 1] 뒤 다시(4번째 뒤부터는 마지막 값) */
	static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(2),
			Duration.ofMinutes(5));

	/** URGENT 첫 '모름' 뒤 재전송까지 */
	static final Duration RESEND_AFTER = BACKOFF.get(0);

	/** 같은 채널 발송 간격(Slack chat.postMessage: 채널당 대체로 초당 1건) */
	static final Duration CHANNEL_GAP = Duration.ofSeconds(1);

	/** 한 회차에서 새 행을 고르는 시간 상한. 넘으면 남은 행은 다음 회차 */
	static final Duration ROUND_BUDGET = Duration.ofSeconds(30);

	/** 선점 만료 = HTTP 최대 시간(연결 + 읽기) + 여유 1분 */
	static final Duration LEASE_MARGIN = Duration.ofMinutes(1);

	/** DB가 계속 안 될 때 WARN 간격(회차 수) */
	static final int FAILURE_LOG_EVERY = 60;

	static final String URGENT = "URGENT";

	static final String LEASE_EXPIRED = "LEASE_EXPIRED";

	static final String NO_CHANNEL = "NO_CHANNEL";

	private final DbLock lock;

	private final OperatorNotificationRepository rows;

	private final SlackClient slack;

	private final TetraProperties.Slack settings;

	private final String consoleBaseUrl;

	private final Duration channelGap;

	private final Duration lease;

	private final Clock clock;

	/** 연속으로 실패한 회차 수(스케줄러 스레드 하나만 쓴다) */
	private int failedRounds;

	@Autowired
	SlackNotifyJob(DataSource dataSource, OperatorNotificationRepository rows, TetraProperties properties,
			JsonMapper jsonMapper, Clock clock) {
		this(new DbLock(dataSource, LOCK_NAME), rows, new SlackClient(properties.slack(), jsonMapper), properties.slack(),
				properties.console().baseUrl(), CHANNEL_GAP, clock);
	}

	/** 테스트용: 잠금 · 클라이언트 · 채널 간격 · 시계를 직접 준다 */
	SlackNotifyJob(DbLock lock, OperatorNotificationRepository rows, SlackClient slack, TetraProperties.Slack settings,
			String consoleBaseUrl, Duration channelGap, Clock clock) {
		this.lock = lock;
		this.rows = rows;
		this.slack = slack;
		this.settings = settings;
		this.consoleBaseUrl = consoleBaseUrl;
		this.channelGap = channelGap;
		this.lease = wholeSeconds(settings.connectTimeout().plus(settings.readTimeout()).plus(LEASE_MARGIN));
		this.clock = clock;
	}

	@Scheduled(initialDelayString = "${tetra.slack.interval:PT10S}", fixedDelayString = "${tetra.slack.interval:PT10S}")
	void scheduled() {
		run();
	}

	/** 한 회차. 잠금을 못 잡으면(다른 태스크가 도는 중) 아무것도 하지 않는다. 예외는 여기서 끝낸다(다음 회차는 그대로 돈다) */
	void run() {
		try {
			lock.runIfAcquired(this::round);
			if (failedRounds > 0) {
				log.info("Slack 발송: 회차가 다시 정상입니다(앞서 {}회 연속 오류)", failedRounds);
				failedRounds = 0;
			}
		}
		catch (RuntimeException ex) {
			failedRounds++;
			if (failedRounds == 1) {
				log.error("Slack 발송 회차 중 오류, 다음 회차에 다시 시도합니다(이어지는 동안은 {}회마다 한 줄)", FAILURE_LOG_EVERY, ex);
			}
			else if (failedRounds % FAILURE_LOG_EVERY == 0) {
				log.warn("Slack 발송 회차 오류가 {}회 이어지고 있습니다: {}", failedRounds, ex.getClass().getSimpleName());
			}
		}
	}

	private void round() {
		expireLeases();
		skipStale();
		sendDue();
	}

	// ---------------------------------------------------------------- ① 선점 만료 · ② 오래된 행

	/** 보내는 중에 태스크가 멈춘 행. 보냈는지 모르므로 UNKNOWN 규칙(URGENT는 한 번 재전송). 채널은 행에 있으면 그것, 없으면 우선순위 채널 */
	private void expireLeases() {
		LocalDateTime now = Kst.now(clock);
		for (OperatorNotification row : rows.findSlackLeaseExpired(now)) {
			unknown(row, row.getSlackNextAt(), channelOf(row), LEASE_EXPIRED, now);
		}
	}

	private void skipStale() {
		int skipped = rows.skipStaleSlack(Kst.now(clock).minus(settings.maxAge()));
		if (skipped > 0) {
			log.warn("Slack 발송: 한 번도 시도하지 않고 {}분 넘게 지난 알림 {}건은 보내지 않습니다(SKIPPED · STALE)",
					settings.maxAge().toMinutes(), skipped);
		}
	}

	// ---------------------------------------------------------------- ③ 발송

	private void sendDue() {
		long deadline = System.nanoTime() + ROUND_BUDGET.toNanos();
		Map<String, Long> lastPost = new HashMap<>();
		Set<String> rateLimited = new HashSet<>();
		for (OperatorNotification row : rows.findSlackDue(Kst.now(clock), Limit.of(BATCH))) {
			if (System.nanoTime() > deadline || Thread.currentThread().isInterrupted()) {
				log.warn("Slack 발송: 회차 시간 상한({}초)을 넘어 남은 알림은 다음 회차에 보냅니다", ROUND_BUDGET.toSeconds());
				return;
			}
			String channel = channelOf(row);
			if (channel != null && rateLimited.contains(channel)) {
				continue;
			}
			LocalDateTime now = Kst.now(clock);
			LocalDateTime leaseUntil = now.plus(lease);
			if (rows.claimSlack(row.getNotificationId(), now, leaseUntil) != 1) {
				continue; // 다른 태스크가 먼저 선점(잠금이 2중 안전장치)
			}
			if (channel == null) {
				applied(rows.failSlack(row.getNotificationId(), leaseUntil, NO_CHANNEL), row);
				log.error("Slack 발송: {}에 맞는 채널이 없어 보내지 않습니다(FAILED)", label(row));
				continue;
			}
			pace(lastPost, channel);
			boolean resend = row.getSlackChannel() != null;
			Result result = slack.post(channel, SlackMessage.text(row, resend, consoleBaseUrl));
			lastPost.put(channel, System.nanoTime());
			if (result.outcome() == SlackClient.Outcome.RATE_LIMITED) {
				rateLimited.add(channel);
			}
			apply(row, channel, leaseUntil, result);
		}
	}

	/** 같은 채널은 channelGap 간격 */
	private void pace(Map<String, Long> lastPost, String channel) {
		Long last = lastPost.get(channel);
		if (last == null) {
			return;
		}
		long waitNanos = channelGap.toNanos() - (System.nanoTime() - last);
		if (waitNanos > 0) {
			try {
				Thread.sleep(Duration.ofNanos(waitNanos));
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt(); // 종료 중 — 이 행은 그대로 보내고 다음 행에서 멈춘다
			}
		}
	}

	private void apply(OperatorNotification row, String channel, LocalDateTime leaseUntil, Result result) {
		long id = row.getNotificationId();
		int attempt = row.getSlackAttempts() + 1;
		LocalDateTime now = Kst.now(clock);
		String what = label(row);
		switch (result.outcome()) {
			case SENT -> {
				String sentChannel = result.channel() != null ? result.channel() : channel;
				applied(rows.markSlackSent(id, leaseUntil, now, sentChannel, result.ts()), row);
				log.info("Slack 발송: {} → {} SENT", what, sentChannel);
			}
			case RATE_LIMITED -> {
				applied(rows.deferSlack(id, leaseUntil, now.plus(result.retryAfter()), result.error()), row);
				log.warn("Slack 발송: {} 속도 제한({}) — {}초 뒤 다시 보내고, 이번 회차에 채널 {}는 더 보내지 않습니다", what, result.error(),
						result.retryAfter().toSeconds(), channel);
			}
			case RETRY -> {
				if (attempt >= MAX_ATTEMPTS) {
					applied(rows.failSlack(id, leaseUntil, result.error()), row);
					log.error("Slack 발송: {} {}회째 실패({}) — 보내지 않습니다(FAILED)", what, attempt, result.error());
				}
				else {
					Duration wait = backoff(attempt);
					applied(rows.retrySlack(id, leaseUntil, now.plus(wait), result.error()), row);
					log.warn("Slack 발송: {} {}회째 실패({}) — {}초 뒤 다시 보냅니다", what, attempt, result.error(), wait.toSeconds());
				}
			}
			case FAILED -> {
				applied(rows.failSlack(id, leaseUntil, result.error()), row);
				log.error("Slack 발송: {} 실패({}) — 다시 해도 안 되는 오류라 보내지 않습니다(FAILED)", what, result.error());
			}
			case UNKNOWN -> unknown(row, leaseUntil, channel, result.error(), now);
		}
	}

	/**
	 * 보냈는지 모름. URGENT이고 아직 재전송 전(slack_channel 없음)이면 그 채널을 남기고 30초 뒤 '[재전송]'으로 한 번 더,
	 * 그 밖은 UNKNOWN(다시 보내지 않음)
	 */
	private void unknown(OperatorNotification row, LocalDateTime leaseUntil, String channel, String error,
			LocalDateTime now) {
		long id = row.getNotificationId();
		if (URGENT.equals(row.getPriority()) && row.getSlackChannel() == null && channel != null) {
			applied(rows.resendSlack(id, leaseUntil, now.plus(RESEND_AFTER), channel, error), row);
			log.warn("Slack 발송: {} 보냈는지 모름({}) — 긴급이라 {}초 뒤 한 번 더 '재전송'으로 보냅니다", label(row), error,
					RESEND_AFTER.toSeconds());
		}
		else {
			applied(rows.unknownSlack(id, leaseUntil, channel, error), row);
			log.warn("Slack 발송: {} 보냈는지 모름({}) — 다시 보내지 않습니다(UNKNOWN)", label(row), error);
		}
	}

	/** 결과 UPDATE가 0행이면 선점이 만료돼 다른 태스크가 이미 처리한 것 — 덮어쓰지 않는다 */
	private static void applied(int updated, OperatorNotification row) {
		if (updated != 1) {
			log.warn("Slack 발송: 알림 #{}의 결과를 반영하지 않았습니다(선점이 만료돼 다른 회차가 처리함)", row.getNotificationId());
		}
	}

	/** 재전송 차례면 앞 시도의 채널, 아니면 우선순위 채널 */
	private String channelOf(OperatorNotification row) {
		return row.getSlackChannel() != null ? row.getSlackChannel()
				: settings.channels().forPriority(row.getPriority());
	}

	/**
	 * 초 단위로 올림. 선점 만료 시각은 결과 UPDATE의 조건(slack_next_at = lease)이라 DATETIME(초, 소수는 반올림해 저장)과
	 * 정확히 같아야 한다 — Kst.now가 초로 자르므로 더하는 기간도 초 단위여야 한다
	 */
	static Duration wholeSeconds(Duration value) {
		return value.getNano() == 0 ? value : Duration.ofSeconds(value.getSeconds() + 1);
	}

	static Duration backoff(int attempt) {
		return BACKOFF.get(Math.min(Math.max(attempt, 1), BACKOFF.size()) - 1);
	}

	/** 로그용: 번호 · 우선순위(DB CHECK 값)만 — 문구는 남기지 않는다 */
	private static String label(OperatorNotification row) {
		return "알림 #" + row.getNotificationId() + "(" + row.getPriority() + (row.getSlackChannel() != null ? ", 재전송" : "")
				+ ")";
	}

}
