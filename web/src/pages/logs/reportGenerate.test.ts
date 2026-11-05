import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import { ApiError, NETWORK_ERROR_MESSAGE } from '../../api/client.ts';
import {
  generateErrorMessage,
  generateFieldErrors,
  generatedMessage,
  kstToday,
  lastMonthRange,
  toGenerateRequest,
  validateGenerateForm,
  type GenerateForm,
} from './reportGenerate.ts';

const audit = (from: string, to: string): GenerateForm => ({ kind: 'OPERATOR_AUDIT', from, to, eventId: '' });
const event = (eventId: string): GenerateForm => ({ kind: 'EVENT_RESULT', from: '2026-10-01', to: '2026-10-31', eventId });

const TODAY = '2026-11-05';

describe('기본 기간 · 오늘(KST)', () => {
  test('오늘은 KST 날짜 — UTC로는 전날 15시여도 KST 다음 날', () => {
    assert.equal(kstToday(new Date('2026-11-04T15:00:00Z')), '2026-11-05');
    assert.equal(kstToday(new Date('2026-11-04T14:59:59Z')), '2026-11-04');
  });

  test('지난달 1일 ~ 말일, 1월이면 전년 12월, 윤년 2월', () => {
    assert.deepEqual(lastMonthRange(new Date('2026-11-05T01:00:00+09:00')), { from: '2026-10-01', to: '2026-10-31' });
    assert.deepEqual(lastMonthRange(new Date('2027-01-10T01:00:00+09:00')), { from: '2026-12-01', to: '2026-12-31' });
    assert.deepEqual(lastMonthRange(new Date('2028-03-01T00:30:00+09:00')), { from: '2028-02-01', to: '2028-02-29' });
    assert.deepEqual(lastMonthRange(new Date('2026-12-01T00:00:00+09:00')), { from: '2026-11-01', to: '2026-11-30' });
  });
});

describe('모달 입력 검사', () => {
  test('운영자 접속기록: 빈 값 · 형식 · 순서 · 미래 · 32일', () => {
    assert.deepEqual(validateGenerateForm(audit('', ''), TODAY), { from: '시작일을 입력해주세요.', to: '종료일을 입력해주세요.' });
    assert.deepEqual(validateGenerateForm(audit('2026-02-30', TODAY), TODAY), { from: '날짜 형식이 올바르지 않습니다.' });
    assert.deepEqual(validateGenerateForm(audit('2026-11-03', '2026-11-02'), TODAY), { to: '종료일은 시작일과 같거나 뒤여야 합니다.' });
    assert.deepEqual(validateGenerateForm(audit('2026-11-05', '2026-11-06'), TODAY), { to: '종료일은 오늘(2026-11-05)까지 고를 수 있습니다.' });
    // 2026-10-05 ~ 11-05 = 32일
    assert.deepEqual(validateGenerateForm(audit('2026-10-05', TODAY), TODAY), { to: '기간은 최대 31일입니다.' });
  });

  test('운영자 접속기록: 하루 · 오늘까지 · 31일은 통과', () => {
    assert.deepEqual(validateGenerateForm(audit(TODAY, TODAY), TODAY), {});
    assert.deepEqual(validateGenerateForm(audit('2026-10-06', TODAY), TODAY), {});
    assert.deepEqual(validateGenerateForm(audit('2026-10-01', '2026-10-31'), TODAY), {});
  });

  test('이벤트 결과: 이벤트 ID는 양의 정수만 — 날짜 칸은 보지 않는다', () => {
    assert.deepEqual(validateGenerateForm(event(' '), TODAY), { eventId: '이벤트 ID를 입력해주세요.' });
    for (const bad of ['abc', '12a', '-3', '0', '1.5', '1234567890123456']) {
      assert.deepEqual(validateGenerateForm(event(bad), TODAY), { eventId: '이벤트 ID는 숫자로 입력해주세요.' }, bad);
    }
    assert.deepEqual(validateGenerateForm(event(' 7101 '), TODAY), {});
    assert.deepEqual(validateGenerateForm({ ...event('7101'), from: '', to: '' }, TODAY), {});
  });
});

describe('요청 본문', () => {
  test('운영자 접속기록은 category · from · to만', () => {
    assert.deepEqual(toGenerateRequest({ kind: 'OPERATOR_AUDIT', from: '2026-11-01', to: '2026-11-05', eventId: '7101' }), {
      category: 'OPERATOR_AUDIT',
      from: '2026-11-01',
      to: '2026-11-05',
    });
  });

  test('이벤트 결과는 category · eventId(숫자)만', () => {
    assert.deepEqual(toGenerateRequest(event(' 7101 ')), { category: 'EVENT_RESULT', eventId: 7101 });
  });
});

describe('결과 문구', () => {
  test('오류 코드별 한글 문구', () => {
    const cases: [ApiError, string][] = [
      [new ApiError(409, 'REPORT_BUSY', '다른 리포트를 만들고 있습니다.'), '다른 리포트를 만드는 중입니다. 잠시 뒤 다시 시도하세요.'],
      [new ApiError(409, 'RESULT_NOT_READY', '이벤트 결과가 아직 없습니다.'), '이벤트 결과 집계가 아직 끝나지 않았습니다.'],
      [new ApiError(409, 'TENANT_NOT_READY', '테넌트 환경 준비가 끝나지 않았습니다.'), '테넌트 환경 준비가 끝나지 않아 만들 수 없습니다.'],
      [new ApiError(404, 'EVENT_NOT_FOUND', '이벤트를 찾을 수 없습니다.'), '이벤트를 찾을 수 없습니다.'],
      [new ApiError(400, 'INVALID_INPUT', '입력 값을 확인해 주세요.', { to: '기간은 최대 31일입니다.' }), '입력을 확인하세요.'],
      [
        new ApiError(409, 'AUDIT_BUCKET_NOT_CONFIGURED', '감사 로그 위치가 설정되지 않아…'),
        '감사 로그 위치가 설정되지 않아 운영자 접속기록을 만들 수 없습니다.',
      ],
    ];
    for (const [error, message] of cases) assert.equal(generateErrorMessage(error), message, error.code);
  });

  test('그 밖(501 · 연결 실패 · 모르는 오류)은 서버 · 공통 문구', () => {
    assert.equal(generateErrorMessage(new ApiError(501, 'NOT_IMPLEMENTED', '아직 제공하지 않는 기능입니다.')), '아직 제공하지 않는 기능입니다.');
    assert.equal(generateErrorMessage(new ApiError(0, 'NETWORK_ERROR', NETWORK_ERROR_MESSAGE)), NETWORK_ERROR_MESSAGE);
    assert.equal(generateErrorMessage(new Error('x')), '처리 중 오류가 발생했습니다.');
  });

  test('400 칸별 사유는 이 모달 입력란 것만', () => {
    const error = new ApiError(400, 'INVALID_INPUT', '입력 값을 확인해 주세요.', { to: '기간은 최대 31일입니다.', month: 'x', body: 'y' });
    assert.deepEqual(generateFieldErrors(error), { to: '기간은 최대 31일입니다.' });
    assert.deepEqual(generateFieldErrors(new ApiError(409, 'REPORT_BUSY', 'm', { to: 'x' })), {});
  });

  test('성공 토스트 — 새로 만듦 / 이미 있던 리포트를 새로 씀', () => {
    assert.equal(generatedMessage(true), '리포트를 만들었습니다.');
    assert.equal(generatedMessage(false), '이미 있는 리포트를 새로 썼습니다.');
  });
});
