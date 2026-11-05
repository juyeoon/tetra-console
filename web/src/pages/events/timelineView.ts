/**
 * OPS-04 이벤트 진행 현황 상단 "이벤트 타임라인" — GET /live-events 응답 → 화면 모델(순수 함수, node 테스트).
 * - 시각은 모두 KST. 한국은 서머타임이 없어서 +9시간 산술로 정시 · 자정을 맞춘다.
 * - "지금"은 브라우저 시계(카드가 TICK_MS마다 다시 넣는다). 데이터는 카드가 POLL_MS마다 따로 받는다.
 * - 막대 · 이름 위치는 트랙 폭 기준 %, 줄 배치(16px 틈 · 오른쪽 끝 맞춤)만 트랙 px 폭과 이름 폭 측정 함수가 필요하다.
 * node --test 대상이라 상대 경로 + .ts로 import한다.
 */
import type { LiveEventItem } from '../../api/types.ts';
import { formatClockMinute, formatDate, formatMonthDayTime, formatMonthDayWeekday, formatTime } from '../../utils/datetime.ts';
import { eventStatusMeta, hostingStatusMeta } from '../../utils/status.ts';

/** 데이터 다시 받기 간격 — 목록 API는 캐시 없이 테넌트 스키마를 읽어서 OPS-05(15초)보다 길게 */
export const POLL_MS = 60_000;

/** 지금 선 · 기간 · 지난 이벤트 여부를 다시 계산하는 간격(12시간 · 900px에서 30초 ≈ 0.6px) */
export const TICK_MS = 30_000;

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const KST_OFFSET_MS = 9 * HOUR;

/** 스크롤 상자 안 최소 폭 · 폭을 재기 전(첫 그림) 가정하는 트랙 폭 */
export const TRACK_MIN_PX = 900;

/** 이름 · 눈금 글자 = 캡션 12px × --type-scale 1.25, 줄 높이 = 18px × 1.25 (styles/tokens.css --fs-caption · --lh-caption) */
export const CAPTION_PX = 15;
export const NAME_LINE_PX = 22.5;

/** 한 줄 = 이름 줄 + 3 + 막대 18 + 줄 사이 11 */
export const NAME_BAR_GAP_PX = 3;
export const BAR_PX = 18;
export const LANE_GAP_PX = 11;
export const LANE_PX = NAME_LINE_PX + NAME_BAR_GAP_PX + BAR_PX + LANE_GAP_PX;

/** 트랙 위쪽(동시 호스팅 글자) · 아래쪽("지금" 칩) */
export const TRACK_TOP_PX = 30;
export const TRACK_BOTTOM_PX = 26;

/** 막대 최소 폭 — 1주처럼 짧은 이벤트도 보이고 누를 수 있게 */
export const BAR_MIN_PX = 6;

/** 같은 줄 앞 이벤트(막대 · 이름 중 끝)와의 최소 틈 */
const LANE_SPACE_PX = 16;

/** 오른쪽 끝 이 % 안의 눈금 글자는 생략(잘림 방지) */
const TICK_LABEL_EDGE_PCT = 97;

/** 1주 날짜 글자는 칸 왼쪽에서 이만큼 안쪽 */
export const WEEK_LABEL_INSET_PX = 6;

/** "지금" 칩 좌우 안쪽 여백 */
export const NOW_CHIP_PAD_X = 6;

// ---------------------------------------------------------------------------
// 기간
// ---------------------------------------------------------------------------

export type PeriodKey = '12h' | '24h' | '48h' | '1w';

export const PERIODS: { key: PeriodKey; label: string }[] = [
  { key: '12h', label: '12시간' },
  { key: '24h', label: '24시간' },
  { key: '48h', label: '48시간' },
  { key: '1w', label: '1주' },
];

/** 처음 열면 12시간(주소 · 저장 없음 — 새로 고침하면 다시 12시간) */
export const DEFAULT_PERIOD: PeriodKey = '12h';

