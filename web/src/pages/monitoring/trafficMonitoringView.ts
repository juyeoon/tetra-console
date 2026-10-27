import type {
  EventPhase,
  MonitoringLevel,
  MonitoringPoint,
  TrafficCharts,
  TrafficEventItem,
  TrafficEventResponse,
  TrafficEventsResponse,
  TrafficFailure,
  TrafficKpi,
  TrafficNode,
  TrafficPlatformResponse,
  TrafficPool,
  TrafficRange,
  TrafficRangeKey,
  TrafficServiceStatus,
  TrafficStep,
  TrafficSummaryKpi,
  TrafficUpcoming,
} from '../../api/types.ts';
import { formatClock, formatDate, formatMonthDayTime, formatTime } from '../../utils/datetime.ts';

/**
 * OPS-05 트래픽 모니터링 응답 → 화면 모델(화면과 분리, node --test 대상).
 * 상태 판정(level · warn)은 서버가 한다(TrafficRules). 여기서는 표시만: 문구 · "–" · 색 구분 · 그래프 데이터.
 * null(시리즈 없음 · 조회 실패)은 '–', 0은 0으로 보인다.
 */

export const EMPTY = '–';

/**
 * 5xx 비율 기준(설계 D-2 확정: 주의 1% · 위험 2%). 판정(kpi.level · kpi.warn · 상태 요약)은 서버 TrafficRules가 하고,
 * 이 상수는 화면에 그리는 문구 · 기준선에만 쓴다 — 서버 ERROR_RATIO_WARN · ERROR_RATIO_DANGER와 같은 값으로 둔다.
 */
export const ERROR_RATIO_WARN = 0.01;
export const ERROR_RATIO_DANGER = 0.02;

/** p95 주의 기준(초안, 부하 시험 뒤 확정) — 서버 P95_WARN_SECONDS와 같은 값 */
export const P95_WARN_MS = 500;

const pct = (ratio: number) => `${Math.round(ratio * 1000) / 10}%`;

/** KPI 5xx 보조 문구 '주의 1% · 위험 2%' */
export const ERROR_RULE_TEXT = `주의 ${pct(ERROR_RATIO_WARN)} · 위험 ${pct(ERROR_RATIO_DANGER)}`;

/** 폴링 간격 — 서버 캐시 TTL(Prometheus · DB 15초)과 같다 */
export const POLL_MS = 15_000;

type Tone = 'green' | 'amber' | 'red' | 'grey' | 'blue';

const isNum = (v: number | null | undefined): v is number => typeof v === 'number' && Number.isFinite(v);

// ---------------------------------------------------------------- 숫자 표기

/** 개수 '2,400' */
export const countText = (n: number | null | undefined) => (isNum(n) ? Math.round(n).toLocaleString('ko-KR') : EMPTY);

/** 초당 요청 — 100 이상은 정수, 그 아래는 소수 첫째 자리까지 */
export const rpsText = (n: number | null | undefined) =>
  isNum(n) ? n.toLocaleString('ko-KR', { maximumFractionDigits: n >= 100 ? 0 : 1 }) : EMPTY;

/** 0~1 → '0.8%'(digits 1) · '46%'(digits 0). 0은 '0%' */
export function percentText(ratio: number | null | undefined, digits = 1): string {
  if (!isNum(ratio)) return EMPTY;
  if (ratio === 0) return '0%';
  return `${(ratio * 100).toFixed(digits)}%`;
}

/** ms 정수 '180ms' */
export const msText = (ms: number | null | undefined) => (isNum(ms) ? `${Math.round(ms).toLocaleString('ko-KR')}ms` : EMPTY);

/** 코어 '3.2 vCPU' */
export const coresText = (cores: number | null | undefined) => (isNum(cores) ? `${cores.toFixed(1)} vCPU` : EMPTY);

const GIB = 1024 ** 3;
const MIB = 1024 ** 2;

/** 바이트 → '4.1 GB'(1 GB 미만은 'MB') */
export function bytesText(bytes: number | null | undefined): string {
  if (!isNum(bytes)) return EMPTY;
  if (bytes === 0) return '0 GB';
  return bytes >= GIB ? `${(bytes / GIB).toFixed(1)} GB` : `${Math.round(bytes / MIB).toLocaleString('ko-KR')} MB`;
}

/** 초당 명령 '42k ops/s' · '1.5k ops/s' · '870 ops/s' */
export function opsText(n: number | null | undefined): string {
  if (!isNum(n)) return EMPTY;
  if (n >= 1000) return `${(n / 1000).toFixed(n >= 10_000 ? 0 : 1)}k ops/s`;
  return `${Math.round(n)} ops/s`;
}

/** 'a / b' (값마다 '–') */
export const pairText = (a: number | null | undefined, b: number | null | undefined) => `${countText(a)} / ${countText(b)}`;

/** 요청량 대비 '요청량의 80%' — 요청량을 모르면 undefined */
export function requestShareText(used: number | null | undefined, request: number | null | undefined): string | undefined {
  if (!isNum(used) || !isNum(request) || request <= 0) return undefined;
  return `요청량의 ${percentText(used / request, 0)}`;
}

/** 걸린 시간 '3분 20초' · '45초' */
export function durationText(seconds: number | null | undefined): string | undefined {
  if (!isNum(seconds)) return undefined;
  const s = Math.max(0, Math.round(seconds));
  const m = Math.floor(s / 60);
  return m > 0 ? `${m}분 ${s % 60}초` : `${s}초`;
}

// ---------------------------------------------------------------- 상태 · 상단

