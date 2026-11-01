package com.tetra.ops.notification;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tetra.ops.common.error.ApiException;
import com.tetra.ops.common.error.ErrorCode;
import com.tetra.ops.common.time.Kst;
import com.tetra.ops.domain.ApplicationStatus;
import com.tetra.ops.domain.platform.OperatorNotification;
import com.tetra.ops.domain.platform.OperatorNotificationRepository;
import com.tetra.ops.domain.platform.OperatorNotificationRepository.CenterRow;
import com.tetra.ops.domain.platform.OperatorNotificationRepository.CountGroup;
import com.tetra.ops.monitoring.DlqDepths;
import com.tetra.ops.monitoring.TrafficMonitoringService;
import com.tetra.ops.notification.NotificationRules.Chip;
import com.tetra.ops.notification.NotificationRules.State;
import com.tetra.ops.notification.NotificationService.Priority;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;

/**
 * OPS-08 알림센터: 운영자 알림 목록과 읽음 처리. 알림을 남기는 쪽은 NotificationService.
 * 거르기(우선순위 AND 분류 AND 안 읽은 것만 AND 내용 검색) · 정렬 · 페이지 · 건수는 서버가 한다(Portal 알림센터와 같은 응답 모양).
 * 보관 기간 없음(삭제하지 않음). 그 페이지 항목에만 칩 · 이동 경로 · 현재 상태를 붙인다({@link NotificationRules}).
 */
@Service
public class NotificationCenterService {

	private static final Logger log = LoggerFactory.getLogger(NotificationCenterService.class);

	/** 한 페이지 기본 크기 · 최대 크기 */
	static final int PAGE_SIZE = 10;

	static final int SIZE_MAX = 50;

	/** 페이지 상한 — OFFSET이 int를 넘으면 Spring Data가 500을 낸다(최대 크기로도 50만 건) */
	static final int PAGE_MAX = 10_000;

	/** 검색어 상한(앞뒤 공백을 지운 뒤) */
	static final int QUERY_MAX = 100;

	/** LIKE 이스케이프 문자 — MySQL 문자열에서 특수한 백슬래시 대신 */
	static final char LIKE_ESCAPE = '!';

	private static final String ALL = "ALL";

