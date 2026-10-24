import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import type { EventDetailResponse, EventResultResponse, StatusType } from '../../api/types.ts';
import {
  clipDetail,
  DETAIL_PREVIEW_LENGTH,
  ENTRY_OPEN_LEAD_MINUTES,
  entryOpenAtOf,
  PEAK_CONCURRENT_HELP,
  processedAtOf,
  rejectReasonOf,
  SESSION_TTL_HOURS,
  STATUS_LOG_TEXT,
  statusLogView,
  timelineOf,
  toEventView,
  toResultView,
} from './eventView.ts';

const SYSTEM = { type: 'SYSTEM', name: null } as const;

const LOGS: EventDetailResponse['statusLogs'] = [
  { changedAt: '2026-10-18T18:09:11+09:00', statusType: 'APPLICATION', prevStatus: null, newStatus: 'APPLYING', detail: null, actor: { type: 'TENANT', name: 'A사' } },
  { changedAt: '2026-10-18T18:09:12+09:00', statusType: 'APPLICATION', prevStatus: 'APPLYING', newStatus: 'APPLIED', detail: null, actor: SYSTEM },
  { changedAt: '2026-10-19T18:09:11+09:00', statusType: 'APPLICATION', prevStatus: 'APPLIED', newStatus: 'REJECTED', detail: '배너 이미지가 정책에 맞지 않습니다.', actor: { type: 'OPERATOR', name: '홍길동' } },
];

/** 승인 → 호스팅 배포 실패(원인) → 재실행으로 배포 완료 (dev 2-14와 같은 흐름) */
const APPROVED_LOGS: EventDetailResponse['statusLogs'] = [
  { changedAt: '2026-10-16T18:09:11+09:00', statusType: 'APPLICATION', prevStatus: null, newStatus: 'APPLYING', detail: null, actor: { type: 'TENANT', name: 'A사' } },
  { changedAt: '2026-10-16T18:09:12+09:00', statusType: 'APPLICATION', prevStatus: 'APPLYING', newStatus: 'APPLIED', detail: null, actor: SYSTEM },
  { changedAt: '2026-10-17T09:30:00+09:00', statusType: 'APPLICATION', prevStatus: 'APPLIED', newStatus: 'APPROVED', detail: null, actor: { type: 'OPERATOR', name: '홍길동' } },
  { changedAt: '2026-10-17T09:30:00+09:00', statusType: 'HOSTING', prevStatus: null, newStatus: 'PENDING', detail: null, actor: SYSTEM },
  { changedAt: '2026-10-17T09:30:00+09:00', statusType: 'EVENT', prevStatus: null, newStatus: 'SCHEDULED', detail: null, actor: SYSTEM },
  { changedAt: '2026-10-17T10:00:00+09:00', statusType: 'HOSTING', prevStatus: 'PENDING', newStatus: 'DEPLOY_FAILED', detail: 'TIMED_OUT: EventAppSyncTimedOut', actor: SYSTEM },
  { changedAt: '2026-10-17T11:00:00+09:00', statusType: 'HOSTING', prevStatus: 'DEPLOY_FAILED', newStatus: 'DEPLOYED', detail: null, actor: SYSTEM },
];

const DETAIL: EventDetailResponse = {
  basic: {
    eventId: 1044,
    name: '9월 얼리버드 쿠폰',
    eventType: 'TIMESALE',
    tenantId: 'k7f2m9qx4b1t',
    tenantName: 'A사',
    hostingStartAt: '2026-10-21T16:09:11+09:00',
    hostingEndAt: '2026-10-21T23:09:11+09:00',
    startAt: '2026-10-21T17:09:11+09:00',
    endAt: '2026-10-21T20:09:11+09:00',
    trafficPlanCode: 'XL',
    stockCount: 3000,
    appliedAt: '2026-10-16T18:09:11+09:00',
    endpointUrl: 'https://shop.a-corp.com/early',
  },
  status: { applicationStatus: 'APPROVED', hostingStatus: 'DEPLOYED', eventStatus: 'SCHEDULED', resultAvailable: false },
  resources: { templateId: 1, domain: 'k7f2q9xa.k7f2m9qx4b1t', fullDomain: 'k7f2q9xa.k7f2m9qx4b1t.tetraops.cloud', publicKey: 'PEM' },
  statusLogs: APPROVED_LOGS,
};

