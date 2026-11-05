import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import type { HostingStatus, LiveEventItem } from '../../api/types.ts';
import {
  LANE_PX,
  layoutLanes,
  nextHostingText,
  nowMarker,
  overlapBands,
  periodOf,
  periodText,
  ticksOf,
  timelineBars,
  trackView,
  TRACK_BOTTOM_PX,
  TRACK_TOP_PX,
  type Measure,
  type Period,
  type TimelineBar,
} from './timelineView.ts';

const MIN = 60_000;
const HOUR = 60 * MIN;
const at = (s: string) => Date.parse(`2026-${s}:00+09:00`);
/** epoch → '+09:00' ISO(서버 응답 모양) */
const iso = (t: number) => new Date(t + 9 * HOUR).toISOString().replace(/\.\d{3}Z$/, '+09:00');

/** 2026-11-04(수) 09:25 KST */
const NOW = at('11-04T09:25');

/** 운영 리드 이벤트: 호스팅 = 시작 −100분, 입장 = 시작 −60분, 종료 = 시작 + live분, 호스팅 종료 = 종료 + 30분 */
function ev(eventId: number, start: string, over: Partial<LiveEventItem> & { live?: number } = {}): LiveEventItem {
  const { live = 40, ...rest } = over;
  const s = at(start);
  return {
    eventId,
    tenantName: 'A마트',
    eventType: 'COUPON',
    name: `이벤트${eventId}`,
    hostingStartAt: iso(s - 100 * MIN),
    entryAt: iso(s - 60 * MIN),
    startAt: iso(s),
    endAt: iso(s + live * MIN),
    hostingEndAt: iso(s + (live + 30) * MIN),
    hostingStatus: 'PENDING',
    eventStatus: 'SCHEDULED',
    trafficPlanCode: 'M',
    ...rest,
  };
}

const P12 = periodOf('12h', NOW);
/** 트랙 900px · 기본 추정 폭 */
const OPT = { trackPx: 900 };
const grows = (b: TimelineBar) => b.segments.map((s) => [s.kind, s.grow / MIN]);

/** 글자별 폭 표 — 표에 없으면 0 */
const widths =
  (w: Record<string, number>): Measure =>
  (parts) =>
    parts.reduce((s, p) => s + (w[p.text] ?? 0), 0);

/** 줄 배치용 최소 막대 */
const bar = (eventId: number, leftPct: number, widthPct: number, name = ''): TimelineBar => ({
  eventId,
  name,
  tenantName: '',
  leftPct,
  widthPct,
  segments: [],
  hollow: false,
  failed: false,
  status: null,
  past: false,
  nameParts: [{ text: name, weight: 600, tone: 'primary' }],
  tooltip: [],
  ariaLabel: '',
});

describe('기간', () => {
  test('4종 시작 · 끝 — 지금 −3h(1h 내림) · −6h(2h) · −12h(3h) · 2일 전 0시', () => {
    assert.deepEqual([P12.from, P12.to], [at('11-04T06:00'), at('11-04T18:00')]);
    const p24 = periodOf('24h', NOW);
    assert.deepEqual([p24.from, p24.to], [at('11-04T02:00'), at('11-05T02:00')]);
    const p48 = periodOf('48h', NOW);
    assert.deepEqual([p48.from, p48.to], [at('11-03T21:00'), at('11-05T21:00')]);
    const w = periodOf('1w', NOW);
    assert.deepEqual([w.from, w.to, w.week], [at('11-02T00:00'), at('11-09T00:00'), true]);
  });

  test('자정 경계 — 0시 넘어 앞날로, 1주는 KST 날짜 기준(UTC 날짜 아님)', () => {
    const p12 = periodOf('12h', at('11-05T01:30'));
    assert.deepEqual([p12.from, p12.to], [at('11-04T22:00'), at('11-05T10:00')]);
    assert.equal(periodOf('24h', at('11-05T00:10')).from, at('11-04T18:00'));
    assert.equal(periodOf('1w', at('11-05T00:00')).from, at('11-03T00:00'));
    // KST 08:30 = UTC 전날 23:30 — 그래도 KST 11-04의 2일 전
    assert.equal(periodOf('1w', at('11-04T08:30')).from, at('11-02T00:00'));
  });

  test('기간 문구 — 같은 날 · 날짜 바뀜 · 1주(월 경계 포함)', () => {
    assert.equal(periodText(P12), '11.04 (수) 06:00 – 18:00');
    assert.equal(periodText(periodOf('48h', NOW)), '11.03 (화) 21:00 – 11.05 (목) 21:00');
    assert.equal(periodText(periodOf('12h', at('11-05T01:30'))), '11.04 (수) 22:00 – 11.05 (목) 10:00');
    assert.equal(periodText(periodOf('1w', NOW)), '11.02 (월) – 11.08 (일)');
    assert.equal(periodText(periodOf('1w', at('11-01T10:00'))), '10.30 (금) – 11.05 (목)');
  });
});

