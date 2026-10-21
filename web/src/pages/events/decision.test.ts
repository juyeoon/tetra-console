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