describe('이벤트 상세 → 화면 모델', () => {
  test('기간 · 시각은 KST 표시 문자열, 할당 도메인은 fullDomain(플랫폼 도메인) 그대로', () => {
    const v = toEventView(DETAIL, new Date('2026-10-21T12:00:00+09:00'));
    assert.deepEqual(v.hostingPeriod, { start: '2026.10.21 16:09', end: '2026.10.21 23:09' });
    assert.deepEqual(v.eventPeriod, { start: '2026.10.21 17:09', end: '2026.10.21 20:09' });
    assert.equal(v.appliedAt, '2026.10.16 18:09');
    assert.equal(v.domain, 'k7f2q9xa.k7f2m9qx4b1t.tetraops.cloud');
    assert.equal(v.type, 'TIMESALE');
    assert.equal(v.plan, 'XL');
    assert.equal(v.quantity, 3000);
    // 처리 일시는 신청 승인 시각 — 그 뒤 호스팅 로그(배포 실패 · 완료)에 끌려가지 않는다
    assert.equal(v.processedAt, '2026.10.17 09:30');
    assert.equal(v.rejectReason, null);
    // 상태 로그는 세 종류 전부, 순서 그대로
    assert.deepEqual(
      v.logs.map((l) => `${l.typeLabel}:${l.status.label}`),
      ['신청:신청됨(처리 중)', '신청:승인 대기 중', '신청:승인됨', '호스팅:배포 대기', '이벤트:오픈 예정', '호스팅:배포 실패', '호스팅:배포 완료'],
    );
  });

  test('반려 사유 · 처리 일시는 신청 로그에서만 꺼낸다(호스팅 원인과 섞이지 않게)', () => {
    assert.equal(rejectReasonOf(LOGS), '배너 이미지가 정책에 맞지 않습니다.');
    assert.equal(processedAtOf(LOGS), '2026-10-19T18:09:11+09:00');
    assert.equal(rejectReasonOf(LOGS.slice(0, 2)), null);
    assert.equal(processedAtOf(LOGS.slice(0, 2)), null);
    // 호스팅 실패 원인(detail)은 반려 사유가 아니다
    assert.equal(rejectReasonOf(APPROVED_LOGS), null);
    assert.equal(processedAtOf(APPROVED_LOGS), '2026-10-17T09:30:00+09:00');
  });

  test('상태 로그 한 칸: 구분 라벨 · 단계 이름 · 배지 · 설명 · 시각 · 처리자 · 원인(실패 강조)', () => {
    assert.deepEqual(statusLogView(APPROVED_LOGS[5]), {
      at: '2026.10.17 10:00',
      actor: '자동 처리',
      typeLabel: '호스팅',
      title: '배포 실패',
      status: { label: '배포 실패', tone: 'red' },
      description: '배포에 실패했습니다.',
      detail: { label: '원인', text: 'TIMED_OUT: EventAppSyncTimedOut', preview: 'TIMED_OUT: EventAppSyncTimedOut', clipped: false, failure: true },
      planned: false,
    });
    const pending = statusLogView(APPROVED_LOGS[3]);
    assert.equal(pending.title, '배포 시작');
    assert.equal(pending.description, '승인과 함께 이벤트 페이지 배포를 시작했습니다.');
    assert.equal(pending.detail, null);
    assert.equal(statusLogView(APPROVED_LOGS[6]).description, '이벤트 페이지와 리소스 배포가 끝났습니다.');
    // 신청 항목은 기존 문구 그대로
    assert.equal(statusLogView(LOGS[0]).title, '신청 접수');
    assert.equal(statusLogView(LOGS[0]).actor, '테넌트 A사');
    const rejected = statusLogView(LOGS[2]);
    assert.equal(rejected.actor, '운영자 홍길동');
    assert.equal(rejected.description, '운영자가 신청을 반려했습니다.');
    assert.deepEqual(rejected.detail, { label: '반려 사유', text: '배너 이미지가 정책에 맞지 않습니다.', preview: '배너 이미지가 정책에 맞지 않습니다.', clipped: false, failure: false });
    assert.equal(statusLogView(APPROVED_LOGS[4]).typeLabel, '이벤트');
  });

  test('모든 상태 값에 단계 이름 · 설명이 있다(신청 5 · 호스팅 7 · 이벤트 4)', () => {
    const all: [StatusType, string[]][] = [
      ['APPLICATION', ['APPLYING', 'APPLIED', 'APPLY_FAILED', 'APPROVED', 'REJECTED']],
      ['HOSTING', ['PENDING', 'DEPLOYED', 'DEPLOY_FAILED', 'HOSTING', 'HOSTING_FAILED', 'ENDED', 'END_FAILED']],
      ['EVENT', ['SCHEDULED', 'OPEN', 'OPEN_FAILED', 'EXPIRED']],
    ];
    for (const [type, statuses] of all) {
      assert.deepEqual(Object.keys(STATUS_LOG_TEXT[type]).sort(), [...statuses].sort(), type);
      for (const status of statuses) {
        const text = STATUS_LOG_TEXT[type][status];
        assert.ok(text.title && text.description.endsWith('다.'), `${type}.${status}`);
      }
    }
  });

  test('호스팅 · 이벤트 문구: 입장(T-60)과 발급(시작 시각)을 구분', () => {
    assert.deepEqual(STATUS_LOG_TEXT.HOSTING.HOSTING, {
      title: '호스팅 시작',
      description: '호스팅 시작 시각이 되어 이벤트 페이지(대기방)를 열었습니다.',
    });
    assert.deepEqual(STATUS_LOG_TEXT.EVENT.SCHEDULED, { title: '오픈 예약', description: '승인되어 이벤트 일정에 맞춰 오픈이 예약되었습니다.' });
    assert.deepEqual(STATUS_LOG_TEXT.EVENT.OPEN, {
      title: '입장 시작',
      description: '입장이 열려 대기열을 받고 있습니다. 쿠폰 발급은 이벤트 시작 시각부터입니다.',
    });
    assert.equal(STATUS_LOG_TEXT.EVENT.OPEN_FAILED.description, '이벤트 준비(입장 열기)에 실패했습니다.');
    assert.equal(STATUS_LOG_TEXT.EVENT.EXPIRED.description, '이벤트가 종료되어 발급이 마감되었습니다.');
    // 배지는 기존 표기 그대로(단계 이름만 바뀜)
    assert.deepEqual(statusLogView(APPROVED_LOGS[4]).status, { label: '오픈 예정', tone: 'blue' });
    assert.equal(statusLogView(APPROVED_LOGS[4]).title, '오픈 예약');
  });

  test('긴 원인은 말줄임(펼치기용 전체 문구는 그대로)', () => {
    const long = 'TIMED_OUT: ' + '가'.repeat(80);
    const item = statusLogView({ ...APPROVED_LOGS[5], detail: long });
    assert.equal(item.detail?.clipped, true);
    assert.equal(item.detail?.text, long);
    assert.equal(Array.from(item.detail?.preview ?? '').length, DETAIL_PREVIEW_LENGTH + 1);
    assert.ok(item.detail?.preview.endsWith('…'));
    assert.deepEqual(clipDetail('짧은 원인'), { preview: '짧은 원인', clipped: false });
  });

  test('모르는 상태 값은 원래 값을 회색 배지 · 단계 이름으로', () => {
    const item = statusLogView({ ...APPROVED_LOGS[3], newStatus: 'SOMETHING_NEW' });
    assert.deepEqual(item.status, { label: 'SOMETHING_NEW', tone: 'grey' });
    assert.equal(item.title, 'SOMETHING_NEW');
  });
});

