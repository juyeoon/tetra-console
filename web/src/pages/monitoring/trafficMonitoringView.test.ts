import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import type { MonitoringSource, TrafficCharts, TrafficEventItem, TrafficEventResponse, TrafficEventsResponse, TrafficKpi, TrafficPlatformResponse, TrafficStep } from '../../api/types.ts';
import {
  addonsView,
  ADMITTED_NOTE,
  barPercent,
  CHART_ROWS,
  bytesText,
  chartRefreshText,
  chartsMissingText,
  chartViews,
  clockText,
  countText,
  dayLabel,
  durationText,
  EMPTY,
  endedKpiTiles,
  ERROR_RATIO_DANGER,
  ERROR_RATIO_WARN,
  ERROR_RULE_TEXT,
  eventBody,
  eventGrafanaLinks,
  eventOptions,
  eventStatusText,
  failureRows,
  GRAFANA_PORT_FORWARD_HINT,
  grafanaBase,
  grafanaHint,
  headerView,
  isClosedPhase,
  kstEpochMs,
  liveKpiGroups,
  MARKER_MAX_LEVELS,
  markerLabels,
  mergeOnTime,
  nodeTooltip,
  OPEN_GROUP_TEXT,
  opsText,
  parseEventId,
  percentText,
  PHASE_META,
  platformGrafanaLink,
  poolViews,
  rangeButtons,
  rdsText,
  requestShareText,
  rpsText,
  sectionStatus,
  selectedEventId,
  serviceTexts,
  stepViews,
  stopPollingWhen,
  summaryCells,
  timeTicks,
  tooltipValue,
  upcomingViews,
  usageTone,
  X_TICK_MIN_PX,
  Y_AXIS_WIDTH,
  yMaxFor,
} from './trafficMonitoringView.ts';

const NOW = new Date('2026-10-26T15:10:00+09:00');

const platform = (patch: Partial<TrafficPlatformResponse> = {}): TrafficPlatformResponse => ({
  prometheus: { asOf: '2026-10-26T15:12:14+09:00', stale: false, staleReason: null },
  aws: { asOf: '2026-10-26T15:12:00+09:00', stale: false, staleReason: null },
  grafanaUrl: 'http://localhost:13000',
  summary: [
    { area: 'CLUSTER', level: 'OK', reason: '노드 16 Ready', openCount: null, eventId: null },
    { area: 'EVENT', level: 'WARN', reason: '7번 5xx 0.8%', openCount: 3, eventId: 7 },
    { area: 'AUTOMATION', level: 'DANGER', reason: 'DLQ tetra-dev-scheduler-dlq', openCount: null, eventId: null },
    { area: 'PLATFORM', level: 'UNKNOWN', reason: '데이터 없음', openCount: null, eventId: null },
  ],
  nodes: { cpu: 0.46, memory: 0.51, history: { stepSeconds: 60, points: [[1000, 0.41, null]] }, pools: [] },
  platform: {
    addons: { healthy: 6, total: 6, groups: [{ name: 'KEDA', healthy: true, problems: [] }, { name: 'Prometheus', healthy: true, problems: [] }] },
    rds: { status: 'available', cpuPercent: 48.04, connections: 36, freeStorageBytes: 18 * 1024 ** 3 },
    services: [{ name: 'portal', running: 1, desired: 1, healthyTargets: 1, totalTargets: 1, fiveXx: 0 }],
  },
  upcoming: [],
  failures: [],
  ...patch,
});

const item = (patch: Partial<TrafficEventItem>): TrafficEventItem => ({
  eventId: 7,
  tenantId: 'aaaaaaaaaaaa',
  tenantName: 'A사',
  name: '쿠폰 이벤트',
  group: 'OPEN',
  phase: 'LIVE',
  level: 'OK',
  reason: '정상',
  prepareAt: '2026-10-26T13:30:00+09:00',
  entryAt: '2026-10-26T14:00:00+09:00',
  startAt: '2026-10-26T15:00:00+09:00',
  endAt: '2026-10-26T15:40:00+09:00',
  eventStatus: 'OPEN',
  hostingStatus: 'HOSTING',
  failedAt: null,
  ...patch,
});

const eventResponse = (phase: TrafficEventResponse['event']['phase'], patch: Partial<TrafficEventResponse> = {}): TrafficEventResponse => ({
  prometheus: { asOf: '2026-10-26T15:10:00+09:00', stale: false, staleReason: null },
  aws: { asOf: '2026-10-26T15:10:00+09:00', stale: false, staleReason: null },
  event: {
    eventId: 7,
    tenantId: 'aaaaaaaaaaaa',
    tenantName: 'A사',
    name: '쿠폰 이벤트',
    phase,
    eventStatus: 'OPEN',
    hostingStatus: 'HOSTING',
    prepareAt: '2026-10-26T13:30:00+09:00',
    entryAt: '2026-10-26T14:00:00+09:00',
    startAt: '2026-10-26T15:00:00+09:00',
    endAt: '2026-10-26T15:40:00+09:00',
    eventEndAt: '2026-10-26T15:30:00+09:00',
    stockCount: 5000,
  },
  steps: [],
  kpi: null,
  range: null,
  charts: null,
  ...patch,
});

describe('숫자 표기: null은 –, 0은 0', () => {
  test('null · undefined → –', () => {
    for (const f of [countText, rpsText, bytesText, opsText]) assert.equal(f(null), EMPTY);
    assert.equal(percentText(undefined), EMPTY);
    assert.equal(durationText(null), undefined);
  });
  test('0은 0으로', () => {
    assert.equal(countText(0), '0');
    assert.equal(rpsText(0), '0');
    assert.equal(percentText(0), '0%');
    assert.equal(bytesText(0), '0 GB');
    assert.equal(opsText(0), '0 ops/s');
  });
  test('값', () => {
    assert.equal(countText(2400), '2,400');
    assert.equal(rpsText(1131.24), '1,131');
    assert.equal(rpsText(12.34), '12.3');
    assert.equal(percentText(0.008), '0.8%');
    assert.equal(percentText(0.46, 0), '46%');
    assert.equal(bytesText(4.1 * 1024 ** 3), '4.1 GB');
    assert.equal(bytesText(570 * 1024 ** 2), '570 MB');
    assert.equal(opsText(42_000), '42k ops/s');
    assert.equal(opsText(1500), '1.5k ops/s');
    assert.equal(durationText(200), '3분 20초');
    assert.equal(durationText(45), '45초');
    assert.equal(requestShareText(3.2, 4), '요청량의 80%');
    assert.equal(requestShareText(3.2, null), undefined);
  });
});

describe('상단 · 상태 요약', () => {
  test('prometheus.stale 기준 "데이터 지연됨"', () => {
    assert.deepEqual(headerView(platform(), false), { delayed: false, text: '자동 갱신 · 15초 · 마지막 15:12:14' });
    assert.deepEqual(headerView(platform({ prometheus: { asOf: '2026-10-26T15:11:44+09:00', stale: true } }), false), {
      delayed: true,
      text: '데이터 지연됨 · 마지막 정상 15:11:44',
    });
    // AWS만 지연이면 상단은 정상
    assert.equal(headerView(platform({ aws: { asOf: null, stale: true } }), false).delayed, false);
    assert.equal(headerView(platform(), true).delayed, true);
    assert.equal(headerView(undefined, false).text, '데이터 지연됨 · 마지막 정상 –');
  });
  test('4칸: 배경 톤 · UNKNOWN은 회색 확인 불가 · 이벤트 칸 진행 중 수', () => {
    const cells = summaryCells(platform());
    assert.deepEqual(
      cells.map((c) => [c.title, c.level, c.tone, c.reason]),
      [
        ['클러스터', '정상', 'green', '노드 16 Ready'],
        ['이벤트 (진행 중 3)', '주의', 'amber', '7번 5xx 0.8%'],
        ['자동화', '위험', 'red', 'DLQ tetra-dev-scheduler-dlq'],
        ['플랫폼', '확인 불가', 'grey', '데이터 없음'],
      ],
    );
    assert.deepEqual(
      summaryCells(undefined).map((c) => [c.title, c.level, c.reason]),
      [
        ['클러스터', '확인 불가', EMPTY],
        ['이벤트 (진행 중 0)', '확인 불가', EMPTY],
        ['자동화', '확인 불가', EMPTY],
        ['플랫폼', '확인 불가', EMPTY],
      ],
    );
  });
});

