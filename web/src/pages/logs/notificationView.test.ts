import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import type { NotificationChip, NotificationItem, NotificationState } from '../../api/types.ts';
import { priorityMeta, priorityOf } from '../../utils/status.ts';
import {
  NOTIFICATION_CHIPS,
  NOTIFICATION_FILTERS,
  countByChip,
  countByFilter,
  itemHref,
  matchesFilter,
  pressItem,
  readOptimistically,
  splitContent,
  stateLabel,
  stateView,
  visibleNotifications,
  withAllRead,
  withRead,
} from './notificationView.ts';

const item = (
  notificationId: number,
  priority: string,
  read: boolean,
  occurredAt: string,
  content = `알림 ${notificationId}`,
  chip: NotificationChip | null = 'EVENT',
  link: string | null = null,
  state: NotificationState | null = null,
): NotificationItem => ({
  notificationId,
  tenantId: null,
  tenantName: null,
  eventId: null,
  category: 'HOSTING',
  priority,
  occurredAt,
  content,
  read,
  chip,
  link,
  state,
});

const ITEMS = [
  item(1, 'URGENT', false, '2026-10-21T10:00:00+09:00', '[A사] 배포에 실패했습니다', 'EVENT'),
  item(2, 'WARNING', true, '2026-10-21T11:00:00+09:00', '[A사] 신청을 반려했습니다', 'APPLICATION'),
  item(3, 'INFO', false, '2026-10-21T12:00:00+09:00', '[B사] Deploy 완료', 'EVENT'),
  item(4, 'INFO', true, '2026-10-21T12:00:00+09:00', '테넌트 무관 알림', 'SYSTEM'),
  item(5, 'SOMETHING', false, '2026-10-21T09:00:00+09:00', '모르는 우선순위', null),
  item(6, 'URGENT', false, '2026-10-21T08:00:00+09:00', '[B사] 환경 준비에 실패했습니다', 'TENANT'),
];

describe('알림센터 탭 · 건수', () => {
  test('탭 5종 건수는 전체 목록 기준, 모르는 우선순위는 정보', () => {
    assert.deepEqual(countByFilter(ITEMS), { ALL: 6, URGENT: 2, WARNING: 1, INFO: 3, UNREAD: 4 });
    assert.deepEqual(countByFilter([]), { ALL: 0, URGENT: 0, WARNING: 0, INFO: 0, UNREAD: 0 });
  });

  test('안읽음 탭은 read=false만', () => {
    assert.deepEqual(ITEMS.filter((n) => matchesFilter(n, 'UNREAD')).map((n) => n.notificationId), [1, 3, 5, 6]);
  });

  test("우선순위 탭 이름은 긴급 · 주의 · 정보", () => {
    assert.deepEqual(NOTIFICATION_FILTERS.map((f) => f.label), ['전체보기', '긴급', '주의', '정보', '안읽음']);
  });
});

describe('칩 필터 · 건수', () => {
  test('칩은 전체 + 신청 · 이벤트 · 테넌트 · 리포트 · 시스템', () => {
    assert.deepEqual(NOTIFICATION_CHIPS.map((c) => c.label), ['전체', '신청', '이벤트', '테넌트', '리포트', '시스템']);
  });

  test('칩 건수는 지금 탭 기준(탭과 AND), 칩이 없는 알림은 전체에만', () => {
    assert.deepEqual(countByChip(ITEMS, 'ALL'), { ALL: 6, APPLICATION: 1, EVENT: 2, TENANT: 1, REPORT: 0, SYSTEM: 1 });
    assert.deepEqual(countByChip(ITEMS, 'URGENT'), { ALL: 2, APPLICATION: 0, EVENT: 1, TENANT: 1, REPORT: 0, SYSTEM: 0 });
    assert.deepEqual(countByChip(ITEMS, 'UNREAD'), { ALL: 4, APPLICATION: 0, EVENT: 2, TENANT: 1, REPORT: 0, SYSTEM: 0 });
  });

  test('보이는 목록은 탭 AND 칩 AND 검색', () => {
    assert.deepEqual(visibleNotifications(ITEMS, 'ALL', 'EVENT', '').map((n) => n.notificationId), [3, 1]);
    assert.deepEqual(visibleNotifications(ITEMS, 'URGENT', 'EVENT', '').map((n) => n.notificationId), [1]);
    assert.deepEqual(visibleNotifications(ITEMS, 'UNREAD', 'APPLICATION', ''), []);
    assert.deepEqual(visibleNotifications(ITEMS, 'ALL', 'TENANT', '환경').map((n) => n.notificationId), [6]);
    assert.deepEqual(visibleNotifications(ITEMS, 'ALL', 'EVENT', 'a사').map((n) => n.notificationId), [1]);
  });
});

