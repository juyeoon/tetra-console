import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import type { NotificationItem } from '../../api/types.ts';
import { priorityMeta, priorityOf } from '../../utils/status.ts';
import { countByFilter, matchesFilter, visibleNotifications, withAllRead, withRead } from './notificationView.ts';

const item = (notificationId: number, priority: string, read: boolean, occurredAt: string, content = `알림 ${notificationId}`): NotificationItem => ({
  notificationId,
  tenantId: null,
  tenantName: null,
  eventId: null,
  category: 'HOSTING',
  priority,
  occurredAt,
  content,
  read,
});

const ITEMS = [
  item(1, 'URGENT', false, '2026-10-21T10:00:00+09:00', '[A사] 배포에 실패했습니다'),
  item(2, 'WARNING', true, '2026-10-21T11:00:00+09:00', '[A사] 신청을 반려했습니다'),
  item(3, 'INFO', false, '2026-10-21T12:00:00+09:00', '[B사] Deploy 완료'),
  item(4, 'INFO', true, '2026-10-21T12:00:00+09:00', '테넌트 무관 알림'),
  item(5, 'SOMETHING', false, '2026-10-21T09:00:00+09:00', '모르는 우선순위'),
];

describe('알림센터 필터 · 건수', () => {
  test('탭 5종 건수는 전체 목록 기준, 모르는 우선순위는 정보', () => {
    assert.deepEqual(countByFilter(ITEMS), { ALL: 5, URGENT: 1, WARNING: 1, INFO: 3, UNREAD: 3 });
    assert.deepEqual(countByFilter([]), { ALL: 0, URGENT: 0, WARNING: 0, INFO: 0, UNREAD: 0 });
  });

  test('안읽음 탭은 read=false만', () => {
    assert.deepEqual(ITEMS.filter((n) => matchesFilter(n, 'UNREAD')).map((n) => n.notificationId), [1, 3, 5]);
  });
});

describe('보이는 목록', () => {
  test('최신순, 같은 시각은 나중 알림 먼저', () => {
    assert.deepEqual(visibleNotifications(ITEMS, 'ALL', '').map((n) => n.notificationId), [4, 3, 2, 1, 5]);
  });

  test('검색은 내용만, 앞뒤 공백 · 대소문자 무시, 탭과 함께 적용', () => {
    assert.deepEqual(visibleNotifications(ITEMS, 'ALL', '  a사 ').map((n) => n.notificationId), [2, 1]);
    assert.deepEqual(visibleNotifications(ITEMS, 'ALL', 'deploy').map((n) => n.notificationId), [3]);
    assert.deepEqual(visibleNotifications(ITEMS, 'UNREAD', 'A사').map((n) => n.notificationId), [1]);
    assert.deepEqual(visibleNotifications(ITEMS, 'URGENT', '없는 말'), []);
  });

  test('원본 배열은 바꾸지 않는다', () => {
    const before = ITEMS.map((n) => n.notificationId);
    visibleNotifications(ITEMS, 'ALL', '');
    assert.deepEqual(ITEMS.map((n) => n.notificationId), before);
  });
});

describe('읽음 처리', () => {
  test('한 건만 바꾸고 되돌릴 수 있다', () => {
    const read = withRead(ITEMS, 1, true);
    assert.equal(read.find((n) => n.notificationId === 1)?.read, true);
    assert.deepEqual(read.filter((n) => n.notificationId !== 1), ITEMS.filter((n) => n.notificationId !== 1));
    assert.equal(withRead(read, 1, false).find((n) => n.notificationId === 1)?.read, false);
  });

  test('모두 읽음 → 안읽음 0', () => {
    assert.equal(countByFilter(withAllRead(ITEMS)).UNREAD, 0);
    assert.equal(countByFilter(withAllRead(ITEMS)).ALL, 5);
  });
});

describe('우선순위 표시', () => {
  test('긴급 빨강 · 경고 주황 · 정보 파랑, 모르는 값은 정보', () => {
    assert.deepEqual([priorityOf('URGENT'), priorityOf('WARNING'), priorityOf('INFO'), priorityOf('X')], ['URGENT', 'WARNING', 'INFO', 'INFO']);
    assert.deepEqual(
      (['URGENT', 'WARNING', 'INFO'] as const).map((p) => [priorityMeta[p].label, priorityMeta[p].tone]),
      [['긴급', 'red'], ['경고', 'amber'], ['정보', 'blue']],
    );
  });
});
