import type { MonitoringEventRef, MonitoringOverviewResponse, MonitoringPodCount } from '../../api/types.ts';
import { formatClock } from '../../utils/datetime.ts';

/**
 * 홈 모니터링 응답 → 화면 모델 (화면과 분리, node --test 대상).
 * null(시리즈 없음 · Prometheus 실패)은 '–'로 보이고 0으로 바꾸지 않는다.
 */

type Overview = MonitoringOverviewResponse;

export const EMPTY_TEXT = '–';

export const NO_EVENT_TEXT = '진행 중 이벤트 없음';

/** OPEN 이벤트는 있는데 요청 시리즈가 없음(예: 로컬 DB에만 있는 이벤트, Pod가 아직 없음) */
export const NO_DATA_TEXT = '아직 요청이 없는 진행 중 이벤트';

/** 폴링 간격 — 서버 캐시 TTL(15초)과 같다 */
export const POLL_MS = 15_000;

const text = (n: number | null | undefined) => (n == null ? EMPTY_TEXT : n.toLocaleString('ko-KR'));

/** 0~1 → '42%' */
export const usageText = (ratio: number | null | undefined) => (ratio == null ? EMPTY_TEXT : `${Math.round(ratio * 100)}%`);

/** 'Ready / 전체' */
export const podText = (ready: number | null | undefined, total: number | null | undefined) => `${text(ready)} / ${text(total)}`;

/** 초당 요청 수 — 소수 첫째 자리까지(정수면 정수) */
export const rpsText = (rps: number | null | undefined) =>
  rps == null ? EMPTY_TEXT : rps.toLocaleString('ko-KR', { maximumFractionDigits: 1 });

/** 카드 제목 옆 "자동 갱신 · 15초 · HH:mm:ss" + 지연 표시 */
export interface UpdatedView {
  /** 마지막으로 성공한 시각(KST HH:mm:ss). 없으면 '–' */
  asOf: string;
  /** 서버가 stale을 주었거나 이번 폴링이 실패함 */
  delayed: boolean;
}

export function updatedView(overview: Overview | undefined, requestFailed: boolean): UpdatedView {
  return { asOf: formatClock(overview?.asOf), delayed: requestFailed || !overview || overview.stale };
}

// ---------------------------------------------------------------- 진행 중 이벤트 판단

/**
 * 진행 중(OPEN) 이벤트가 있는지 — 응답 tenants[].events(DB event_registry 기준). 노드 · Pod 수로 판단하지 않는다
 * (DB에 OPEN이 있어도 클러스터에 Pod가 없을 수 있음). 응답 전이면 false
 */
export function hasOpenEvents(overview: Overview | undefined): boolean {
  return (overview?.tenants ?? []).some((t) => t.events.length > 0);
}

/** 진행 중 이벤트 목록 '테넌트명 · 이벤트명'(이벤트마다 한 줄) */
export function openEventLabels(overview: Overview | undefined): string[] {
  return (overview?.tenants ?? []).flatMap((t) => t.events.map((e) => `${tenantLabel(t)} · ${e.name}`));
}

/** 실시간 트래픽 빈 상태. 시리즈가 있으면 null, OPEN 0건이면 '진행 중 이벤트 없음', OPEN이 있으면 '아직 요청이 없는 진행 중 이벤트' + 이벤트 목록 */
export type TrafficEmptyState = { text: string; events: string[] } | null;

export function trafficEmptyState(overview: Overview | undefined): TrafficEmptyState {
  if ((overview?.traffic.series ?? []).length > 0) return null;
  if (!hasOpenEvents(overview)) return { text: NO_EVENT_TEXT, events: [] };
  return { text: NO_DATA_TEXT, events: openEventLabels(overview) };
}

// ---------------------------------------------------------------- 클러스터 사용량

export interface ClusterView {
  cpu: string;
  memory: string;
  appNodes: string;
  redisNodes: string;
  systemNodes: string;
  /** OPEN 이벤트 0건 → 노드 옆 '(진행 중 이벤트 없음)'. OPEN이 있으면 app 노드가 0이어도 괄호 문구 없음 */
  noEvent: boolean;
  apiPods: string;
  redisPods: string;
}

const pods = (p: MonitoringPodCount | undefined) => podText(p?.ready, p?.total);

export function clusterView(overview: Overview | undefined): ClusterView {
  const c = overview?.cluster;
  return {
    cpu: usageText(c?.cpuUsage),
    memory: usageText(c?.memoryUsage),
    appNodes: text(c?.nodes.app),
    redisNodes: text(c?.nodes.redis),
    systemNodes: text(c?.nodes.system),
    noEvent: overview !== undefined && !hasOpenEvents(overview),
    apiPods: pods(c?.pods.api),
    redisPods: pods(c?.pods.redis),
  };
}

// ---------------------------------------------------------------- 실시간 트래픽

