package com.tetra.ops.dashboard;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tetra.ops.common.time.Kst;
import com.tetra.ops.domain.AccountStatus;
import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.platform.EventRegistryRepository;
import com.tetra.ops.domain.platform.OperatorNotificationRepository;
import com.tetra.ops.domain.platform.TenantRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * OPS-02 홈 대시보드. 확정된 지표만 돌려준다.
 * TODO(미정): 클러스터 사용량, 실시간 트래픽, 테넌트별 처리 현황은 설계서에서도 미확정이라 응답에 넣지 않는다.
 */
@Tag(name = "대시보드", description = "OPS-02 홈 대시보드")
@RestController
@RequestMapping("/api/ops")
public class DashboardController {

	private final TenantRepository tenants;

	private final EventRegistryRepository registries;

	private final OperatorNotificationRepository notifications;

	private final Clock clock;

	public DashboardController(TenantRepository tenants, EventRegistryRepository registries,
			OperatorNotificationRepository notifications, Clock clock) {
		this.tenants = tenants;
		this.registries = registries;
		this.notifications = notifications;
		this.clock = clock;
	}

	public record DashboardResponse(TenantSummary tenants, OpenEvents openEvents, List<Notification> recentNotifications) {
	}

	public record TenantSummary(long active, long total, long newThisMonth) {
	}

	/** asOf = 응답 시각(카드 제목 옆 기준 시각). */
	public record OpenEvents(long count, OffsetDateTime asOf) {
	}

	public record Notification(Long notificationId, String tenantId, Long eventId, String category, String priority,
			OffsetDateTime occurredAt, String content, boolean read) {
	}

	@Operation(summary = "OPS-02 홈 요약",
			description = "활성/전체/이번 달(KST) 신규 테넌트 수, 진행 중(OPEN) 이벤트 수와 기준 시각(asOf), 최근 알림 5건. "
					+ "클러스터 사용량·실시간 트래픽·테넌트별 처리 현황은 미확정이라 없음")
	@GetMapping("/dashboard")
	@Transactional(readOnly = true)
	public DashboardResponse dashboard() {
		LocalDateTime now = Kst.now(clock);
		LocalDateTime monthStart = now.toLocalDate().withDayOfMonth(1).atStartOfDay();
		var tenantSummary = new TenantSummary(tenants.countByAccountStatus(AccountStatus.ACTIVE), tenants.count(),
				tenants.countByCreatedAtGreaterThanEqual(monthStart));
		var openEvents = new OpenEvents(registries.countByEventStatus(EventStatus.OPEN), Kst.toOffset(now));
		var recent = notifications.findTop5ByOrderByOccurredAtDescNotificationIdDesc()
			.stream()
			.map(n -> new Notification(n.getNotificationId(), n.getTenantId(), n.getEventId(), n.getCategory(),
					n.getPriority(), Kst.toOffset(n.getOccurredAt()), n.getContent(), n.isRead()))
			.toList();
		return new DashboardResponse(tenantSummary, openEvents, recent);
	}

}
