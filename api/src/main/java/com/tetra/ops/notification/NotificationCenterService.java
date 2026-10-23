package com.tetra.ops.notification;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tetra.ops.common.error.ApiException;
import com.tetra.ops.common.error.ErrorCode;
import com.tetra.ops.common.time.Kst;
import com.tetra.ops.domain.platform.OperatorNotificationRepository;

/**
 * OPS-08 알림센터: 운영자 알림 목록과 읽음 처리. 알림을 남기는 쪽은 NotificationService.
 * 필터(우선순위 · 안읽음) · 검색 · 건수는 화면이 계산한다(페이지네이션 없음). 보관 기간 없음(삭제하지 않음).
 */
@Service
public class NotificationCenterService {

	private final OperatorNotificationRepository notifications;

	public NotificationCenterService(OperatorNotificationRepository notifications) {
		this.notifications = notifications;
	}

	public record NotificationListResponse(List<NotificationItem> items) {
	}

	/** tenantId · tenantName · eventId는 해당 대상이 없는 알림이면 null. */
	public record NotificationItem(Long notificationId, String tenantId, String tenantName, Long eventId,
			String category, String priority, OffsetDateTime occurredAt, String content, boolean read) {
	}

	@Transactional(readOnly = true)
	public NotificationListResponse list() {
		return new NotificationListResponse(notifications.findAllWithTenantName().stream().map(row -> {
			var n = row.notification();
			return new NotificationItem(n.getNotificationId(), n.getTenantId(), row.tenantName(), n.getEventId(),
					n.getCategory(), n.getPriority(), Kst.toOffset(n.getOccurredAt()), n.getContent(), n.isRead());
		}).toList());
	}

	/** 이미 읽은 알림이어도 성공(멱등). 없는 id면 404. */
	@Transactional
	public void markRead(Long notificationId) {
		if (notifications.markRead(notificationId) == 0 && !notifications.existsById(notificationId)) {
			throw new ApiException(ErrorCode.NOTIFICATION_NOT_FOUND);
		}
	}

	/** 안읽음만 갱신. 여러 번 불러도 결과가 같다. */
	@Transactional
	public void markAllRead() {
		notifications.markAllRead();
	}

}
