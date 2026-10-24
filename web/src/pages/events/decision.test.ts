import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import { ApiError, NETWORK_ERROR_MESSAGE } from '../../api/client.ts';
import { decisionFailure } from './decision.ts';

describe('승인 · 반려 실패 → 대화상자', () => {
  test('이미 처리된 신청(409 APPLICATION_NOT_APPLIED)은 서버 문구 + 상세 다시 받기', () => {
    assert.deepEqual(decisionFailure(new ApiError(409, 'APPLICATION_NOT_APPLIED', '이미 처리된 신청입니다.')), {
      message: '이미 처리된 신청입니다.',
      reloadDetail: true,
    });
  });

  test('호스팅 기간 지남 · 환경 준비 전(409)은 서버 문구만', () => {
    for (const [code, message] of [
      ['HOSTING_PERIOD_ENDED', '호스팅 기간이 지나 승인할 수 없습니다.'],
      ['TENANT_NOT_READY', '테넌트 환경 준비가 끝나지 않았습니다.'],
    ]) {
      assert.deepEqual(decisionFailure(new ApiError(409, code, message)), { message, reloadDetail: false }, code);
    }
  });

  test('승인 전 검사 위반(409 APPLICATION_DATA_INVALID)은 서버 문구 + 사유 목록(서버 순서), 상세는 다시 받지 않음', () => {
    const f = decisionFailure(
      new ApiError(409, 'APPLICATION_DATA_INVALID', '신청 내용이 승인 조건에 맞지 않아 승인할 수 없습니다.', {
        HOSTING_START_TOO_LATE: '호스팅 시작은 이벤트 시작 100분 전까지여야 합니다. (호스팅 시작 10.25 12:00 · 이벤트 시작 10.25 13:00)',
        CONCURRENT_EVENT_LIMIT: '같은 기간에 진행되는 이벤트가 최대 3개를 넘습니다. (10.25 13:00에 4개)',
      }),
    );
    assert.deepEqual(f, {
      message: '신청 내용이 승인 조건에 맞지 않아 승인할 수 없습니다.',
      reloadDetail: false,
      reasons: [
        '호스팅 시작은 이벤트 시작 100분 전까지여야 합니다. (호스팅 시작 10.25 12:00 · 이벤트 시작 10.25 13:00)',
        '같은 기간에 진행되는 이벤트가 최대 3개를 넘습니다. (10.25 13:00에 4개)',
      ],
    });
  });

  test('다른 승인 처리 중(409 APPROVE_BUSY)은 서버 문구만 — 사유 목록 없음', () => {
    const message = '다른 승인을 처리하고 있습니다. 잠시 후 다시 시도해 주세요.';
    assert.deepEqual(decisionFailure(new ApiError(409, 'APPROVE_BUSY', message)), { message, reloadDetail: false });
  });

  test('반려 사유 400은 입력란 오류', () => {
    const f = decisionFailure(new ApiError(400, 'INVALID_INPUT', '입력 값을 확인해 주세요.', { reason: '500자 이하로 입력해 주세요.' }));
    assert.equal(f.reasonError, '500자 이하로 입력해 주세요.');
    assert.equal(f.reloadDetail, false);
  });

  test('연결 실패는 공통 문구', () => {
    assert.deepEqual(decisionFailure(new ApiError(0, 'NETWORK_ERROR', NETWORK_ERROR_MESSAGE)), {
      message: NETWORK_ERROR_MESSAGE,
      reloadDetail: false,
    });
  });
});
