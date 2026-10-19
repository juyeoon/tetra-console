package com.tetra.ops.event;

import java.time.OffsetDateTime;
import java.util.List;

import com.tetra.ops.domain.ApplicationStatus;
import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.EventType;
import com.tetra.ops.domain.HostingStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/** OPS-03-1, OPS-04-1 이벤트 상세(개요). 상태 값은 DB 값 그대로 내려주고 한글 표기는 프런트가 한다. */
public record EventDetailResponse(Basic basic, Status status, Resources resources, List<ApplicationLog> applicationLogs) {

	public record Basic(Long eventId, String name, EventType eventType, String tenantId, String tenantName,
			OffsetDateTime hostingStartAt, OffsetDateTime hostingEndAt, OffsetDateTime startAt, OffsetDateTime endAt,
			String trafficPlanCode, Long stockCount, OffsetDateTime appliedAt, String endpointUrl) {
	}

	/** 승인 전에는 hostingStatus·eventStatus가 null. */
	public record Status(ApplicationStatus applicationStatus, HostingStatus hostingStatus, EventStatus eventStatus,
			@Schema(description = "OPS-04 '이벤트 진행 결과' 탭을 열 수 있는지. 이벤트가 끝나기 전(결과 API의 state가 IN_PROGRESS)이면 false → 탭 비활성")
			boolean resultAvailable) {
	}

	/**
	 * domain = {slug}.{tenant_id}, fullDomain = domain + "." + root_domain.
	 * 승인 전 또는 event_status = EXPIRED이면 domain·fullDomain·publicKey는 null.
	 */
	public record Resources(Long templateId, String domain, String fullDomain, String publicKey) {
	}

	public record ApplicationLog(OffsetDateTime changedAt, String prevStatus, String newStatus, String detail) {
	}

}
