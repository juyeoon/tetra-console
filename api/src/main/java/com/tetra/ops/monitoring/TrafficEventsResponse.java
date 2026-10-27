package com.tetra.ops.monitoring;

import java.time.OffsetDateTime;
import java.util.List;

import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.HostingStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * GET /api/ops/monitoring/events — 드롭다운 목록 + 기본 선택(명세 3절).
 * 목록 순서: 진행 중(OPEN, 나쁜 순 → 시작 늦은 순) → 오늘 예정(입장 이른 순) → 최근 7일 종료 · 준비 실패(늦은 순).
 */
public record TrafficEventsResponse(
		@Schema(description = "기본 선택. 진행 중 중 가장 나쁜 것 → 오늘 예정 중 입장 가장 이른 것 → 최근 종료 → 없으면 null") Long defaultEventId,
		List<Item> items) {

	public enum Group {
		/** 진행 중(event_status OPEN) */
		OPEN,
		/** 오늘(KST) 시작 예정(SCHEDULED). 입장 전(T−90 ~ T−60)도 여기 */
		TODAY,
		/** 최근 7일 안에 종료(hosting_end_at) · 준비 실패(입장 예정 시각) · 배포 실패(실패 시각, 없으면 입장 예정 시각) ·
		 * hosting_end_at이 지난 호스팅 실패 */
		ENDED
	}

	/**
	 * level · reason은 OPEN만(나머지 null). endAt = hosting_end_at(WF4, 테넌트가 READY가 아니면 null).
	 * failedAt = 지금 실패 상태(DEPLOY_FAILED · HOSTING_FAILED · OPEN_FAILED)로 바뀐 마지막 로그 시각(최근 7일, 없으면 null).
	 * 화면 표기: phase 이름 + 시각 — "예정 · 15:00 시작" · "종료 · 10-24 15:40" · "준비 실패 · 입장 시각" · "배포 실패 · 실패 시각"
	 */
	public record Item(Long eventId, String tenantId, String tenantName, String name, Group group, EventPhase phase,
			MonitoringLevel level, String reason, OffsetDateTime prepareAt, OffsetDateTime entryAt,
			OffsetDateTime startAt, OffsetDateTime endAt, EventStatus eventStatus, HostingStatus hostingStatus,
			OffsetDateTime failedAt) {
	}

}
