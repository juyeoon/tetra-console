import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import type { EventDetailResponse, EventResultResponse } from '../../api/types.ts';
import { processedAtOf, rejectReasonOf, statusHistoryView, toEventView, toResultView } from './eventView.ts';

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
    const v = toEventView(DETAIL);
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
    // 상태 처리 기록은 세 종류 전부, 순서 그대로
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

  test('상태 처리 기록 한 줄: 구분 · 배지 · 시각 · 처리자 · 원인(실패 강조)', () => {
    assert.deepEqual(statusHistoryView(APPROVED_LOGS[5]), {
      at: '2026.10.17 10:00',
      typeLabel: '호스팅',
      status: { label: '배포 실패', tone: 'red' },
      actor: '자동 처리',
      detail: 'TIMED_OUT: EventAppSyncTimedOut',
      detailLabel: '원인',
      failure: true,
    });
    const rejected = statusHistoryView(LOGS[2]);
    assert.equal(rejected.actor, '운영자 홍길동');
    assert.equal(rejected.detailLabel, '반려 사유');
    assert.equal(rejected.failure, false);
    assert.equal(statusHistoryView(LOGS[0]).actor, '테넌트 A사');
    assert.equal(statusHistoryView(APPROVED_LOGS[4]).typeLabel, '이벤트');
    assert.equal(statusHistoryView(APPROVED_LOGS[6]).failure, false);
  });

  test('모르는 상태 값은 원래 값을 회색으로', () => {
    assert.deepEqual(statusHistoryView({ ...APPROVED_LOGS[3], newStatus: 'SOMETHING_NEW' }).status, { label: 'SOMETHING_NEW', tone: 'grey' });
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
      peakAt: '2026.10.21 17:01',
      planCapacity: 10000,
      perMinute: [{ minute: '17:00', issued: 500, soldOut: 0 }],
    });
  });

  test('READY인데 결과가 없으면 집계 중으로', () => {
    assert.deepEqual(toResultView({ ...RESULT_BASE, state: 'READY' }, new Date()).body, { state: 'AGGREGATING' });
  });
});