describe('눈금', () => {
  test('12시간 — 1시간마다, 맨 왼쪽은 왼쪽 맞춤, 끝(100%) 글자 생략', () => {
    const ticks = ticksOf(P12, NOW, OPT);
    assert.equal(ticks.length, 13);
    assert.deepEqual([ticks[0].label, ticks[0].align, ticks[0].tone], ['06:00', 'start', 'tertiary']);
    assert.deepEqual([ticks[1].label, ticks[1].align, ticks[1].pct.toFixed(3)], ['07:00', 'center', '8.333']);
    assert.equal(ticks[12].label, null);
    assert.ok(ticks.every((t) => !t.midnight));
  });

  test('0시 눈금 — MM.DD (요일) 굵게 · 본문 색, 격자 진하게', () => {
    const midnight = ticksOf(periodOf('12h', at('11-05T01:30')), at('11-05T01:30'), OPT).find((t) => t.t === at('11-05T00:00'));
    assert.deepEqual(midnight && [midnight.label, midnight.bold, midnight.tone, midnight.midnight], ['11.05 (목)', true, 'primary', true]);
  });

  test('오른쪽 끝 3% 안 눈금은 글자 생략', () => {
    const p: Period = { key: '12h', from: at('11-04T01:00'), to: at('11-04T11:15'), tick: HOUR, week: false };
    const ticks = ticksOf(p, NOW, OPT);
    assert.equal(ticks.at(-1)?.pct.toFixed(2), '97.56');
    assert.equal(ticks.at(-1)?.label, null);
    assert.equal(ticks.at(-2)?.label, '10:00');
  });

  test('앞 글자와 겹치면 0시 글자를 남기고 시각 글자를 뺌 — 48시간이 21:00에 시작', () => {
    const now = at('11-05T10:25');
    // 900px: 3시간 = 56px인데 '11.05 (목)'은 약 75px → 앞 '21:00'(왼쪽 맞춤)과 뒤 '03:00'이 빠짐
    const ticks = ticksOf(periodOf('48h', now), now, OPT);
    assert.deepEqual(ticks.slice(0, 4).map((t) => t.label), [null, '11.05 (목)', null, '06:00']);
    // 넓은 트랙이면 둘 다 남김
    assert.deepEqual(ticksOf(periodOf('48h', now), now, { trackPx: 2400 }).slice(0, 2).map((t) => t.label), ['21:00', '11.05 (목)']);
  });

  test("1주 — 0시 날짜(칸 왼쪽) · 12시 옅은 선, 오늘은 ' 오늘' 굵게, 다른 날은 보조 글자", () => {
    const ticks = ticksOf(periodOf('1w', NOW), NOW, OPT);
    assert.equal(ticks.length, 15);
    const days = ticks.filter((t) => t.label);
    assert.deepEqual(
      days.map((t) => t.label),
      ['11.02 (월)', '11.03 (화)', '11.04 (수) 오늘', '11.05 (목)', '11.06 (금)', '11.07 (토)', '11.08 (일)'],
    );
    assert.deepEqual([days[2].bold, days[2].tone, days[0].bold, days[0].tone, days[0].align], [true, 'primary', false, 'secondary', 'cell']);
    assert.deepEqual([ticks[1].midnight, ticks[1].label], [false, null]);
    assert.equal(ticks[14].label, null);
  });
});

