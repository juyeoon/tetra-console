package com.tetra.ops.domain.tenant;

import java.time.LocalDateTime;

import com.tetra.ops.tenancy.TenantScopedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Tetra Portal 알림센터. 이 앱은 반려(WF1-3) 때 한 줄을 넣는다. */
@Entity
@Table(name = "tenant_notification")
public class TenantNotification extends TenantScopedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "notification_id", nullable = false)
	private Long notificationId;

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

	protected TenantNotification() {
	}

	public static TenantNotification unread(String tenantId, Long eventId, String category, String priority,
			LocalDateTime occurredAt, String content) {
		TenantNotification notification = new TenantNotification();
		notification.tenantId = tenantId;
		notification.eventId = eventId;
		notification.category = category;
		notification.priority = priority;
		notification.occurredAt = occurredAt;
		notification.content = content;
		notification.read = false;
		return notification;
	}

}