/** 지금 − lead를 unit 단위로 내린 곳에서 length만큼, 눈금은 tick 간격 → 지금이 왼쪽 약 1/4 ~ 1/3 */
const HOUR_RULES: Record<Exclude<PeriodKey, '1w'>, { lead: number; unit: number; length: number; tick: number }> = {
  '12h': { lead: 3 * HOUR, unit: HOUR, length: 12 * HOUR, tick: HOUR },
  '24h': { lead: 6 * HOUR, unit: 2 * HOUR, length: 24 * HOUR, tick: 2 * HOUR },
  '48h': { lead: 12 * HOUR, unit: 3 * HOUR, length: 48 * HOUR, tick: 3 * HOUR },
};

export interface Period {
  key: PeriodKey;
  /** epoch ms, [from, to) */
  from: number;
  to: number;
  /** 눈금 간격(1주는 하루 — 12시 옅은 선은 따로) */
  tick: number;
  week: boolean;
}

/** KST 기준 unit 단위로 내림(정시 · 2시간 · 3시간 · 자정) */
const floorKst = (t: number, unit: number) => Math.floor((t + KST_OFFSET_MS) / unit) * unit - KST_OFFSET_MS;

/** KST 날짜 번호(같은 날 비교용) */
const kstDay = (t: number) => Math.floor((t + KST_OFFSET_MS) / DAY);

export function periodOf(key: PeriodKey, now: number): Period {
  if (key === '1w') {
    const from = floorKst(now, DAY) - 2 * DAY;
    return { key, from, to: from + 7 * DAY, tick: DAY, week: true };
  }
  const r = HOUR_RULES[key];
  const from = floorKst(now - r.lead, r.unit);
  return { key, from, to: from + r.length, tick: r.tick, week: false };
}

/** 머리 기간 문구 — 같은 날 '11.04 (수) 06:00 – 18:00', 날짜가 바뀌면 '11.03 (화) 21:00 – 11.05 (목) 21:00', 1주 '11.02 (월) – 11.08 (일)' */
export function periodText(p: Period): string {
  if (p.week) return `${formatMonthDayWeekday(p.from)} – ${formatMonthDayWeekday(p.to - 1)}`;
  const start = `${formatMonthDayWeekday(p.from)} ${formatClockMinute(p.from)}`;
  if (kstDay(p.from) === kstDay(p.to)) return `${start} – ${formatClockMinute(p.to)}`;
  return `${start} – ${formatMonthDayWeekday(p.to)} ${formatClockMinute(p.to)}`;
}

// ---------------------------------------------------------------------------
// 눈금
// ---------------------------------------------------------------------------

export interface Tick {
  t: number;
  /** 트랙 왼쪽에서 % */
  pct: number;
  /** 0시 — 세로 격자를 진하게(--mono-200), 그 밖(1주 12시 포함)은 옅게(--mono-100) */
  midnight: boolean;
  /** 눈금 글자. 오른쪽 끝 3% 안 · 1주 12시는 null */
  label: string | null;
  /** center = 눈금 가운데, start = 왼쪽 맞춤(맨 왼쪽 눈금), cell = 1주 날짜 칸 왼쪽 + 6px */
  align: 'center' | 'start' | 'cell';
  tone: 'primary' | 'secondary' | 'tertiary';
  bold: boolean;
}

/** 이웃 눈금 글자 사이 최소 틈(px) */
const TICK_LABEL_GAP_PX = 6;

/**
 * 눈금(기간 시작 ~ 끝, 끝 포함). 12 · 24 · 48시간: 0시는 'MM.DD (요일)' 굵게, 그 밖 'HH:00' 흐리게.
 * 1주: 0시 선 + 12시 옅은 선, 날짜 글자는 칸 왼쪽 — 오늘은 ' 오늘'을 붙여 굵게, 다른 날은 보조 글자.
 * 글자가 앞 글자와 겹치면(예: 48시간이 21:00에 시작 — 왼쪽 맞춤 '21:00'과 3시간 뒤 '11.05 (목)') 0시 글자를 남기고 시각 글자를 뺀다.
 */