describe('보이는 목록', () => {
  test('최신순, 같은 시각은 나중 알림 먼저', () => {
    assert.deepEqual(visibleNotifications(ITEMS, 'ALL', 'ALL', '').map((n) => n.notificationId), [4, 3, 2, 1, 5, 6]);
  });

  test('검색은 내용만, 앞뒤 공백 · 대소문자 무시, 탭과 함께 적용', () => {
    assert.deepEqual(visibleNotifications(ITEMS, 'ALL', 'ALL', '  a사 ').map((n) => n.notificationId), [2, 1]);
    assert.deepEqual(visibleNotifications(ITEMS, 'ALL', 'ALL', 'deploy').map((n) => n.notificationId), [3]);
    assert.deepEqual(visibleNotifications(ITEMS, 'UNREAD', 'ALL', 'A사').map((n) => n.notificationId), [1]);
    assert.deepEqual(visibleNotifications(ITEMS, 'URGENT', 'ALL', '없는 말'), []);
  });

  test('원본 배열은 바꾸지 않는다', () => {
    const before = ITEMS.map((n) => n.notificationId);
    visibleNotifications(ITEMS, 'ALL', 'ALL', '');
    assert.deepEqual(ITEMS.map((n) => n.notificationId), before);
  });
});

describe('항목 문구', () => {
  test('첫 문장은 첫 줄, 나머지는 둘째 줄', () => {
    assert.deepEqual(splitContent('[A사] [1043:봄 쿠폰] 이벤트 배포에 실패했습니다. 확인이 필요합니다. 원인: StartExecution 실패'), {
      head: '[A사] [1043:봄 쿠폰] 이벤트 배포에 실패했습니다.',
      rest: '확인이 필요합니다. 원인: StartExecution 실패',
    });
    // 문장이 하나면 나머지 없음, 소수점 · 시각은 나누지 않는다
    assert.deepEqual(splitContent('[A사] [1043:쿠폰 2.0] 입장이 열렸습니다(10:00).'), { head: '[A사] [1043:쿠폰 2.0] 입장이 열렸습니다(10:00).', rest: '' });
    assert.deepEqual(splitContent('끝 문장 부호 없음'), { head: '끝 문장 부호 없음', rest: '' });
  });
});

describe('현재 상태', () => {
  test('기존 상태 라벨을 쓴다', () => {
    assert.equal(stateLabel({ kind: 'APPLICATION_STATUS', value: 'REJECTED', alert: false }), '반려됨');
    assert.equal(stateLabel({ kind: 'HOSTING_STATUS', value: 'DEPLOY_FAILED', alert: true }), '배포 실패');
    assert.equal(stateLabel({ kind: 'EVENT_STATUS', value: 'OPEN', alert: false }), '진행 중');
    assert.equal(stateLabel({ kind: 'ACCOUNT_STATUS', value: 'INACTIVE', alert: false }), '비활성');
    assert.equal(stateLabel({ kind: 'PROVISIONING_STATUS', value: 'FAILED', alert: true }), '환경 준비 실패');
    assert.equal(stateLabel({ kind: 'PROVISIONING_STATUS', value: 'READY', alert: false }), '환경 준비 완료');
    assert.equal(stateLabel({ kind: 'PASSWORD_SETUP', value: 'SET', alert: false }), '설정 완료');
    assert.equal(stateLabel({ kind: 'PASSWORD_SETUP', value: 'NOT_SET', alert: false }), '비밀번호 미설정');
    assert.equal(stateLabel({ kind: 'DLQ_MESSAGES', value: '1200', alert: true }), 'DLQ 메시지 1,200건');
    assert.equal(stateLabel({ kind: 'DLQ_MESSAGES', value: '0', alert: false }), 'DLQ 메시지 0건');
  });

  test('모르는 값은 DB 값 그대로', () => {
    assert.equal(stateLabel({ kind: 'HOSTING_STATUS', value: 'PAUSED', alert: false }), 'PAUSED');
    assert.equal(stateLabel({ kind: 'SOMETHING', value: 'X', alert: false }), 'X');
  });

  test("'현재: 라벨', alert면 빨강", () => {
    assert.deepEqual(stateView({ kind: 'HOSTING_STATUS', value: 'END_FAILED', alert: true }), { text: '현재: 종료 실패', tone: 'red' });
    assert.deepEqual(stateView({ kind: 'HOSTING_STATUS', value: 'ENDED', alert: false }), { text: '현재: 호스팅 종료', tone: null });
    assert.equal(stateView(null), null);
  });
});

