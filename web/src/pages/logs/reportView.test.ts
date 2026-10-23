import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import type { ReportItem } from '../../api/types.ts';
import { emailSentMessage, toReportRow } from './reportView.ts';

const report = (periodStart: string, periodEnd: string): ReportItem => ({
  reportId: 3,
  name: '2026년 9월 운영 리포트',
  category: 'MONTHLY',
  periodStart,
  periodEnd,
  createdAt: '2026-10-01T10:00:00+09:00',
});

describe('리포트 표 한 줄', () => {
  test('기간은 날짜만, 같은 해면 끝 날짜의 연도 생략 · 생성일은 날짜', () => {
    assert.deepEqual(toReportRow(report('2026-09-01T00:00:00+09:00', '2026-09-30T23:59:59+09:00')), {
      id: 3,
      name: '2026년 9월 운영 리포트',
      period: '2026.09.01 ~ 09.30',
      createdAt: '2026.10.01',
    });
  });

  test('해가 바뀌면 끝 날짜도 연도 포함, KST 기준', () => {
    assert.equal(toReportRow(report('2025-12-01T00:00:00+09:00', '2026-01-31T23:59:59+09:00')).period, '2025.12.01 ~ 2026.01.31');
    // UTC로 온 값도 KST 날짜로 (2026-09-30T15:00Z = 10-01 00:00 KST)
    assert.equal(toReportRow(report('2026-09-30T15:00:00Z', '2026-10-31T14:59:59Z')).period, '2026.10.01 ~ 10.31');
  });
});

describe('이메일 전송 토스트', () => {
  test('받는 운영자 이메일과 리포트명', () => {
    assert.equal(emailSentMessage('operator@tetra-operation.local', '9월 리포트'), 'operator@tetra-operation.local로 "9월 리포트" 리포트 안내 메일을 보냈습니다.');
  });
});