export const LEVEL_META: Record<MonitoringLevel, { label: string; tone: Tone }> = {
  OK: { label: '정상', tone: 'green' },
  WARN: { label: '주의', tone: 'amber' },
  DANGER: { label: '위험', tone: 'red' },
  UNKNOWN: { label: '확인 불가', tone: 'grey' },
};

/** 상단 "자동 갱신 · 15초 · 마지막 HH:mm:ss" / "데이터 지연됨 · 마지막 정상 HH:mm:ss" — prometheus.stale 기준 */
export interface HeaderView {
  delayed: boolean;
  text: string;
}

export function headerView(platform: TrafficPlatformResponse | undefined, requestFailed: boolean): HeaderView {
  const asOf = formatClock(platform?.prometheus.asOf);
  const delayed = requestFailed || !platform || platform.prometheus.stale;
  return { delayed, text: delayed ? `데이터 지연됨 · 마지막 정상 ${asOf}` : `자동 갱신 · ${POLL_MS / 1000}초 · 마지막 ${asOf}` };
}

const AREA_TITLE = { CLUSTER: '클러스터', EVENT: '이벤트', AUTOMATION: '자동화', PLATFORM: '플랫폼' } as const;

export interface SummaryCell {
  key: string;
  title: string;
  level: string;
  tone: Tone;
  reason: string;
}

/** A. 상태 요약 4칸(배경색으로 상태). 응답 전이면 4칸 모두 '확인 불가' */
export function summaryCells(platform: TrafficPlatformResponse | undefined): SummaryCell[] {
  const areas = ['CLUSTER', 'EVENT', 'AUTOMATION', 'PLATFORM'] as const;
  return areas.map((area) => {
    const cell = platform?.summary.find((c) => c.area === area);
    const meta = LEVEL_META[cell?.level ?? 'UNKNOWN'];
    const title = area === 'EVENT' ? `${AREA_TITLE.EVENT} (진행 중 ${cell?.openCount ?? 0})` : AREA_TITLE[area];
    return { key: area, title, level: meta.label, tone: meta.tone, reason: cell?.reason ?? EMPTY };
  });
}

// ---------------------------------------------------------------- B-1 노드

/** 노드 막대 색: 70~80% 주황(warn), 80% 이상 빨강(danger). 기준선은 80% 점선 */
export const USAGE_WARN = 0.7;
export const USAGE_DANGER = 0.8;

export type UsageTone = 'normal' | 'warn' | 'danger';

export function usageTone(ratio: number | null | undefined): UsageTone {
  if (!isNum(ratio)) return 'normal';
  if (ratio >= USAGE_DANGER) return 'danger';
  if (ratio >= USAGE_WARN) return 'warn';
  return 'normal';
}

/** 막대 높이(0~100). 값이 없으면 0 */
export const barPercent = (ratio: number | null | undefined) => (isNum(ratio) ? Math.min(100, Math.max(0, ratio * 100)) : 0);

const POOL_META: Record<string, { label: string; sub: string }> = {
  system: { label: 'system', sub: '고정' },
  app: { label: 'app', sub: 'Karpenter' },
  redis: { label: 'redis', sub: 'Karpenter' },
  other: { label: '기타', sub: '풀 라벨 없음' },
};

export interface NodeCellView {
  key: string;
  cpu: number;
  memory: number;
  cpuTone: UsageTone;
  memoryTone: UsageTone;
  /** 최근 10분 안에 생김 — 점선 테두리 */
  recent: boolean;
  ready: boolean;
  /** 툴팁 줄(이름 · 타입 · 값 · 뜬 시각). 타입이 없으면 줄을 뺀다 */
  tooltip: string[];
}

export interface PoolView {
  key: string;
  label: string;
  sub: string;
  count: string;
  /** '+3 (10분)' — 없으면 null */
  added: string | null;
  cpuAvg: string;
  cpuMax: string;
  cpuMaxTone: UsageTone;
  memoryAvg: string;
  memoryMax: string;
  memoryMaxTone: UsageTone;
  nodes: NodeCellView[];
}

export function nodeTooltip(n: TrafficNode): string[] {
  const lines = [n.name];
  if (n.type) lines.push(n.type);
  lines.push(`CPU ${percentText(n.cpu, 0)} · 메모리 ${percentText(n.memory, 0)}`);
  if (n.createdAt) lines.push(`뜬 시각 ${formatMonthDayTime(n.createdAt)}${n.recent ? ' (최근 10분)' : ''}`);
  if (!n.ready) lines.push('Ready 아님');
  return lines;
}

export function poolViews(pools: TrafficPool[] | undefined): PoolView[] {
  return (pools ?? []).map((p) => {
    const meta = POOL_META[p.pool] ?? { label: p.pool, sub: '' };
    return {
      key: p.pool,
      label: meta.label,
      sub: meta.sub,
      count: p.count == null ? EMPTY : `${countText(p.count)}대`,
      added: p.addedRecently > 0 ? `+${p.addedRecently} (10분)` : null,
      cpuAvg: percentText(p.cpu.avg, 0),
      cpuMax: percentText(p.cpu.max, 0),
      cpuMaxTone: usageTone(p.cpu.max),
      memoryAvg: percentText(p.memory.avg, 0),
      memoryMax: percentText(p.memory.max, 0),
      memoryMaxTone: usageTone(p.memory.max),
      nodes: p.nodes.map((n) => ({
        key: n.name,
        cpu: barPercent(n.cpu),
        memory: barPercent(n.memory),
        cpuTone: usageTone(n.cpu),
        memoryTone: usageTone(n.memory),
        recent: n.recent,
        ready: n.ready,
        tooltip: nodeTooltip(n),
      })),
    };
  });
}

/** 0~1 → 0~100(그래프), 소수 둘째 자리 */
const toPercent = (v: number | null | undefined) => (isNum(v) ? Math.round(v * 10_000) / 100 : null);