describe('링크 · 읽음', () => {
  test('href는 서버 경로 그대로, 앱 밖 주소는 링크로 만들지 않는다', () => {
    const at = '2026-10-21T10:00:00+09:00';
    assert.equal(itemHref(item(1, 'URGENT', false, at, 'x', 'EVENT', '/monitoring/traffic?eventId=1043')), '/monitoring/traffic?eventId=1043');
    assert.equal(itemHref(item(1, 'URGENT', false, at, 'x', 'EVENT', '/events/progress/1043')), '/events/progress/1043');
    assert.equal(itemHref(item(1, 'URGENT', false, at, 'x', 'TENANT', null)), null);
    assert.equal(itemHref(item(1, 'URGENT', false, at, 'x', 'TENANT', 'https://example.com')), null);
    assert.equal(itemHref(item(1, 'URGENT', false, at, 'x', 'TENANT', '//example.com')), null);
  });

  test('클릭 · 가운데 클릭은 안 읽은 것만 읽음, 오른쪽 클릭은 아님', () => {
    const calls: number[] = [];
    const onRead = (id: number) => calls.push(id);
    const unread = ITEMS[0];
    pressItem(unread, { button: 0 }, onRead);
    pressItem(unread, { button: 1 }, onRead);
    pressItem(unread, { button: 2 }, onRead);
    pressItem(ITEMS[1], { button: 0 }, onRead); // 이미 읽음
    pressItem(ITEMS[1], { button: 1 }, onRead);
    assert.deepEqual(calls, [1, 1]);
  });

  test('읽음이 실패해도 이동은 막지 않고, 되돌린 뒤 토스트', async () => {
    let items = ITEMS;
    const errors: unknown[] = [];
    let pending: Promise<void> | undefined;
    const event = { button: 0, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } };

    pressItem(ITEMS[0], event, (id) => {
      pending = readOptimistically(id, (change) => { items = change(items); }, () => Promise.reject(new Error('네트워크 오류')), (e) => errors.push(e));
    });

    // 누른 즉시: 이동(기본 동작)을 막지 않고 화면은 먼저 읽음
    assert.equal(event.defaultPrevented, false);
    assert.equal(items.find((n) => n.notificationId === 1)?.read, true);
    await pending;
    assert.equal(items.find((n) => n.notificationId === 1)?.read, false);
    assert.equal(errors.length, 1);
  });

  test('읽음이 성공하면 그대로 읽음', async () => {
    let items = ITEMS;
    await readOptimistically(1, (change) => { items = change(items); }, async () => {}, () => assert.fail('토스트 없음'));
    assert.equal(items.find((n) => n.notificationId === 1)?.read, true);
  });

  test('한 건만 바꾸고 되돌릴 수 있다', () => {
    const read = withRead(ITEMS, 1, true);
    assert.equal(read.find((n) => n.notificationId === 1)?.read, true);
    assert.deepEqual(read.filter((n) => n.notificationId !== 1), ITEMS.filter((n) => n.notificationId !== 1));
    assert.equal(withRead(read, 1, false).find((n) => n.notificationId === 1)?.read, false);
  });

  test('모두 읽음 → 안읽음 0', () => {
    assert.equal(countByFilter(withAllRead(ITEMS)).UNREAD, 0);
    assert.equal(countByFilter(withAllRead(ITEMS)).ALL, 6);
  });
});

describe('우선순위 표시', () => {
  test('긴급 빨강 · 주의 주황 · 정보 파랑, 모르는 값은 정보', () => {
    assert.deepEqual([priorityOf('URGENT'), priorityOf('WARNING'), priorityOf('INFO'), priorityOf('X')], ['URGENT', 'WARNING', 'INFO', 'INFO']);
    assert.deepEqual(
      (['URGENT', 'WARNING', 'INFO'] as const).map((p) => [priorityMeta[p].label, priorityMeta[p].tone]),
      [['긴급', 'red'], ['주의', 'amber'], ['정보', 'blue']],
    );
  });
});