describe('노드 막대 색 경계: 70~80% 주황, 80% 이상 빨강', () => {
  test('usageTone', () => {
    assert.equal(usageTone(0.69), 'normal');
    assert.equal(usageTone(0.7), 'warn');
    assert.equal(usageTone(0.799), 'warn');
    assert.equal(usageTone(0.8), 'danger');
    assert.equal(usageTone(1), 'danger');
    assert.equal(usageTone(null), 'normal');
  });
  test('막대 높이 0~100, 값 없으면 0', () => {
    assert.equal(barPercent(0.46), 46);
    assert.equal(barPercent(1.2), 100);
    assert.equal(barPercent(null), 0);
  });
  test('툴팁: 타입이 null이면 줄을 뺀다', () => {
    const node = { name: 'ip-10-0-1-23', type: null, ready: true, cpu: 0.62, memory: 0.41, createdAt: '2026-10-26T15:05:00+09:00', recent: true };
    assert.deepEqual(nodeTooltip(node), ['ip-10-0-1-23', 'CPU 62% · 메모리 41%', '뜬 시각 10.26 15:05 (최근 10분)']);
    assert.deepEqual(nodeTooltip({ ...node, type: 'c7i.xlarge', recent: false, ready: false }), [
      'ip-10-0-1-23',
      'c7i.xlarge',
      'CPU 62% · 메모리 41%',
      '뜬 시각 10.26 15:05',
      'Ready 아님',
    ]);
  });
  test('풀 요약: 개수 null은 –, 최근 추가, 최고값 색', () => {
    const [app, redis] = poolViews([
      {
        pool: 'app',
        count: 12,
        addedRecently: 3,
        cpu: { avg: 0.55, max: 0.83 },
        memory: { avg: 0.49, max: 0.7 },
        nodes: [{ name: 'n1', type: null, ready: true, cpu: 0.83, memory: 0.7, createdAt: null, recent: true }],
      },
      { pool: 'redis', count: null, addedRecently: 0, cpu: { avg: null, max: null }, memory: { avg: null, max: null }, nodes: [] },
    ]);
    assert.deepEqual([app.label, app.sub, app.count, app.added, app.cpuMax, app.cpuMaxTone, app.memoryMaxTone], ['app', 'Karpenter', '12대', '+3 (10분)', '83%', 'danger', 'warn']);
    assert.deepEqual([app.nodes[0].cpuTone, app.nodes[0].memoryTone, app.nodes[0].recent], ['danger', 'warn', true]);
    assert.deepEqual([redis.count, redis.added, redis.cpuAvg], [EMPTY, null, EMPTY]);
  });
});

describe('플랫폼 · 다가오는 이벤트 · 실패', () => {
  test('애드온: 평소 정상 n/n, 문제만 이름 + Ready 수', () => {
    assert.equal(addonsView(platform()).summary, '정상 6/6');
    const broken = platform();
    broken.platform.addons = {
      healthy: 4,
      total: 6,
      groups: [
        { name: 'KEDA', healthy: false, problems: [{ namespace: 'keda', name: 'keda-operator', ready: 0, desired: 1 }] },
        { name: 'Prometheus', healthy: false, problems: [] },
      ],
    };
    assert.deepEqual(addonsView(broken), { summary: '문제 2 · 정상 4/6', ok: false, names: 'KEDA · Prometheus', problems: ['KEDA keda-operator 0/1 Ready', 'Prometheus 응답 없음'] });
    broken.platform.addons.healthy = null;
    assert.equal(addonsView(broken).summary, '확인 불가');
  });
  test('RDS · Portal/Console 한 줄', () => {
    assert.equal(rdsText(platform()), 'available · CPU 48% · 연결 36 · 남은 저장 공간 18.0 GB');
    const noRds = platform();
    noRds.platform.rds = null;
    assert.equal(rdsText(noRds), EMPTY);
    assert.deepEqual(serviceTexts(platform().platform.services), [{ key: 'portal', text: 'Portal · 태스크 1/1 · 정상 대상 1 · 5xx 0' }]);
    assert.deepEqual(serviceTexts([{ name: 'console', running: null, desired: null, healthyTargets: null, totalTargets: null, fiveXx: null }]), [
      { key: 'console', text: 'Console · 태스크 –/– · 정상 대상 – · 5xx –' },
    ]);
  });
  test('다가오는 이벤트: 오늘은 날짜 없이, 내일은 "내일"', () => {
    const today = { eventId: 7, tenantId: 't', tenantName: 'A사', name: '쿠폰 이벤트', prepareAt: '2026-10-26T13:30:00+09:00', entryAt: '2026-10-26T14:00:00+09:00', startAt: '2026-10-26T15:00:00+09:00', endAt: '2026-10-26T15:40:00+09:00' };
    const tomorrow = { ...today, eventId: 9, tenantName: 'B사', name: '타임세일', prepareAt: '2026-10-27T09:30:00+09:00', entryAt: '2026-10-27T10:00:00+09:00', startAt: '2026-10-27T11:00:00+09:00', endAt: '2026-10-27T11:40:00+09:00' };
    assert.deepEqual(upcomingViews([today, tomorrow], NOW), [
      { key: 7, title: '7 · A사 쿠폰 이벤트', times: '준비 13:30 · 입장 14:00 · 시작 15:00 · 종료 15:40' },
      { key: 9, title: '9 · B사 타임세일', times: '내일 · 준비 09:30 · 입장 10:00 · 시작 11:00 · 종료 11:40' },
    ]);
    assert.equal(dayLabel('2026-10-30T09:00:00+09:00', NOW), '10.30');
    // 자정 근처 KST 날짜(UTC로는 전날)
    assert.equal(dayLabel('2026-10-27T00:30:00+09:00', NOW), '내일');
  });
  test('실패 목록: 단계 이름 · 상세 링크(이벤트 없으면 알림센터 긴급 · 읽은 것 포함)', () => {
    const rows = failureRows([
      { occurredAt: '2026-10-25T13:31:00+09:00', eventId: 5, tenantId: 't', tenantName: 'C사', eventName: '신규 가입 쿠폰', step: 'PREPARE', reason: '동결 확인 시간 초과' },
      { occurredAt: '2026-10-24T09:00:00+09:00', eventId: null, tenantId: null, tenantName: null, eventName: null, step: 'MAIL', reason: null },
    ]);
    assert.deepEqual(
      rows.map((r) => [r.time, r.event, r.step, r.reason, r.link]),
      [
        ['10.25 13:31', '5 · C사 신규 가입 쿠폰', '준비(WF2-2) 실패', '동결 확인 시간 초과', '/events/progress/5'],
        ['10.24 09:00', EMPTY, '메일 발송 실패', EMPTY, '/logs/notifications?priority=URGENT&unreadOnly=false'],
      ],
    );
  });
  test('실패 목록: 1분 주기 확인 알림(단계 지연 · DLQ)도 단계 이름으로', () => {
    const rows = failureRows([
      { occurredAt: '2026-10-30T15:00:00+09:00', eventId: 7, tenantId: 't', tenantName: 'A사', eventName: '쿠폰', step: 'DELAY', reason: '입장 시각(14:54)이 5분 지났지만 입장이 열리지 않았습니다.' },
      { occurredAt: '2026-10-30T14:00:00+09:00', eventId: null, tenantId: null, tenantName: null, eventName: null, step: 'SYSTEM', reason: '스케줄러 DLQ에 메시지 2건이 있습니다.' },
    ]);
    assert.deepEqual(
      rows.map((r) => [r.event, r.step, r.link]),
      [
        ['7 · A사 쿠폰', '단계 지연', '/events/progress/7'],
        [EMPTY, '시스템(DLQ)', '/logs/notifications?priority=URGENT&unreadOnly=false'],
      ],
    );
  });
});

