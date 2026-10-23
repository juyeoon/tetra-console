package com.tetra.ops.notification;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.tetra.ops.common.error.ApiErrorCodes;
import com.tetra.ops.common.error.ErrorCode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/** OPS-08 알림센터. Tetra Portal 알림센터(UI-TP-13)와 같은 화면. Slack 연동은 예정이라 없음. */
@Tag(name = "알림센터", description = "OPS-08 알림 목록 · 읽음 처리")
@RestController
@RequestMapping("/api/ops/notifications")
public class NotificationController {

	private final NotificationCenterService service;

	public NotificationController(NotificationCenterService service) {
		this.service = service;
	}

	@Operation(summary = "OPS-08 알림 목록",
			description = "운영자 알림 전체(종류 무관), 최신순(occurredAt · notificationId 내림차순). 페이지네이션 없음 — "
					+ "우선순위 · 안읽음 필터, 내용 검색, 건수는 화면이 계산. tenantName은 테넌트 무관 알림이면 null")
	@GetMapping
	public NotificationCenterService.NotificationListResponse list() {
		return service.list();
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