export interface HistoryView {
  timestamps: number[];
  cpu: (number | null)[];
  memory: (number | null)[];
}

/** 클러스터 CPU · 메모리 최근 1시간(%) */
export function historyView(platform: TrafficPlatformResponse | undefined): HistoryView {
  const points = platform?.nodes.history.points ?? [];
  return { timestamps: points.map(([t]) => t), cpu: points.map(([, c]) => toPercent(c)), memory: points.map(([, , m]) => toPercent(m)) };
}

// ---------------------------------------------------------------- B-2 플랫폼 · B-3 다가오는 이벤트

export interface AddonsView {
  /** '정상 6/6' · '문제 1 · 정상 5/6' · '확인 불가' */
  summary: string;
  ok: boolean | null;
  names: string;
  /** 문제 있는 것만 'KEDA keda-operator 0/1 Ready' */
  problems: string[];
}

export function addonsView(platform: TrafficPlatformResponse | undefined): AddonsView {
  const addons = platform?.platform.addons;
  if (!addons) return { summary: EMPTY, ok: null, names: '', problems: [] };
  const names = addons.groups.map((g) => g.name).join(' · ');
  const problems = addons.groups.flatMap((g) =>
    g.healthy === false && g.problems.length === 0
      ? [`${g.name} 응답 없음`]
      : g.problems.map((p) => `${g.name} ${p.name} ${pairText(p.ready, p.desired).replace(' / ', '/')} Ready`),
  );
  if (addons.healthy == null) return { summary: '확인 불가', ok: null, names, problems };
  const ok = addons.healthy === addons.total;
  return { summary: ok ? `정상 ${addons.healthy}/${addons.total}` : `문제 ${addons.total - addons.healthy} · 정상 ${addons.healthy}/${addons.total}`, ok, names, problems };
}

/** 'available · CPU 48% · 연결 36 · 남은 저장 공간 18.0 GB' */
export function rdsText(platform: TrafficPlatformResponse | undefined): string {
  const rds = platform?.platform.rds;
  if (!rds) return EMPTY;
  return [rds.status ?? EMPTY, `CPU ${isNum(rds.cpuPercent) ? `${Math.round(rds.cpuPercent)}%` : EMPTY}`, `연결 ${countText(rds.connections)}`, `남은 저장 공간 ${bytesText(rds.freeStorageBytes)}`].join(' · ');
}

const SERVICE_LABEL: Record<string, string> = { portal: 'Portal', console: 'Console' };

/** Portal · Console 한 줄씩 'Portal · 태스크 1/1 · 정상 대상 1 · 5xx 0' */
export function serviceTexts(services: TrafficServiceStatus[] | undefined): { key: string; text: string }[] {
  return (services ?? []).map((s) => ({
    key: s.name,
    text: [SERVICE_LABEL[s.name] ?? s.name, `태스크 ${pairText(s.running, s.desired).replace(' / ', '/')}`, `정상 대상 ${countText(s.healthyTargets)}`, `5xx ${countText(s.fiveXx)}`].join(' · '),
  }));
}

/** AWS 값만 지난 값이면 해당 칸(RDS · Portal/Console · ALB 5xx)에 작게 "지연" */
export const awsDelayed = (source: { stale: boolean } | undefined) => source?.stale ?? true;

/** '7 · A사 쿠폰 이벤트' */
export const eventTitle = (e: { eventId: number; tenantName: string | null; name: string }) =>
  `${e.eventId} · ${e.tenantName ? `${e.tenantName} ` : ''}${e.name}`;

const kstDay = (value: string | number) => formatDate(typeof value === 'number' ? new Date(value).toISOString() : value);

/** 진행 단계 · 드롭다운 시각: 오늘(KST)이면 'HH:mm', 아니면 'MM-DD HH:mm' */
export function clockText(iso: string, now: Date): string {
  return kstDay(iso) === kstDay(now.getTime()) ? formatTime(iso) : formatMonthDayTime(iso).replace('.', '-');
}

/** 오늘이면 '', 내일이면 '내일', 그 밖은 'MM.dd' */
export function dayLabel(iso: string, now: Date): string {
  const day = kstDay(iso);
  if (day === kstDay(now.getTime())) return '';
  if (day === kstDay(now.getTime() + 24 * 3600_000)) return '내일';
  return day.slice(5);
}

export interface UpcomingView {
  key: number;
  title: string;
  times: string;
}

/** B-3 '준비 13:30 · 입장 14:00 · 시작 15:00 · 종료 15:40'(오늘이 아니면 앞에 '내일' · 'MM.dd', 종료 날짜가 다르면 날짜 포함) */
export function upcomingViews(upcoming: TrafficUpcoming[] | undefined, now: Date): UpcomingView[] {
  return (upcoming ?? []).map((u) => {
    const day = dayLabel(u.prepareAt, now);
    const end = u.endAt == null ? EMPTY : kstDay(u.endAt) === kstDay(u.prepareAt) ? formatTime(u.endAt) : formatMonthDayTime(u.endAt);
    const times = `준비 ${formatTime(u.prepareAt)} · 입장 ${formatTime(u.entryAt)} · 시작 ${formatTime(u.startAt)} · 종료 ${end}`;
    return { key: u.eventId, title: eventTitle(u), times: day ? `${day} · ${times}` : times };
  });
}

// ---------------------------------------------------------------- E 최근 자동화 실패

export const FAILURE_STEP_LABEL: Record<TrafficFailure['step'], string> = {
  DEPLOY: '배포(WF1-2) 실패',
  HOSTING_START: '호스팅 시작(WF2-1) 실패',
  PREPARE: '준비(WF2-2) 실패',
  END: '종료(WF4) 실패',
  PROVISIONING: '환경 준비(WF5) 실패',
  MAIL: '메일 발송 실패',
  OTHER: '긴급 알림',
};