describe('드롭다운 선택 유지', () => {
  const events: TrafficEventsResponse = { defaultEventId: 7, items: [item({})] };
  test('주소 ?eventId=가 있으면 그대로(기본 선택이 바뀌어도, 목록에서 빠져도 유지)', () => {
    assert.equal(selectedEventId('12', events), 12);
    assert.equal(selectedEventId('12', { defaultEventId: 3, items: [] }), 12);
    assert.equal(selectedEventId(null, events), 7);
    assert.equal(selectedEventId('abc', events), 7);
    assert.equal(selectedEventId(null, undefined), null);
    assert.equal(selectedEventId(null, { defaultEventId: null, items: [] }), null);
    assert.equal(parseEventId('7;drop'), null);
  });
  test('목록 밖 이벤트는 맨 앞에 붙인다', () => {
    assert.deepEqual(eventOptions(events, 7, NOW), [{ value: 7, label: '7 · A사 쿠폰 이벤트 · 진행 중' }]);
    assert.deepEqual(eventOptions(events, 12, NOW)[0], { value: 12, label: '12 · 목록 밖 이벤트(최근 7일 이전)' });
  });
  test('같은 이벤트는 한 번만 — 선택돼 있어도, 서버 목록에 두 번 와도', () => {
    const ended9 = { eventId: 9, phase: 'ENDED' as const, level: null, eventStatus: 'SCHEDULED' as const, hostingStatus: 'DEPLOY_FAILED' as const, endAt: '2026-10-26T13:30:00+09:00' };
    const list: TrafficEventsResponse = {
      defaultEventId: 9,
      items: [
        item({ ...ended9, group: 'TODAY' }),
        item({ eventId: 10, group: 'TODAY', phase: 'SCHEDULED', level: null, eventStatus: 'SCHEDULED', startAt: '2026-10-26T14:25:00+09:00' }),
        item({ ...ended9, group: 'ENDED' }),
        item({ eventId: 6, group: 'ENDED', phase: 'ENDED', level: null, eventStatus: 'EXPIRED', endAt: '2026-10-25T18:00:00+09:00' }),
      ],
    };
    assert.deepEqual(eventOptions(list, 9, NOW).map((o) => o.value), [9, 10, 6]);
    assert.equal(eventOptions(list, 9, NOW)[0].label, '9 · A사 쿠폰 이벤트 · 종료 · 13:30');
    // 서버가 고친 뒤 순서(예정 → 최근 종료): 고른 9가 목록에 있으면 앞에 따로 붙이지 않는다
    assert.deepEqual(eventOptions({ defaultEventId: 10, items: list.items.slice(1, 3) }, 9, NOW).map((o) => o.value), [10, 9]);
  });
  test('항목 끝 상태: 서버 phase 기준, 시각은 오늘이 아니면 MM-DD HH:mm', () => {
    const early = new Date('2026-10-26T12:00:00+09:00');
    assert.equal(eventStatusText(item({ group: 'TODAY', phase: 'SCHEDULED', level: null }), early), '예정 · 15:00 시작');
    assert.equal(
      eventStatusText(item({ group: 'TODAY', phase: 'SCHEDULED', startAt: '2026-10-28T15:00:00+09:00' }), early),
      '예정 · 10-28 15:00 시작',
    );
    assert.equal(eventStatusText(item({ group: 'TODAY', phase: 'PRE_ENTRY' }), new Date('2026-10-26T13:52:00+09:00')), '입장 직전 · 14:00 입장');
    assert.equal(eventStatusText(item({ group: 'OPEN', phase: 'LIVE' }), NOW), '진행 중');
    // 입장 ~ 시작 전: DB는 OPEN(진행 중 묶음)이지만 상태는 '입장 중'
    assert.equal(eventStatusText(item({ group: 'OPEN', phase: 'ENTRY_OPEN' }), new Date('2026-10-26T14:57:00+09:00')), '입장 중 · 15:00 시작');
    assert.equal(eventStatusText(item({ group: 'ENDED', phase: 'ENDED', endAt: '2026-10-24T15:40:00+09:00' }), NOW), '종료 · 10-24 15:40');
    assert.equal(eventStatusText(item({ group: 'ENDED', phase: 'PREPARE_FAILED', entryAt: '2026-10-25T14:00:00+09:00' }), NOW), '준비 실패 · 10-25 14:00');
    // 배포 · 호스팅 실패: 실패 시각(없으면 이름만). 호스팅 실패는 hosting_end_at이 지나도 '호스팅 실패'
    assert.equal(eventStatusText(item({ group: 'ENDED', phase: 'DEPLOY_FAILED', failedAt: '2026-10-25T10:01:00+09:00' }), NOW), '배포 실패 · 10-25 10:01');
    assert.equal(eventStatusText(item({ group: 'TODAY', phase: 'HOSTING_FAILED', failedAt: '2026-10-26T13:50:00+09:00' }), NOW), '호스팅 실패 · 13:50');
    assert.equal(eventStatusText(item({ group: 'ENDED', phase: 'HOSTING_FAILED', failedAt: null }), NOW), '호스팅 실패');
  });
  test('DB는 OPEN이어도 hosting_end_at이 지났으면(서버 phase ENDED) 드롭다운도 "종료"', () => {
    const stale = item({ group: 'OPEN', phase: 'ENDED', eventStatus: 'OPEN', endAt: '2026-10-21T22:09:00+09:00' });
    assert.equal(eventStatusText(stale, NOW), '종료 · 10-21 22:09');
    assert.deepEqual(eventOptions({ defaultEventId: 7, items: [stale] }, 7, NOW), [{ value: 7, label: '7 · A사 쿠폰 이벤트 · 종료 · 10-21 22:09' }]);
  });
  test('clockText: 오늘(KST)이면 HH:mm, 아니면 MM-DD HH:mm', () => {
    assert.equal(clockText('2026-10-26T09:05:00+09:00', NOW), '09:05');
    assert.equal(clockText('2026-10-25T23:59:00+09:00', NOW), '10-25 23:59');
    assert.equal(clockText('2026-10-27T00:10:00+09:00', NOW), '10-27 00:10');
  });
});

