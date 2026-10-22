package com.tetra.ops.event;

import java.time.OffsetDateTime;
import java.util.List;

import com.tetra.ops.domain.ApplicationStatus;
import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.EventType;
import com.tetra.ops.domain.HostingStatus;
import com.tetra.ops.domain.StatusType;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * OPS-03-1, OPS-04-1 이벤트 상세(개요). 상태 값은 DB 값 그대로 내려주고 한글 표기는 프런트가 한다.
 * 안쪽 record는 이름이 흔해서(테넌트 개요의 Basic 등) 문서 스키마 이름을 EventDetail*로 따로 준다.
 */
public record EventDetailResponse(Basic basic, Status status, Resources resources,
		@Schema(description = "상태 처리 기록 — 신청(APPLICATION) · 호스팅(HOSTING) · 이벤트(EVENT) 로그 전체, changed_at 오름차순(같은 시각은 기록 순서)")
		List<StatusLog> statusLogs) {

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

	/** event_status_log 한 줄. detail = 반려 사유 · 실패 원인 등(없으면 null). */
	@Schema(name = "EventDetailStatusLog")
	public record StatusLog(OffsetDateTime changedAt, StatusType statusType,
			@Schema(description = "이전 상태. 최초 기록이면 null") String prevStatus, String newStatus, String detail,
			Actor actor) {
	}

	/**
	 * 처리자. changed_by가 있으면 운영자(이름), 없으면 신청 접수(prev 없음 → APPLYING)는 테넌트(테넌트명),
	 * 그 밖(신청 저장 · 실패, 승인 때 함께 쓰는 호스팅 · 이벤트 시작 로그, 워크플로우 결과)은 자동 처리(name null).
	 */
	@Schema(name = "EventDetailActor")
	public record Actor(ActorType type, @Schema(description = "운영자 이름 또는 테넌트명. 자동 처리면 null") String name) {
	}

	public enum ActorType {

		OPERATOR, TENANT, SYSTEM

	}

}