export interface FailureRow {
  key: string;
  time: string;
  event: string;
  step: string;
  reason: string;
  /** 이벤트 진행 상세, 이벤트가 없으면 알림센터 */
  link: string;
}

export function failureRows(failures: TrafficFailure[] | undefined): FailureRow[] {
  return (failures ?? []).map((f, i) => ({
    key: `${f.occurredAt}-${f.eventId ?? 'none'}-${i}`,
    time: formatMonthDayTime(f.occurredAt),
    event: f.eventId != null ? eventTitle({ eventId: f.eventId, tenantName: f.tenantName, name: f.eventName ?? '' }).trim() : (f.tenantName ?? EMPTY),
    step: FAILURE_STEP_LABEL[f.step] ?? f.step,
    reason: f.reason ?? EMPTY,
    link: f.eventId != null ? `/events/progress/${f.eventId}` : '/logs/notifications',
  }));
}

export const NO_FAILURE_TEXT = '최근 7일 실패 없음';

// ---------------------------------------------------------------- C 이벤트 선택

/** 주소 ?eventId= → 숫자(형식이 아니면 null) */
export function parseEventId(param: string | null | undefined): number | null {
  return param != null && /^\d{1,10}$/.test(param) ? Number(param) : null;
}

/**
 * 보여 줄 이벤트: 운영자가 고른 것(주소 ?eventId=)이 있으면 그대로 — 자동 갱신으로 기본 선택이 바뀌어도, 고른 이벤트가 끝나 목록에서
 * 빠져도 유지. 고르지 않았으면 서버 기본 선택(defaultEventId). 둘 다 없으면 null(드롭다운 끔)
 */
export function selectedEventId(param: string | null | undefined, events: TrafficEventsResponse | undefined): number | null {
  return parseEventId(param) ?? events?.defaultEventId ?? null;
}

/**
 * 이벤트 상태 — 이벤트 구역 배지와 드롭다운 항목이 같이 쓴다(서버 phase 기준: hosting_end_at이 지났으면 DB 상태가 OPEN이어도 '종료').
 * 실패는 배포 → 호스팅 → 준비 순으로 시각 · 종료보다 먼저 본다(호스팅 실패는 hosting_end_at이 지나도 '호스팅 실패').
 */
export const PHASE_META: Record<EventPhase, { label: string; tone: Tone }> = {
  SCHEDULED: { label: '예정', tone: 'grey' },
  PRE_ENTRY: { label: '입장 직전', tone: 'blue' },
  ENTRY_OPEN: { label: '입장 중', tone: 'blue' },
  LIVE: { label: '진행 중', tone: 'blue' },
  ENDED: { label: '종료', tone: 'grey' },
  PREPARE_FAILED: { label: '준비 실패', tone: 'red' },
  DEPLOY_FAILED: { label: '배포 실패', tone: 'red' },
  HOSTING_FAILED: { label: '호스팅 실패', tone: 'red' },
};

/** 상태 옆 기준 시각: 예정 · 입장 중 → 시작, 입장 직전 · 준비 실패 → 입장, 종료 → hosting_end_at, 배포 · 호스팅 실패 → 실패 시각, 진행 중 → 없음 */
function phaseTime(phase: EventPhase, e: { entryAt: string; startAt: string; endAt: string | null; failedAt: string | null }, now: Date): string | null {
  switch (phase) {
    case 'SCHEDULED':
    case 'ENTRY_OPEN':
      return `${clockText(e.startAt, now)} 시작`;
    case 'PRE_ENTRY':
      return `${clockText(e.entryAt, now)} 입장`;
    case 'PREPARE_FAILED':
      return clockText(e.entryAt, now);
    case 'ENDED':
      return e.endAt ? clockText(e.endAt, now) : null;
    case 'DEPLOY_FAILED':
    case 'HOSTING_FAILED':
      return e.failedAt ? clockText(e.failedAt, now) : null;
    default:
      return null;
  }
}

/**
 * 드롭다운 항목 끝 상태: '진행 중' · '예정 · 15:00 시작' · '입장 직전 · 14:00 입장' · '입장 중 · 15:00 시작' · '종료 · 10-24 15:40' · '준비 실패 · 10-25 14:00' ·
 * '배포 실패 · 10-26 14:05' · '호스팅 실패 · 13:50'
 */
export function eventStatusText(item: Pick<TrafficEventItem, 'phase' | 'entryAt' | 'startAt' | 'endAt' | 'failedAt'>, now: Date): string {
  const time = phaseTime(item.phase, item, now);
  return time ? `${PHASE_META[item.phase].label} · ${time}` : PHASE_META[item.phase].label;
}

export interface EventOption {
  value: number;
  label: string;
}

/** 드롭다운 항목(서버 순서: 진행 중 → 오늘 예정 → 최근 종료). 같은 이벤트는 처음 것만. 고른 이벤트가 목록에 없으면 맨 앞에 붙인다 */
export function eventOptions(events: TrafficEventsResponse | undefined, selected: number | null, now: Date): EventOption[] {
  const seen = new Set<number>();
  const options = (events?.items ?? [])
    .filter((item) => !seen.has(item.eventId) && seen.add(item.eventId))
    .map((item) => ({ value: item.eventId, label: `${eventTitle(item)} · ${eventStatusText(item, now)}` }));
  if (selected != null && !options.some((o) => o.value === selected)) {
    options.unshift({ value: selected, label: `${selected} · 목록 밖 이벤트(최근 7일 이전)` });
  }
  return options;
}

