import type { ApplicationStatus, EventDetailResponse, EventResultResponse, EventStatus, EventStatusLog, EventType, HostingStatus, StatusType } from '../../api/types.ts';
import type { EventResult } from '../../types/index.ts';
import { formatDateTime, formatTime } from '../../utils/datetime.ts';
import { applicationStatusMeta, eventStatusMeta, hostingStatusMeta } from '../../utils/status.ts';

/**
 * 이벤트 API 응답 → 화면 모델 (화면과 분리, node --test 대상).
 * 일시는 여기서 표시 문자열(KST)로 바꾼다.
 */

type Tone = 'blue' | 'amber' | 'green' | 'red' | 'grey';

const TYPE_LABEL: Record<StatusType, string> = { APPLICATION: '신청', HOSTING: '호스팅', EVENT: '이벤트' };

const META: Record<StatusType, Record<string, { label: string; tone: Tone }>> = {
  APPLICATION: applicationStatusMeta,
  HOSTING: hostingStatusMeta,
  EVENT: eventStatusMeta,
};

/** 실패 계열 — "원인" 박스를 강조한다 */
const FAILURE_STATUSES = new Set(['APPLY_FAILED', 'DEPLOY_FAILED', 'HOSTING_FAILED', 'END_FAILED', 'OPEN_FAILED']);

/** 상태 처리 기록 한 줄 (화면) */
export interface StatusHistoryItem {
  /** 표시용(KST) */
  at: string;
  /** 신청 / 호스팅 / 이벤트 */
  typeLabel: string;
  status: { label: string; tone: Tone };
  /** "운영자 홍길동" · "테넌트 A사" · "자동 처리" */
  actor: string;
  detail: string | null;
  /** 신청 반려면 "반려 사유", 그 밖은 "원인" */
  detailLabel: string;
  /** 실패 계열이면 원인 박스를 강조 */
  failure: boolean;
}

function actorText(actor: EventStatusLog['actor']): string {
  if (actor.type === 'OPERATOR') return actor.name ? `운영자 ${actor.name}` : '운영자';
  if (actor.type === 'TENANT') return actor.name ? `테넌트 ${actor.name}` : '테넌트';
  return '자동 처리';
}

export function statusHistoryView(log: EventStatusLog): StatusHistoryItem {
  const meta = META[log.statusType]?.[log.newStatus] ?? { label: log.newStatus, tone: 'grey' as Tone };
  return {
    at: formatDateTime(log.changedAt),
    typeLabel: TYPE_LABEL[log.statusType] ?? log.statusType,
    status: meta,
    actor: actorText(log.actor),
    detail: log.detail,
    detailLabel: log.statusType === 'APPLICATION' && log.newStatus === 'REJECTED' ? '반려 사유' : '원인',
    failure: FAILURE_STATUSES.has(log.newStatus),
  };
}

/** 신청(APPLICATION) 로그만 — 반려 사유 · 처리 일시는 신청 상태에서 꺼낸다(호스팅 · 이벤트 로그의 detail과 섞이지 않게) */
const lastApplicationLog = (logs: EventStatusLog[], match: (status: string) => boolean) =>
  [...logs].reverse().find((l) => l.statusType === 'APPLICATION' && match(l.newStatus)) ?? null;

/** 반려 사유 = 마지막 신청 REJECTED 로그의 detail (상세 API에 따로 없다) */
export function rejectReasonOf(logs: EventStatusLog[]): string | null {
  return lastApplicationLog(logs, (s) => s === 'REJECTED')?.detail ?? null;
}

/** 처리 일시 = 마지막 신청 승인 · 반려 · 신청 실패 로그의 시각(ISO) */
export function processedAtOf(logs: EventStatusLog[]): string | null {
  return lastApplicationLog(logs, (s) => s === 'APPROVED' || s === 'REJECTED' || s === 'APPLY_FAILED')?.changedAt ?? null;
}

