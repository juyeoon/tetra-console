package com.tetra.ops.stub;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tetra.ops.common.error.ApiException;
import com.tetra.ops.common.error.ApiErrorCodes;
import com.tetra.ops.common.error.ErrorCode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 껍데기 API (501). Tetra Portal 화면설계서 최신본이 나오면 채운다.
 * OPS-03-2·04-2 신청 내용은 EventController에 있다.
 */
@Tag(name = "미구현", description = "화면이 확정되면 채울 API. 지금은 501")
@RestController
@RequestMapping("/api/ops")
public class NotImplementedController {

	/** OPS-08 알림센터. TODO(미정): Tetra Portal 알림센터와 같은 레이아웃. 필드·필터 확정 후 구현 */
	@Operation(summary = "OPS-08 알림센터 (미구현)")
	@ApiErrorCodes(ErrorCode.NOT_IMPLEMENTED)
	@GetMapping("/notifications")
	public void notifications() {
		throw new ApiException(ErrorCode.NOT_IMPLEMENTED);
	}

	/** OPS-09 리포트. TODO(미정): Tetra Portal 리포트와 같은 레이아웃. 확정 후 구현 */
	@Operation(summary = "OPS-09 리포트 (미구현)")
	@ApiErrorCodes(ErrorCode.NOT_IMPLEMENTED)
	@GetMapping("/reports")
	public void reports() {
		throw new ApiException(ErrorCode.NOT_IMPLEMENTED);
	}

}