describe('구역 상태별 표시(명세 4절)', () => {
  test('상태 칩: 고른 이벤트의 상태(예정이면 "예정"), 진행 중 · 입장 직전은 판정도', () => {
    assert.deepEqual(sectionStatus(eventResponse('SCHEDULED')), { chips: [{ label: '예정', tone: 'grey' }], refresh: '자동 갱신 · 15초', autoRefresh: true });
    const live = eventResponse('LIVE', { kpi: { level: 'WARN', reason: '5xx 1.2%', warn: [], live: null, summary: null } });
    assert.deepEqual(sectionStatus(live).chips, [
      { label: '진행 중', tone: 'blue' },
      { label: '주의', tone: 'amber' },
    ]);
    assert.deepEqual(sectionStatus(eventResponse('PRE_ENTRY')).chips, [
      { label: '입장 직전', tone: 'blue' },
      { label: '확인 불가', tone: 'grey' },
    ]);
    assert.deepEqual(sectionStatus(eventResponse('ENDED')), { chips: [{ label: '종료', tone: 'grey' }], refresh: '자동 갱신 꺼짐', autoRefresh: false });
    assert.deepEqual(sectionStatus(eventResponse('PREPARE_FAILED')).chips, [{ label: '준비 실패', tone: 'red' }]);
    assert.deepEqual(sectionStatus(eventResponse('ENTRY_OPEN', { kpi: { level: 'OK', reason: '정상', warn: [], live: null, summary: null } })).chips, [
      { label: '입장 중', tone: 'blue' },
      { label: '정상', tone: 'green' },
    ]);
    assert.deepEqual(sectionStatus(undefined).chips, []);
  });
  test('상태별 갱신: 마무리는 실시간, 종료 처리 중은 진행 단계만 15초, 종료 · 종료 실패는 꺼짐', () => {
    const wrapUp = eventResponse('WRAP_UP', { kpi: { level: 'OK', reason: '정상', warn: [], live: null, summary: null } });
    assert.deepEqual(sectionStatus(wrapUp), {
      chips: [
        { label: '마무리', tone: 'blue' },
        { label: '정상', tone: 'green' },
      ],
      refresh: '자동 갱신 · 15초',
      autoRefresh: true,
    });
    assert.deepEqual(sectionStatus(eventResponse('ENDING')), { chips: [{ label: '종료 처리 중', tone: 'grey' }], refresh: '자동 갱신 · 15초 · 진행 단계만', autoRefresh: true });
    assert.deepEqual(sectionStatus(eventResponse('END_FAILED')), { chips: [{ label: '종료·집계 실패', tone: 'red' }], refresh: '자동 갱신 꺼짐', autoRefresh: false });
    for (const [phase, stop] of [
      ['PRE_ENTRY', false],
      ['ENTRY_OPEN', false],
      ['LIVE', false],
      ['WRAP_UP', false],
      ['ENDING', false],
      ['ENDED', true],
      ['END_FAILED', true],
    ] as const) {
      assert.equal(stopPollingWhen(eventResponse(phase)), stop, phase);
    }
    // 그래프 머리글: 호스팅 종료 뒤는 값 고정
    assert.equal(chartRefreshText(eventResponse('WRAP_UP')), '앱 지표 15초 · ALB·RDS 60초 갱신');
    assert.equal(chartRefreshText(eventResponse('ENDING')), '호스팅 종료 뒤 값 고정');
    assert.equal(chartRefreshText(eventResponse('LIVE', { prometheus: { asOf: null, stale: true, staleReason: 'SOURCE' } })), '앱 지표 15초 · ALB·RDS 60초 갱신 · 데이터 지연됨');
    assert.equal(isClosedPhase('ENDING') && isClosedPhase('ENDED') && isClosedPhase('END_FAILED'), true);
    assert.equal(isClosedPhase('WRAP_UP') || isClosedPhase('LIVE'), false);
  });
  test('드롭다운 상태 시각: 마무리는 호스팅 종료 예정, 호스팅 종료 뒤는 hosting_end_at', () => {
    assert.equal(eventStatusText(item({ phase: 'WRAP_UP' }), NOW), '마무리 · 15:40 호스팅 종료');
    assert.equal(eventStatusText(item({ group: 'ENDED', phase: 'ENDING' }), NOW), '종료 처리 중 · 15:40');
    assert.equal(eventStatusText(item({ group: 'ENDED', phase: 'END_FAILED' }), NOW), '종료·집계 실패 · 15:40');
    assert.equal(OPEN_GROUP_TEXT, '진행 중(입장 중 · 마무리 포함)');
  });
  test('구역 배지와 드롭다운 항목은 같은 상태 이름(PHASE_META)', () => {
    for (const phase of ['SCHEDULED', 'PRE_ENTRY', 'ENTRY_OPEN', 'LIVE', 'WRAP_UP', 'ENDING', 'ENDED', 'END_FAILED', 'PREPARE_FAILED', 'DEPLOY_FAILED', 'HOSTING_FAILED'] as const) {
      const badge = sectionStatus(eventResponse(phase)).chips[0].label;
      assert.equal(badge, PHASE_META[phase].label);
      assert.ok(eventStatusText(item({ phase }), NOW).startsWith(badge), phase);
    }
  });
  test('종료면 다시 부르지 않음', () => {
    assert.equal(stopPollingWhen(eventResponse('ENDED')), true);
    assert.equal(stopPollingWhen(eventResponse('LIVE')), false);
  });
  test('예정 · 준비 실패는 그래프 · KPI 대신 안내', () => {
    assert.deepEqual(eventBody(eventResponse('SCHEDULED')), {
      kind: 'scheduled',
      title: '아직 수집 전이에요',
      description: '그래프와 KPI는 입장 10분 전(13:50)부터 표시돼요 · 사전 점검은 위쪽 노드·플랫폼 칸에서',
    });
    const failed = eventResponse('PREPARE_FAILED', {
      steps: [{ key: 'PREPARE', plannedAt: '2026-10-26T13:30:00+09:00', actualAt: '2026-10-26T13:31:00+09:00', durationSeconds: null, state: 'FAILED', reason: '동결 확인 시간 초과' }],
    });
    assert.deepEqual(eventBody(failed), {
      kind: 'failed',
      title: '준비(WF2-2) 실패 — 동결 확인 시간 초과',
      description: '입장 게이트가 열리지 않아 그래프·KPI가 없어요',
      link: '/events/progress/7',
    });
    assert.deepEqual(eventBody(eventResponse('LIVE')), { kind: 'metrics' });
    assert.deepEqual(eventBody(eventResponse('ENDED')), { kind: 'metrics' });
  });
  test('배포 · 호스팅 실패도 그래프 · KPI 대신 빨간 안내(실패 단계 이름 + 사유), 자동 갱신은 켜 둔다', () => {
    const deploy = eventResponse('DEPLOY_FAILED', {
      steps: [{ key: 'DEPLOY', plannedAt: null, actualAt: '2026-10-26T10:01:00+09:00', durationSeconds: null, state: 'FAILED', reason: 'ECR 이미지 없음' }],
    });
    assert.deepEqual(eventBody(deploy), {
      kind: 'failed',
      title: '배포(WF1-2) 실패 — ECR 이미지 없음',
      description: '배포가 끝나지 않아 이벤트가 열리지 않았어요 · 그래프·KPI가 없어요',
      link: '/events/progress/7',
    });
    // 실패 단계가 응답에 없으면 상태 이름으로
    assert.deepEqual(eventBody(eventResponse('HOSTING_FAILED', { steps: [] })), {
      kind: 'failed',
      title: '호스팅 시작(WF2-1) 실패',
      description: '이벤트 페이지가 열리지 않아 그래프·KPI가 없어요',
      link: '/events/progress/7',
    });
    assert.deepEqual(sectionStatus(deploy), { chips: [{ label: '배포 실패', tone: 'red' }], refresh: '자동 갱신 · 15초', autoRefresh: true });
    assert.equal(stopPollingWhen(eventResponse('HOSTING_FAILED')), false);
  });

  test('그래프가 없을 때 문구: Prometheus 자체 장애면 응답 지연, 그 밖은 처리 오류', () => {
    const source = (stale: boolean, staleReason: MonitoringSource['staleReason']): MonitoringSource => ({ asOf: null, stale, staleReason });
    assert.equal(chartsMissingText(source(true, 'SOURCE')), 'Prometheus 응답이 늦어 그래프를 받지 못했어요');
    assert.equal(chartsMissingText(source(true, 'INTERNAL')), '그래프를 만들지 못했어요');
    // 갱신은 됐는데 그래프 구역만 가공에 실패(stale 아님)
    assert.equal(chartsMissingText(source(false, null)), '그래프를 만들지 못했어요');
    // staleReason이 없는 이전 서버 응답의 stale은 지연으로
    assert.equal(chartsMissingText({ asOf: null, stale: true } as MonitoringSource), 'Prometheus 응답이 늦어 그래프를 받지 못했어요');
  });
  test('진행 단계 아래 글자', () => {
    const steps: TrafficStep[] = [
      { key: 'APPROVE', plannedAt: null, actualAt: '2026-10-23T10:02:00+09:00', durationSeconds: null, state: 'DONE', reason: null },
      { key: 'PREPARE', plannedAt: '2026-10-26T13:30:00+09:00', actualAt: '2026-10-26T14:00:20+09:00', durationSeconds: 200, state: 'DONE', reason: null },
      { key: 'START', plannedAt: '2026-10-26T15:00:00+09:00', actualAt: '2026-10-26T15:00:00+09:00', durationSeconds: null, state: 'ACTIVE', reason: null },
      { key: 'END', plannedAt: '2026-10-26T15:40:00+09:00', actualAt: null, durationSeconds: null, state: 'WAITING', reason: null },
      { key: 'AGGREGATE', plannedAt: null, actualAt: null, durationSeconds: null, state: 'WAITING', reason: null },
    ];
    assert.deepEqual(
      stepViews(steps, NOW).map((s) => [s.label, s.sub]),
      [
        ['승인', '10-23 10:02 완료'],
        // TEST-LEAD: 서비스 값 T−90
        // ['준비 (T−90)', '14:00 완료 · 3분 20초'],
        ['준비 (T−15)', '14:00 완료 · 3분 20초'], // TEST-LEAD
        ['이벤트 진행', '15:00 시작 · 진행 중'],
        ['호스팅 종료', '15:40 예정'],
        ['집계', '호스팅 종료 뒤'],
      ],
    );
    const failed: TrafficStep[] = [
      { key: 'PREPARE', plannedAt: '2026-10-25T13:30:00+09:00', actualAt: '2026-10-25T13:31:00+09:00', durationSeconds: null, state: 'FAILED', reason: 'x' },
      { key: 'ENTRY', plannedAt: '2026-10-25T14:00:00+09:00', actualAt: null, durationSeconds: null, state: 'SKIPPED', reason: null },
      { key: 'END', plannedAt: '2026-10-25T15:40:00+09:00', actualAt: null, durationSeconds: null, state: 'SKIPPED', reason: null },
    ];
    // 앞 단계 실패로 진행 안 된 칸은 모두 '진행 안 됨'(종료 · 집계 포함)
    assert.deepEqual(
      stepViews(failed, NOW).map((s) => s.sub),
      ['10-25 13:31 실패', '진행 안 됨', '진행 안 됨'],
    );
    // 배포: 배포 중(PENDING)은 시작(승인) 시각과 '시작 · 진행 중'(모르면 '진행 중'), 실패는 실패 시각
    assert.deepEqual(
      stepViews(
        [
          { key: 'DEPLOY', plannedAt: null, actualAt: '2026-10-26T09:58:00+09:00', durationSeconds: null, state: 'ACTIVE', reason: null },
          { key: 'DEPLOY', plannedAt: null, actualAt: null, durationSeconds: null, state: 'ACTIVE', reason: null },
          { key: 'DEPLOY', plannedAt: null, actualAt: '2026-10-26T10:00:40+09:00', durationSeconds: null, state: 'DONE', reason: null },
          { key: 'DEPLOY', plannedAt: null, actualAt: null, durationSeconds: null, state: 'DONE', reason: null },
          { key: 'DEPLOY', plannedAt: null, actualAt: '2026-10-26T10:01:00+09:00', durationSeconds: null, state: 'FAILED', reason: 'x' },
        ],
        NOW,
      ).map((s) => [s.label, s.sub]),
      [
        ['배포', '09:58 시작 · 진행 중'],
        ['배포', '진행 중'],
        ['배포', '10:00 완료'],
        ['배포', `${EMPTY} 완료`],
        ['배포', '10:01 실패'],
      ],
    );
    // 입장 중(시작 전): 입장이 지금 단계(OPEN 로그 시각), 시작은 예정. 준비는 같은 OPEN 로그 시각으로 완료
    assert.deepEqual(
      stepViews(
        [
          { key: 'PREPARE', plannedAt: '2026-10-26T14:45:00+09:00', actualAt: '2026-10-26T14:55:20+09:00', durationSeconds: null, state: 'DONE', reason: null },
          { key: 'ENTRY', plannedAt: '2026-10-26T14:55:00+09:00', actualAt: '2026-10-26T14:55:20+09:00', durationSeconds: null, state: 'ACTIVE', reason: null },
          { key: 'START', plannedAt: '2026-10-26T15:00:00+09:00', actualAt: null, durationSeconds: null, state: 'WAITING', reason: null },
        ],
        new Date('2026-10-26T14:57:00+09:00'),
      ).map((s) => s.sub),
      ['14:55 완료', '14:55 시작 · 진행 중', '15:00 예정'],
    );
    // 입장 직전: 준비가 진행 중(계획 시각 지남)
    assert.equal(
      stepViews([{ key: 'PREPARE', plannedAt: '2026-10-26T13:30:00+09:00', actualAt: null, durationSeconds: null, state: 'ACTIVE', reason: null }], new Date('2026-10-26T13:55:00+09:00'))[0].sub,
      '13:30 시작 · 진행 중',
    );
  });
  test('이벤트 진행은 시작 ~ 이벤트 종료, 호스팅 종료 · 집계는 진행 중이면 "종료 처리 중" · "집계 중"', () => {
    const at = new Date('2026-10-26T15:41:00+09:00');
    const steps: TrafficStep[] = [
      { key: 'START', plannedAt: '2026-10-26T15:00:00+09:00', actualAt: '2026-10-26T15:00:00+09:00', endAt: '2026-10-26T15:30:00+09:00', durationSeconds: null, state: 'DONE', reason: null },
      { key: 'END', plannedAt: '2026-10-26T15:40:00+09:00', actualAt: null, endAt: null, durationSeconds: null, state: 'ACTIVE', reason: null },
      { key: 'AGGREGATE', plannedAt: null, actualAt: null, endAt: null, durationSeconds: null, state: 'ACTIVE', reason: null },
      // 다른 날이면 끝 시각에도 날짜
      { key: 'START', plannedAt: '2026-10-25T23:00:00+09:00', actualAt: '2026-10-25T23:00:00+09:00', endAt: '2026-10-26T01:00:00+09:00', durationSeconds: null, state: 'DONE', reason: null },
    ];
    assert.deepEqual(
      stepViews(steps, at).map((s) => [s.label, s.sub]),
      [
        ['이벤트 진행', '15:00~15:30 완료'],
        ['호스팅 종료', '종료 처리 중'],
        ['집계', '집계 중'],
        ['이벤트 진행', '10-25 23:00~10-26 01:00 완료'],
      ],
    );
  });
  test('지난 계획 시각이어도 시작 기록이 없으면 "HH:mm 예정"("기록 없음"은 쓰지 않음 — 실패처럼 읽힌다)', () => {
    const waiting: TrafficStep[] = [
      { key: 'HOSTING_START', plannedAt: '2026-10-21T16:09:00+09:00', actualAt: null, durationSeconds: null, state: 'WAITING', reason: null },
      { key: 'END', plannedAt: '2026-10-26T15:09:00+09:00', actualAt: null, durationSeconds: null, state: 'WAITING', reason: null },
      { key: 'AGGREGATE', plannedAt: null, actualAt: null, durationSeconds: null, state: 'WAITING', reason: null },
      { key: 'START', plannedAt: '2026-10-26T15:30:00+09:00', actualAt: null, durationSeconds: null, state: 'WAITING', reason: null },
    ];
    const subs = stepViews(waiting, NOW).map((s) => s.sub);
    assert.deepEqual(subs, ['10-21 16:09 예정', '15:09 예정', '호스팅 종료 뒤', '15:30 예정']);
    assert.ok(subs.every((sub) => !sub.includes('기록 없음')));
  });
  test('KPI: 입장 직전은 요청 처리 · 대기 인원 –(입장 뒤 표시), 주의 값만 표시', () => {
    const kpi: TrafficKpi = {
      level: 'WARN',
      reason: '5xx 0.8%',
      warn: ['errorRatio', 'redisConnection'],
      summary: null,
      live: {
        rps: null, errorRatio: null, p95Ms: null, p95IssuanceMs: null, p95TicketMs: null,
        podsReady: 6, podsMax: 12, podsMin: 6, podsPending: 0, cpuCores: 0.4, cpuRequestCores: 3, memoryBytes: 2.8 * 1024 ** 3, memoryRequestBytes: 6 * 1024 ** 3,
        waiting: null, stockRemaining: 5000, stockTotal: 5000, redisMemoryRatio: 0.02, redisMemoryUsedBytes: 0.1 * 1024 ** 3, redisMemoryMaxBytes: 4 * 1024 ** 3,
        redisOpsPerSec: 100, redisRestarts: 0, redisConnections: 12, redisConnection: 'DOWN',
      },
    };
    const groups = liveKpiGroups(kpi, 'PRE_ENTRY');
    assert.deepEqual(groups.map((g) => g.title), ['요청 처리', '앱 자원', '이벤트 진행', 'Redis']);
    assert.deepEqual(groups[0].tiles.map((t) => [t.value, t.sub, t.warn]), [
      [EMPTY, '입장 뒤 표시', false],
      [EMPTY, '입장 뒤 표시', true],
      [EMPTY, '입장 뒤 표시', false],
    ]);
    assert.deepEqual(groups[1].tiles.map((t) => [t.value, t.sub]), [
      ['6 / 12', undefined],
      ['0.4 vCPU', '요청량의 13%'],
      ['2.8 GB', '요청량의 47%'],
    ]);
    assert.deepEqual(groups[2].tiles.map((t) => [t.value, t.sub]), [
      [EMPTY, '입장 뒤 표시'],
      ['5,000', '전체 5,000'],
    ]);
    assert.deepEqual(groups[3].tiles.map((t) => [t.value, t.sub, t.warn]), [
      ['2%', '102 MB / 4.0 GB', false],
      ['100 ops/s', undefined, false],
      ['0', '연결 끊김', true],
    ]);
    // 진행 중은 기준 안내
    assert.equal(liveKpiGroups(kpi, 'LIVE')[0].tiles[1].sub, '주의 1% · 위험 2%');
    // 값이 없으면 모두 –
    assert.ok(liveKpiGroups(null, 'LIVE').flatMap((g) => g.tiles).every((t) => t.value === EMPTY || t.value === '– / –'));
  });
  test('종료 최종값', () => {
    const tiles = endedKpiTiles({
      peakRps: { value: 1820, at: '2026-10-24T15:03:00+09:00' },
      errorRatioAvg: 0.003, errorRatioMax: 0.011, p95MaxMs: 240, p95AvgMs: 95, podsMax: 10, podsLimit: 12,
      peakWaiting: { value: 6200, at: '2026-10-24T14:59:00+09:00' }, issued: 5000, stockTotal: 5000, soldOutAt: '2026-10-24T15:21:00+09:00',
      cpuMaxCores: 4.6, cpuRequestCores: 5, memoryMaxBytes: 5.2 * 1024 ** 3, memoryRequestBytes: 8 * 1024 ** 3, redisMemoryMax: 0.44, redisRestarts: 0,
    });
    assert.deepEqual(tiles.map((t) => [t.label, t.value, t.sub]), [
      ['최대 RPS', '1,820', '15:03'],
      ['5xx 비율', '0.3%', '전체 평균 · 최대 1.1%'],
      ['p95 지연', '240ms', '최대 · 평균 95ms'],
      ['Pod 최대', '10 / 12', undefined],
      ['최대 대기 인원', '6,200', '14:59'],
      ['발급 완료', '5,000 / 5,000', '15:21 소진'],
      ['Pod CPU 최대', '4.6 vCPU', '요청량의 92%'],
      ['Pod 메모리 최대', '5.2 GB', '요청량의 65%'],
      ['Redis 메모리 최대', '44%', undefined],
      ['Redis 재시작', '0', undefined],
    ]);
    // 최대가 0이면 서버가 시각을 비운다 → '–'. Redis 지표가 없으면 재시작도 '–'
    const quiet = endedKpiTiles({
      peakRps: { value: 0, at: null },
      errorRatioAvg: 0, errorRatioMax: 0, p95MaxMs: null, p95AvgMs: null, podsMax: 2, podsLimit: 4,
      peakWaiting: { value: 0, at: null }, issued: 0, stockTotal: 15, soldOutAt: null,
      cpuMaxCores: null, cpuRequestCores: null, memoryMaxBytes: null, memoryRequestBytes: null, redisMemoryMax: null, redisRestarts: null,
    });
    assert.deepEqual(quiet.filter((t) => ['최대 RPS', '최대 대기 인원', 'Redis 재시작'].includes(t.label)).map((t) => [t.label, t.value, t.sub]), [
      ['최대 RPS', '0', EMPTY],
      ['최대 대기 인원', '0', EMPTY],
      ['Redis 재시작', EMPTY, undefined],
    ]);
  });
  test('Redis 칸: 서버가 재시작을 비우면(Redis 지표 없음) 메모리 · 연결처럼 "–"', () => {
    const kpi: TrafficKpi = {
      level: 'OK', reason: '정상', warn: [], summary: null,
      live: {
        rps: 1, errorRatio: 0, p95Ms: null, p95IssuanceMs: null, p95TicketMs: null, podsReady: 2, podsMax: 4, podsMin: 2, podsPending: 0, cpuCores: 0.2, cpuRequestCores: 1,
        memoryBytes: 1, memoryRequestBytes: 2, waiting: 0, stockRemaining: 5, stockTotal: 15, redisMemoryRatio: null, redisMemoryUsedBytes: null, redisMemoryMaxBytes: null,
        redisOpsPerSec: null, redisRestarts: null, redisConnections: null, redisConnection: null,
      },
    };
    assert.deepEqual(liveKpiGroups(kpi, 'LIVE')[3].tiles.map((t) => [t.label, t.value, t.sub]), [
      ['메모리', EMPTY, undefined],
      ['처리량', EMPTY, undefined],
      ['재시작', EMPTY, `연결 ${EMPTY}`],
    ]);
  });
  test('Redis 칸 연결: 판정과 같은 말 — 연결됨은 연결 수, 1분 안은 확인 중, 1분 이어지면 끊김(둘 다 주의 글씨)', () => {
    const redisTile = (redisConnection: 'UP' | 'CHECKING' | 'DOWN', warn: string[]) =>
      liveKpiGroups({
        level: 'OK', reason: '정상', warn, summary: null,
        live: {
          rps: 1, errorRatio: 0, p95Ms: null, p95IssuanceMs: null, p95TicketMs: null, podsReady: 2, podsMax: 4, podsMin: 2, podsPending: 0, cpuCores: 0.2, cpuRequestCores: 1,
          memoryBytes: 1, memoryRequestBytes: 2, waiting: 0, stockRemaining: 5, stockTotal: 15, redisMemoryRatio: 0.1, redisMemoryUsedBytes: 1, redisMemoryMaxBytes: 10,
          redisOpsPerSec: 5, redisRestarts: 0, redisConnections: 12, redisConnection,
        },
      }, 'LIVE')[3].tiles[2];
    assert.deepEqual([redisTile('UP', []).sub, redisTile('UP', []).warn], ['연결 12', false]);
    assert.deepEqual([redisTile('CHECKING', ['redisConnection']).sub, redisTile('CHECKING', ['redisConnection']).warn], ['연결 확인 중', true]);
    assert.deepEqual([redisTile('DOWN', ['redisConnection']).sub, redisTile('DOWN', ['redisConnection']).warn], ['연결 끊김', true]);
  });
  test('범위 버튼', () => {
    assert.deepEqual(
      rangeButtons({ key: 'entry', options: ['entry', '15m', '1h'], from: '', to: '', stepSeconds: 30, entryAt: '', startAt: '' }).map((b) => [b.label, b.selected]),
      [
        ['입장 −10분 ~ 지금', true],
        ['15분', false],
        ['1시간', false],
      ],
    );
    assert.deepEqual(rangeButtons({ key: 'full', options: ['full', 'start'], from: '', to: '', stepSeconds: 90, entryAt: '', startAt: '' }).map((b) => b.label), ['전체', '시작 ±10분']);
    assert.deepEqual(rangeButtons(null), []);
  });
});

