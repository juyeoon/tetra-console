import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import type { MonitoringOverviewResponse } from '../../api/types.ts';
import { formatClock } from '../../utils/datetime.ts';
import {
  clusterView,
  EMPTY_TEXT,
  hasOpenEvents,
  inflowDots,
  legendLabel,
  MAX_DOTS,
  NO_DATA_TEXT,
  NO_EVENT_TEXT,
  openEventLabels,
  podCountText,
  splitDots,
  tenantFlowViews,
  trafficEmptyState,
  trafficView,
  updatedView,
} from './monitoringView.ts';

const T0 = Date.parse('2026-10-26T14:30:00+09:00');

const overview = (patch: Partial<MonitoringOverviewResponse> = {}): MonitoringOverviewResponse => ({
  asOf: '2026-10-26T15:00:07+09:00',
  stale: false,
  cluster: {
    cpuUsage: 0.4213,
    memoryUsage: 0.605,
    nodes: { app: 3, redis: 1, system: 2 },
    pods: { api: { ready: 5, total: 6 }, redis: { ready: 1, total: 1 } },
  },
  traffic: {
    rangeMinutes: 30,
    stepSeconds: 15,
    totalRps: 1131.2,
    series: [
      {
        tenantId: 'aaaaaaaaaaaa',
        tenantName: 'A사',
        events: [
          { eventId: 7, name: '가을 쿠폰', startAt: '2026-10-26T14:30:15+09:00' },
          { eventId: 8, name: '옛날 이벤트', startAt: '2026-10-26T09:00:00+09:00' },
        ],
        points: [
          [T0, null],
          [T0 + 15_000, 700],
          [T0 + 30_000, 706.1],
        ],
      },
      { tenantId: 'cccccccccccc', tenantName: null, events: [], points: [[T0 + 30_000, null]] },
    ],
  },
  tenants: [
    {
      tenantId: 'aaaaaaaaaaaa',
      tenantName: 'A사',
      namespace: 'tenant-aaaaaaaaaaaa',
      events: [{ eventId: 7, name: '가을 쿠폰', startAt: '2026-10-26T14:30:15+09:00' }],
      rps: 706.1,
      errorRps: 7.1,
      pods: { ready: 6, max: 12 },
      completed: 12345,
      failed: 8,
      since: '2026-10-26T13:30:15+09:00',
    },
  ],
  ...patch,
});

describe('갱신 표시', () => {
  test('asOf → KST HH:mm:ss, stale · 폴링 실패 · 데이터 없음이면 지연', () => {
    assert.deepEqual(updatedView(overview(), false), { asOf: '15:00:07', delayed: false });
    assert.equal(updatedView(overview({ stale: true }), false).delayed, true);
    assert.equal(updatedView(overview(), true).delayed, true);
    assert.deepEqual(updatedView(undefined, false), { asOf: EMPTY_TEXT, delayed: true });
    assert.equal(updatedView(overview({ asOf: null, stale: true }), false).asOf, EMPTY_TEXT);
    assert.equal(formatClock('2026-10-26T06:00:07Z'), '15:00:07');
  });
});

describe('클러스터 사용량', () => {
  test('% · 노드 · Pod Ready / 전체', () => {
    assert.deepEqual(clusterView(overview()), {
      cpu: '42%',
      memory: '61%',
      appNodes: '3',
      redisNodes: '1',
      systemNodes: '2',
      noEvent: false,
      apiPods: '5 / 6',
      redisPods: '1 / 1',
    });
  });

  test('OPEN 0건 → (진행 중 이벤트 없음)', () => {
    const idle = overview({ tenants: [] });
    idle.cluster = { ...idle.cluster, nodes: { app: 0, redis: 0, system: 2 }, pods: { api: { ready: 0, total: 0 }, redis: { ready: 0, total: 0 } } };
    const view = clusterView(idle);
    assert.equal(view.noEvent, true);
    assert.equal(view.apiPods, '0 / 0');
  });

  test('OPEN 있음 + app 노드 0 → 괄호 문구 없음(노드 수로 판단하지 않음)', () => {
    const o = overview();
    o.cluster = { ...o.cluster, nodes: { app: 0, redis: 0, system: 2 } };
    assert.equal(clusterView(o).noEvent, false);
    assert.equal(clusterView(o).appNodes, '0');
  });

  test('OPEN 0건이면 app 노드가 남아 있어도 (진행 중 이벤트 없음), 응답 전이면 표시 안 함', () => {
    assert.equal(clusterView(overview({ tenants: [] })).noEvent, true);
    assert.equal(clusterView(undefined).noEvent, false);
  });

  test('null은 – (0으로 바꾸지 않음), 데이터 없음도 –', () => {
    const empty = overview();
    empty.cluster = {
      cpuUsage: null,
      memoryUsage: null,
      nodes: { app: null, redis: null, system: null },
      pods: { api: { ready: null, total: null }, redis: { ready: 3, total: null } },
    };
    assert.deepEqual(clusterView(empty), {
      cpu: '–',
      memory: '–',
      appNodes: '–',
      redisNodes: '–',
      systemNodes: '–',
      noEvent: false,
      apiPods: '– / –',
      redisPods: '3 / –',
    });
    assert.equal(clusterView(undefined).cpu, '–');
  });
});

