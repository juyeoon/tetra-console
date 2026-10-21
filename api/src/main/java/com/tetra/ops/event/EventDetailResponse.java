package com.tetra.ops.event;

import java.time.OffsetDateTime;
import java.util.List;

import com.tetra.ops.domain.ApplicationStatus;
import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.EventType;
import com.tetra.ops.domain.HostingStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * OPS-03-1, OPS-04-1 이벤트 상세(개요). 상태 값은 DB 값 그대로 내려주고 한글 표기는 프런트가 한다.
 * 안쪽 record는 이름이 흔해서(테넌트 개요의 Basic 등) 문서 스키마 이름을 EventDetail*로 따로 준다.
 */
public record EventDetailResponse(Basic basic, Status status, Resources resources, List<ApplicationLog> applicationLogs) {

	@Schema(name = "EventDetailBasic")
	public record Basic(Long eventId, String name, EventType eventType, String tenantId, String tenantName,
			OffsetDateTime hostingStartAt, OffsetDateTime hostingEndAt, OffsetDateTime startAt, OffsetDateTime endAt,
			String trafficPlanCode, Long stockCount, OffsetDateTime appliedAt, String endpointUrl) {
	}

	/** 승인 전에는 hostingStatus·eventStatus가 null. */
	@Schema(name = "EventDetailStatus")
	public record Status(ApplicationStatus applicationStatus, HostingStatus hostingStatus, EventStatus eventStatus,
			@Schema(description = "OPS-04 '이벤트 진행 결과' 탭을 열 수 있는지. 이벤트가 끝나기 전(결과 API의 state가 IN_PROGRESS)이면 false → 탭 비활성")
			boolean resultAvailable) {
	}

	/**
	 * domain = {slug}.{tenant_id}, fullDomain = domain + "." + 플랫폼 도메인(tetra.platform-domain, 기본 tetraops.cloud).
	 * 승인 전 또는 event_status = EXPIRED이면 domain·fullDomain·publicKey는 null.
	 */
	@Schema(name = "EventDetailResources")
	public record Resources(Long templateId, String domain, String fullDomain, String publicKey) {
	}

	@Schema(name = "EventDetailApplicationLog")
	public record ApplicationLog(OffsetDateTime changedAt, String prevStatus, String newStatus, String detail) {
	}

}