describe('막대 구간', () => {
  test('기간 안 — 4구간 시간 비율(입장 전 40 · 입장 60 · 진행 40 · 마무리 30분), 위치 %', () => {
    const [b] = timelineBars([ev(1, '11-04T12:00')], P12, NOW);
    assert.deepEqual(grows(b), [['beforeEntry', 40], ['entry', 60], ['live', 40], ['wrap', 30]]);
    assert.equal(b.leftPct.toFixed(3), '36.111'); // 10:20
    assert.equal(b.widthPct.toFixed(3), '23.611'); // 170분
    assert.deepEqual(b.nameParts.map((p) => p.text), ['이벤트1', ' A마트']);
  });

  test('왼쪽 걸침 — 기간 시작에서 자름', () => {
    const p = periodOf('12h', at('11-04T14:55')); // 11:00 ~ 23:00
    const [b] = timelineBars([ev(1, '11-04T12:30')], p, NOW); // 호스팅 10:50
    assert.equal(b.leftPct, 0);
    assert.deepEqual(grows(b), [['beforeEntry', 30], ['entry', 60], ['live', 40], ['wrap', 30]]);
  });

  test('오른쪽 걸침 — 기간 끝에서 자르고, 기간 밖 구간은 뺌', () => {
    const [b] = timelineBars([ev(1, '11-04T17:30')], P12, NOW); // 진행 17:30 ~ 18:10
    assert.deepEqual(grows(b), [['beforeEntry', 40], ['entry', 60], ['live', 30]]);
    assert.equal((b.leftPct + b.widthPct).toFixed(6), '100.000000');
  });

  test('길이 0 이하 구간은 뺌 — 입장이 호스팅 시작보다 앞 · 입장 = 시작', () => {
    const s = at('11-04T12:00');
    const early = ev(1, '11-04T12:00', { hostingStartAt: iso(s - 25 * MIN), entryAt: iso(s - 60 * MIN) });
    const same = ev(2, '11-04T12:00', { entryAt: iso(s) });
    const [b, a] = timelineBars([early, same], P12, NOW); // 막대 시작 순: same(10:20) → early(11:35)
    assert.deepEqual([a.eventId, b.eventId], [1, 2]);
    assert.deepEqual(grows(a), [['entry', 25], ['live', 40], ['wrap', 30]]);
    assert.deepEqual(grows(b), [['beforeEntry', 100], ['live', 40], ['wrap', 30]]);
  });

  test('막대 순서 = 막대 시작 순(Tab 순서) — 응답은 시작 내림차순', () => {
    const bars = timelineBars([ev(3, '11-04T15:00'), ev(2, '11-04T12:00'), ev(1, '11-04T10:00')], P12, NOW);
    assert.deepEqual(bars.map((b) => b.eventId), [1, 2, 3]);
  });

  test('종료 · 호스팅 종료가 null이면 막대 없음', () => {
    const bars = timelineBars([ev(1, '11-04T12:00', { endAt: null }), ev(2, '11-04T12:00', { hostingEndAt: null })], P12, NOW);
    assert.deepEqual(bars, []);
  });

  test('기간에 걸치지 않으면 뺌(호스팅 종료 = 기간 시작도 뺌)', () => {
    const touching = ev(1, '11-04T05:00', { live: 30 }); // 호스팅 종료 06:00
    assert.deepEqual(timelineBars([touching, ev(2, '11-05T12:00')], P12, NOW), []);
  });

  test('1주 이름은 이벤트명만', () => {
    const [b] = timelineBars([ev(1, '11-04T12:00')], periodOf('1w', NOW), NOW);
    assert.deepEqual(b.nameParts.map((p) => [p.text, p.weight, p.tone]), [['이벤트1', 600, 'primary']]);
  });
});