export const NO_EVENT_TEXT = '최근 7일 이벤트 없음';

/** 진행 중 묶음(event_status OPEN) 이름. 입장 ~ 시작 전(상태 '입장 중')도 들어 있다 */
export const OPEN_GROUP_TEXT = '진행 중(입장 중 포함)';
export const LIST_ORDER_TEXT = `목록 순서: ${OPEN_GROUP_TEXT} → 오늘 예정 → 최근 종료(7일)`;

// ---------------------------------------------------------------- C 구역 상태 · 진행 단계

export interface SectionStatusView {
  /** 이벤트 상태(드롭다운과 같은 PHASE_META) + 입장 직전 · 입장 중 · 진행 중이면 판정(정상 · 주의 · 위험 · 확인 불가) */
  chips: { label: string; tone: Tone }[];
  refresh: string;
  /** 종료면 false — 다시 부르지 않음 */
  autoRefresh: boolean;
}

/** 이벤트 구역 오른쪽 위: 고른 이벤트의 상태 칩 + 갱신 문구(명세 4절 '갱신') */
export function sectionStatus(event: TrafficEventResponse | undefined): SectionStatusView {
  const refresh = `자동 갱신 · ${POLL_MS / 1000}초`;
  if (!event) return { chips: [], refresh, autoRefresh: true };
  const phase = event.event.phase;
  const chips = [PHASE_META[phase]];
  if (phase === 'PRE_ENTRY' || phase === 'ENTRY_OPEN' || phase === 'LIVE') chips.push(LEVEL_META[event.kpi?.level ?? 'UNKNOWN']);
  return phase === 'ENDED' ? { chips, refresh: '자동 갱신 꺼짐', autoRefresh: false } : { chips, refresh, autoRefresh: true };
}

/** 종료된 이벤트는 값이 바뀌지 않아 다시 부르지 않는다 */
export const stopPollingWhen = (event: TrafficEventResponse) => event.event.phase === 'ENDED';

const STEP_LABEL: Record<TrafficStep['key'], string> = {
  APPROVE: '승인',
  DEPLOY: '배포',
  HOSTING_START: '호스팅 시작',
  // TEST-LEAD: 서비스 값 T−90 · T−60
  // PREPARE: '준비 (T−90)',
  // ENTRY: '입장 (T−60)',
  PREPARE: '준비 (T−15)', // TEST-LEAD
  ENTRY: '입장 (T−5)', // TEST-LEAD
  START: '이벤트 시작',
  END: '종료',
  AGGREGATE: '집계',
};

const STEP_FAILURE_LABEL: Partial<Record<TrafficStep['key'], string>> = {
  DEPLOY: '배포(WF1-2) 실패',
  HOSTING_START: '호스팅 시작(WF2-1) 실패',
  PREPARE: '준비(WF2-2) 실패',
  END: '종료(WF4) 실패',
};

export interface StepView {
  key: string;
  label: string;
  state: TrafficStep['state'];
  sub: string;
}

/**
 * 8단계 아래 글자: '13:30 완료 · 3분 20초' · '13:30 진행 중' · '14:55 시작 · 진행 중'(입장 · 시작) · '15:40 예정' · '16:09 기록 없음'(계획 시각이 지났는데 완료 기록이 없음) ·
 * '배포 중' · '종료 뒤' · '10-25 13:31 실패' · '진행 안 됨'(앞 단계 실패). 시각은 오늘이 아니면 날짜 포함(clockText)
 */
export function stepViews(steps: TrafficStep[] | undefined, now: Date): StepView[] {
  const when = (iso: string) => clockText(iso, now);
  return (steps ?? []).map((s) => {
    let sub: string;
    switch (s.state) {
      case 'DONE': {
        const d = durationText(s.durationSeconds);
        sub = `${s.actualAt ? when(s.actualAt) : EMPTY} 완료${d ? ` · ${d}` : ''}`;
        break;
      }
      case 'ACTIVE': {
        if (s.key === 'DEPLOY') {
          sub = '배포 중';
          break;
        }
        const t = s.actualAt ?? s.plannedAt;
        // 입장 · 시작은 시작한 시각을 붙인다(입장 = OPEN 로그, 시작 = 시작 시각)
        const running = s.key === 'ENTRY' || s.key === 'START' ? '시작 · 진행 중' : '진행 중';
        sub = t ? `${when(t)} ${now.getTime() >= Date.parse(t) ? running : '예정'}` : '진행 중';
        break;
      }
      case 'FAILED': {
        const t = s.actualAt ?? s.plannedAt;
        sub = `${t ? `${when(t)} ` : ''}실패`;
        break;
      }
      case 'SKIPPED':
        sub = '진행 안 됨';
        break;
      default:
        sub = s.plannedAt
          ? `${when(s.plannedAt)} ${now.getTime() > Date.parse(s.plannedAt) ? '기록 없음' : '예정'}`
          : s.key === 'AGGREGATE'
            ? '종료 뒤'
            : EMPTY;
    }
    return { key: s.key, label: STEP_LABEL[s.key] ?? s.key, state: s.state, sub };
  });
}

/** C·D 구역 본문(명세 4절): 예정 · 실패(배포 · 호스팅 · 준비)는 그래프 · KPI 대신 안내 */
export type EventBody =
  | { kind: 'scheduled'; title: string; description: string }
  | { kind: 'failed'; title: string; description: string; link: string }
  | { kind: 'metrics' };

