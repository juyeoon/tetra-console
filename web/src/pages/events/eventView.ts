import type { ApplicationStatus, EventApplicationLog, EventDetailResponse, EventResultResponse, EventStatus, EventType, HostingStatus } from '../../api/types.ts';
import type { ApplicationLog, EventResult } from '../../types/index.ts';
import { formatDateTime, formatTime } from '../../utils/datetime.ts';

/**
 * 이벤트 API 응답 → 화면 모델 (화면과 분리, node --test 대상).
 * 일시는 여기서 표시 문자열(KST)로 바꾼다.
 */

/** 신청 상태 로그 문구. 서버는 상태 전이만 주므로 제목 · 설명은 화면이 정한다(신청 실패 · 반려는 detail을 그대로) */
const LOG_TEXT: Record<ApplicationStatus, { title: string; description: (detail: string | null) => string }> = {
  APPLYING: { title: '신청 접수', description: () => '테넌트가 이벤트 신청서를 제출했습니다. 신청 정보와 배너 이미지 저장을 시작합니다.' },
  APPLIED: { title: '신청 저장 완료', description: () => '신청 정보 저장 및 배너 업로드가 완료되어 승인 대기 상태로 전환되었습니다.' },
  APPLY_FAILED: { title: '신청 실패', description: (detail) => detail ?? '신청을 처리하지 못했습니다.' },
  APPROVED: { title: '운영자 승인', description: () => '운영자가 신청을 승인했습니다. 할당 도메인과 인증키가 발급되었습니다.' },
  REJECTED: { title: '운영자 반려', description: (detail) => (detail ? `반려 사유: ${detail}` : '운영자가 신청을 반려했습니다.') },
};

export function applicationLogView(log: EventApplicationLog): ApplicationLog {
  const text = LOG_TEXT[log.newStatus];
  return { at: formatDateTime(log.changedAt), title: text.title, status: log.newStatus, description: text.description(log.detail) };
}

const lastLog = (logs: EventApplicationLog[], match: (status: ApplicationStatus) => boolean) =>
  [...logs].reverse().find((l) => match(l.newStatus)) ?? null;

/** 반려 사유 = 마지막 REJECTED 로그의 detail (상세 API에 따로 없다) */
export function rejectReasonOf(logs: EventApplicationLog[]): string | null {
  return lastLog(logs, (s) => s === 'REJECTED')?.detail ?? null;
}

/** 처리 일시 = 마지막 승인 · 반려 · 신청 실패 로그의 시각(ISO) */
export function processedAtOf(logs: EventApplicationLog[]): string | null {
  return lastLog(logs, (s) => s === 'APPROVED' || s === 'REJECTED' || s === 'APPLY_FAILED')?.changedAt ?? null;
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
  logs: ApplicationLog[];
}

export function toEventView(d: EventDetailResponse): EventView {
  const processedAt = processedAtOf(d.applicationLogs);
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
    rejectReason: rejectReasonOf(d.applicationLogs),
    processedAt: processedAt ? formatDateTime(processedAt) : null,
    templateId: d.resources.templateId,
    domain: d.resources.fullDomain,
    publicKey: d.resources.publicKey,
    logs: d.applicationLogs.map(applicationLogView),
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
