import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import { formatDate, formatDateTime, formatMonthDayTime, formatMonthDayWeekday } from './datetime.ts';

describe('formatDateTime · formatDate (KST 고정)', () => {
  test('API의 +09:00 값을 그대로 표시', () => {
    assert.equal(formatDateTime('2026-10-20T18:09:11+09:00'), '2026.10.20 18:09');
    assert.equal(formatDate('2026-10-20T18:09:11+09:00'), '2026.10.20');
  });

  test('다른 오프셋이어도 KST로 바꿔 표시 (날짜가 넘어가는 경우 포함)', () => {
    assert.equal(formatDateTime('2026-10-20T15:30:00Z'), '2026.10.21 00:30');
    assert.equal(formatDate('2026-10-20T15:30:00Z'), '2026.10.21');
  });

  test('자정은 24시가 아니라 00시', () => {
    assert.equal(formatDateTime('2026-01-01T00:00:00+09:00'), '2026.01.01 00:00');
  });

  test('값이 없거나 형식이 틀리면 -', () => {
    for (const v of [null, undefined, '', 'not-a-date']) {
      assert.equal(formatDateTime(v), '-', String(v));
      assert.equal(formatDate(v), '-', String(v));
    }
  });
});

describe('formatMonthDayTime (KST, 연도 생략)', () => {
  test('MM.DD HH:mm, 다른 오프셋 · 자정 넘김도 KST로', () => {
    assert.equal(formatMonthDayTime('2026-10-24T12:00:00+09:00'), '10.24 12:00');
    assert.equal(formatMonthDayTime('2026-10-24T14:30:00.000Z'), '10.24 23:30');
    assert.equal(formatMonthDayTime('2026-10-24T15:30:00Z'), '10.25 00:30');
  });

  test('값이 없거나 틀리면 -', () => {
    assert.equal(formatMonthDayTime(null), '-');
    assert.equal(formatMonthDayTime('not-a-date'), '-');
  });
});

describe('formatMonthDayWeekday (KST 날짜 · 요일)', () => {
  test("'MM.DD (요일)' — ISO · epoch 모두, 다른 오프셋은 KST 날짜로", () => {
    assert.equal(formatMonthDayWeekday('2026-11-04T06:00:00+09:00'), '11.04 (수)');
    assert.equal(formatMonthDayWeekday(Date.parse('2026-11-08T23:59:00+09:00')), '11.08 (일)');
    // UTC 11-02 15:00 = KST 11-03 00:00(화)
    assert.equal(formatMonthDayWeekday('2026-11-02T15:00:00Z'), '11.03 (화)');
  });

  test('값이 없거나 틀리면 -', () => {
    assert.equal(formatMonthDayWeekday(null), '-');
    assert.equal(formatMonthDayWeekday('not-a-date'), '-');
  });
});