/** 테넌트 이름(없으면 tenant_id) */
export const tenantLabel = (t: { tenantId: string; tenantName: string | null }) => t.tenantName ?? t.tenantId;

/** 범례: '테넌트 · 이벤트1, 이벤트2' */
export const legendLabel = (t: { tenantId: string; tenantName: string | null; events: MonitoringEventRef[] }) =>
  t.events.length === 0 ? tenantLabel(t) : `${tenantLabel(t)} · ${t.events.map((e) => e.name).join(', ')}`;

export interface TrafficView {
  /** 시리즈가 없음 → 그래프 대신 빈 상태 문구(trafficEmptyState). 시리즈가 있으면 null */
  empty: TrafficEmptyState;
  total: string;
  /** x축(epoch 밀리초) — 모든 시리즈 공통 */
  timestamps: number[];
  series: { id: string; label: string; current: string; data: (number | null)[] }[];
  /** 이벤트 시작 세로 점선 — 그래프 범위 안의 것만 */
  eventStarts: { key: string; x: number; label: string }[];
}

export function trafficView(overview: Overview | undefined): TrafficView {
  const series = overview?.traffic.series ?? [];
  // 서버가 같은 격자로 주지만, 혹시 어긋나도 시각 합집합으로 맞춘다
  const timestamps = [...new Set(series.flatMap((s) => s.points.map(([t]) => t)))].sort((a, b) => a - b);
  const first = timestamps[0];
  const last = timestamps[timestamps.length - 1];
  const eventStarts = series.flatMap((s) =>
    s.events
      .map((e) => ({ key: `${s.tenantId}-${e.eventId}`, x: new Date(e.startAt).getTime(), label: `${e.name} 시작` }))
      .filter((e) => first !== undefined && e.x >= first && e.x <= last),
  );
  return {
    empty: trafficEmptyState(overview),
    total: rpsText(overview?.traffic.totalRps),
    timestamps,
    series: series.map((s) => {
      const byTime = new Map(s.points);
      const data = timestamps.map((t) => byTime.get(t) ?? null);
      const current = [...data].reverse().find((v) => v != null) ?? null;
      return { id: s.tenantId, label: legendLabel(s), current: rpsText(current), data };
    }),
    eventStarts,
  };
}

// ---------------------------------------------------------------- 테넌트 별 처리 현황

/** 점이 레인을 지나가는 시간(초) — 고정. rps는 점 간격(생성 빈도)만 바꾼다 */
export const LANE_SECONDS = 2.4;

/** 레인 위 점 최대 개수(상한) */
export const MAX_DOTS = 8;

/**
 * 유입 레인의 점 개수 — rps에 로그 비례(1 → 1개, 10 → 3개, 100 → 5개, 1,000 → 7개, 상한 8개).
 * rps가 없거나 0이면 0개(흐름 없음).
 */
export function inflowDots(rps: number | null | undefined): number {
  if (rps == null || rps <= 0) return 0;
  return Math.min(MAX_DOTS, Math.max(1, Math.round(1 + 2 * Math.log10(rps))));
}

/** 유입 점을 처리 완료 · 실패 레인으로 나눈다 — 실시간 5xx 비율(errorRps / rps). errorRps > 0이면 실패 점 최소 1개 */
export function splitDots(total: number, rps: number | null | undefined, errorRps: number | null | undefined) {
  if (total === 0 || rps == null || rps <= 0) return { completed: 0, failed: 0 };
  const ratio = Math.min(1, Math.max(0, (errorRps ?? 0) / rps));
  let failed = Math.round(total * ratio);
  if ((errorRps ?? 0) > 0) failed = Math.max(1, failed);
  return { completed: Math.max(0, total - failed), failed };
}

export interface TenantFlowView {
  key: string;
  name: string;
  /** 이벤트 이름(여럿이면 쉼표) */
  events: string;
  /** 원 툴팁 '네임스페이스 tenant-…' */
  namespaceTitle: string;
  /** 원 안 큰 숫자 'ready/max'(공백 없이 한 줄, 최대 '32/32'). max가 null이면 ready만 */
  podCount: string;
  completed: string;
  failed: string;
  dots: { inflow: number; completed: number; failed: number };
}

/** 'ready/max', max를 모르면 'ready' */
export const podCountText = (ready: number | null, max: number | null) => (max == null ? text(ready) : `${text(ready)}/${text(max)}`);

export function tenantFlowViews(overview: Overview | undefined): TenantFlowView[] {
  return (overview?.tenants ?? []).map((t) => {
    const inflow = inflowDots(t.rps);
    return {
      key: t.tenantId,
      name: tenantLabel(t),
      events: t.events.map((e) => e.name).join(', '),
      namespaceTitle: `네임스페이스 ${t.namespace}`,
      podCount: podCountText(t.pods.ready, t.pods.max),
      completed: text(t.completed),
      failed: text(t.failed),
      dots: { inflow, ...splitDots(inflow, t.rps, t.errorRps) },
    };
  });
}