/** 실패 상태별 안내: 실패한 단계가 응답에 없을 때의 이름 · 그래프가 없는 이유 */
const FAILED_BODY: Partial<Record<EventPhase, { label: string; description: string }>> = {
  DEPLOY_FAILED: { label: '배포(WF1-2) 실패', description: '배포가 끝나지 않아 이벤트가 열리지 않았어요 · 그래프·KPI가 없어요' },
  HOSTING_FAILED: { label: '호스팅 시작(WF2-1) 실패', description: '이벤트 페이지가 열리지 않아 그래프·KPI가 없어요' },
  PREPARE_FAILED: { label: '준비(WF2-2) 실패', description: '입장 게이트가 열리지 않아 그래프·KPI가 없어요' },
};

export function eventBody(event: TrafficEventResponse): EventBody {
  const phase: EventPhase = event.event.phase;
  if (phase === 'SCHEDULED') {
    const chartFrom = formatTime(new Date(Date.parse(event.event.entryAt) - 10 * 60_000).toISOString());
    return {
      kind: 'scheduled',
      title: '아직 수집 전이에요',
      description: `그래프와 KPI는 입장 10분 전(${chartFrom})부터 표시돼요 · 사전 점검은 위쪽 노드·플랫폼 칸에서`,
    };
  }
  const failure = FAILED_BODY[phase];
  if (failure) {
    const failed = event.steps.find((s) => s.state === 'FAILED');
    const label = (failed && STEP_FAILURE_LABEL[failed.key]) ?? failure.label;
    return {
      kind: 'failed',
      title: failed?.reason ? `${label} — ${failed.reason}` : label,
      description: failure.description,
      link: `/events/progress/${event.event.eventId}`,
    };
  }
  return { kind: 'metrics' };
}

// ---------------------------------------------------------------- D 범위 버튼 · KPI

export const RANGE_LABEL: Record<TrafficRangeKey, string> = {
  entry: '입장 −10분 ~ 지금',
  '15m': '15분',
  '1h': '1시간',
  full: '전체',
  start: '시작 ±10분',
};

export function rangeButtons(range: TrafficRange | null | undefined): { key: TrafficRangeKey; label: string; selected: boolean }[] {
  return (range?.options ?? []).map((key) => ({ key, label: RANGE_LABEL[key] ?? key, selected: key === range?.key }));
}

export interface KpiTile {
  label: string;
  value: string;
  sub?: string;
  /** 주의 값 — 황토색 글씨 */
  warn: boolean;
}

export interface KpiGroup {
  title: string;
  tiles: KpiTile[];
}

const AFTER_ENTRY = '입장 뒤 표시';

/** 입장 직전 · 진행 중 KPI 4묶음. 입장 직전은 요청 처리 · 대기 인원이 '–'(입장 뒤 표시) */
export function liveKpiGroups(kpi: TrafficKpi | null | undefined, phase: EventPhase): KpiGroup[] {
  const live = kpi?.live ?? null;
  const warn = new Set(kpi?.warn ?? []);
  const before = phase === 'PRE_ENTRY' ? AFTER_ENTRY : undefined;
  return [
    {
      title: '요청 처리',
      tiles: [
        { label: '현재 RPS', value: rpsText(live?.rps), sub: before, warn: false },
        { label: '5xx 비율', value: percentText(live?.errorRatio), sub: before ?? ERROR_RULE_TEXT, warn: warn.has('errorRatio') },
        { label: 'p95 지연', value: msText(live?.p95Ms), sub: before ?? `주의 ${P95_WARN_MS}ms (초안)`, warn: warn.has('p95') },
      ],
    },
    {
      title: '앱 자원',
      tiles: [
        {
          label: 'Pod (Ready / 최대)',
          value: pairText(live?.podsReady, live?.podsMax),
          sub: isNum(live?.podsPending) && live.podsPending > 0 ? `Pending ${countText(live.podsPending)}` : undefined,
          warn: warn.has('pods'),
        },
        { label: 'Pod CPU', value: coresText(live?.cpuCores), sub: requestShareText(live?.cpuCores, live?.cpuRequestCores), warn: false },
        { label: 'Pod 메모리', value: bytesText(live?.memoryBytes), sub: requestShareText(live?.memoryBytes, live?.memoryRequestBytes), warn: false },
      ],
    },
    {
      title: '이벤트 진행',
      tiles: [
        { label: '대기 인원 (추정)', value: countText(live?.waiting), sub: before, warn: false },
        { label: '남은 재고', value: countText(live?.stockRemaining), sub: isNum(live?.stockTotal) ? `전체 ${countText(live.stockTotal)}` : undefined, warn: false },
      ],
    },
    {
      title: 'Redis',
      tiles: [
        {
          label: '메모리',
          value: percentText(live?.redisMemoryRatio, 0),
          sub: live && (isNum(live.redisMemoryUsedBytes) || isNum(live.redisMemoryMaxBytes)) ? `${bytesText(live.redisMemoryUsedBytes)} / ${bytesText(live.redisMemoryMaxBytes)}` : undefined,
          warn: warn.has('redisMemory'),
        },
        { label: '처리량', value: opsText(live?.redisOpsPerSec), warn: false },
        {
          label: '재시작',
          value: countText(live?.redisRestarts),
          sub: live?.redisUp === false ? '연결 끊김' : `연결 ${countText(live?.redisConnections)}`,
          warn: warn.has('redisRestarts') || warn.has('redisUp'),
        },
      ],
    },
  ];
}

const peakAt = (at: string | null) => (at ? formatTime(at) : undefined);

