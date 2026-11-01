package com.tetra.ops.notification;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.tetra.ops.common.error.ApiErrorCodes;
import com.tetra.ops.common.error.ErrorCode;
import com.tetra.ops.notification.NotificationCenterService.ListCondition;
import com.tetra.ops.notification.NotificationRules.Chip;
import com.tetra.ops.notification.NotificationService.Priority;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/** OPS-08 알림센터. Tetra Portal 알림센터(UI-TP-13)와 같은 화면 · 응답 모양. 필터 · 검색 · 페이지 · 건수는 서버에서. Slack 연동은 예정이라 없음. */
@Tag(name = "알림센터", description = "OPS-08 알림 목록 · 읽음 처리")
@RestController
@RequestMapping("/api/ops/notifications")
public class NotificationController {

	private final NotificationCenterService service;

	public NotificationController(NotificationCenterService service) {
		this.service = service;
	}

	@Operation(summary = "OPS-08 알림 목록",
			description = "운영자 알림(종류 무관) 최신순(occurredAt · notificationId 내림차순) 한 페이지와 화면 건수. 조건은 모두 AND. "
					+ "범위 밖 page는 빈 items(전체 수 · 페이지 수는 그대로). tenantName은 테넌트 무관 알림이면 null")
	@ApiErrorCodes({ ErrorCode.INVALID_INPUT })
	@GetMapping
	public NotificationCenterService.NotificationPage list(
			@Parameter(description = "URGENT · WARNING · INFO. 없으면 전체") @RequestParam(required = false) Priority priority,
			@Parameter(description = "APPLICATION · EVENT · TENANT · REPORT · SYSTEM. 없으면 전체") @RequestParam(required = false) Chip chip,
			@Parameter(description = "안 읽은 것만") @RequestParam(defaultValue = "false") boolean unreadOnly,
			@Parameter(description = "내용 부분 일치 — 앞뒤 공백 · 대소문자 무시, "
					+ NotificationCenterService.QUERY_MAX + "자 이하") @RequestParam(required = false) String q,
			@Parameter(description = "1부터, " + NotificationCenterService.PAGE_MAX + " 이하") @RequestParam(defaultValue = "1") int page,
			@Parameter(description = "1~" + NotificationCenterService.SIZE_MAX) @RequestParam(defaultValue = ""
					+ NotificationCenterService.PAGE_SIZE) int size) {
		return service.list(new ListCondition(priority, chip, unreadOnly, q, page, size));
	}

	@Operation(summary = "OPS-08 안 읽은 알림 수",
			description = "사이드바 배지. 목록 응답 unreadCount · unreadUrgent와 같은 값(읽음은 운영자 공통)")
	@GetMapping("/unread-count")
	public NotificationCenterService.UnreadCount unreadCount() {
		return service.unreadCount();
	}

	@Operation(summary = "OPS-08 알림 읽음",
			description = "본문 없음. 이미 읽은 알림이어도 204. 읽음 여부는 운영자 공통")
	@ApiErrorCodes({ ErrorCode.INVALID_INPUT, ErrorCode.NOTIFICATION_NOT_FOUND })
	@PatchMapping("/{notificationId}/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void markRead(@Parameter(example = "1") @PathVariable Long notificationId) {
		service.markRead(notificationId);
	}

	@Operation(summary = "OPS-08 모두 읽음", description = "본문 없음. 안읽음 알림만 읽음으로 바꾼다. 여러 번 보내도 204")
	@PatchMapping("/read-all")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void markAllRead() {
		service.markAllRead();
	}

}
