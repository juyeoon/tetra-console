import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import type { EventExecution, EventExecutionsResponse, ExecutionState, TrafficStep } from '../../api/types.ts';
import {
  EXECUTION_REFRESH_GRACE_MS,
  EXECUTIONS_FAILED_TEXT,
  executionPanel,
  executionStepsKey,
  executionView,
  hasRunningExecution,
  stateRow,
  stopExecutionPolling,
} from './executionView.ts';

const NOW = new Date('2026-10-28T07:30:00Z'); // 16:30 KST

const ok = { asOf: '2026-10-28T16:30:00+09:00', stale: false, staleReason: null } as const;

function state(partial: Partial<ExecutionState> & Pick<ExecutionState, 'names'>): ExecutionState {
  return { kind: 'TASK', count: 1, startedAt: '2026-10-28T14:45:37+09:00', endedAt: '2026-10-28T14:45:39+09:00', durationSeconds: 2, retries: 0, status: 'SUCCEEDED', ...partial };
}

/** approve-9: Parallel 가지 안 앱 확인 20회 뒤 EventAppSyncTimedOut(dev 2026-10-27) */
const approve9: EventExecution = {
  flow: 'DEPLOY',
  workflow: 'WF1-2',
  name: 'approve-9',
  attempt: 1,
  status: 'FAILED',
  startedAt: '2026-10-27T11:09:40+09:00',
  stoppedAt: '2026-10-27T11:20:03+09:00',
  durationSeconds: 622,
  redriveCount: 0,
  failure: {
    state: 'IsEventAppReady',
    error: 'EventAppSyncTimedOut',
    reason: 'EventAppSyncTimedOut · 앱 event-9 상태 WAITING (sync=OutOfSync, health=Suspended, …',
    cause: 'IsEventAppReady: 앱 event-9 상태 WAITING (sync=OutOfSync, health=Suspended), 확인 20회 (10분 초과)',
  },
  states: [
    state({ kind: 'PARALLEL', names: ['PrepareEventAssets'], startedAt: '2026-10-27T11:09:40+09:00', endedAt: '2026-10-27T11:20:03+09:00', durationSeconds: 622, status: 'FAILED' }),
    state({ names: ['CopyEventTemplate'], retries: 1 }),
    state({ kind: 'LOOP', names: ['WaitEventAppSync', 'CheckEventAppStatus', 'IsEventAppReady'], count: 20, startedAt: '2026-10-27T11:09:44+09:00', endedAt: '2026-10-27T11:20:03+09:00', durationSeconds: 619, status: 'FAILED' }),
  ],
  truncated: false,
};

/** 이벤트 13 WF2-2: 앱 Ready 확인 6회 · 입장 시각까지 대기 */
const prepare13: EventExecution = {
  flow: 'PREPARE',
  workflow: 'WF2-2',
  name: '0d6ae18b-dcc7-42e0-9c36-4879312be520',
  attempt: 1,
  status: 'SUCCEEDED',
  startedAt: '2026-10-28T14:45:37+09:00',
  stoppedAt: '2026-10-28T14:55:24+09:00',
  durationSeconds: 587,
  redriveCount: 0,
  failure: null,
  states: [
    state({ kind: 'LOOP', names: ['WaitEventReady', 'CheckEventApp', 'IsEventAppReady'], count: 6, startedAt: '2026-10-28T14:45:41+09:00', endedAt: '2026-10-28T14:48:46+09:00', durationSeconds: 184 }),
    state({ kind: 'WAIT', names: ['WaitEntryOpen'], startedAt: '2026-10-28T14:49:04+09:00', endedAt: '2026-10-28T14:55:00+09:00', durationSeconds: 356 }),
  ],
  truncated: false,
};

const response = (executions: EventExecution[], aws: EventExecutionsResponse['aws'] = ok): EventExecutionsResponse => ({ aws, eventId: 13, executions });