/** 종료된 이벤트 최종값(최대 · 평균 · 발급 총량) */
export function endedKpiTiles(summary: TrafficSummaryKpi | null | undefined): KpiTile[] {
  const s = summary;
  return [
    { label: '최대 RPS', value: rpsText(s?.peakRps.value), sub: peakAt(s?.peakRps.at ?? null), warn: false },
    { label: '5xx 비율', value: percentText(s?.errorRatioAvg), sub: `전체 평균 · 최대 ${percentText(s?.errorRatioMax)}`, warn: false },
    { label: 'p95 지연', value: msText(s?.p95MaxMs), sub: `최대 · 평균 ${msText(s?.p95AvgMs)}`, warn: false },
    { label: 'Pod 최대', value: pairText(s?.podsMax, s?.podsLimit), warn: false },
    { label: '최대 대기 인원', value: countText(s?.peakWaiting.value), sub: peakAt(s?.peakWaiting.at ?? null), warn: false },
    { label: '발급 완료', value: pairText(s?.issued, s?.stockTotal), sub: s?.soldOutAt ? `${formatTime(s.soldOutAt)} 소진` : undefined, warn: false },
    { label: 'Pod CPU 최대', value: coresText(s?.cpuMaxCores), sub: requestShareText(s?.cpuMaxCores, s?.cpuRequestCores), warn: false },
    { label: 'Pod 메모리 최대', value: bytesText(s?.memoryMaxBytes), sub: requestShareText(s?.memoryMaxBytes, s?.memoryRequestBytes), warn: false },
    { label: 'Redis 메모리 최대', value: percentText(s?.redisMemoryMax, 0), warn: false },
    { label: 'Redis 재시작', value: countText(s?.redisRestarts), warn: false },
  ];
}

// ---------------------------------------------------------------- D-2 그래프 7개

/** 주 지표 남색(primary), 보조 지표 연보라(secondary). 이벤트 그래프에는 빨강을 쓰지 않는다(Pending도 보조색) */
export type SeriesRole = 'primary' | 'secondary';

export interface ChartSeriesView {
  id: string;
  label: string;
  role: SeriesRole;
  data: (number | null)[];
}

export interface ChartView {
  key: string;
  title: string;
  unit: string;
  /** 머리글 오른쪽 현재 값 */
  current: string;
  currentUnit: string;
  x: number[];
  series: ChartSeriesView[];
  /** 주의 기준(가로 점선, 황토) */
  thresholds: { y: number; label: string }[];
  /** 그 밖의 가로 점선(Pod 최소 · 최대) */
  guides: { y: number; label: string }[];
  /** 세로 점선(입장 · 시작 · 종료) — 범위 안의 것만 */
  markers: { x: number; label: string }[];
  yMax?: number;
  /** 값이 하나도 없음 */
  empty: boolean;
  /** AWS 값 지연(ALB · RDS 그래프만) */
  delayed: boolean;
}

/** Prometheus 격자 + AWS 점(60초)을 시각 합집합 하나로 맞춘다(빈 자리는 null, 그래프는 선을 잇는다) */
export function mergeOnTime(timestamps: number[], prom: (number | null)[][], aws: MonitoringPoint[][]): { x: number[]; prom: (number | null)[][]; aws: (number | null)[][] } {
  const x = [...new Set([...timestamps, ...aws.flatMap((points) => points.map(([t]) => t))])].sort((a, b) => a - b);
  const promAt = prom.map((values) => new Map(timestamps.map((t, i) => [t, values[i] ?? null])));
  const awsAt = aws.map((points) => new Map(points));
  return { x, prom: promAt.map((m) => x.map((t) => m.get(t) ?? null)), aws: awsAt.map((m) => x.map((t) => m.get(t) ?? null)) };
}

const lastValue = (values: (number | null)[]) => {
  for (let i = values.length - 1; i >= 0; i--) {
    const v = values[i];
    if (isNum(v)) return v;
  }
  return null;
};

const percentSeries = (values: (number | null)[]) => values.map((v) => toPercent(v));

const allEmpty = (series: ChartSeriesView[]) => series.every((s) => s.data.every((v) => !isNum(v)));

/** 그래프 세로 점선 후보 */
export interface ChartMarkers {
  entryAt: string | null;
  startAt: string | null;
  endAt?: string | null;
  soldOutAt?: string | null;
}

function markersIn(x: number[], m: ChartMarkers): { x: number; label: string }[] {
  if (x.length === 0) return [];
  const first = x[0];
  const last = x[x.length - 1];
  const candidates: [string | null | undefined, string][] = [
    [m.entryAt, '입장'],
    [m.startAt, '시작'],
    [m.soldOutAt, '재고 소진'],
    [m.endAt, '종료'],
  ];
  return candidates
    .filter((c): c is [string, string] => !!c[0])
    .map(([iso, label]) => ({ x: Date.parse(iso), label }))
    .filter((p) => p.x >= first && p.x <= last);
}

/**
 * 기준선 · 점선(주의 500ms, 최대 Pod 등)이 값보다 위에 있으면 축을 그 위까지 올린다(안 그러면 선이 그래프 밖으로 숨는다).
 * 값이 더 크면 undefined(그래프가 자동으로 정함)
 */
export function yMaxFor(series: ChartSeriesView[], lines: { y: number }[]): number | undefined {
  let dataMax = 0;
  for (const s of series) for (const v of s.data) if (isNum(v) && v > dataMax) dataMax = v;
  const lineMax = lines.reduce((m, l) => Math.max(m, l.y), 0);
  return lineMax > 0 && lineMax >= dataMax ? Math.ceil(lineMax * 1.15) : undefined;
}

const numText = (v: number | null, digits = 0) => (isNum(v) ? v.toLocaleString('ko-KR', { maximumFractionDigits: digits }) : EMPTY);

/**
 * 그래프 7개(3줄: 트래픽 · 에러 · 지연 / Pod · 진행 / Redis · RDS). 에러는 앱 5xx(이 이벤트) + ALB 5xx(전체 이벤트 합) —
 * errors.eventTarget은 쓰지 않는다. 비율은 %로 바꿔 그린다.
 */
