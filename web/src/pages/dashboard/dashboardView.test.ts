import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import { formatRelative, formatTime } from '../../utils/datetime.ts';
import { alertView } from './dashboardView.ts';

const NOW = new Date('2026-10-21T18:00:00+09:00');

const notification = (priority: string, occurredAt: string) => ({
  notificationId: 7,
  tenantId: 'k7f2m9qx4b1t',
  eventId: 1043,
  category: 'APPLICATION',
  priority,
  occurredAt,
  content: 'A사 이벤트 신청이 접수되었습니다.',
  read: false,
});

describe('대시보드 최근 알림', () => {
  test('priority → 우선순위(모르는 값은 INFO), content → 제목, occurredAt → 경과', () => {
    assert.deepEqual(alertView(notification('URGENT', '2026-10-21T17:58:00+09:00'), NOW), {
      id: 7,
      title: 'A사 이벤트 신청이 접수되었습니다.',
      ago: '2분 전',
      priority: 'URGENT',
    });
    assert.equal(alertView(notification('WARNING', '2026-10-21T15:00:00+09:00'), NOW).priority, 'WARNING');
    assert.equal(alertView(notification('INFO', '2026-10-21T15:00:00+09:00'), NOW).priority, 'INFO');
    assert.equal(alertView(notification('UNKNOWN', '2026-10-21T15:00:00+09:00'), NOW).priority, 'INFO');
  });
});

describe('formatRelative · formatTime', () => {
  test('방금 · 분 · 시간 · 일 · 오래된 날짜', () => {
    assert.equal(formatRelative('2026-10-21T17:59:30+09:00', NOW), '방금 전');
    assert.equal(formatRelative('2026-10-21T18:05:00+09:00', NOW), '방금 전');
    assert.equal(formatRelative('2026-10-21T17:18:00+09:00', NOW), '42분 전');
    assert.equal(formatRelative('2026-10-21T15:00:00+09:00', NOW), '3시간 전');
    assert.equal(formatRelative('2026-10-19T18:00:00+09:00', NOW), '2일 전');
    assert.equal(formatRelative('2026-10-01T18:00:00+09:00', NOW), '2026.10.01');
    assert.equal(formatRelative(null, NOW), '-');
  });

  test('분당 축 HH:mm (KST)', () => {
    assert.equal(formatTime('2026-10-21T08:05:00Z'), '17:05');
    assert.equal(formatTime(null), '-');
  });
});
