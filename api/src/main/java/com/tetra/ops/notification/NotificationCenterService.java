package com.tetra.ops.notification;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tetra.ops.common.error.ApiException;
import com.tetra.ops.common.error.ErrorCode;
import com.tetra.ops.common.time.Kst;
import com.tetra.ops.domain.ApplicationStatus;
import com.tetra.ops.domain.platform.OperatorNotificationRepository;
import com.tetra.ops.domain.platform.OperatorNotificationRepository.CenterRow;
import com.tetra.ops.monitoring.DlqDepths;
import com.tetra.ops.monitoring.TrafficMonitoringService;
import com.tetra.ops.notification.NotificationRules.Chip;
import com.tetra.ops.notification.NotificationRules.State;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * OPS-08 알림센터: 운영자 알림 목록과 읽음 처리. 알림을 남기는 쪽은 NotificationService.
 * 필터(우선순위 · 칩 · 안읽음) · 검색 · 건수는 화면이 계산한다(페이지네이션 없음). 보관 기간 없음(삭제하지 않음).
 * 항목마다 칩 · 이동 경로 · 현재 상태를 붙인다({@link NotificationRules}).
 */
@Service
public class NotificationCenterService {

	private static final Logger log = LoggerFactory.getLogger(NotificationCenterService.class);

	private final OperatorNotificationRepository notifications;

	private final TrafficMonitoringService traffic;

	private final DlqDepths dlqDepths;

	public NotificationCenterService(OperatorNotificationRepository notifications, TrafficMonitoringService traffic,
			DlqDepths dlqDepths) {
		this.notifications = notifications;
		this.traffic = traffic;
		this.dlqDepths = dlqDepths;
	}

	public record NotificationListResponse(List<NotificationItem> items) {
	}

	/** tenantId · tenantName · eventId는 해당 대상이 없는 알림이면 null. */
	public record NotificationItem(Long notificationId, String tenantId, String tenantName, Long eventId,
			String category, String priority, OffsetDateTime occurredAt, String content, boolean read,
			@Schema(description = "칩. 모르는 category면 null(칩 필터 '전체'에서만)") Chip chip,
			@Schema(description = "누르면 갈 화면 경로(같은 앱, '/'로 시작). 필요한 id가 없으면 null(읽음만)",
					example = "/monitoring/traffic?eventId=1043") String link,
			@Schema(description = "관련 상태의 지금 값. 보여 줄 값이 없으면 null") State state) {
	}

	/**
	 * 목록 쿼리 1번(트랜잭션은 쿼리 안에서만 — AWS를 기다리는 동안 커넥션을 잡지 않는다).
	 * SYSTEM 행이 있을 때만 DLQ 메시지 수(60초 캐시), 승인된 이벤트 알림이 있을 때만 OPS-05 드롭다운 범위(DB 캐시)를 읽는다.
	 */
	public NotificationListResponse list() {
		List<CenterRow> rows = notifications.findAllForCenter();
		List<String> queues = rows.stream().flatMap(row -> NotificationRules.dlqQueue(row).stream()).distinct().toList();
		Map<String, Integer> depths = queues.isEmpty() ? Map.of() : dlqDepths.depths(queues);
		Set<Long> trafficEventIds = rows.stream().anyMatch(NotificationCenterService::approvedEventRow)
				? trafficEventIds() : Set.of();
		return new NotificationListResponse(rows.stream().map(row -> {
			var n = row.notification();
			Chip chip = NotificationRules.chip(n.getCategory(), n.getEventId());
			return new NotificationItem(n.getNotificationId(), n.getTenantId(), row.tenantName(), n.getEventId(),
					n.getCategory(), n.getPriority(), Kst.toOffset(n.getOccurredAt()), n.getContent(), n.isRead(), chip,
					NotificationRules.link(chip, n.getEventId(), n.getTenantId(), row.applicationStatus(), trafficEventIds),
					NotificationRules.state(row, depths));
		}).toList());
	}

	private static boolean approvedEventRow(CenterRow row) {
		var n = row.notification();
		return NotificationRules.chip(n.getCategory(), n.getEventId()) == Chip.EVENT
				&& row.applicationStatus() == ApplicationStatus.APPROVED;
	}

	/** 못 읽으면 빈 집합 — 이벤트 알림은 진행 현황 상세로 간다 */
	private Set<Long> trafficEventIds() {
		try {
			return traffic.listedEventIds();
		}
		catch (RuntimeException ex) {
			log.warn("알림센터: OPS-05 이벤트 목록을 읽지 못해 이벤트 알림을 진행 현황 상세로 연결합니다({})",
					ex.getClass().getSimpleName());
			return Set.of();
		}
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