const RESULT_BASE: EventResultResponse = {
  eventId: 1044,
  state: 'IN_PROGRESS',
  eventOpenAt: '2026-10-21T17:00:00+09:00',
  aggregatedAt: null,
  hostingStatus: 'HOSTING',
  eventStatus: 'OPEN',
  endAt: '2026-10-21T20:00:00+09:00',
  failure: null,
  result: null,
};

describe('진행 결과: API state → 화면 상태', () => {
  test('IN_PROGRESS는 오픈 전(NONE) / 진행 중(ONGOING)을 지금 시각으로 나눈다', () => {
    assert.deepEqual(toResultView(RESULT_BASE, new Date('2026-10-21T16:59:00+09:00')).body, { state: 'NONE' });
    assert.deepEqual(toResultView(RESULT_BASE, new Date('2026-10-21T17:00:00+09:00')).body, { state: 'ONGOING' });
    const v = toResultView(RESULT_BASE, new Date('2026-10-21T18:00:00+09:00'));
    assert.equal(v.openAt, '2026.10.21 17:00');
    assert.equal(v.endAt, '2026.10.21 20:00');
    assert.equal(v.csvReady, false);
  });

  test('AGGREGATING · END_FAILED', () => {
    assert.deepEqual(toResultView({ ...RESULT_BASE, state: 'AGGREGATING' }, new Date()).body, { state: 'AGGREGATING' });
    const failed = toResultView(
      { ...RESULT_BASE, state: 'END_FAILED', hostingStatus: 'END_FAILED', failure: { cause: 'Lambda timeout', occurredAt: '2026-10-21T20:05:00+09:00' } },
      new Date(),
    );
    assert.deepEqual(failed.body, { state: 'FAILED', cause: 'Lambda timeout', occurredAt: '2026.10.21 20:05' });
    assert.equal(failed.csvReady, false);
  });

  test('READY: 지표 이름을 화면 이름으로, 분당 축은 HH:mm, CSV 가능', () => {
    const v = toResultView(
      {
        ...RESULT_BASE,
        state: 'READY',
        aggregatedAt: '2026-10-21T20:30:00+09:00',
        result: {
          metrics: {
            successCount: 900,
            totalStock: 1000,
            selloutRate: 0.9,
            soldoutCount: 120,
            peakConcurrent: 4200,
            maxCapacity: 10000,
            capacityUsageRate: 0.42,
            peakAt: '2026-10-21T17:01:00+09:00',
          },
          composition: { successCount: 900, soldoutCount: 120, abandonedCount: 30 },
          perMinute: [{ minute: '2026-10-21T17:00:00+09:00', successCount: 500, soldoutCount: 0 }],
        },
      },
      new Date(),
    );
    assert.equal(v.csvReady, true);
    assert.deepEqual(v.body, {
      state: 'DONE',
      aggregatedAt: '2026.10.21 20:30',
      issued: 900,
      stock: 1000,
      soldOut: 120,
      dropped: 30,
      peakConcurrent: 4200,
      peakSub: '사용률 42.0% · 2026.10.21 17:01',
      planCapacity: 10000,
      perMinute: [{ minute: '17:00', issued: 500, soldOut: 0 }],
    });
  });

  test('최대 동시 접속자: 보조 문구는 시각이 없으면 -, 툴팁의 세션 유효 시간은 상수', () => {
    assert.equal(SESSION_TTL_HOURS, 2);
    assert.equal(PEAK_CONCURRENT_HELP, '입장 후 2시간 동안 유효한 세션 수의 최댓값입니다. 창을 닫아도 세션이 끝날 때까지 포함됩니다.');
    const v = toResultView(
      {
        ...RESULT_BASE,
        state: 'READY',
        aggregatedAt: '2026-10-21T20:30:00+09:00',
        result: {
          metrics: { successCount: 0, totalStock: 10, selloutRate: 0, soldoutCount: 0, peakConcurrent: 0, maxCapacity: 30000, capacityUsageRate: 0, peakAt: null },
          composition: { successCount: 0, soldoutCount: 0, abandonedCount: 0 },
          perMinute: [],
        },
      },
      new Date(),
    );
    assert.equal(v.body.state === 'DONE' && v.body.peakSub, '사용률 0.0% · -');
  });

  test('READY인데 결과가 없으면 집계 중으로', () => {
    assert.deepEqual(toResultView({ ...RESULT_BASE, state: 'READY' }, new Date()).body, { state: 'AGGREGATING' });
  });
});

