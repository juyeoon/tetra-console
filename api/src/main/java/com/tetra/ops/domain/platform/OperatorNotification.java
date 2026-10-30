package com.tetra.ops.domain.platform;

import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

}