describe('실시간 트래픽', () => {
  test('합계 · 범례(테넌트 + 이벤트) · 현재 값(마지막 non-null) · 시각 격자', () => {
    const view = trafficView(overview());
    assert.equal(view.empty, null);
    assert.equal(view.total, '1,131.2');
    assert.deepEqual(view.timestamps, [T0, T0 + 15_000, T0 + 30_000]);
    assert.deepEqual(view.series[0], { id: 'aaaaaaaaaaaa', label: 'A사 · 가을 쿠폰, 옛날 이벤트', current: '706.1', data: [null, 700, 706.1] });
    // 이름이 없으면 tenant_id, 값이 전부 null이면 현재 값 –
    assert.deepEqual(view.series[1], { id: 'cccccccccccc', label: 'cccccccccccc', current: '–', data: [null, null, null] });
  });

  test('이벤트 시작 세로 점선은 그래프 범위 안의 것만', () => {
    assert.deepEqual(trafficView(overview()).eventStarts, [
      { key: 'aaaaaaaaaaaa-7', x: Date.parse('2026-10-26T14:30:15+09:00'), label: '가을 쿠폰 시작' },
    ]);
  });

  test('시리즈가 없으면 빈 상태(0 선 없음), 못 읽었으면 합계 –', () => {
    const none = overview({ tenants: [] });
    none.traffic = { ...none.traffic, totalRps: 0, series: [] };
    assert.deepEqual(trafficView(none), { empty: { text: '진행 중 이벤트 없음', events: [] }, total: '0', timestamps: [], series: [], eventStarts: [] });
    none.traffic.totalRps = null;
    assert.equal(trafficView(none).total, '–');
    assert.deepEqual(trafficView(undefined).empty, { text: NO_EVENT_TEXT, events: [] });
  });

  test('범례: 이벤트 없으면 이름만', () => {
    assert.equal(legendLabel({ tenantId: 'aaaaaaaaaaaa', tenantName: 'A사', events: [] }), 'A사');
  });
});