/** 이벤트 시작 13:00 → 입장 12:00. 승인 · 배포 · 호스팅 시작(11:00) · 입장 시작(12:00) */
const START_AT = '2026-10-24T13:00:00+09:00';
const OPENED_LOGS: EventDetailResponse['statusLogs'] = [
  { changedAt: '2026-10-23T09:00:00+09:00', statusType: 'APPLICATION', prevStatus: 'APPLIED', newStatus: 'APPROVED', detail: null, actor: { type: 'OPERATOR', name: '홍길동' } },
  { changedAt: '2026-10-23T09:00:00+09:00', statusType: 'EVENT', prevStatus: null, newStatus: 'SCHEDULED', detail: null, actor: SYSTEM },
  { changedAt: '2026-10-24T11:00:00+09:00', statusType: 'HOSTING', prevStatus: 'DEPLOYED', newStatus: 'HOSTING', detail: null, actor: SYSTEM },
  { changedAt: '2026-10-24T12:00:30+09:00', statusType: 'EVENT', prevStatus: 'SCHEDULED', newStatus: 'OPEN', detail: null, actor: SYSTEM },
];
const ENDED_LOGS: EventDetailResponse['statusLogs'] = [
  ...OPENED_LOGS,
  { changedAt: '2026-10-24T18:00:00+09:00', statusType: 'HOSTING', prevStatus: 'HOSTING', newStatus: 'ENDED', detail: null, actor: SYSTEM },
  { changedAt: '2026-10-24T18:00:00+09:00', statusType: 'EVENT', prevStatus: 'OPEN', newStatus: 'EXPIRED', detail: null, actor: SYSTEM },
];