describe('자동화 실행 상세', () => {
  test('흐름 4칸은 실행이 없어도 자리를 둔다(흐름 순)', () => {
    const panel = executionPanel(response([prepare13, approve9]), false, NOW);
    assert.deepEqual(
      panel.flows.map((f) => [f.title, f.executions.map((e) => e.name)]),
      [
        ['배포 · WF1-2', ['approve-9']],
        ['호스팅 시작 · WF2-1', []],
        ['준비 · WF2-2', ['0d6ae18b-dcc7-42e0-9c36-4879312be520']],
        ['호스팅 종료 · WF4', []],
      ],
    );
    assert.equal(panel.notice, null);
  });

  test('실패 실행: 실패 단계 · 오류 · 요약 · 원문, 처음부터 펼침', () => {
    const v = executionView(approve9, NOW);
    assert.deepEqual(v.status, { label: '실패', tone: 'red' });
    assert.equal(v.time, '10-27 11:09~10-27 11:20 · 10분 22초');
    assert.equal(v.failure?.title, '실패 단계 IsEventAppReady · EventAppSyncTimedOut');
    assert.match(v.failure?.reason ?? '', /^EventAppSyncTimedOut · 앱 event-9/);
    assert.match(v.failure?.cause ?? '', /확인 20회/);
    assert.equal(v.defaultOpen, true);
    const loop = v.states[2];
    assert.equal(loop.name, 'WaitEventAppSync → CheckEventAppStatus → IsEventAppReady');
    assert.equal(loop.badge, '반복 20회');
    assert.equal(loop.time, '11:09:44~11:20:03');
    assert.equal(loop.duration, '10분 19초');
    assert.equal(loop.failed, true);
    assert.equal(v.states[0].badge, '병렬');
    assert.equal(v.states[1].retries, '재시도 1회');
  });

  test('성공 실행: 반복 횟수 · 대기 · 접힘', () => {
    const v = executionView(prepare13, NOW);
    assert.equal(v.time, '14:45~14:55 · 9분 47초');
    assert.equal(v.failure, null);
    assert.equal(v.defaultOpen, false);
    assert.deepEqual(
      v.states.map((s) => [s.badge, s.time, s.duration]),
      [
        ['반복 6회', '14:45:41~14:48:46', '3분 4초'],
        ['대기', '14:49:04~14:55:00', '5분 56초'],
      ],
    );
  });

  test('재실행 · 이어서 실행 표시', () => {
    const v = executionView({ ...approve9, name: 'approve-9-r1', attempt: 2, redriveCount: 1, status: 'SUCCEEDED', failure: null }, NOW);
    assert.deepEqual(v.badges, ['재실행 1', '이어서 1회']);
  });

  test('실행 중: 지금까지 걸린 시간 · 진행 중 단계, 실행 중일 때만 다시 부름', () => {
    const running: EventExecution = {
      ...prepare13,
      status: 'RUNNING',
      stoppedAt: null,
      durationSeconds: 200,
      states: [state({ kind: 'LOOP', names: ['WaitEventReady', 'CheckEventApp', 'IsEventAppReady'], count: 3, endedAt: null, durationSeconds: 95, status: 'RUNNING' })],
    };
    const v = executionView(running, NOW);
    assert.deepEqual(v.status, { label: '실행 중', tone: 'blue' });
    assert.equal(v.time, '14:45 시작 · 3분 20초째');
    assert.equal(v.states[0].time, '14:45:37~진행 중');
    assert.equal(v.states[0].running, true);
    assert.equal(v.defaultOpen, true);

    assert.equal(hasRunningExecution(response([running, approve9])), true);
    // 단계가 바뀐 적 없음(기한 0)
    assert.equal(stopExecutionPolling(response([running]), NOW.getTime(), 0), false);
    assert.equal(stopExecutionPolling(response([prepare13, approve9]), NOW.getTime(), 0), true);
    assert.equal(stopExecutionPolling(response([{ ...approve9, status: 'PENDING_REDRIVE' }]), NOW.getTime(), 0), false);
  });

  test('시간 초과 · 실패 단계를 모르면', () => {
    const v = executionView({ ...approve9, status: 'TIMED_OUT', failure: { state: null, error: null, reason: null, cause: null } }, NOW);
    assert.equal(v.failure?.title, '시간 초과 · 단계 확인 불가');
    assert.equal(v.failure?.cause, null);
  });

  test('지연 · 불러오기 실패 안내', () => {
    assert.deepEqual(executionPanel(undefined, false, NOW), { notice: null, flows: [] });
    assert.deepEqual(executionPanel(undefined, true, NOW).notice, { text: EXECUTIONS_FAILED_TEXT, tone: 'red' });
    const stale = executionPanel(response([approve9], { asOf: '2026-10-28T16:29:10+09:00', stale: true, staleReason: 'SOURCE' }), false, NOW);
    assert.deepEqual(stale.notice, { text: '지연 · 마지막 정상 16:29:10', tone: 'amber' });
    assert.equal(stale.flows[0].executions.length, 1);
    const none = executionPanel(response([], { asOf: null, stale: true, staleReason: 'SOURCE' }), false, NOW);
    assert.deepEqual(none.notice, { text: EXECUTIONS_FAILED_TEXT, tone: 'red' });
  });

  test('단계 줄: Map 항목 횟수 · 시각 없음', () => {
    assert.equal(stateRow(state({ names: ['CreateEventSchedule'], count: 3 }), 0).badge, '3회');
    assert.equal(stateRow(state({ kind: 'MAP', names: ['CreateSchedules'] }), 0).badge, '항목별');
    assert.equal(stateRow(state({ names: ['ScaleUpEvent'] }), 0).badge, null);
    const unknown = stateRow(state({ names: ['X'], startedAt: null, endedAt: null, durationSeconds: null, status: 'FAILED' }), 0);
    assert.equal(unknown.time, '–~–');
    assert.equal(unknown.duration, '–');
  });
});

function step(key: TrafficStep['key'], state: TrafficStep['state'], actualAt: string | null = null, extra: Partial<TrafficStep> = {}): TrafficStep {
  return { key, plannedAt: null, actualAt, endAt: null, durationSeconds: null, state, reason: null, ...extra };
}

