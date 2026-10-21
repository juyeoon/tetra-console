import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import { ApiError, NETWORK_ERROR_MESSAGE } from '../api/client.ts';
import { toFormErrors } from './formErrors.ts';

const REGISTER_FIELDS = ['name', 'email', 'rootDomain', 'contactName'] as const;

describe('toFormErrors (API 오류 → 폼)', () => {
  test('400 fields는 입력란별 사유로, 전부 붙으면 Alert 없음', () => {
    const error = new ApiError(400, 'INVALID_INPUT', '입력 값을 확인해 주세요.', {
      name: '100자 이하로 입력해 주세요.',
      rootDomain: '도메인 형식이 올바르지 않습니다.',
    });
    assert.deepEqual(toFormErrors(error, REGISTER_FIELDS), {
      fields: { name: '100자 이하로 입력해 주세요.', rootDomain: '도메인 형식이 올바르지 않습니다.' },
      message: null,
    });
  });

  test('폼에 없는 사유(fields.body 등)가 섞이면 서버 문구도 Alert로', () => {
    const error = new ApiError(400, 'INVALID_INPUT', '입력 값을 확인해 주세요.', {
      email: '이메일 형식이 올바르지 않습니다.',
      body: 'name, contactName, email 중 하나만 보내 주세요.',
    });
    assert.deepEqual(toFormErrors(error, REGISTER_FIELDS), {
      fields: { email: '이메일 형식이 올바르지 않습니다.' },
      message: '입력 값을 확인해 주세요.',
    });
  });

  test('409 EMAIL_DUPLICATED는 email 입력란 오류', () => {
    const error = new ApiError(409, 'EMAIL_DUPLICATED', '이미 등록된 이메일입니다.');
    assert.deepEqual(toFormErrors(error, ['email'] as const), { fields: { email: '이미 등록된 이메일입니다.' }, message: null });
  });

  test('email 입력란이 없는 폼에서 EMAIL_DUPLICATED는 Alert', () => {
    const error = new ApiError(409, 'EMAIL_DUPLICATED', '이미 등록된 이메일입니다.');
    assert.deepEqual(toFormErrors(error, ['name'] as const), { fields: {}, message: '이미 등록된 이메일입니다.' });
  });

  test('그 밖의 오류(409 · 연결 실패 · ApiError 아님)는 Alert', () => {
    assert.deepEqual(toFormErrors(new ApiError(404, 'TENANT_NOT_FOUND', '테넌트를 찾을 수 없습니다.'), REGISTER_FIELDS), {
      fields: {},
      message: '테넌트를 찾을 수 없습니다.',
    });
    assert.equal(toFormErrors(new ApiError(0, 'NETWORK_ERROR', NETWORK_ERROR_MESSAGE), REGISTER_FIELDS).message, NETWORK_ERROR_MESSAGE);
    assert.equal(toFormErrors(new Error('x'), REGISTER_FIELDS).message, '처리 중 오류가 발생했습니다.');
  });
});