const titles = (logs: EventDetailResponse['statusLogs'], now: string) =>
  timelineOf(logs, START_AT, new Date(now)).map((l) => l.title);

describe('상태 로그: 이벤트 시작 항목 · 입장 시각', () => {
  test('시작 전: 입장 시작 다음에 예정(회색 점 · 예정 칩 · 예정 시각 · 처리자 "예정")', () => {
    const items = timelineOf(OPENED_LOGS, START_AT, new Date('2026-10-24T12:59:59+09:00'));
    assert.deepEqual(items.map((l) => l.title), ['운영자 승인', '오픈 예약', '호스팅 시작', '입장 시작', '이벤트 시작']);
    assert.deepEqual(items[4], {
      at: '2026.10.24 13:00',
      actor: '예정',
      typeLabel: '이벤트',
      title: '이벤트 시작',
      status: { label: '예정', tone: 'grey' },
      description: '이벤트 시작 시각에 쿠폰 발급이 시작됩니다.',
      detail: null,
      planned: true,
    });
  });

  test('시작 시각부터: 일반 점 · 진행 중 칩 · 발급 시작 문구 · 처리자 "자동 처리"', () => {
    const item = timelineOf(OPENED_LOGS, START_AT, new Date(START_AT)).at(-1);
    assert.deepEqual(item, {
      at: '2026.10.24 13:00',
      actor: '자동 처리',
      typeLabel: '이벤트',
      title: '이벤트 시작',
      status: { label: '진행 중', tone: 'green' },
      description: '쿠폰 발급이 시작되었습니다.',
      detail: null,
      planned: false,
    });
  });

  test('시각순: 끝난 이벤트는 입장 시작과 호스팅 종료 · 만료 사이', () => {
    assert.deepEqual(titles(ENDED_LOGS, '2026-10-25T09:00:00+09:00'), [
      '운영자 승인',
      '오픈 예약',
      '호스팅 시작',
      '입장 시작',
      '이벤트 시작',
      '호스팅 종료',
      '이벤트 만료',
    ]);
  });

  test('입장이 열린 적 없으면(OPEN 기록 없음) 넣지 않는다', () => {
    const now = '2026-10-25T09:00:00+09:00';
    // 오픈 예약까지만(입장 전) · 반려 · 배포 실패 · 입장 열기 실패
    assert.ok(!titles(OPENED_LOGS.slice(0, 3), now).includes('이벤트 시작'));
    assert.ok(!titles(LOGS, now).includes('이벤트 시작'));
    assert.ok(!titles(APPROVED_LOGS.slice(0, 6), now).includes('이벤트 시작'));
    const openFailed: EventDetailResponse['statusLogs'] = [
      ...OPENED_LOGS.slice(0, 3),
      { changedAt: '2026-10-24T12:05:00+09:00', statusType: 'EVENT', prevStatus: 'SCHEDULED', newStatus: 'OPEN_FAILED', detail: 'States.Timeout', actor: SYSTEM },
    ];
    assert.ok(!titles(openFailed, now).includes('이벤트 시작'));
    // 입장 열기 실패 뒤 재실행으로 열렸으면 넣는다
    const rerun: EventDetailResponse['statusLogs'] = [
      ...openFailed,
      { changedAt: '2026-10-24T12:20:00+09:00', statusType: 'EVENT', prevStatus: 'OPEN_FAILED', newStatus: 'OPEN', detail: null, actor: SYSTEM },
    ];
    assert.equal(titles(rerun, now).at(-1), '이벤트 시작');
  });

  test('호스팅 시작 설명에 실제 입장 시각(시작 − 60분, MM.DD HH:mm)', () => {
    assert.equal(ENTRY_OPEN_LEAD_MINUTES, 60);
    const hosting = timelineOf(OPENED_LOGS, START_AT, new Date(START_AT))[2];
    assert.equal(hosting.description, '호스팅 시작 시각이 되어 이벤트 페이지(대기방)를 열었습니다. 입장은 10.24 12:00에 열립니다.');
    // 자정을 넘으면 전날 날짜
    assert.equal(
      statusLogView(OPENED_LOGS[2], '2026-10-25T00:30:00+09:00').description,
      '호스팅 시작 시각이 되어 이벤트 페이지(대기방)를 열었습니다. 입장은 10.24 23:30에 열립니다.',
    );
    assert.equal(entryOpenAtOf('2026-10-25T00:30:00+09:00'), '2026-10-24T14:30:00.000Z');
    // 다른 항목은 시작 시각을 받아도 그대로
    assert.equal(statusLogView(OPENED_LOGS[3], START_AT).description, STATUS_LOG_TEXT.EVENT.OPEN.description);
  });

  test('상세 화면 모델(toEventView)에도 같은 규칙', () => {
    const v = toEventView({ ...DETAIL, basic: { ...DETAIL.basic, startAt: START_AT }, statusLogs: OPENED_LOGS }, new Date('2026-10-24T12:30:00+09:00'));
    assert.equal(v.logs.at(-1)?.title, '이벤트 시작');
    assert.equal(v.logs.at(-1)?.planned, true);
  });
});