export function ticksOf(p: Period, now: number, { trackPx, measure = estimateWidth }: LayoutOptions): Tick[] {
  const step = p.week ? DAY / 2 : p.tick;
  const span = p.to - p.from;
  const ticks: Tick[] = [];
  for (let t = p.from; t <= p.to; t += step) {
    const pct = ((t - p.from) / span) * 100;
    const midnight = (t + KST_OFFSET_MS) % DAY === 0;
    const align: Tick['align'] = p.week ? 'cell' : pct === 0 ? 'start' : 'center';
    let label: string | null;
    let tone: Tick['tone'] = 'tertiary';
    let bold = false;
    if (p.week) {
      const today = kstDay(t) === kstDay(now);
      label = midnight ? `${formatMonthDayWeekday(t)}${today ? ' 오늘' : ''}` : null;
      tone = today ? 'primary' : 'secondary';
      bold = today;
    } else if (midnight) {
      label = formatMonthDayWeekday(t);
      tone = 'primary';
      bold = true;
    } else {
      label = formatClockMinute(t);
    }
    ticks.push({ t, pct, midnight, label: pct > TICK_LABEL_EDGE_PCT ? null : label, align, tone, bold });
  }

  const px = trackPx > 0 ? trackPx : TRACK_MIN_PX;
  let prev: { tick: Tick; right: number } | null = null;
  for (const tick of ticks) {
    if (!tick.label) continue;
    const w = measure([{ text: tick.label, weight: tick.bold ? 600 : 400, tone: 'primary' }]);
    const x = (tick.pct / 100) * px;
    const left = tick.align === 'center' ? x - w / 2 : tick.align === 'start' ? 0 : x + WEEK_LABEL_INSET_PX;
    if (prev && left < prev.right + TICK_LABEL_GAP_PX) {
      if (!tick.midnight || prev.tick.midnight) {
        tick.label = null;
        continue;
      }
      prev.tick.label = null;
    }
    prev = { tick, right: left + w };
  }
  return ticks;
}

// ---------------------------------------------------------------------------
// 막대
// ---------------------------------------------------------------------------

/** 입장 전(호스팅 시작~입장) · 입장(입장~시작) · 진행(시작~종료) · 마무리(종료~호스팅 종료) */
export type SegmentKind = 'beforeEntry' | 'entry' | 'live' | 'wrap';

export const LEGEND: { kind: SegmentKind | 'failed'; label: string }[] = [
  { kind: 'beforeEntry', label: '입장 전 (호스팅 시작~입장)' },
  { kind: 'entry', label: '입장 (입장~시작)' },
  { kind: 'live', label: '진행 (시작~종료)' },
  { kind: 'wrap', label: '마무리 (종료~호스팅 종료)' },
  { kind: 'failed', label: '실패' },
];

/** 이름 한 조각 — 폭 측정 · 그리기 공용 */
export interface NamePart {
  text: string;
  weight: 400 | 600;
  tone: 'primary' | 'secondary' | 'failed';
}

/** 이름 폭(px) 측정. 화면은 canvas measureText, 기본값은 글자 수 추정 */
export type Measure = (parts: NamePart[]) => number;

/** 이름 폭 추정(px) — OPS-05 세로선 이름 추정과 같은 계수(15px 기준 한글 = 글자 크기, 띄어쓰기 4, 그 밖 8) */
export const estimateWidth: Measure = (parts) =>
  parts.reduce((w, p) => w + [...p.text].reduce((s, c) => s + (/[가-힣]/.test(c) ? CAPTION_PX : c === ' ' ? 4 : 8), 0), 0);

export interface Failure {
  /** 호스팅 DEPLOY_FAILED — 속이 빈 막대(호스팅 시작~종료 계획) */
  deploy: boolean;
  /** utils/status.ts 문구 그대로 */
  label: string;
}