	/** 최신순, 같은 시각이면 나중 알림 먼저 */
	private static final Sort LATEST_FIRST = Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("notificationId"));

	private final OperatorNotificationRepository notifications;

	private final TrafficMonitoringService traffic;

	private final DlqDepths dlqDepths;

	public NotificationCenterService(OperatorNotificationRepository notifications, TrafficMonitoringService traffic,
			DlqDepths dlqDepths) {
		this.notifications = notifications;
		this.traffic = traffic;
		this.dlqDepths = dlqDepths;
	}

	/** tenantId · tenantName · eventId는 해당 대상이 없는 알림이면 null. */
	public record NotificationItem(Long notificationId, String tenantId, String tenantName, Long eventId,
			String category, String priority, OffsetDateTime occurredAt, String content, boolean read,
			@Schema(description = "칩. 모르는 category면 null(분류 '전체'에서만)") Chip chip,
			@Schema(description = "누르면 갈 화면 경로(같은 앱, '/'로 시작). 필요한 id가 없으면 null(읽음만)",
					example = "/monitoring/traffic?eventId=1043") String link,
			@Schema(description = "관련 상태의 지금 값. 보여 줄 값이 없으면 null") State state) {
	}

	/**
	 * 목록 조건. priority · chip이 null이면 전체, q는 내용 부분 일치(앞뒤 공백을 지우고 비면 null). page는 1부터.
	 * 범위 밖이면 400 — @RequestParam 검증 애너테이션은 처리하지 않는 예외(500)로 끝나서 여기서 직접 검사한다.
	 */
	public record ListCondition(Priority priority, Chip chip, boolean unreadOnly, String q, int page, int size) {

		public ListCondition {
			q = q == null || q.isBlank() ? null : q.strip();
			if (q != null && q.length() > QUERY_MAX) {
				throw ApiException.invalid("q", QUERY_MAX + "자 이하로 입력해 주세요.");
			}
			if (page < 1) {
				throw ApiException.invalid("page", "1 이상이어야 합니다.");
			}
			if (page > PAGE_MAX) {
				throw ApiException.invalid("page", PAGE_MAX + " 이하여야 합니다.");
			}
			if (size < 1) {
				throw ApiException.invalid("size", "1 이상이어야 합니다.");
			}
			if (size > SIZE_MAX) {
				throw ApiException.invalid("size", SIZE_MAX + " 이하여야 합니다.");
			}
		}

	}

	/** 목록 한 페이지 + 화면 건수. 건수 키는 화면 필터 키(ALL + 값)와 같다 */
	public record NotificationPage(List<NotificationItem> items, @Schema(description = "1부터") int page, int size,
			@Schema(description = "조건(검색 포함)에 맞는 전체 수") long totalCount,
			@Schema(description = "0이면 맞는 알림 없음. page가 이보다 크면 items가 비어 있다") int totalPages,
			@Schema(description = "안 읽은 알림 수(조건과 무관) — [모두 읽음] 버튼 · 사이드바 배지") long unreadCount,
			@Schema(description = "안 읽은 URGENT 알림이 있는지(조건과 무관) — 사이드바 배지 색") boolean unreadUrgent,
			Counts counts) {
	}

	public record Counts(
			@Schema(description = "ALL · URGENT · WARNING · INFO — 안 읽은 것만만 따른다(분류 · 검색과 무관)",
					example = "{\"ALL\": 42, \"URGENT\": 3, \"WARNING\": 9, \"INFO\": 30}") Map<String, Long> priority,
			@Schema(description = "ALL · APPLICATION · EVENT · TENANT · REPORT · SYSTEM — 우선순위 · 안 읽은 것만을 따른다(고른 분류 · 검색과 무관). 칩이 없는 알림은 ALL에만",
					example = "{\"ALL\": 42, \"APPLICATION\": 5, \"EVENT\": 25, \"TENANT\": 7, \"REPORT\": 0, \"SYSTEM\": 5}") Map<String, Long> chip) {
	}

	/** 안 읽은 알림 — 목록 응답(unreadCount · unreadUrgent)과 사이드바 배지가 같이 쓴다 */
	public record UnreadCount(@Schema(description = "안 읽은 알림 수") long count,
			@Schema(description = "안 읽은 URGENT 알림이 있는지") boolean urgent) {
	}

	/**
	 * 조건에 맞는 한 페이지(최신순)와 건수. 트랜잭션은 쿼리마다(AWS를 기다리는 동안 커넥션을 잡지 않는다).
	 * 쿼리: 페이지(조인 없음) · 전체 수(첫 페이지가 다 차지 않으면 생략) · 그 페이지의 현재 값(조인) · 건수 재료 · 안 읽은 수 · 안 읽은 긴급.
	 * 그 페이지에 SYSTEM 행이 있을 때만 DLQ 메시지 수(60초 캐시), 승인된 이벤트 알림이 있을 때만 OPS-05 드롭다운 범위(DB 캐시)를 읽는다.
	 */
	public NotificationPage list(ListCondition c) {
		Page<OperatorNotification> found = notifications.findAll(where(c),
				PageRequest.of(c.page() - 1, c.size(), LATEST_FIRST));
		List<CenterRow> rows = centerRows(found.getContent());
		List<String> queues = rows.stream().flatMap(row -> NotificationRules.dlqQueue(row).stream()).distinct().toList();
		Map<String, Integer> depths = queues.isEmpty() ? Map.of() : dlqDepths.depths(queues);
		Set<Long> trafficEventIds = rows.stream().anyMatch(NotificationCenterService::approvedEventRow)
				? trafficEventIds() : Set.of();
		List<NotificationItem> items = rows.stream().map(row -> item(row, depths, trafficEventIds)).toList();
		UnreadCount unread = unreadCount();
		return new NotificationPage(items, c.page(), c.size(), found.getTotalElements(), found.getTotalPages(),
				unread.count(), unread.urgent(), counts(notifications.countGroups(), c));
	}

	/** 안 읽은 알림 수와 긴급 여부 — 목록 응답과 사이드바 배지가 같이 쓴다. 둘 다 인덱스(idx_operator_notif_read)로 읽는다 */
	@Transactional(readOnly = true)
	public UnreadCount unreadCount() {
		return new UnreadCount(notifications.countUnread(),
				notifications.existsByReadFalseAndPriority(Priority.URGENT.name()));
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

	/** 페이지 알림에 테넌트 · 이벤트 현재 값을 붙인다(페이지 순서 그대로). 페이지가 비면 조회하지 않는다 */
	private List<CenterRow> centerRows(List<OperatorNotification> page) {
		if (page.isEmpty()) {
			return List.of();
		}
		Map<Long, CenterRow> facts = notifications
			.findCenterFacts(page.stream().map(OperatorNotification::getNotificationId).toList())
			.stream()
			.collect(Collectors.toMap(row -> row.notification().getNotificationId(), Function.identity()));
		return page.stream().map(n -> facts.get(n.getNotificationId())).toList();
	}

	private static NotificationItem item(CenterRow row, Map<String, Integer> depths, Set<Long> trafficEventIds) {
		var n = row.notification();
		Chip chip = NotificationRules.chip(n.getCategory(), n.getEventId());
		return new NotificationItem(n.getNotificationId(), n.getTenantId(), row.tenantName(), n.getEventId(),
				n.getCategory(), n.getPriority(), Kst.toOffset(n.getOccurredAt()), n.getContent(), n.isRead(), chip,
				NotificationRules.link(chip, n.getEventId(), n.getTenantId(), row.applicationStatus(), trafficEventIds),
				NotificationRules.state(row, depths));
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

	/** 목록 조건 → WHERE. 값이 있는 조건만 붙인다 */
	private static Specification<OperatorNotification> where(ListCondition c) {
		List<Specification<OperatorNotification>> parts = new ArrayList<>();
		if (c.priority() != null) {
			parts.add((n, query, cb) -> cb.equal(n.get("priority"), c.priority().name()));
		}
		if (c.chip() != null) {
			parts.add(chipIs(NotificationRules.match(c.chip())));
		}
		if (c.unreadOnly()) {
			parts.add((n, query, cb) -> cb.isFalse(n.get("read")));
		}
		if (c.q() != null) {
			parts.add((n, query, cb) -> cb.like(n.get("content"), "%" + escapeLike(c.q()) + "%", LIKE_ESCAPE));
		}
		return Specification.allOf(parts);
	}

	/** 칩 조건 — category IN 묶음, 이벤트 유무가 갈리는 묶음은 event_id 조건과 함께. 빈 묶음은 붙이지 않는다 */
	private static Specification<OperatorNotification> chipIs(NotificationRules.Match m) {
		return (n, query, cb) -> {
			Path<String> category = n.get("category");
			List<Predicate> any = new ArrayList<>();
			if (!m.any().isEmpty()) {
				any.add(category.in(m.any()));
			}
			if (!m.withEvent().isEmpty()) {
				any.add(cb.and(category.in(m.withEvent()), cb.isNotNull(n.get("eventId"))));
			}
			if (!m.withoutEvent().isEmpty()) {
				any.add(cb.and(category.in(m.withoutEvent()), cb.isNull(n.get("eventId"))));
			}
			return cb.or(any.toArray(Predicate[]::new));
		};
	}

	/** 검색어의 ! · % · _를 글자 그대로 찾게 한다 */
	static String escapeLike(String q) {
		return q.replace("!", "!!").replace("%", "!%").replace("_", "!_");
	}

	/**
	 * 건수 — 우선순위는 안 읽은 것만만 따르고, 분류는 우선순위 · 안 읽은 것만을 따른다(고른 분류 · 검색과 무관).
	 * 칩은 목록 항목과 같은 NotificationRules.chip으로 정하고, 칩이 없는 알림은 ALL에만 센다.
	 */
	private static Counts counts(List<CountGroup> groups, ListCondition c) {
		Map<String, Long> priority = zeros(Priority.values());
		Map<String, Long> chip = zeros(Chip.values());
		for (CountGroup g : groups) {
			if (c.unreadOnly() && g.getRead()) {
				continue;
			}
			add(priority, ALL, g.getTotal());
			add(priority, g.getPriority(), g.getTotal());
			if (c.priority() != null && !c.priority().name().equals(g.getPriority())) {
				continue;
			}
			add(chip, ALL, g.getTotal());
			add(chip, NotificationRules.chip(g.getCategory(), true), g.getWithEvent());
			add(chip, NotificationRules.chip(g.getCategory(), false), g.getTotal() - g.getWithEvent());
		}
		return new Counts(priority, chip);
	}

	private static Map<String, Long> zeros(Enum<?>[] keys) {
		Map<String, Long> counts = new LinkedHashMap<>();
		counts.put(ALL, 0L);
		for (Enum<?> key : keys) {
			counts.put(key.name(), 0L);
		}
		return counts;
	}

	private static void add(Map<String, Long> counts, Chip chip, long n) {
		if (chip != null) {
			add(counts, chip.name(), n);
		}
	}

	/** 표에 없는 키(모르는 우선순위)는 세지 않는다 */
	private static void add(Map<String, Long> counts, String key, long n) {
		counts.computeIfPresent(key, (k, v) -> v + n);
	}

}