/** 이벤트 상세(개요 · 승인/반려 대화상자) 화면 모델 */
export interface EventView {
  id: number;
  name: string;
  type: EventType;
  tenantId: string;
  tenantName: string;
  hostingPeriod: { start: string; end: string };
  eventPeriod: { start: string; end: string };
  plan: string;
  quantity: number;
  appliedAt: string;
  endpointUrl: string;
  applicationStatus: ApplicationStatus;
  hostingStatus: HostingStatus | null;
  eventStatus: EventStatus | null;
  rejectReason: string | null;
  /** 표시용(KST). 없으면 null */
  processedAt: string | null;
  templateId: number;
  /** 할당 도메인 — {slug}.{tenant_id}.{플랫폼 도메인}(fullDomain). 승인 전 · 만료면 null */
  domain: string | null;
  publicKey: string | null;
  /** 상태 처리 기록 — 신청 · 호스팅 · 이벤트 전체(오름차순) */
  logs: StatusHistoryItem[];
}

export function toEventView(d: EventDetailResponse): EventView {
  const processedAt = processedAtOf(d.statusLogs);
  return {
    id: d.basic.eventId,
    name: d.basic.name,
    type: d.basic.eventType,
    tenantId: d.basic.tenantId,
    tenantName: d.basic.tenantName,
    hostingPeriod: { start: formatDateTime(d.basic.hostingStartAt), end: formatDateTime(d.basic.hostingEndAt) },
    eventPeriod: { start: formatDateTime(d.basic.startAt), end: formatDateTime(d.basic.endAt) },
    plan: d.basic.trafficPlanCode,
    quantity: d.basic.stockCount,
    appliedAt: formatDateTime(d.basic.appliedAt),
    endpointUrl: d.basic.endpointUrl,
    applicationStatus: d.status.applicationStatus,
    hostingStatus: d.status.hostingStatus,
    eventStatus: d.status.eventStatus,
    rejectReason: rejectReasonOf(d.statusLogs),
    processedAt: processedAt ? formatDateTime(processedAt) : null,
    templateId: d.resources.templateId,
    domain: d.resources.fullDomain,
    publicKey: d.resources.publicKey,
    logs: d.statusLogs.map(statusHistoryView),
  };
}

/** 진행 결과 탭 화면 모델 — 상태 화면(body)과 머리글 · 사실 줄에 쓰는 값 */
export interface ResultView {
  hostingStatus: HostingStatus | null;
  eventStatus: EventStatus | null;
  /** 표시용(KST) */
  openAt: string;
  endAt: string;
  aggregatedAt: string;
  /** 발급 이력 CSV를 내려받을 수 있는지(집계 완료) */
  csvReady: boolean;
  body: EventResult;
}

/**
 * API state → 화면 상태.
 * IN_PROGRESS는 "이벤트가 끝나기 전"이라 오픈 전(NONE)과 진행 중(ONGOING)을 지금 시각과 eventOpenAt으로 나눈다.
 * READY인데 결과가 비어 있으면(있으면 안 되는 경우) 집계 중으로 본다.
 */
export function toResultView(r: EventResultResponse, now: Date): ResultView {
  const head = {
    hostingStatus: r.hostingStatus,
    eventStatus: r.eventStatus,
    openAt: formatDateTime(r.eventOpenAt),
    endAt: formatDateTime(r.endAt),
    aggregatedAt: formatDateTime(r.aggregatedAt),
  };
  switch (r.state) {
    case 'IN_PROGRESS': {
      const opened = r.eventOpenAt !== null && new Date(r.eventOpenAt).getTime() <= now.getTime();
      return { ...head, csvReady: false, body: { state: opened ? 'ONGOING' : 'NONE' } };
    }
    case 'AGGREGATING':
      return { ...head, csvReady: false, body: { state: 'AGGREGATING' } };
    case 'END_FAILED':
      return {
        ...head,
        csvReady: false,
        body: { state: 'FAILED', cause: r.failure?.cause ?? '-', occurredAt: formatDateTime(r.failure?.occurredAt) },
      };
    case 'READY': {
      if (!r.result) return { ...head, csvReady: false, body: { state: 'AGGREGATING' } };
      const { metrics, composition, perMinute } = r.result;
      return {
        ...head,
        csvReady: true,
        body: {
          state: 'DONE',
          aggregatedAt: head.aggregatedAt,
          issued: metrics.successCount,
          stock: metrics.totalStock,
          soldOut: metrics.soldoutCount,
          dropped: composition.abandonedCount,
          peakConcurrent: metrics.peakConcurrent,
          peakAt: formatDateTime(metrics.peakAt),
          planCapacity: metrics.maxCapacity,
          perMinute: perMinute.map((m) => ({ minute: formatTime(m.minute), issued: m.successCount, soldOut: m.soldoutCount })),
        },
      };
    }
  }
}