/** 실패 표시 — 배포 실패 / 호스팅 준비 실패 · 종료 실패 / 이벤트 준비 실패(호스팅 · 이벤트 둘 다 실패면 호스팅 쪽) */
export function failureOf(e: Pick<LiveEventItem, 'hostingStatus' | 'eventStatus'>): Failure | null {
  const h = e.hostingStatus;
  if (h === 'DEPLOY_FAILED') return { deploy: true, label: hostingStatusMeta[h].label };
  if (h === 'HOSTING_FAILED' || h === 'END_FAILED') return { deploy: false, label: hostingStatusMeta[h].label };
  if (e.eventStatus === 'OPEN_FAILED') return { deploy: false, label: eventStatusMeta.OPEN_FAILED.label };
  return null;
}

/** 서버 시각(+09:00) → epoch ms. 시간대가 빠진 값은 KST로 본다. 없거나 틀리면 null */
function epoch(v: string | null | undefined): number | null {
  if (!v) return null;
  const t = Date.parse(/(Z|[+-]\d{2}:?\d{2})$/i.test(v) ? v : `${v}+09:00`);
  return Number.isNaN(t) ? null : t;
}

interface Times {
  hostingStart: number;
  entry: number;
  start: number;
  end: number;
  hostingEnd: number;
}

/** 종료 · 호스팅 종료가 없으면(테넌트 스키마를 못 읽음) null — 막대를 그리지 않는다(표에는 있다) */
function timesOf(e: LiveEventItem): Times | null {
  const hostingStart = epoch(e.hostingStartAt);
  const start = epoch(e.startAt);
  const end = epoch(e.endAt);
  const hostingEnd = epoch(e.hostingEndAt);
  if (hostingStart == null || start == null || end == null || hostingEnd == null) return null;
  return { hostingStart, entry: epoch(e.entryAt) ?? start, start, end, hostingEnd };
}

/** 툴팁 · aria 단계 시각 — 첫 단계 'MM.DD (요일) HH:mm', 그 뒤는 앞 단계와 날짜가 다를 때만 'MM.DD HH:mm' */
function stepsText(e: LiveEventItem, failure: Failure | null): string {
  const steps: [string, string | null][] = failure?.deploy
    ? [
        ['호스팅 시작(예정)', e.hostingStartAt],
        ['시작(예정)', e.startAt],
      ]
    : [
        ['호스팅 시작', e.hostingStartAt],
        ['입장', e.entryAt],
        ['시작', e.startAt],
        ['종료', e.endAt],
        ['호스팅 종료', e.hostingEndAt],
      ];
  let prevDay = '';
  return steps
    .filter((s): s is [string, string] => !!s[1])
    .map(([label, iso], i) => {
      const day = formatDate(iso);
      const when = i === 0 ? `${formatMonthDayWeekday(iso)} ${formatTime(iso)}` : day !== prevDay ? formatMonthDayTime(iso) : formatTime(iso);
      prevDay = day;
      return `${label} ${when}`;
    })
    .join(' → ');
}

export interface TimelineBar {
  eventId: number;
  name: string;
  tenantName: string;
  /** 막대 위치(기간 안으로 자름) — 트랙 폭 기준 % */
  leftPct: number;
  widthPct: number;
  /** 구간(시간 비율 = grow, 0 이하 구간은 뺌). 배포 실패는 빈 배열(속이 빈 막대) */
  segments: { kind: SegmentKind; grow: number }[];
  /** 배포 실패 — 속이 빈 막대 + 빨간 점선 */
  hollow: boolean;
  /** 실패 — 빨간 점선 테두리 */
  failed: boolean;
  /** 이름 뒤 빨간 상태 문구('배포 실패' 등). 실패가 아니면 null */
  status: string | null;
  /** 호스팅 종료가 지남(실패 포함) — 막대 · 이름 흐리게 */
  past: boolean;
  /** 이름 — 12 · 24 · 48시간은 이벤트명 + 테넌트명, 1주는 이벤트명만. 실패면 뒤에 상태 문구 */
  nameParts: NamePart[];
  /** 툴팁 줄(이름 · 테넌트 / 단계 시각 / 실패 문구) */
  tooltip: string[];
  ariaLabel: string;
}