/** 이벤트 13 호스팅 시작 뒤 · 준비 계획 시각 전(WF2-1 끝 → WF2-2 시작 전, 실행 중 없음) */
const beforePrepare: TrafficStep[] = [
  step('APPROVE', 'DONE', '2026-10-28T14:20:00+09:00'),
  step('DEPLOY', 'DONE', '2026-10-28T14:28:00+09:00'),
  step('HOSTING_START', 'DONE', '2026-10-28T14:36:00+09:00'),
  step('PREPARE', 'WAITING'),
  step('ENTRY', 'WAITING'),
  step('START', 'WAITING'),
  step('END', 'WAITING'),
  step('AGGREGATE', 'WAITING'),
];

const replace = (steps: TrafficStep[], next: TrafficStep) => steps.map((s) => (s.key === next.key ? next : s));

describe('진행 단계가 바뀌면 자동화 실행 상세를 다시 부른다', () => {
  test('흐름 4칸의 key:state:actualAt만', () => {
    assert.equal(
      executionStepsKey(beforePrepare),
      'DEPLOY:DONE:2026-10-28T14:28:00+09:00,HOSTING_START:DONE:2026-10-28T14:36:00+09:00,PREPARE:WAITING:,END:WAITING:',
    );
    assert.equal(executionStepsKey(undefined), '');
  });

  test('단계 변화 → 키가 바뀜(시작 · 끝 · 재실행 재실패)', () => {
    const base = executionStepsKey(beforePrepare);
    // 준비 계획 시각(WF2-2 시작)
    const preparing = replace(beforePrepare, step('PREPARE', 'ACTIVE'));
    assert.notEqual(executionStepsKey(preparing), base);
    // WF2-2 성공(OPEN 로그)
    const prepared = replace(preparing, step('PREPARE', 'DONE', '2026-10-28T14:55:24+09:00'));
    assert.notEqual(executionStepsKey(prepared), executionStepsKey(preparing));
    // 준비 실패 뒤 수동 재실행이 또 실패: FAILED 그대로, 실패 시각만 바뀜
    const failed = replace(beforePrepare, step('PREPARE', 'FAILED', '2026-10-28T14:55:00+09:00'));
    const failedAgain = replace(beforePrepare, step('PREPARE', 'FAILED', '2026-10-28T15:20:00+09:00'));
    assert.notEqual(executionStepsKey(failedAgain), executionStepsKey(failed));
    // 호스팅 종료 시각(WF4 시작)
    assert.notEqual(executionStepsKey(replace(beforePrepare, step('END', 'ACTIVE'))), base);
  });

  test('변화 없음 → 키가 같음(새 배열 · 흐름 밖 칸 · 사유 · 걸린 시간)', () => {
    const base = executionStepsKey(beforePrepare);
    assert.equal(executionStepsKey(beforePrepare.map((s) => ({ ...s }))), base);
    // 입장 · 시작 · 집계 · 승인 칸은 실행과 무관
    assert.equal(executionStepsKey(replace(beforePrepare, step('ENTRY', 'ACTIVE', '2026-10-28T14:55:24+09:00'))), base);
    assert.equal(executionStepsKey(replace(beforePrepare, step('START', 'DONE', '2026-10-28T15:00:00+09:00'))), base);
    assert.equal(executionStepsKey(replace(beforePrepare, step('AGGREGATE', 'ACTIVE'))), base);
    assert.equal(executionStepsKey(replace(beforePrepare, step('APPROVE', 'DONE', null))), base);
    // 준비 걸린 시간(Prometheus) · 실패 사유가 늦게 채워져도
    const prepared = replace(beforePrepare, step('PREPARE', 'DONE', '2026-10-28T14:55:24+09:00'));
    const withDuration = replace(prepared, step('PREPARE', 'DONE', '2026-10-28T14:55:24+09:00', { durationSeconds: 200, reason: '사유' }));
    assert.equal(executionStepsKey(withDuration), executionStepsKey(prepared));
  });

  test('멈춤: 실행 중이 없고 단계가 바뀐 뒤 2분이 지났을 때만', () => {
    const changedAt = NOW.getTime();
    const until = changedAt + EXECUTION_REFRESH_GRACE_MS;
    assert.equal(EXECUTION_REFRESH_GRACE_MS, 120_000);
    const done = response([prepare13]);
    // 2분 안: 실행 중이 없어도 15초마다(새 실행이 서버 캐시 · 스케줄러 지연 뒤에 보임)
    assert.equal(stopExecutionPolling(response([]), changedAt, until), false);
    assert.equal(stopExecutionPolling(done, changedAt + 105_000, until), false);
    // 2분 지남
    assert.equal(stopExecutionPolling(done, until, until), true);
    assert.equal(stopExecutionPolling(response([]), until + 15_000, until), true);
    // 실행 중이면 2분이 지나도 15초 유지
    const running = response([{ ...prepare13, status: 'RUNNING', stoppedAt: null }]);
    assert.equal(stopExecutionPolling(running, until + 600_000, until), false);
    assert.equal(stopExecutionPolling(response([{ ...approve9, status: 'PENDING_REDRIVE' }]), until + 600_000, until), false);
  });
});