describe('테넌트 별 처리 현황', () => {
  test('행: 이름 · 네임스페이스(툴팁) · 원 안 Pod ready/max · 누적 숫자', () => {
    const [row] = tenantFlowViews(overview());
    assert.equal(row.name, 'A사');
    assert.equal(row.events, '가을 쿠폰');
    assert.equal(row.namespaceTitle, '네임스페이스 tenant-aaaaaaaaaaaa');
    assert.equal(row.podCount, '6/12');
    assert.equal(row.completed, '12,345');
    assert.equal(row.failed, '8');
  });

  test('null 수치는 – , HPA max를 모르면 ready만, 이름 없으면 tenant_id', () => {
    const o = overview();
    o.tenants = [{ ...o.tenants[0], tenantName: null, rps: null, errorRps: null, pods: { ready: null, max: null }, completed: null, failed: null }];
    const [row] = tenantFlowViews(o);
    assert.equal(row.name, 'aaaaaaaaaaaa');
    assert.equal(row.podCount, '–');
    assert.equal(row.completed, '–');
    assert.equal(row.failed, '–');
    assert.deepEqual(row.dots, { inflow: 0, completed: 0, failed: 0 });
  });

  test('원 안 Pod 숫자: ready/max 공백 없이 한 줄, max null이면 ready만', () => {
    assert.equal(podCountText(32, 32), '32/32');
    assert.equal(podCountText(0, 12), '0/12');
    assert.equal(podCountText(6, null), '6');
    assert.equal(podCountText(null, 12), '–/12');
    assert.equal(podCountText(null, null), '–');
  });

  test('OPEN 이벤트가 없으면 빈 목록', () => {
    assert.deepEqual(tenantFlowViews(overview({ tenants: [] })), []);
    assert.deepEqual(tenantFlowViews(undefined), []);
  });

  test('유입 점: rps 로그 비례 + 상한, 0 · null이면 없음', () => {
    assert.equal(inflowDots(null), 0);
    assert.equal(inflowDots(0), 0);
    assert.equal(inflowDots(0.2), 1);
    assert.equal(inflowDots(1), 1);
    assert.equal(inflowDots(10), 3);
    assert.equal(inflowDots(100), 5);
    assert.equal(inflowDots(1000), 7);
    assert.equal(inflowDots(1_000_000), MAX_DOTS);
  });

  test('완료 · 실패 점: 실시간 5xx 비율, errorRps > 0이면 실패 최소 1개', () => {
    assert.deepEqual(splitDots(7, 706.1, 0), { completed: 7, failed: 0 });
    assert.deepEqual(splitDots(7, 706.1, null), { completed: 7, failed: 0 });
    assert.deepEqual(splitDots(7, 706.1, 7.1), { completed: 6, failed: 1 });
    assert.deepEqual(splitDots(6, 100, 50), { completed: 3, failed: 3 });
    assert.deepEqual(splitDots(5, 100, 100), { completed: 0, failed: 5 });
    assert.deepEqual(splitDots(1, 10, 0.1), { completed: 0, failed: 1 });
    assert.deepEqual(splitDots(0, null, null), { completed: 0, failed: 0 });
    assert.deepEqual(tenantFlowViews(overview())[0].dots, { inflow: 7, completed: 6, failed: 1 });
  });
});

describe('진행 중 이벤트 판단 (tenants[].events, DB 기준)', () => {
  const noSeries = (o: MonitoringOverviewResponse) => ({ ...o, traffic: { ...o.traffic, totalRps: 0, series: [] } });

  test('hasOpenEvents: tenants[].events가 하나라도 있으면 true, 응답 전 · 빈 목록이면 false', () => {
    assert.equal(hasOpenEvents(overview()), true);
    assert.equal(hasOpenEvents(overview({ tenants: [] })), false);
    assert.equal(hasOpenEvents(undefined), false);
  });

  test('openEventLabels: 이벤트마다 "테넌트명 · 이벤트명", 이름 없으면 tenant_id', () => {
    const o = overview();
    o.tenants = [
      { ...o.tenants[0], events: [o.tenants[0].events[0], { eventId: 9, name: '겨울 쿠폰', startAt: '2026-10-26T14:40:00+09:00' }] },
      { ...o.tenants[0], tenantId: 'bbbbbbbbbbbb', tenantName: null, events: [{ eventId: 10, name: 'B 이벤트', startAt: '2026-10-26T14:00:00+09:00' }] },
    ];
    assert.deepEqual(openEventLabels(o), ['A사 · 가을 쿠폰', 'A사 · 겨울 쿠폰', 'bbbbbbbbbbbb · B 이벤트']);
  });

  test('트래픽 빈 상태: 시리즈 있음 → null', () => {
    assert.equal(trafficEmptyState(overview()), null);
    // OPEN이 없어도 시리즈가 있으면 그래프를 그린다
    assert.equal(trafficEmptyState(overview({ tenants: [] })), null);
  });

  test('트래픽 빈 상태: OPEN 0건 + 시리즈 없음 → 진행 중 이벤트 없음', () => {
    assert.deepEqual(trafficEmptyState(noSeries(overview({ tenants: [] }))), { text: NO_EVENT_TEXT, events: [] });
  });

  test('트래픽 빈 상태: OPEN 있음 + 시리즈 없음 → 아직 요청이 없는 진행 중 이벤트 + 이벤트 목록', () => {
    assert.equal(NO_DATA_TEXT, '아직 요청이 없는 진행 중 이벤트');
    assert.deepEqual(trafficEmptyState(noSeries(overview())), { text: '아직 요청이 없는 진행 중 이벤트', events: ['A사 · 가을 쿠폰'] });
    assert.deepEqual(trafficView(noSeries(overview())).empty, { text: '아직 요청이 없는 진행 중 이벤트', events: ['A사 · 가을 쿠폰'] });
  });
});