/** 응답 전체 중 기간에 걸치는 이벤트의 막대(종료 시각이 없으면 뺌). 막대 시작 순(같으면 응답 순) — Tab 순서가 왼쪽 → 오른쪽 */
export function timelineBars(items: LiveEventItem[], p: Period, now: number): TimelineBar[] {
  const span = p.to - p.from;
  const bars: TimelineBar[] = [];
  for (const e of items) {
    const t = timesOf(e);
    if (!t) continue;
    const failure = failureOf(e);
    const barFrom = t.hostingStart;
    const barTo = failure?.deploy ? t.end : t.hostingEnd;
    const from = Math.max(barFrom, p.from);
    const to = Math.min(barTo, p.to);
    if (to <= from) continue;

    let segments: TimelineBar['segments'] = [];
    if (!failure?.deploy) {
      // 경계를 시간 순서로 맞춘다(예: 입장이 호스팅 시작보다 앞이면 입장 전 구간 길이 0 → 뺌, 입장 구간은 호스팅 시작부터)
      const b = [t.hostingStart, t.entry, t.start, t.end, t.hostingEnd];
      for (let i = 1; i < b.length; i++) b[i] = Math.max(b[i], b[i - 1]);
      const kinds: SegmentKind[] = ['beforeEntry', 'entry', 'live', 'wrap'];
      segments = kinds
        .map((kind, i) => ({ kind, grow: Math.min(b[i + 1], p.to) - Math.max(b[i], p.from) }))
        .filter((s) => s.grow > 0);
    }

    const steps = stepsText(e, failure);
    const nameParts: NamePart[] = [{ text: e.name, weight: 600, tone: 'primary' }];
    if (!p.week) nameParts.push({ text: ` ${e.tenantName}`, weight: 400, tone: 'secondary' });
    if (failure) nameParts.push({ text: ` ${failure.label}`, weight: 400, tone: 'failed' });

    bars.push({
      eventId: e.eventId,
      name: e.name,
      tenantName: e.tenantName,
      leftPct: ((from - p.from) / span) * 100,
      widthPct: ((to - from) / span) * 100,
      segments,
      hollow: !!failure?.deploy,
      failed: !!failure,
      status: failure?.label ?? null,
      past: t.hostingEnd < now,
      nameParts,
      tooltip: [`${e.name} · ${e.tenantName}`, steps, ...(failure ? [failure.label] : [])],
      ariaLabel: [`${e.name} 상세 보기`, e.tenantName, steps, ...(failure ? [failure.label] : [])].join(' · '),
    });
  }
  return bars.sort((a, b) => a.leftPct - b.leftPct);
}

// ---------------------------------------------------------------------------
// 줄 배치
// ---------------------------------------------------------------------------

export interface PlacedBar extends TimelineBar {
  /** 줄(0이 맨 위) */
  lane: number;
  /** 최소 폭 때문에 트랙 오른쪽을 넘으면 안쪽으로 민 막대 위치(%) */
  barLeftPct: number;
  /** 이름 왼쪽(%) — 막대 시작에 맞춤. null이면 이름 끝을 트랙 오른쪽 끝에 맞춤 */
  nameLeftPct: number | null;
}

export interface LayoutOptions {
  /** 트랙 폭(px). 0 이하면 TRACK_MIN_PX로 본다 */
  trackPx: number;
  measure?: Measure;
}

/**
 * 줄 배치. 이벤트마다 차지 구간 = [min(막대 시작, 이름 시작), max(막대 끝, 이름 끝)](px, 막대는 최소 6px).
 * 차지 시작 순으로, 앞 줄부터 보며 그 줄의 마지막 차지 끝 + 16px ≤ 시작이면 그 줄, 아니면 새 줄. 최소 1줄.
 * 이름은 막대 시작에 왼쪽 맞춤, 이름 끝이 트랙 오른쪽 끝을 넘으면 이름 끝을 트랙 끝에 맞춘다.
 */
