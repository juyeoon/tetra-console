package com.tetra.ops.notification;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.tetra.ops.domain.ApplicationStatus;
import com.tetra.ops.domain.platform.OperatorNotificationRepository.CenterRow;
import com.tetra.ops.notification.NotificationService.Category;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * OPS-08 알림센터 항목의 칩 · 이동 경로 · 현재 상태(목록 API가 계산하고 화면은 그대로 쓴다). 순수 규칙만 — DB · AWS 값은 부르는 쪽이 넘긴다.
 * - 칩 5개: 신청 = APPLICATION, 이벤트 = HOSTING · EVENT · DELAY · MAIL(event_id 있음), 테넌트 = ACCOUNT · PROVISIONING · MAIL(event_id 없음),
 *   리포트 = REPORT, 시스템 = SYSTEM. 모르는 category는 null(분류 "전체"에서만 보임). 목록 필터 · 건수도 이 규칙 하나로 판정한다({@link #match}).
 * - 이동 경로: 필요한 id가 없으면 null(읽음만). 이벤트는 OPS-05 드롭다운 범위면 트래픽 모니터링, 아니면 진행 현황 상세 —
 *   진행 현황 상세는 APPROVED만 열리므로 그 밖은 신청 현황 상세.
 * - 현재 상태: "복구됨"을 판정하지 않고 관련 열의 지금 값을 그대로 준다. 실패 값(FAILED · *_FAILED)이나 DLQ 메시지가 있으면 alert.
 */
public final class NotificationRules {

	private NotificationRules() {
	}

	/** 알림센터 칩 */
	public enum Chip {

		APPLICATION, EVENT, TENANT, REPORT, SYSTEM

	}

	/** 현재 상태가 가리키는 값 */
	public enum StateKind {

		/** event_registry.application_status */
		APPLICATION_STATUS,
		/** event_registry.hosting_status */
		HOSTING_STATUS,
		/** event_registry.event_status */
		EVENT_STATUS,
		/** tenant.provisioning_status */
		PROVISIONING_STATUS,
		/** tenant.account_status */
		ACCOUNT_STATUS,
		/** 비밀번호 설정 여부(tenant.password IS NOT NULL): SET · NOT_SET */
		PASSWORD_SETUP,
		/** DLQ 메시지 수(10진 문자열) */
		DLQ_MESSAGES

	}

	@Schema(name = "NotificationState", description = "관련 상태의 지금 값(복구 여부는 판정하지 않음)")
	public record State(@Schema(description = "어느 값인지") StateKind kind,
			@Schema(description = "DB 값 그대로(PASSWORD_SETUP은 SET · NOT_SET, DLQ_MESSAGES는 메시지 수)", example = "DEPLOY_FAILED") String value,
			@Schema(description = "실패 값(FAILED · *_FAILED)이거나 DLQ 메시지가 1건 이상이면 true — 화면이 빨강으로") boolean alert) {
	}

	static final String PASSWORD_SET = "SET";

	static final String PASSWORD_NOT_SET = "NOT_SET";

	static Chip chip(String category, Long eventId) {
		return chip(category, eventId != null);
	}

	/** 칩은 category와 event_id 유무만 본다(목록 필터 · 건수도 이 규칙으로 — {@link #match}) */
	static Chip chip(String category, boolean hasEvent) {
		Category known = category(category);
		if (known == null) {
			return null;
		}
		return switch (known) {
			case APPLICATION -> Chip.APPLICATION;
			case HOSTING, EVENT, DELAY -> Chip.EVENT;
			case ACCOUNT, PROVISIONING -> Chip.TENANT;
			// 개인키 메일(이벤트) · 비밀번호 설정 메일(테넌트)
			case MAIL -> hasEvent ? Chip.EVENT : Chip.TENANT;
			case REPORT -> Chip.REPORT;
			case SYSTEM -> Chip.SYSTEM;
		};
	}

	/**
	 * 칩 하나에 드는 category 묶음 — 목록 필터의 SQL 조건이 쓴다.
	 * any = 이벤트와 상관없이, withEvent = event_id가 있을 때만, withoutEvent = event_id가 없을 때만.
	 */
	record Match(List<String> any, List<String> withEvent, List<String> withoutEvent) {
	}

	/** {@link #chip(String, boolean)}을 모든 Category × 이벤트 있음/없음에 돌려 나눈다(규칙을 SQL에 따로 쓰지 않는다, Portal과 같은 방식) */
	static Match match(Chip chip) {
		List<String> any = new ArrayList<>();
		List<String> withEvent = new ArrayList<>();
		List<String> withoutEvent = new ArrayList<>();
		for (Category c : Category.values()) {
			boolean with = chip(c.name(), true) == chip;
			boolean without = chip(c.name(), false) == chip;
			if (with && without) {
				any.add(c.name());
			}
			else if (with) {
				withEvent.add(c.name());
			}
			else if (without) {
				withoutEvent.add(c.name());
			}
		}
		return new Match(List.copyOf(any), List.copyOf(withEvent), List.copyOf(withoutEvent));
	}

	/**
	 * 화면 경로(같은 앱 안, '/'로 시작). 필요한 id가 없으면 null.
	 * @param applicationStatus 이벤트의 지금 신청 상태(registry, 없으면 null)
	 * @param trafficEventIds OPS-05 드롭다운 범위의 이벤트 번호
	 */
	static String link(Chip chip, Long eventId, String tenantId, ApplicationStatus applicationStatus,
			Set<Long> trafficEventIds) {
		if (chip == null) {
			return null;
		}
		return switch (chip) {
			case APPLICATION -> eventId == null ? null : "/events/applications/" + eventId;
			case EVENT -> eventId == null ? null : eventLink(eventId, applicationStatus, trafficEventIds);
			case TENANT -> tenantId == null ? null : "/tenants/" + tenantId;
			case REPORT -> "/logs/reports";
			case SYSTEM -> "/monitoring/traffic";
		};
	}

	private static String eventLink(long eventId, ApplicationStatus applicationStatus, Set<Long> trafficEventIds) {
		if (applicationStatus != ApplicationStatus.APPROVED) {
			return "/events/applications/" + eventId;
		}
		return trafficEventIds.contains(eventId) ? "/monitoring/traffic?eventId=" + eventId : "/events/progress/" + eventId;
	}

	/** 이 행에 필요한 DLQ 이름(SYSTEM 행만). 목록에 SYSTEM 행이 없으면 AWS를 부르지 않게 부르는 쪽이 모은다 */
	static Optional<String> dlqQueue(CenterRow row) {
		return category(row.notification().getCategory()) == Category.SYSTEM
				? NotificationService.dlqQueueOf(row.notification().getDedupeKey()) : Optional.empty();
	}

	/**
	 * @param dlqDepths 큐 이름 → 지금 메시지 수(못 읽은 큐는 없음)
	 * @return 보여 줄 값이 없으면 null
	 */
	static State state(CenterRow row, Map<String, Integer> dlqDepths) {
		var n = row.notification();
		Category known = category(n.getCategory());
		if (known == null) {
			return null;
		}
		return switch (known) {
			case APPLICATION -> status(StateKind.APPLICATION_STATUS, row.applicationStatus());
			case HOSTING -> status(StateKind.HOSTING_STATUS, row.hostingStatus());
			case EVENT -> status(StateKind.EVENT_STATUS, row.eventStatus());
			case DELAY -> NotificationService.delayStepOf(n.getDedupeKey()).map(step -> switch (step) {
				case HOSTING_START, END_START, END_DONE -> status(StateKind.HOSTING_STATUS, row.hostingStatus());
				case PREPARE_START, ENTRY_OPEN -> status(StateKind.EVENT_STATUS, row.eventStatus());
			}).orElse(null);
			case PROVISIONING -> status(StateKind.PROVISIONING_STATUS, row.provisioningStatus());
			case ACCOUNT -> status(StateKind.ACCOUNT_STATUS, row.accountStatus());
			// 개인키 메일(event_id 있음)은 다시 보낼 수 없어 보여 줄 상태가 없다. 비밀번호 설정 메일은 설정했는지
			case MAIL -> n.getEventId() != null || row.tenantName() == null ? null
					: new State(StateKind.PASSWORD_SETUP, row.passwordSet() ? PASSWORD_SET : PASSWORD_NOT_SET, false);
			case SYSTEM -> dlqQueue(row).map(dlqDepths::get)
				.map(messages -> new State(StateKind.DLQ_MESSAGES, String.valueOf(messages), messages > 0))
				.orElse(null);
			case REPORT -> null;
		};
	}

	private static State status(StateKind kind, Enum<?> value) {
		if (value == null) {
			return null;
		}
		String name = value.name();
		return new State(kind, name, name.equals("FAILED") || name.endsWith("_FAILED"));
	}

	private static Category category(String value) {
		for (Category c : Category.values()) {
			if (c.name().equals(value)) {
				return c;
			}
		}
		return null;
	}

}