describe('Grafana 링크(새 탭)', () => {
  const BASE = 'http://localhost:13000';
  // 준비(prepareAt) 13:30 − 5분 — 준비 시각은 서버 값(TEST-LEAD가 켜져 있어도 맞다)
  const FROM = Date.parse('2026-10-26T13:25:00+09:00');
  const INFRA = `${BASE}/d/tetra-event-infra?var-tenant=aaaaaaaaaaaa&var-event=7&from=${FROM}&to=now&refresh=10s`;
  const links = (phase: TrafficEventResponse['event']['phase'], patch: Partial<TrafficEventResponse> = {}, now = NOW) =>
    eventGrafanaLinks(eventResponse(phase, patch), BASE, now).map((l) => [l.label, l.href, l.danger]);
  const kpi = (level: TrafficKpi['level']): TrafficKpi => ({ level, reason: '', warn: [], live: null, summary: null });

  test('주소: http(s)만, 끝 / 없이 — 아니면 링크 숨김', () => {
    assert.equal(grafanaBase(' http://localhost:13000/ '), BASE);
    assert.equal(grafanaBase('https://grafana.example.com'), 'https://grafana.example.com');
    assert.equal(grafanaBase(null), null);
    assert.equal(grafanaBase(''), null);
    assert.equal(grafanaBase('javascript:alert(1)'), null);
  });
  test('안내: 주소가 localhost(포트 포워딩)일 때만', () => {
    assert.equal(grafanaHint(BASE), GRAFANA_PORT_FORWARD_HINT);
    assert.equal(grafanaHint('http://127.0.0.1:13000'), GRAFANA_PORT_FORWARD_HINT);
    assert.equal(grafanaHint('https://grafana.example.com'), null);
    assert.equal(grafanaHint(null), null);
  });
  test('준비 ~ 진행 중: 관제(10초 갱신), 준비 − 5분 ~ now. 위험 판정이면 장애 대응(now-15m ~ now)', () => {
    for (const phase of ['PRE_ENTRY', 'ENTRY_OPEN', 'LIVE', 'WRAP_UP'] as const) {
      assert.deepEqual(links(phase, { kpi: kpi('OK') }), [['Grafana에서 자세히 ↗', INFRA, false]]);
    }
    assert.deepEqual(links('LIVE', { kpi: kpi('DANGER') }), [
      ['Grafana에서 자세히 ↗', INFRA, false],
      ['장애 대응 ↗', `${BASE}/d/tetra-incident?var-tenant=aaaaaaaaaaaa&var-event=7&from=now-15m&to=now`, true],
    ]);
    // 주의 · 확인 불가는 관제만
    assert.equal(links('LIVE', { kpi: kpi('WARN') }).length, 1);
    assert.equal(links('PRE_ENTRY', { kpi: kpi('UNKNOWN') }).length, 1);
  });
  test('준비 전은 없음, 준비가 지난 예정은 관제', () => {
    assert.deepEqual(links('SCHEDULED', {}, new Date('2026-10-26T13:29:59+09:00')), []);
    assert.deepEqual(links('SCHEDULED', {}, new Date('2026-10-26T13:30:00+09:00')), [['Grafana에서 자세히 ↗', INFRA, false]]);
  });
  test('종료(종료 처리 중 · 종료): 지표 상세, 준비 − 5분 ~ hosting_end_at + 5분', () => {
    const graph = `${BASE}/d/tetra-event-graph?var-tenant=aaaaaaaaaaaa&var-event=7&from=${FROM}&to=${Date.parse('2026-10-26T15:45:00+09:00')}`;
    assert.deepEqual(links('ENDING'), [['Grafana에서 자세히 ↗', graph, false]]);
    assert.deepEqual(links('ENDED'), [['Grafana에서 자세히 ↗', graph, false]]);
    // hosting_end_at이 없으면 ~ now
    assert.ok(String(links('ENDED', { event: { ...eventResponse('ENDED').event, endAt: null } })[0][1]).endsWith('&to=now'));
  });
  test('준비 실패 · 종료 실패: 관제만(준비 − 5분 ~ now), 장애 대응 없음', () => {
    for (const phase of ['PREPARE_FAILED', 'END_FAILED'] as const) {
      assert.deepEqual(links(phase), [['Grafana에서 자세히 ↗', INFRA, false]]);
      assert.deepEqual(links(phase, { kpi: kpi('DANGER') }), [['Grafana에서 자세히 ↗', INFRA, false]]);
    }
  });
  test('배포 · 호스팅 실패, 준비 시각 · 주소 없음은 링크 없음', () => {
    for (const phase of ['DEPLOY_FAILED', 'HOSTING_FAILED'] as const) {
      assert.deepEqual(links(phase), []);
    }
    for (const prepareAt of ['', null as unknown as string, '날짜 아님']) {
      assert.deepEqual(links('PREPARE_FAILED', { event: { ...eventResponse('PREPARE_FAILED').event, prepareAt } }), []);
    }
    assert.deepEqual(eventGrafanaLinks(eventResponse('LIVE'), null, NOW), []);
  });
  test('시각: DB DATETIME(KST, 시간대 없음)은 +09:00으로 본다', () => {
    assert.equal(kstEpochMs('2026-10-26T13:30:00'), Date.parse('2026-10-26T04:30:00Z'));
    assert.equal(kstEpochMs('2026-10-26T13:30:00+09:00'), Date.parse('2026-10-26T04:30:00Z'));
  });
  test('플랫폼: 변수 없는 플랫폼 대시보드', () => {
    assert.deepEqual(platformGrafanaLink(BASE), { key: 'platform', label: 'Grafana ↗', href: `${BASE}/d/tetra-platform`, danger: false });
    assert.equal(platformGrafanaLink(null), null);
  });
});