export function layoutLanes(bars: TimelineBar[], { trackPx, measure = estimateWidth }: LayoutOptions): { lanes: number; bars: PlacedBar[] } {
  const px = trackPx > 0 ? trackPx : TRACK_MIN_PX;
  const boxes = bars.map((b, i) => {
    let barStart = (b.leftPct / 100) * px;
    const barW = Math.max((b.widthPct / 100) * px, BAR_MIN_PX);
    if (barStart + barW > px) barStart = Math.max(0, px - barW);
    const nameW = measure(b.nameParts);
    const toEnd = barStart + nameW > px;
    const nameStart = toEnd ? Math.max(0, px - nameW) : barStart;
    return { b, i, barStart, toEnd, start: Math.min(barStart, nameStart), end: Math.max(barStart + barW, nameStart + nameW) };
  });
  const order = [...boxes].sort((a, z) => a.start - z.start || a.i - z.i);
  const laneEnds: number[] = [];
  const placed: PlacedBar[] = new Array(bars.length);
  for (const box of order) {
    let lane = laneEnds.findIndex((end) => end + LANE_SPACE_PX <= box.start);
    if (lane < 0) {
      lane = laneEnds.length;
      laneEnds.push(box.end);
    } else {
      laneEnds[lane] = box.end;
    }
    const barLeftPct = (box.barStart / px) * 100;
    placed[box.i] = { ...box.b, lane, barLeftPct, nameLeftPct: box.toEnd ? null : barLeftPct };
  }
  return { lanes: Math.max(1, laneEnds.length), bars: placed };
}

// ---------------------------------------------------------------------------
// 동시 호스팅 · 지금 · 다음 호스팅
// ---------------------------------------------------------------------------

export interface OverlapBand {
  leftPct: number;
  widthPct: number;
  /** 그 구간의 최대 동시 수 */
  count: number;
  /** '동시 호스팅 N건 · HH:mm–HH:mm'(1주는 '동시 N건') */
  label: string;
  /** 글자 왼쪽(%) — 띠 가운데, 트랙 끝에서 잘리면 안쪽으로 */
  labelLeftPct: number;
}

const clamp = (v: number, lo: number, hi: number) => Math.min(Math.max(v, lo), Math.max(lo, hi));

/**
 * 호스팅 겹침 — 배포 실패를 뺀(막대가 있는) 이벤트의 [호스팅 시작, 호스팅 종료](기간 안으로 자름)가 2개 이상 겹치는 구간.
 * 한 이벤트의 끝과 다른 이벤트의 시작이 같은 시각이면 겹침이 아니다(끝을 먼저 센다).
 */
export function overlapBands(items: LiveEventItem[], p: Period, { trackPx, measure = estimateWidth }: LayoutOptions): OverlapBand[] {
  const px = trackPx > 0 ? trackPx : TRACK_MIN_PX;
  const span = p.to - p.from;
  const points: { t: number; d: 1 | -1 }[] = [];
  for (const e of items) {
    if (e.hostingStatus === 'DEPLOY_FAILED') continue;
    const t = timesOf(e);
    if (!t) continue;
    const a = Math.max(t.hostingStart, p.from);
    const b = Math.min(t.hostingEnd, p.to);
    if (b <= a) continue;
    points.push({ t: a, d: 1 }, { t: b, d: -1 });
  }
  points.sort((x, y) => x.t - y.t || x.d - y.d);

  const raw: { from: number; to: number; count: number }[] = [];
  let cur = 0;
  let open: { from: number; count: number } | null = null;
  for (const pt of points) {
    cur += pt.d;
    if (cur >= 2) {
      if (!open) open = { from: pt.t, count: cur };
      else open.count = Math.max(open.count, cur);
    } else if (open) {
      const last = raw[raw.length - 1];
      // 같은 시각에 하나가 끝나고 다른 하나가 시작해 잠깐 1이 된 곳은 한 띠로 잇는다
      if (last && last.to === open.from) Object.assign(last, { to: pt.t, count: Math.max(last.count, open.count) });
      else if (pt.t > open.from) raw.push({ from: open.from, to: pt.t, count: open.count });
      open = null;
    }
  }

  return raw.map((r) => {
    const label = p.week ? `동시 ${r.count}건` : `동시 호스팅 ${r.count}건 · ${formatClockMinute(r.from)}–${formatClockMinute(r.to)}`;
    const leftPct = ((r.from - p.from) / span) * 100;
    const widthPct = ((r.to - r.from) / span) * 100;
    const w = measure([{ text: label, weight: 600, tone: 'primary' }]);
    const center = ((leftPct + widthPct / 2) / 100) * px;
    return { leftPct, widthPct, count: r.count, label, labelLeftPct: (clamp(center - w / 2, 0, px - w) / px) * 100 };
  });
}

