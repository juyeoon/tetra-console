import type {
  EventPhase,
  MonitoringLevel,
  MonitoringPoint,
  MonitoringSource,
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
  DELAY: '단계 지연',
  SYSTEM: '시스템(DLQ)',
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

/** 이벤트가 없는 실패의 상세 — 알림센터 긴급 · 읽은 것도(이미 읽은 실패 알림도 보이게) */
export const FAILURE_NOTIFICATIONS_LINK = '/logs/notifications?priority=URGENT&unreadOnly=false';

export function failureRows(failures: TrafficFailure[] | undefined): FailureRow[] {
  return (failures ?? []).map((f, i) => ({
    key: `${f.occurredAt}-${f.eventId ?? 'none'}-${i}`,
    time: formatMonthDayTime(f.occurredAt),
    event: f.eventId != null ? eventTitle({ eventId: f.eventId, tenantName: f.tenantName, name: f.eventName ?? '' }).trim() : (f.tenantName ?? EMPTY),
    step: FAILURE_STEP_LABEL[f.step] ?? f.step,
    reason: f.reason ?? EMPTY,
    link: f.eventId != null ? `/events/progress/${f.eventId}` : FAILURE_NOTIFICATIONS_LINK,
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
 * 이벤트 상태 — 이벤트 구역 배지와 드롭다운 항목이 같이 쓴다(서버 phase 기준: hosting_end_at이 지났으면 DB 상태가 OPEN이어도 '종료 처리 중').
 * 종료 시각은 둘: 이벤트 종료(end_at) 뒤 '마무리', 호스팅 종료(hosting_end_at) 뒤 '종료 처리 중' → 집계 완료 '종료'.
 * 실패는 배포 → 호스팅 → 준비 → WF4 순으로 시각보다 먼저 본다(호스팅 실패는 hosting_end_at이 지나도 '호스팅 실패').
 */
export const PHASE_META: Record<EventPhase, { label: string; tone: Tone }> = {
  SCHEDULED: { label: '예정', tone: 'grey' },
  PRE_ENTRY: { label: '입장 직전', tone: 'blue' },
  ENTRY_OPEN: { label: '입장 중', tone: 'blue' },
  LIVE: { label: '진행 중', tone: 'blue' },
  WRAP_UP: { label: '마무리', tone: 'blue' },
  ENDING: { label: '종료 처리 중', tone: 'grey' },
  ENDED: { label: '종료', tone: 'grey' },
  END_FAILED: { label: '종료·집계 실패', tone: 'red' },
  PREPARE_FAILED: { label: '준비 실패', tone: 'red' },
  DEPLOY_FAILED: { label: '배포 실패', tone: 'red' },
  HOSTING_FAILED: { label: '호스팅 실패', tone: 'red' },
};

/**
 * 상태 옆 기준 시각: 예정 · 입장 중 → 시작, 입장 직전 · 준비 실패 → 입장, 마무리 → 호스팅 종료 예정, 호스팅 종료 뒤 → hosting_end_at,
 * 배포 · 호스팅 실패 → 실패 시각, 진행 중 → 없음
 */
function phaseTime(phase: EventPhase, e: { entryAt: string; startAt: string; endAt: string | null; failedAt: string | null }, now: Date): string | null {
  switch (phase) {
    case 'SCHEDULED':
    case 'ENTRY_OPEN':
      return `${clockText(e.startAt, now)} 시작`;
    case 'PRE_ENTRY':
      return `${clockText(e.entryAt, now)} 입장`;
    case 'PREPARE_FAILED':
      return clockText(e.entryAt, now);
    case 'WRAP_UP':
      return e.endAt ? `${clockText(e.endAt, now)} 호스팅 종료` : null;
    case 'ENDING':
    case 'ENDED':
    case 'END_FAILED':
      return e.endAt ? clockText(e.endAt, now) : null;
    case 'DEPLOY_FAILED':
    case 'HOSTING_FAILED':
      return e.failedAt ? clockText(e.failedAt, now) : null;
    default:
      return null;
  }
}

/**
 * 드롭다운 항목 끝 상태: '진행 중' · '예정 · 15:00 시작' · '입장 직전 · 14:00 입장' · '입장 중 · 15:00 시작' · '마무리 · 15:40 호스팅 종료' ·
 * '종료 처리 중 · 15:40' · '종료 · 10-24 15:40' · '종료·집계 실패 · 15:40' · '준비 실패 · 10-25 14:00' · '배포 실패 · 10-26 14:05' · '호스팅 실패 · 13:50'
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

/** 진행 중 묶음(event_status OPEN이고 호스팅 종료 전) 이름. 입장 ~ 시작 전(입장 중) · 이벤트 종료 ~ 호스팅 종료(마무리)도 들어 있다 */
export const OPEN_GROUP_TEXT = '진행 중(입장 중 · 마무리 포함)';
export const LIST_ORDER_TEXT = `목록 순서: ${OPEN_GROUP_TEXT} → 오늘 예정 → 최근 종료(7일)`;

// ---------------------------------------------------------------- C 구역 상태 · 진행 단계

export interface SectionStatusView {
  /** 이벤트 상태(드롭다운과 같은 PHASE_META) + 입장 직전 ~ 마무리면 판정(정상 · 주의 · 위험 · 확인 불가) */
  chips: { label: string; tone: Tone }[];
  refresh: string;
  /** 종료 · 종료 실패면 false — 다시 부르지 않음 */
  autoRefresh: boolean;
}

/** 실시간 KPI · 판정이 있는 상태(입장 직전 · 입장 중 · 진행 중 · 마무리) */
const LIVE_PHASES: readonly EventPhase[] = ['PRE_ENTRY', 'ENTRY_OPEN', 'LIVE', 'WRAP_UP'];

/** 호스팅 종료 뒤(종료 처리 중 · 종료 · 종료 실패): 최종값 · 그래프 범위 고정 */
const CLOSED_PHASES: readonly EventPhase[] = ['ENDING', 'ENDED', 'END_FAILED'];

export const isClosedPhase = (phase: EventPhase) => CLOSED_PHASES.includes(phase);

/** 자동 갱신을 끄는 상태: 집계까지 끝났거나(종료) WF4가 실패(종료 실패) — 더 바뀌지 않는다 */
const STOPPED_PHASES: readonly EventPhase[] = ['ENDED', 'END_FAILED'];

/**
 * 이벤트 구역 오른쪽 위: 고른 이벤트의 상태 칩 + 갱신 문구(명세 4절 '갱신').
 * 종료 처리 중은 WF4 · 집계 진행을 보려고 15초마다 부르되 서버가 Prometheus · CloudWatch를 다시 조회하지 않는다('진행 단계만')
 */
export function sectionStatus(event: TrafficEventResponse | undefined): SectionStatusView {
  const refresh = `자동 갱신 · ${POLL_MS / 1000}초`;
  if (!event) return { chips: [], refresh, autoRefresh: true };
  const phase = event.event.phase;
  const chips = [PHASE_META[phase]];
  if (LIVE_PHASES.includes(phase)) chips.push(LEVEL_META[event.kpi?.level ?? 'UNKNOWN']);
  if (STOPPED_PHASES.includes(phase)) return { chips, refresh: '자동 갱신 꺼짐', autoRefresh: false };
  return { chips, refresh: phase === 'ENDING' ? `${refresh} · 진행 단계만` : refresh, autoRefresh: true };
}

/** 종료 · 종료 실패는 값이 바뀌지 않아 다시 부르지 않는다(종료 처리 중은 계속 — 집계가 끝나면 종료로 바뀐다) */
export const stopPollingWhen = (event: TrafficEventResponse) => STOPPED_PHASES.includes(event.event.phase);

/** 그래프 머리글 갱신 문구. 호스팅 종료 뒤는 범위 · 값 고정 */
export function chartRefreshText(event: TrafficEventResponse): string {
  const base = isClosedPhase(event.event.phase) ? '호스팅 종료 뒤 값 고정' : '앱 지표 15초 · ALB·RDS 60초 갱신';
  return event.prometheus.stale ? `${base} · 데이터 지연됨` : base;
}

const STEP_LABEL: Record<TrafficStep['key'], string> = {
  APPROVE: '승인',
  DEPLOY: '배포',
  HOSTING_START: '호스팅 시작',
  // TEST-LEAD: 서비스 값 T−90 · T−60
  // PREPARE: '준비 (T−90)',
  // ENTRY: '입장 (T−60)',
  PREPARE: '준비 (T−15)', // TEST-LEAD
  ENTRY: '입장 (T−5)', // TEST-LEAD
  START: '이벤트 진행',
  END: '호스팅 종료',
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
 * 8단계 아래 글자(2026-10-28 정리). 시각은 오늘이 아니면 날짜 포함(clockText), 좁으면 두 줄로 다 보인다(StepTimeline).
 * - 끝남(채운 점 · 실선): '13:30 완료' · '13:30 완료 · 3분 20초'(준비) · '15:00~15:40 완료'(이벤트 진행 = 시작 ~ 이벤트 종료)
 * - 진행 중(빈 점 · 굵게, 지금 실제로 돌고 있는 단계만): '14:55 시작 · 진행 중' · '종료 처리 중'(호스팅 종료) · '집계 중'
 * - 아직(회색 점 · 점선): '15:40 예정' — 계획 시각이 지났어도 같다('기록 없음'은 쓰지 않는다, 실패처럼 읽힌다). 계획이 없는 집계는 '호스팅 종료 뒤'
 * - '10-25 13:31 실패' · '진행 안 됨'(앞 단계 실패)
 */
export function stepViews(steps: TrafficStep[] | undefined, now: Date): StepView[] {
  const when = (iso: string) => clockText(iso, now);
  return (steps ?? []).map((s) => {
    let sub: string;
    switch (s.state) {
      case 'DONE': {
        if (s.key === 'START' && s.actualAt && s.endAt) {
          // 같은 날이면 끝 시각은 'HH:mm'만, 날짜가 넘어가면 끝에도 날짜
          const end = kstDay(s.endAt) === kstDay(s.actualAt) ? formatTime(s.endAt) : formatMonthDayTime(s.endAt).replace('.', '-');
          sub = `${when(s.actualAt)}~${end} 완료`;
          break;
        }
        const d = durationText(s.durationSeconds);
        sub = `${s.actualAt ? when(s.actualAt) : EMPTY} 완료${d ? ` · ${d}` : ''}`;
        break;
      }
      case 'ACTIVE': {
        if (s.key === 'END') {
          sub = '종료 처리 중';
          break;
        }
        if (s.key === 'AGGREGATE') {
          sub = '집계 중';
          break;
        }
        const t = s.actualAt ?? s.plannedAt;
        sub = t ? `${when(t)} 시작 · 진행 중` : '진행 중';
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
        sub = s.plannedAt ? `${when(s.plannedAt)} 예정` : s.key === 'AGGREGATE' ? '호스팅 종료 뒤' : EMPTY;
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

/**
 * 그래프(charts)가 없을 때 문구 — prometheus.staleReason으로 나눈다.
 * Prometheus 자체 장애(연결 · 5xx · 시간 초과 · 미설정)면 응답 지연, 그 밖(갱신은 됐는데 그래프 가공 실패 포함)은 처리 오류.
 * staleReason이 없는 응답(이전 서버)의 stale은 지연으로 본다
 */
export function chartsMissingText(prometheus: MonitoringSource | undefined): string {
  return prometheus?.stale && prometheus.staleReason !== 'INTERNAL'
    ? 'Prometheus 응답이 늦어 그래프를 받지 못했어요'
    : '그래프를 만들지 못했어요';
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

/** 최대값 시각(1분 구간의 시작, 서버가 맞춘다). 최대가 0이거나 모르면 '–' */
const peakAt = (at: string | null) => (at ? formatTime(at) : EMPTY);

/** 호스팅이 끝난 이벤트 최종값(최대 · 평균 · 발급 총량). 집계 구간은 입장 ~ 이벤트 종료(그래프 범위보다 좁다) */
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

// ---------------------------------------------------------------- D-2 그래프 8개

/** 주 지표 진보라(primary), 보조 지표 연보라(secondary). 이벤트 그래프에는 빨강을 쓰지 않는다(Pending도 보조색) */
export type SeriesRole = 'primary' | 'secondary';

export interface ChartSeriesView {
  id: string;
  label: string;
  role: SeriesRole;
  data: (number | null)[];
  /** 툴팁 값 뒤 단위(예: '번호/초'). 없으면 숫자만 */
  unit?: string;
  /** 점선(보조선) — 그래프 선과 범례 모두 */
  dashed?: boolean;
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
  /** 세로 점선(입장 · 시작 · 재고 소진 · 이벤트 종료 · 호스팅 종료) — 범위 안의 것만 */
  markers: { x: number; label: string }[];
  yMax?: number;
  /** 값이 하나도 없음 */
  empty: boolean;
  /** AWS 값 지연(ALB · RDS 그래프만) */
  delayed: boolean;
  /** 툴팁 아래 안내 한 줄(입장 그래프의 추정 안내). 없으면 기본 툴팁 */
  tooltipNote?: string;
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

/** 그래프 세로 점선 후보. eventEndAt = 이벤트 종료(end_at, 마무리부터 보임), hostingEndAt = 호스팅 종료(hosting_end_at) */
export interface ChartMarkers {
  entryAt: string | null;
  startAt: string | null;
  eventEndAt?: string | null;
  hostingEndAt?: string | null;
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
    [m.eventEndAt, '이벤트 종료'],
    [m.hostingEndAt, '호스팅 종료'],
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

/** 입장 그래프 툴팁 안내 — 입장(명/초)은 입장이 허용된 번호표 수의 기울기라 떠난 사람도 들어간다 */
export const ADMITTED_NOTE = '입장: 번호표를 받은 뒤 떠난 사람도 포함된 추정치';

/**
 * 그래프 8개(3줄: 트래픽 · 에러 · 지연 / Pod · 진행 · 입장 / Redis · RDS). 에러는 앱 5xx(이 이벤트) + ALB 5xx(전체 이벤트 합) —
 * errors.eventTarget은 쓰지 않는다. 비율은 %로 바꿔 그린다.
 */
export function chartViews(charts: TrafficCharts, markers: ChartMarkers, awsStale: boolean): ChartView[] {
  const t = charts.timestamps;
  const mk = (x: number[]) => markersIn(x, markers);

  const traffic: ChartSeriesView[] = [{ id: 'rps', label: 'API 전체 RPS', role: 'primary', data: charts.traffic.rps, unit: 'RPS' }];

  // 둘 다 요청 수가 아니다. 입장 = min(서빙 커서, 번호표 끝)의 기울기(실선), 입장 허용 = 서빙 커서 전진(점선, 번호표 끝보다 앞설 수 있음)
  const admission: ChartSeriesView[] = [
    { id: 'admitted', label: '입장 (명/초, 추정)', role: 'primary', data: charts.admission.admittedPerSec, unit: '명/초' },
    { id: 'cursor', label: '입장 허용 (커서 전진, 번호/초)', role: 'secondary', data: charts.admission.cursorPerSec, unit: '번호/초', dashed: true },
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
      key: 'admission',
      title: '입장',
      unit: '명/초 · 번호/초',
      current: numText(lastValue(charts.admission.admittedPerSec), 1),
      currentUnit: '명/초',
      x: t,
      series: admission,
      tooltipNote: ADMITTED_NOTE,
    }),
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

/** 그래프 줄 나눔: 트래픽 · 에러 · 지연 / Pod · 진행 · 입장 / Redis · RDS */
export const CHART_ROWS = [['traffic', 'errors', 'latency'], ['pods', 'progress', 'admission'], ['redis', 'rds']] as const;

/** x축 눈금 사이 최소 폭(px) — 'HH:mm' 글자(약 32px) + 여백 */
export const X_TICK_MIN_PX = 56;

/** y축 폭(MetricChart와 같은 값) */
export const Y_AXIS_WIDTH = 48;

/** 폭을 아직 모를 때(첫 그림) 가정하는 그래프 폭 */
const DEFAULT_CHART_WIDTH = 300;

/** 눈금 간격 후보(분) */
const TICK_STEP_MINUTES = [1, 2, 5, 10, 15, 30, 60, 120, 180, 360, 720, 1440];

const KST_OFFSET_MS = 9 * 3600_000;

/**
 * x축 눈금(epoch 밀리초) — 그래프 폭에 맞춰 눈금 사이가 X_TICK_MIN_PX 이상인 가장 촘촘한 간격(1분 이상, 글자가 'HH:mm'),
 * KST 정각 단위로 맞춘다. 3칸 줄(트래픽 · 에러 · 지연)은 좁아 눈금 수를 고정(4)하면 짧은 범위에서 1분 눈금이 겹쳤다('09:4509:46…', 2026-10-28)
 */
export function timeTicks(from: number, to: number, width: number): number[] {
  if (!Number.isFinite(from) || !Number.isFinite(to)) return [];
  if (to <= from) return [from];
  const plot = Math.max(1, (width > 0 ? width : DEFAULT_CHART_WIDTH) - Y_AXIS_WIDTH);
  const maxSteps = Math.max(1, Math.floor(plot / X_TICK_MIN_PX));
  const minutes = TICK_STEP_MINUTES.find((m) => (to - from) / (m * 60_000) <= maxSteps) ?? TICK_STEP_MINUTES[TICK_STEP_MINUTES.length - 1];
  const step = minutes * 60_000;
  const ticks: number[] = [];
  for (let t = Math.ceil((from + KST_OFFSET_MS) / step) * step - KST_OFFSET_MS; t <= to; t += step) ticks.push(t);
  return ticks;
}

/** 세로 점선 이름 글자 크기(px) = 캡션 12px × --type-scale 1.25(styles/tokens.css --fs-caption) */
const MARKER_FONT_PX = 15;

/** 세로 점선 이름 폭 추정(px): 한글은 글자 크기, 띄어쓰기 4, 그 밖 8 */
const markerLabelWidth = (label: string) => [...label].reduce((w, c) => w + (/[가-힣]/.test(c) ? MARKER_FONT_PX : c === ' ' ? 4 : 8), 0);

/** 그래프 오른쪽 여백(MetricChart margin.right) — 점선 이름은 여기까지 넘칠 수 있다 */
const CHART_RIGHT_MARGIN = 24;

/** 세로 점선과 이름 사이(px, x-charts 기본 간격) */
export const MARKER_LABEL_OFFSET = 5;

/** 세로 점선 이름 단 높이(px) · 최대 단 수 */
export const MARKER_LEVEL_PX = 18;

export const MARKER_MAX_LEVELS = 3;

/** 같은 단 이름 사이 최소 간격(px) */
const MARKER_LABEL_GAP = 4;

/** 세로 점선 하나 — level = 이름 단(0이 맨 위), side = 이름이 선 오른쪽 · 왼쪽 */
export interface MarkerLabel {
  x: number;
  label: string;
  level: number;
  side: 'right' | 'left';
}

/**
 * 세로 점선 이름 배치. 시각 순으로 겹치지 않는 가장 낮은 단(최대 3단)에 놓는다 — 모든 그래프에 이름 5종이 보이게.
 * 이름은 선 오른쪽에 붙고, 그래프 오른쪽 끝을 넘으면 선 왼쪽에 붙인다. 3단에도 못 놓으면 이름만 비운다(선은 그대로).
 * 폭은 그래프마다 잰 px라 세 칸 · 두 칸 줄을 따로 나누지 않는다. 한 줄로만 놓아 그래프 폭마다 다른 이름이 숨던 것(테스트 13, 2026-10-28)
 */
export function markerLabels(markers: { x: number; label: string }[], from: number, to: number, width: number): MarkerLabel[] {
  const plot = Math.max(1, (width > 0 ? width : DEFAULT_CHART_WIDTH) - Y_AXIS_WIDTH);
  const drawing = Math.max(1, plot - CHART_RIGHT_MARGIN);
  // 단마다 놓인 이름의 [왼쪽, 오른쪽] px
  const levels: [number, number][][] = Array.from({ length: MARKER_MAX_LEVELS }, () => []);
  return [...markers]
    .sort((a, b) => a.x - b.x)
    .map((m) => {
      const px = to > from ? ((m.x - from) / (to - from)) * drawing : 0;
      const w = markerLabelWidth(m.label);
      const side = px + MARKER_LABEL_OFFSET + w <= plot ? 'right' : 'left';
      const span: [number, number] = side === 'right' ? [px + MARKER_LABEL_OFFSET, px + MARKER_LABEL_OFFSET + w] : [px - MARKER_LABEL_OFFSET - w, px - MARKER_LABEL_OFFSET];
      const level = span[0] < 0 ? -1 : levels.findIndex((placed) => placed.every(([s, e]) => span[1] + MARKER_LABEL_GAP <= s || e + MARKER_LABEL_GAP <= span[0]));
      if (level < 0) return { ...m, label: '', level: 0, side: 'right' };
      levels[level].push(span);
      return { ...m, level, side };
    });
}

/** 툴팁 값: 없으면 '–', 있으면 숫자(+ 단위) */
export function tooltipValue(v: number | null | undefined, unit?: string): string {
  if (!isNum(v)) return EMPTY;
  const text = v.toLocaleString('ko-KR', { maximumFractionDigits: 3 });
  return unit ? `${text} ${unit}` : text;
}