describe('실패 · 지난 이벤트', () => {
  test('배포 실패 — 속이 빈 막대(호스팅 시작~종료 계획) · 이름 뒤 빨간 문구 · 툴팁 (예정)', () => {
    const [b] = timelineBars([ev(1, '11-04T12:00', { hostingStatus: 'DEPLOY_FAILED' })], P12, NOW);
    assert.deepEqual([b.hollow, b.failed, b.status, b.segments], [true, true, '배포 실패', []]);
    assert.equal(b.widthPct.toFixed(3), '19.444'); // 10:20 ~ 12:40 = 140분
    assert.deepEqual(b.nameParts.at(-1), { text: ' 배포 실패', weight: 400, tone: 'failed' });
    assert.deepEqual(b.tooltip, ['이벤트1 · A마트', '호스팅 시작(예정) 11.04 (수) 10:20 → 시작(예정) 12:00', '배포 실패']);
  });

  test('다른 실패 — 4구간 그대로 + 점선, 문구는 status.ts 그대로(둘 다면 호스팅 쪽)', () => {
    const cases: [HostingStatus, LiveEventItem['eventStatus'], string][] = [
      ['HOSTING_FAILED', 'SCHEDULED', '호스팅 준비 실패'],
      ['END_FAILED', 'OPEN', '종료 실패'],
      ['HOSTING', 'OPEN_FAILED', '이벤트 준비 실패'],
      ['HOSTING_FAILED', 'OPEN_FAILED', '호스팅 준비 실패'],
    ];
    for (const [hostingStatus, eventStatus, label] of cases) {
      const [b] = timelineBars([ev(1, '11-04T12:00', { hostingStatus, eventStatus })], P12, NOW);
      assert.deepEqual([b.hollow, b.failed, b.status, b.segments.length, b.nameParts.at(-1)?.text], [false, true, label, 4, ` ${label}`], hostingStatus);
      assert.equal(b.tooltip[2], label);
    }
  });

  test('aria-label — 이벤트명 상세 보기 · 테넌트 · 단계 시각 (· 실패 문구)', () => {
    const [ok, failed] = timelineBars([ev(1, '11-04T12:00'), ev(2, '11-04T12:00', { hostingStatus: 'HOSTING_FAILED' })], P12, NOW);
    assert.equal(ok.ariaLabel, '이벤트1 상세 보기 · A마트 · 호스팅 시작 11.04 (수) 10:20 → 입장 11:00 → 시작 12:00 → 종료 12:40 → 호스팅 종료 13:10');
    assert.ok(failed.ariaLabel.endsWith('호스팅 종료 13:10 · 호스팅 준비 실패'));
  });

  test('툴팁 — 앞 단계와 날짜가 바뀐 단계만 MM.DD 붙임', () => {
    const now = at('11-04T20:00');
    const [b] = timelineBars([ev(1, '11-05T00:20')], periodOf('12h', now), now);
    assert.equal(b.tooltip[1], '호스팅 시작 11.04 (수) 22:40 → 입장 23:20 → 시작 11.05 00:20 → 종료 01:00 → 호스팅 종료 01:30');
  });

  test('지난 이벤트 = 호스팅 종료 < 지금(실패 포함, 같으면 아님)', () => {
    const [past, failedPast, edge] = timelineBars(
      [ev(1, '11-04T08:00', { live: 30 }), ev(2, '11-04T08:00', { live: 30, hostingStatus: 'END_FAILED' }), ev(3, '11-04T08:25', { live: 30 })],
      P12,
      NOW,
    );
    assert.deepEqual([past.past, failedPast.past, edge.past], [true, true, false]); // 3번 호스팅 종료 = 09:25 = 지금
  });
});

describe('줄 배치', () => {
  test('겹치면 새 줄, 안 겹치면 앞 줄 — 최소 1줄', () => {
    const m = widths({ A: 100, B: 100, C: 100 });
    const { lanes, bars } = layoutLanes([bar(1, 0, 5, 'A'), bar(2, 5, 5, 'B'), bar(3, 50, 5, 'C')], { trackPx: 1000, measure: m });
    assert.equal(lanes, 2);
    assert.deepEqual(bars.map((b) => b.lane), [0, 1, 0]);
    assert.equal(layoutLanes([], { trackPx: 1000 }).lanes, 1);
  });

  test('16px 경계 — 앞 끝 + 16 = 시작이면 같은 줄, 1px 모자라면 새 줄', () => {
    const m = widths({ A: 100 });
    // A: 막대 0 ~ 6px, 이름 0 ~ 100px → 차지 끝 100. B는 25% 위치
    assert.equal(layoutLanes([bar(1, 0, 0, 'A'), bar(2, 25, 0)], { trackPx: 464, measure: m }).lanes, 1); // 116
    assert.equal(layoutLanes([bar(1, 0, 0, 'A'), bar(2, 25, 0)], { trackPx: 460, measure: m }).lanes, 2); // 115
  });

  test('막대 최소 6px — 짧은 막대도 6px 차지', () => {
    const m = widths({});
    assert.equal(layoutLanes([bar(1, 0, 0.1), bar(2, 25, 0)], { trackPx: 88, measure: m }).lanes, 1); // 6 + 16 = 22
    assert.equal(layoutLanes([bar(1, 0, 0.1), bar(2, 25, 0)], { trackPx: 84, measure: m }).lanes, 2); // 21
  });

  test('이름 끝이 트랙 끝을 넘으면 이름 끝을 트랙 끝에 맞춤(차지 시작도 이름 시작)', () => {
    const m = widths({ B: 120, C: 50 });
    const { lanes, bars } = layoutLanes([bar(1, 95, 1, 'B'), bar(2, 80, 5, 'C')], { trackPx: 1000, measure: m });
    assert.deepEqual([bars[0].nameLeftPct, bars[1].nameLeftPct], [null, 80]);
    assert.equal(lanes, 1); // C 끝 850 + 16 ≤ B 이름 시작 880
  });
});