export interface NowMarker {
  pct: number;
  /** '지금 HH:mm' */
  label: string;
  /** 칩 왼쪽(%) — 세로선 가운데, 트랙 끝에서 잘리면 안쪽으로 */
  chipLeftPct: number;
}

export function nowMarker(p: Period, now: number, { trackPx, measure = estimateWidth }: LayoutOptions): NowMarker {
  const px = trackPx > 0 ? trackPx : TRACK_MIN_PX;
  const pct = clamp(((now - p.from) / (p.to - p.from)) * 100, 0, 100);
  const label = `지금 ${formatClockMinute(now)}`;
  const w = measure([{ text: label, weight: 600, tone: 'primary' }]) + NOW_CHIP_PAD_X * 2;
  return { pct, label, chipLeftPct: (clamp((pct / 100) * px - w / 2, 0, px - w) / px) * 100 };
}

/**
 * 머리 "다음 호스팅" — 응답 전체에서 호스팅 시작이 지금 이후인 이벤트 중 가장 이른 것(배포 실패 제외, 기간 · 종료 시각과 무관).
 * '· 다음 호스팅 MM.DD HH:mm {이벤트명}', 없으면 '· 다음 예정 없음'
 */
export function nextHostingText(items: LiveEventItem[], now: number): string {
  let next: { e: LiveEventItem; t: number } | null = null;
  for (const e of items) {
    if (e.hostingStatus === 'DEPLOY_FAILED') continue;
    const t = epoch(e.hostingStartAt);
    if (t == null || t <= now) continue;
    if (!next || t < next.t) next = { e, t };
  }
  return next ? `· 다음 호스팅 ${formatMonthDayTime(next.e.hostingStartAt)} ${next.e.name}` : '· 다음 예정 없음';
}

// ---------------------------------------------------------------------------
// 트랙 전체
// ---------------------------------------------------------------------------

export interface TrackView {
  ticks: Tick[];
  bars: PlacedBar[];
  lanes: number;
  /** 트랙 높이(px) = 위 30 + 줄 수 × 한 줄 + 아래 26 */
  height: number;
  overlaps: OverlapBand[];
  now: NowMarker;
}

/** 줄 i의 이름 · 막대 top(px) */
export const laneNameTop = (lane: number) => TRACK_TOP_PX + lane * LANE_PX;
export const laneBarTop = (lane: number) => laneNameTop(lane) + NAME_LINE_PX + NAME_BAR_GAP_PX;

/** items가 없으면(첫 응답 전 · 빈 기간) 격자 · 지금 선만 — 최소 1줄 높이 */
export function trackView(items: LiveEventItem[] | undefined, p: Period, now: number, options: LayoutOptions): TrackView {
  const list = items ?? [];
  const { lanes, bars } = layoutLanes(timelineBars(list, p, now), options);
  return {
    ticks: ticksOf(p, now, options),
    bars,
    lanes,
    height: TRACK_TOP_PX + lanes * LANE_PX + TRACK_BOTTOM_PX,
    overlaps: overlapBands(list, p, options),
    now: nowMarker(p, now, options),
  };
}