export function chartViews(charts: TrafficCharts, markers: ChartMarkers, awsStale: boolean): ChartView[] {
  const t = charts.timestamps;
  const mk = (x: number[]) => markersIn(x, markers);

  const traffic: ChartSeriesView[] = [
    { id: 'rps', label: 'API 전체 RPS', role: 'primary', data: charts.traffic.rps },
    { id: 'cursor', label: '커서 도달 / 초', role: 'secondary', data: charts.traffic.cursorPerSec },
  ];

  const errorsMerged = mergeOnTime(t, [percentSeries(charts.errors.app)], [charts.errors.alb.map(([time, v]) => [time, toPercent(v)] as MonitoringPoint)]);
  const errors: ChartSeriesView[] = [
    { id: 'app', label: '앱 5xx (이 이벤트)', role: 'primary', data: errorsMerged.prom[0] },
    { id: 'alb', label: 'ALB 5xx (전체 이벤트 합)', role: 'secondary', data: errorsMerged.aws[0] },
  ];

  const latency: ChartSeriesView[] = [
    { id: 'issuance', label: '발급 p95', role: 'primary', data: charts.latency.issuance },
    { id: 'ticket', label: '번호표 p95', role: 'secondary', data: charts.latency.ticket },
  ];

  const pods: ChartSeriesView[] = [
    { id: 'ready', label: 'Ready', role: 'primary', data: charts.pods.ready },
    { id: 'pending', label: 'Pending', role: 'secondary', data: charts.pods.pending },
  ];
  const guides: { y: number; label: string }[] = [];
  if (isNum(charts.pods.max)) guides.push({ y: charts.pods.max, label: `최대 ${charts.pods.max}` });
  if (isNum(charts.pods.min)) guides.push({ y: charts.pods.min, label: `최소 ${charts.pods.min}` });

  const progress: ChartSeriesView[] = [
    { id: 'waiting', label: '대기 인원', role: 'primary', data: charts.progress.waiting },
    { id: 'stock', label: '남은 재고', role: 'secondary', data: charts.progress.stock },
  ];

  const redis: ChartSeriesView[] = [
    { id: 'memory', label: '메모리 %', role: 'primary', data: percentSeries(charts.redis.memory) },
    { id: 'cpu', label: 'CPU %', role: 'secondary', data: percentSeries(charts.redis.cpu) },
  ];

  const rdsMerged = mergeOnTime([], [], [charts.rds.cpu, charts.rds.connections]);
  const rds: ChartSeriesView[] = [
    { id: 'cpu', label: 'CPU %', role: 'primary', data: rdsMerged.aws[0] },
    { id: 'connections', label: '연결 수', role: 'secondary', data: rdsMerged.aws[1] },
  ];

  const view = (v: Omit<ChartView, 'empty' | 'markers' | 'guides' | 'thresholds' | 'delayed'> & Partial<Pick<ChartView, 'guides' | 'thresholds' | 'delayed'>>): ChartView => {
    const thresholds = v.thresholds ?? [];
    const guides = v.guides ?? [];
    return {
      delayed: false,
      ...v,
      thresholds,
      guides,
      yMax: v.yMax ?? yMaxFor(v.series, [...thresholds, ...guides]),
      markers: mk(v.x),
      empty: v.x.length === 0 || allEmpty(v.series),
    };
  };

  const readyNow = lastValue(charts.pods.ready);
  return [
    view({ key: 'traffic', title: '트래픽', unit: 'RPS (초당 요청)', current: numText(lastValue(charts.traffic.rps)), currentUnit: 'RPS', x: t, series: traffic }),
    view({
      key: 'errors',
      title: '에러',
      unit: '5xx 비율 %',
      current: numText(lastValue(errors[0].data), 1),
      currentUnit: '%',
      x: errorsMerged.x,
      series: errors,
      thresholds: [{ y: ERROR_RATIO_WARN * 100, label: `주의 기준 ${pct(ERROR_RATIO_WARN)}` }],
      delayed: awsStale,
    }),
    view({
      key: 'latency',
      title: '지연',
      unit: 'p95 ms',
      current: numText(lastValue(charts.latency.issuance)),
      currentUnit: 'ms',
      x: t,
      series: latency,
      thresholds: [{ y: P95_WARN_MS, label: `주의 기준 ${P95_WARN_MS}ms (초안)` }],
    }),
    view({ key: 'pods', title: 'Pod', unit: '개수', current: `${numText(readyNow)} / ${numText(charts.pods.max)}`, currentUnit: '', x: t, series: pods, guides }),
    view({ key: 'progress', title: '진행', unit: '명 · 개', current: numText(lastValue(charts.progress.waiting)), currentUnit: '명 대기', x: t, series: progress }),
    view({
      key: 'redis',
      title: 'Redis',
      unit: '메모리 % · CPU %',
      current: numText(lastValue(redis[0].data)),
      currentUnit: '% 메모리',
      x: t,
      series: redis,
      thresholds: [{ y: 80, label: '주의 기준 80%' }],
      yMax: 100,
    }),
    view({
      key: 'rds',
      title: 'RDS',
      unit: 'CPU % · 연결 수 · 60초 단위',
      current: numText(lastValue(rds[0].data)),
      currentUnit: '% CPU',
      x: rdsMerged.x,
      series: rds,
      thresholds: [{ y: 80, label: '주의 기준 CPU 80%' }],
      delayed: awsStale,
    }),
  ];
}

/** 그래프 줄 나눔: 트래픽 · 에러 · 지연 / Pod · 진행 / Redis · RDS */
export const CHART_ROWS = [['traffic', 'errors', 'latency'], ['pods', 'progress'], ['redis', 'rds']] as const;