describe('동시 호스팅', () => {
  test('2건 — 띠 + "동시 호스팅 2건 · HH:mm–HH:mm"', () => {
    const bands = overlapBands([ev(1, '11-04T12:00'), ev(2, '11-04T13:00', { live: 30 })], P12, { trackPx: 900 });
    assert.equal(bands.length, 1);
    assert.deepEqual([bands[0].count, bands[0].label], [2, '동시 호스팅 2건 · 11:20–13:10']);
    assert.equal(bands[0].leftPct.toFixed(3), ((320 / 720) * 100).toFixed(3)); // 06:00 → 11:20
  });

  test('3건 — 한 띠, N = 최대 동시 수 · 1주는 "동시 N건"', () => {
    const items = [ev(1, '11-04T12:00'), ev(2, '11-04T13:00', { live: 30 }), ev(3, '11-04T13:20', { live: 30 })];
    const [band] = overlapBands(items, P12, { trackPx: 900 });
    assert.deepEqual([band.count, band.label], [3, '동시 호스팅 3건 · 11:20–14:00']);
    assert.equal(overlapBands(items, periodOf('1w', NOW), { trackPx: 900 })[0].label, '동시 3건');
  });

  test('끝 = 시작은 겹침 아님 · 배포 실패 · 종료 시각 없음은 빼고 셈', () => {
    const a = ev(1, '11-04T12:00'); // 호스팅 10:20 ~ 13:10
    const touch = ev(2, '11-04T14:50'); // 호스팅 13:10 ~
    assert.deepEqual(overlapBands([a, touch], P12, { trackPx: 900 }), []);
    assert.deepEqual(overlapBands([a, ev(3, '11-04T13:00', { hostingStatus: 'DEPLOY_FAILED' })], P12, { trackPx: 900 }), []);
    assert.deepEqual(overlapBands([a, ev(4, '11-04T13:00', { hostingEndAt: null, endAt: null })], P12, { trackPx: 900 }), []);
  });
});

describe('다음 호스팅 · 지금 · 빈 기간', () => {
  test('호스팅 시작이 지금 이후 중 가장 이른 것 — 배포 실패 · 지난 것 제외, 기간 · 종료 시각과 무관', () => {
    const items = [
      ev(1, '11-04T09:00'), // 호스팅 07:20 — 지남
      ev(2, '11-04T11:40', { hostingStatus: 'DEPLOY_FAILED' }), // 10:00 — 배포 실패
      ev(3, '11-04T12:10', { endAt: null, hostingEndAt: null, name: '겨울 쿠폰' }), // 10:30
      ev(4, '11-06T12:00'),
    ];
    assert.equal(nextHostingText(items, NOW), '· 다음 호스팅 11.04 10:30 겨울 쿠폰');
    assert.equal(nextHostingText([ev(5, '11-12T12:00', { name: '먼 이벤트' })], NOW), '· 다음 호스팅 11.12 10:20 먼 이벤트');
    assert.equal(nextHostingText(items.slice(0, 2), NOW), '· 다음 예정 없음');
    assert.equal(nextHostingText([], NOW), '· 다음 예정 없음');
  });

  test('빈 기간 · 첫 응답 전 — 막대 · 띠 없이 격자 · 지금 선, 1줄 높이', () => {
    for (const items of [[ev(1, '11-06T12:00')], undefined]) {
      const v = trackView(items, P12, NOW, { trackPx: 900 });
      assert.deepEqual([v.bars, v.overlaps, v.lanes, v.ticks.length], [[], [], 1, 13]);
      assert.equal(v.height, TRACK_TOP_PX + LANE_PX + TRACK_BOTTOM_PX);
      assert.deepEqual([v.now.pct.toFixed(2), v.now.label], ['28.47', '지금 09:25']);
    }
  });

  test('지금 칩 — 세로선 가운데, 트랙 끝에서 잘리면 안쪽으로', () => {
    const m: Measure = () => 88; // + 안쪽 여백 12 = 100px
    assert.equal(nowMarker(P12, NOW, { trackPx: 1000, measure: m }).chipLeftPct.toFixed(2), (((0.2847222 * 1000 - 50) / 1000) * 100).toFixed(2));
    assert.equal(nowMarker(P12, P12.from, { trackPx: 1000, measure: m }).chipLeftPct, 0);
    assert.equal(nowMarker(P12, P12.to, { trackPx: 1000, measure: m }).chipLeftPct, 90);
  });

  test('한 줄 높이 = 이름 줄 22.5 + 3 + 막대 18 + 11', () => {
    assert.equal(LANE_PX, 54.5);
  });
});