describe('그래프 변환', () => {
  const t0 = Date.parse('2026-10-26T14:50:00+09:00');
  const charts: TrafficCharts = {
    timestamps: [t0, t0 + 30_000, t0 + 60_000],
    traffic: { rps: [100, null, 1131.24] },
    errors: { app: [0, 0.008, 0.01], alb: [[t0, 0.002], [t0 + 90_000, 0.004]], eventTarget: [[t0, 0.9]] },
    latency: { issuance: [180, 190, 200], ticket: [null, null, null] },
    pods: { ready: [6, 6, 8], pending: [0, 0, 2], min: 6, max: 12 },
    progress: { waiting: [0, 1200, 2400], stock: [5000, 5000, 3100] },
    // 테스트 13 모양: 커서가 번호표 끝보다 앞서 입장 허용(커서 전진)은 크고 입장은 번호표 발급 속도
    admission: { admittedPerSec: [0, 0.11, 0.17], cursorPerSec: [0, 34, 54] },
    redis: { memory: [0.3, 0.35, 0.38], cpu: [0.1, 0.2, 0.6], restarts: [0, 0, 0] },
    resources: { cpu: [1, 2, 3], memory: [1, 2, 3], cpuRequest: [4, 4, 4], memoryRequest: [8, 8, 8] },
    rds: { cpu: [[t0, 30], [t0 + 60_000, 48]], connections: [[t0, 20], [t0 + 60_000, 36]] },
  };
  const markers = { entryAt: '2026-10-26T14:00:00+09:00', startAt: '2026-10-26T14:50:30+09:00' };

  test('8개 순서와 줄 나눔 — 입장은 진행 옆', () => {
    const views = chartViews(charts, markers, false);
    assert.deepEqual(views.map((v) => v.key), ['traffic', 'errors', 'latency', 'pods', 'progress', 'admission', 'redis', 'rds']);
    assert.deepEqual(CHART_ROWS, [['traffic', 'errors', 'latency'], ['pods', 'progress', 'admission'], ['redis', 'rds']]);
    // 줄 나눔에 모든 그래프가 한 번씩
    assert.deepEqual(CHART_ROWS.flat().slice().sort(), views.map((v) => v.key).sort());
  });
  test('에러: 앱 5xx + ALB 5xx(%), eventTarget은 쓰지 않음, AWS 점은 시각 합집합으로', () => {
    const errors = chartViews(charts, markers, true).find((v) => v.key === 'errors')!;
    assert.deepEqual(errors.series.map((s) => s.label), ['앱 5xx (이 이벤트)', 'ALB 5xx (전체 이벤트 합)']);
    assert.deepEqual(errors.x, [t0, t0 + 30_000, t0 + 60_000, t0 + 90_000]);
    assert.deepEqual(errors.series[0].data, [0, 0.8, 1, null]);
    assert.deepEqual(errors.series[1].data, [0.2, null, null, 0.4]);
    assert.ok(!errors.series.some((s) => s.data.includes(90)));
    assert.deepEqual(errors.thresholds, [{ y: 1, label: '주의 기준 1%' }]);
    assert.equal(errors.delayed, true);
    assert.equal(errors.current, '1');
  });
  test('색: 주 지표 primary · 보조 secondary(Pending도 보조), 주의선 · 점선', () => {
    const views = chartViews(charts, markers, false);
    const pods = views.find((v) => v.key === 'pods')!;
    assert.deepEqual(pods.series.map((s) => [s.label, s.role]), [['Ready', 'primary'], ['Pending', 'secondary']]);
    assert.deepEqual(pods.guides, [{ y: 12, label: '최대 12' }, { y: 6, label: '최소 6' }]);
    assert.equal(pods.current, '8 / 12');
    const latency = views.find((v) => v.key === 'latency')!;
    assert.deepEqual(latency.thresholds, [{ y: 500, label: '주의 기준 500ms (초안)' }]);
    const redis = views.find((v) => v.key === 'redis')!;
    assert.deepEqual(redis.series[0].data, [30, 35, 38]);
    assert.equal(redis.yMax, 100);
    const rds = views.find((v) => v.key === 'rds')!;
    assert.deepEqual(rds.x, [t0, t0 + 60_000]);
    assert.deepEqual(rds.series[1].data, [20, 36]);
    assert.equal(rds.current, '48');
    for (const v of views) assert.ok(v.series.every((s) => s.role === 'primary' || s.role === 'secondary'));
  });
  test('트래픽은 API 전체 RPS만', () => {
    const traffic = chartViews(charts, markers, false)[0];
    assert.deepEqual(traffic.series.map((s) => [s.label, s.unit]), [['API 전체 RPS', 'RPS']]);
    assert.equal(traffic.tooltipNote, undefined);
  });
  test('입장: 실선 입장(명/초, 추정) · 점선 입장 허용(커서 전진) — 범례 두 줄, 툴팁 단위 · 추정 안내', () => {
    const views = chartViews(charts, { ...markers, eventEndAt: '2026-10-26T14:50:45+09:00' }, false);
    const admission = views.find((v) => v.key === 'admission')!;
    assert.equal(admission.title, '입장');
    assert.deepEqual(admission.series.map((s) => [s.label, s.unit, s.role, s.dashed ?? false]), [
      ['입장 (명/초, 추정)', '명/초', 'primary', false],
      ['입장 허용 (커서 전진, 번호/초)', '번호/초', 'secondary', true],
    ]);
    assert.deepEqual(admission.series[0].data, [0, 0.11, 0.17]);
    assert.deepEqual(admission.series[1].data, [0, 34, 54]);
    assert.equal(admission.tooltipNote, ADMITTED_NOTE);
    assert.equal(ADMITTED_NOTE, '입장: 번호표를 받은 뒤 떠난 사람도 포함된 추정치');
    assert.deepEqual([admission.current, admission.currentUnit], ['0.2', '명/초']);
    // 세로 점선 · x축은 다른 Prometheus 그래프와 같은 규칙(같은 격자 · 같은 점선)
    const progress = views.find((v) => v.key === 'progress')!;
    assert.deepEqual(admission.x, progress.x);
    assert.deepEqual(admission.markers, progress.markers);
    assert.deepEqual(admission.markers.map((m) => m.label), ['시작', '이벤트 종료']);
    assert.equal(admission.empty, false);
    assert.equal(chartViews({ ...charts, admission: { admittedPerSec: [null, null, null], cursorPerSec: [null, null, null] } }, markers, false).find((v) => v.key === 'admission')!.empty, true);
    // 다른 그래프에는 점선 시리즈 · 툴팁 안내가 없다
    for (const v of views.filter((x) => x.key !== 'admission')) {
      assert.ok(v.series.every((s) => !s.dashed), v.key);
      assert.equal(v.tooltipNote, undefined, v.key);
    }
    assert.equal(tooltipValue(0.17, '명/초'), '0.17 명/초');
    assert.equal(tooltipValue(0.5, '번호/초'), '0.5 번호/초');
    assert.equal(tooltipValue(1131.24, 'RPS'), '1,131.24 RPS');
    assert.equal(tooltipValue(null, 'RPS'), EMPTY);
    assert.equal(tooltipValue(0), '0');
  });
  test('세로 점선: 이벤트 종료 · 호스팅 종료도 범위 안이면', () => {
    const views = chartViews(charts, { ...markers, eventEndAt: '2026-10-26T14:50:45+09:00', hostingEndAt: '2026-10-26T14:51:00+09:00' }, false);
    assert.deepEqual(views[0].markers.map((m) => m.label), ['시작', '이벤트 종료', '호스팅 종료']);
    // 범위 밖(진행 중의 이벤트 종료 예정)은 그리지 않는다
    assert.deepEqual(chartViews(charts, { ...markers, eventEndAt: '2026-10-26T16:00:00+09:00' }, false)[0].markers.map((m) => m.label), ['시작']);
  });
  describe('세로 점선 이름: 겹치면 단을 엇갈린다(테스트 13 시각)', () => {
    const t = (hm: string) => Date.parse(`2026-10-28T${hm}:00+09:00`);
    // 입장 14:55 · 시작 15:00 · 재고 소진 15:01 · 이벤트 종료 15:10 · 호스팅 종료 15:15, 순서를 섞어 넣는다
    const markers = [
      { x: t('15:15'), label: '호스팅 종료' },
      { x: t('14:55'), label: '입장' },
      { x: t('15:00'), label: '시작' },
      { x: t('15:01'), label: '재고 소진' },
      { x: t('15:10'), label: '이벤트 종료' },
    ];
    const layout = (from: string, to: string, width: number) =>
      markerLabels(markers.filter((m) => m.x >= t(from) && m.x <= t(to)), t(from), t(to), width).map((m) => (m.label ? `${m.label}@${m.level}${m.side === 'left' ? '←' : ''}` : ''));

    test('전체 범위(입장 −10분 ~ 호스팅 종료 +10분): 세 칸 · 두 칸 모두 5개가 보인다', () => {
      // 1440px 창의 세 칸(326px): 입장↔시작(5분)과 재고 소진↔이벤트 종료(9분)가 겹쳐 두 단
      assert.deepEqual(layout('14:45', '15:25', 326), ['입장@0', '시작@1', '재고 소진@0', '이벤트 종료@1', '호스팅 종료@0']);
      // 두 칸(507px): 시작↔재고 소진(1분), 이벤트 종료↔호스팅 종료(5분)만 겹친다
      assert.deepEqual(layout('14:45', '15:25', 507), ['입장@0', '시작@0', '재고 소진@1', '이벤트 종료@0', '호스팅 종료@1']);
    });
    test('오른쪽 끝을 넘으면 선 왼쪽에 붙인다', () => {
      // 시작 ±10분: 이벤트 종료가 오른쪽 끝
      assert.deepEqual(layout('14:50', '15:10', 326), ['입장@0', '시작@0', '재고 소진@1', '이벤트 종료@0←']);
      // 진행 중(15:12): 끝 2분 전의 이벤트 종료는 왼쪽에 붙어 세 번째 단까지 내려간다
      assert.deepEqual(layout('14:45', '15:12', 326), ['입장@0', '시작@0', '재고 소진@1', '이벤트 종료@2←']);
    });
    test('세 단에도 못 놓으면 이름만 비운다(선 · 순서는 그대로)', () => {
      // 1280px 창의 세 칸(278px)
      assert.deepEqual(layout('14:45', '15:25', 278), ['입장@0', '시작@1', '재고 소진@2', '이벤트 종료@0', '']);
      const labels = markerLabels(markers, t('14:45'), t('15:25'), 278);
      assert.deepEqual(labels.map((m) => m.x), [t('14:55'), t('15:00'), t('15:01'), t('15:10'), t('15:15')]);
      assert.equal(MARKER_MAX_LEVELS, 3);
    });
    test('넓으면 모두 맨 위 단', () => {
      assert.ok(markerLabels(markers, t('14:45'), t('15:25'), 1600).every((m) => m.label && m.level === 0 && m.side === 'right'));
    });
  });
  test('x축 눈금: 폭에 맞춰 눈금 사이 56px 이상, 1분 이상 간격, KST 정각 단위', () => {
    const from = Date.parse('2026-10-28T09:45:00+09:00');
    const min = 60_000;
    const clock = (ticks: number[]) => ticks.map((t) => new Date(t + 9 * 3600_000).toISOString().slice(11, 16));
    // 3칸 줄(약 280px): 8분 범위도 1분 눈금 대신 2분(겹침 '09:4509:46…' 방지), 짝수 분에 맞춘다
    assert.deepEqual(clock(timeTicks(from, from + 8 * min, 280)), ['09:46', '09:48', '09:50', '09:52']);
    // 넓은 그래프(약 520px)는 더 촘촘히
    assert.deepEqual(clock(timeTicks(from, from + 8 * min, 520)).length, 9);
    // 50분(입장 −10분 ~ 호스팅 종료 +10분): 280px면 15분 간격
    assert.deepEqual(clock(timeTicks(from + 30_000, from + 50 * min, 280)), ['10:00', '10:15', '10:30']);
    for (const width of [240, 280, 330, 520, 900]) {
      const ticks = timeTicks(from, from + 21 * min, width);
      const gap = ((width - Y_AXIS_WIDTH) * (ticks[1] - ticks[0])) / (21 * min);
      assert.ok(gap >= X_TICK_MIN_PX, `${width}px: ${gap}`);
      assert.ok(ticks.every((t) => (t + 9 * 3600_000) % min === 0));
    }
    // 폭을 모르면 300px로, 범위가 없으면 그 점 하나
    assert.deepEqual(timeTicks(from, from + 21 * min, 0), timeTicks(from, from + 21 * min, 300));
    assert.deepEqual(timeTicks(from, from, 300), [from]);
    assert.deepEqual(timeTicks(Number.NaN, from, 300), []);
  });
  test('세로 점선은 범위 안의 것만 · 값이 없으면 empty', () => {
    const traffic = chartViews(charts, markers, false)[0];
    assert.deepEqual(traffic.markers, [{ x: Date.parse('2026-10-26T14:50:30+09:00'), label: '시작' }]);
    assert.equal(traffic.current, '1,131');
    const empty = chartViews({ ...charts, latency: { issuance: [null, null, null], ticket: [null, null, null] }, rds: { cpu: [], connections: [] } }, markers, false);
    assert.equal(empty.find((v) => v.key === 'latency')!.empty, true);
    assert.equal(empty.find((v) => v.key === 'rds')!.empty, true);
    assert.equal(empty.find((v) => v.key === 'rds')!.current, EMPTY);
  });
  test('기준선이 값보다 위면 축을 올려 선이 보이게', () => {
    const views = chartViews(charts, markers, false);
    assert.equal(views.find((v) => v.key === 'latency')!.yMax, 575);
    assert.equal(views.find((v) => v.key === 'pods')!.yMax, 14);
    assert.equal(views.find((v) => v.key === 'errors')!.yMax, 2);
    // 기준선이 없거나 값이 더 크면 자동
    assert.equal(views.find((v) => v.key === 'traffic')!.yMax, undefined);
    assert.equal(views.find((v) => v.key === 'redis')!.yMax, 100);
    assert.equal(yMaxFor([{ id: 'a', label: 'a', role: 'primary', data: [900] }], [{ y: 500 }]), undefined);
  });
  test('5xx 기준: KPI 문구와 에러 그래프 기준선이 같은 상수(주의 1% · 위험 2%)', () => {
    assert.equal(ERROR_RATIO_WARN, 0.01);
    assert.equal(ERROR_RATIO_DANGER, 0.02);
    assert.equal(ERROR_RULE_TEXT, '주의 1% · 위험 2%');
    const errors = chartViews(charts, markers, false).find((v) => v.key === 'errors')!;
    assert.deepEqual(errors.thresholds, [{ y: ERROR_RATIO_WARN * 100, label: '주의 기준 1%' }]);
    assert.equal(liveKpiGroups({ level: 'OK', reason: null, warn: [], live: null, summary: null }, 'LIVE')[0].tiles[1].sub, ERROR_RULE_TEXT);
  });
  test('mergeOnTime', () => {
    assert.deepEqual(mergeOnTime([1, 2], [[10, null]], [[[2, 5], [3, 6]]]), { x: [1, 2, 3], prom: [[10, null, null]], aws: [[null, 5, 6]] });
  });
});
