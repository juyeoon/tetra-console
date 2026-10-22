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

/**
 * 상태 로그 단계 이름 · 설명 — 서버는 상태 전이만 주므로 문구는 화면이 정한다(신청 항목의 기존 말투 그대로, 호스팅 · 이벤트 추가).
 * 원인 · 반려 사유(detail)는 설명과 따로 보여 준다.
 */
export const STATUS_LOG_TEXT: Record<StatusType, Record<string, { title: string; description: string }>> = {
  APPLICATION: {
    APPLYING: { title: '신청 접수', description: '테넌트가 이벤트 신청서를 제출했습니다. 신청 정보와 배너 이미지 저장을 시작합니다.' },
    APPLIED: { title: '신청 저장 완료', description: '신청 정보 저장 및 배너 업로드가 완료되어 승인 대기 상태로 전환되었습니다.' },
    APPLY_FAILED: { title: '신청 실패', description: '신청 정보를 저장하지 못했습니다.' },
    APPROVED: { title: '운영자 승인', description: '운영자가 신청을 승인했습니다. 할당 도메인과 인증키가 발급되었습니다.' },
    REJECTED: { title: '운영자 반려', description: '운영자가 신청을 반려했습니다.' },
  },
  HOSTING: {
    PENDING: { title: '배포 시작', description: '승인과 함께 이벤트 페이지 배포를 시작했습니다.' },
    DEPLOYED: { title: '배포 완료', description: '이벤트 페이지와 리소스 배포가 끝났습니다.' },
    DEPLOY_FAILED: { title: '배포 실패', description: '배포에 실패했습니다.' },
    HOSTING: { title: '호스팅 시작', description: '호스팅 시작 시각이 되어 이벤트 페이지(대기방)를 열었습니다. 입장은 오픈 60분 전에 열립니다.' },
    HOSTING_FAILED: { title: '호스팅 준비 실패', description: '호스팅을 시작하지 못했습니다.' },
    ENDED: { title: '호스팅 종료', description: '호스팅 기간이 끝나 이벤트 페이지를 내렸습니다.' },
    END_FAILED: { title: '종료 실패', description: '이벤트 종료 처리에 실패했습니다.' },
  },
  EVENT: {
    SCHEDULED: { title: '오픈 예약', description: '승인되어 이벤트 일정에 맞춰 오픈이 예약되었습니다.' },
    // OPEN은 WF2-2가 끝나는 T-60(이벤트 시작 60분 전)에 된다 — 입장(대기열)만 열리고 발급은 시작 시각부터
    OPEN: { title: '입장 시작', description: '입장이 열려 대기열을 받고 있습니다. 쿠폰 발급은 이벤트 시작 시각부터입니다.' },
    OPEN_FAILED: { title: '이벤트 준비 실패', description: '이벤트 준비(입장 열기)에 실패했습니다.' },
    EXPIRED: { title: '이벤트 만료', description: '이벤트가 종료되어 발급이 마감되었습니다.' },
  },
};

/** 실패 계열 — 원인을 빨강으로 */
const FAILURE_STATUSES = new Set(['APPLY_FAILED', 'DEPLOY_FAILED', 'HOSTING_FAILED', 'END_FAILED', 'OPEN_FAILED']);

/** 원인이 길면 이 길이에서 말줄임하고 [펼치기] */
export const DETAIL_PREVIEW_LENGTH = 60;

/** 상태 로그 한 칸 (가로 타임라인) */
export interface StatusLogItem {
  /** 표시용(KST) */
  at: string;
  /** "운영자 홍길동" · "테넌트 A사" · "자동 처리" — 시각 옆에 작게 */
  actor: string;
  /** 신청 / 호스팅 / 이벤트 — 단계 이름 앞 작은 라벨 */
  typeLabel: string;
  /** 단계 이름 */
  title: string;
  /** 상태 배지(기존 한글 표기 · 색) */
  status: { label: string; tone: Tone };
  description: string;
  /** 원인 · 반려 사유. 없으면 null */
  detail: { label: string; text: string; preview: string; clipped: boolean; failure: boolean } | null;
}

function actorText(actor: EventStatusLog['actor']): string {
  if (actor.type === 'OPERATOR') return actor.name ? `운영자 ${actor.name}` : '운영자';
  if (actor.type === 'TENANT') return actor.name ? `테넌트 ${actor.name}` : '테넌트';
  return '자동 처리';
}

/** 긴 원인은 앞부분만(말줄임) */
export function clipDetail(text: string, max = DETAIL_PREVIEW_LENGTH): { preview: string; clipped: boolean } {
  const chars = Array.from(text);
  return chars.length <= max ? { preview: text, clipped: false } : { preview: `${chars.slice(0, max).join('')}…`, clipped: true };
}

export function statusLogView(log: EventStatusLog): StatusLogItem {
  const meta = META[log.statusType]?.[log.newStatus] ?? { label: log.newStatus, tone: 'grey' as Tone };
  const text = STATUS_LOG_TEXT[log.statusType]?.[log.newStatus] ?? { title: meta.label, description: '' };
  const rejected = log.statusType === 'APPLICATION' && log.newStatus === 'REJECTED';
  return {
    at: formatDateTime(log.changedAt),
    actor: actorText(log.actor),
    typeLabel: TYPE_LABEL[log.statusType] ?? log.statusType,
    title: text.title,
    status: meta,
    description: text.description,
    detail: log.detail
      ? { label: rejected ? '반려 사유' : '원인', text: log.detail, ...clipDetail(log.detail), failure: FAILURE_STATUSES.has(log.newStatus) }
      : null,
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
  /** 상태 로그 — 신청 · 호스팅 · 이벤트 전체(오름차순) */
  logs: StatusLogItem[];
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
    logs: d.statusLogs.map(statusLogView),
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
