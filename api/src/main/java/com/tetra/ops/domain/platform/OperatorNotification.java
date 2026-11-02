package com.tetra.ops.domain.platform;

import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.tetra.ops.domain.SlackStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "operator_notification", catalog = "tetra_platform")
public class OperatorNotification {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "notification_id", nullable = false)
	private Long notificationId;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "tenant_id", length = 12)
	private String tenantId;

	@Column(name = "event_id", columnDefinition = "int unsigned")
	private Long eventId;

	@Column(name = "category", length = 30, nullable = false)
	private String category;

	@Column(name = "priority", length = 20, nullable = false)
	private String priority;

	@Column(name = "occurred_at", nullable = false)
	private LocalDateTime occurredAt;

	@Column(name = "content", length = 1000, nullable = false)
	private String content;

	@Column(name = "is_read", nullable = false)
	private boolean read;

	/**
	 * 중복 방지 키(V4, uk_operator_notif_dedupe). 같은 키는 1행만 — 다시 넣으면 1062.
	 * 1분 주기 확인 알림만 쓴다(DLQ:{큐}:{yyyy-MM-dd} · DELAY:{event_id}:{단계}). 상태 전이 알림은 NULL(제한 없음).
	 */
	@Column(name = "dedupe_key", length = 100)
	private String dedupeKey;

	// ---- Slack 발송 상태(V5). INSERT는 DB 기본값(PENDING · 0 · NULL)에 맡기고(Portal도 이 열을 모른다),
	// 바꾸는 것은 SlackNotifyJob의 조건부 UPDATE(OperatorNotificationRepository)만 — 엔티티로는 읽기만 한다. ----

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "slack_status", length = 20, nullable = false, insertable = false, updatable = false)
	private SlackStatus slackStatus;

	/** 실제로 보낸 시도 수(429 · ratelimited는 세지 않는다) */
	@Column(name = "slack_attempts", columnDefinition = "tinyint unsigned", nullable = false, insertable = false,
			updatable = false)
	private int slackAttempts;

	/** PENDING: 다음 시도 가능 시각(NULL이면 바로) · SENDING: 선점 만료 시각 */
	@Column(name = "slack_next_at", insertable = false, updatable = false)
	private LocalDateTime slackNextAt;

	@Column(name = "slack_sent_at", insertable = false, updatable = false)
	private LocalDateTime slackSentAt;

	/** 마지막 실패 · 건너뜀 원인 코드(Slack error · HTTP 상태 · 예외 종류만) */
	@Column(name = "slack_error", length = 100, insertable = false, updatable = false)
	private String slackError;

	/**
	 * 보낸(또는 보냈을 수 있는) 채널 ID. 보내지 않았으면 NULL. SENT 전인데 값이 있으면 앞 시도가 보냈는지 모르는 것이다 —
	 * URGENT 재전송 차례(SlackNotifyJob)
	 */
	@Column(name = "slack_channel", length = 20, insertable = false, updatable = false)
	private String slackChannel;

	/** chat.postMessage 응답의 메시지 ts */
	@Column(name = "slack_ts", length = 32, insertable = false, updatable = false)
	private String slackTs;

	protected OperatorNotification() {
	}

	public static OperatorNotification unread(String tenantId, Long eventId, String category, String priority,
			LocalDateTime occurredAt, String content) {
		return unread(tenantId, eventId, category, priority, occurredAt, content, null);
	}

	public static OperatorNotification unread(String tenantId, Long eventId, String category, String priority,
			LocalDateTime occurredAt, String content, String dedupeKey) {
		OperatorNotification notification = new OperatorNotification();
		notification.tenantId = tenantId;
		notification.eventId = eventId;
		notification.category = category;
		notification.priority = priority;
		notification.occurredAt = occurredAt;
		notification.content = content;
		notification.read = false;
		notification.dedupeKey = dedupeKey;
		return notification;
	}

	public Long getNotificationId() {
		return notificationId;
	}

	public String getTenantId() {
		return tenantId;
	}

	public Long getEventId() {
		return eventId;
	}

	public String getCategory() {
		return category;
	}

	public String getPriority() {
		return priority;
	}

	public LocalDateTime getOccurredAt() {
		return occurredAt;
	}

	public String getContent() {
		return content;
	}

	public boolean isRead() {
		return read;
	}

	public String getDedupeKey() {
		return dedupeKey;
	}

	public SlackStatus getSlackStatus() {
		return slackStatus;
	}

	public int getSlackAttempts() {
		return slackAttempts;
	}

	public LocalDateTime getSlackNextAt() {
		return slackNextAt;
	}

	public LocalDateTime getSlackSentAt() {
		return slackSentAt;
	}

	public String getSlackError() {
		return slackError;
	}

	public String getSlackChannel() {
		return slackChannel;
	}

	public String getSlackTs() {
		return slackTs;
	}

}
