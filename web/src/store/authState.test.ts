import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import { ApiError, NETWORK_ERROR_MESSAGE } from '../api/client.ts';
import { attemptLogin, loginRedirectState, loginTarget, resolveSession } from './authState.ts';

const operator = { email: 'operator@tetra-operation.com', name: '홍길동' };

describe('resolveSession (앱 시작 시 GET /me)', () => {
  test('성공하면 로그인 상태', async () => {
    assert.deepEqual(await resolveSession(async () => operator), { status: 'authenticated', operator });
  });

  test('401이면 로그인 전 → 로그인 화면', async () => {
    const state = await resolveSession(async () => {
      throw new ApiError(401, 'UNAUTHENTICATED', '로그인이 필요합니다.');
    });
    assert.deepEqual(state, { status: 'anonymous', reason: 'none' });
  });

  test('서버에 닿지 못하면 error (로그인 화면으로 보내지 않는다)', async () => {
    const state = await resolveSession(async () => {
      throw new ApiError(0, 'NETWORK_ERROR', NETWORK_ERROR_MESSAGE);
    });
    assert.deepEqual(state, { status: 'error', message: NETWORK_ERROR_MESSAGE });
  });
});

describe('attemptLogin', () => {
  test('성공하면 운영자 정보, 이메일 앞뒤 공백은 뺀다', async () => {
    let sent: string[] = [];
    const result = await attemptLogin(async (email, password) => {
      sent = [email, password];
      return operator;
    }, '  operator@tetra-operation.com ', 'pw');

    assert.deepEqual(result, { ok: true, operator });
    assert.deepEqual(sent, ['operator@tetra-operation.com', 'pw']);
  });

  test('실패하면 서버 문구', async () => {
    const result = await attemptLogin(async () => {
      throw new ApiError(401, 'LOGIN_FAILED', '이메일 또는 비밀번호가 일치하지 않습니다.');
    }, 'a@b.c', 'wrong');

    assert.deepEqual(result, { ok: false, message: '이메일 또는 비밀번호가 일치하지 않습니다.' });
  });
});

describe('loginRedirectState (로그인 화면으로 보낼 때 넘길 값)', () => {
  test('직접 로그아웃: 돌아갈 경로 · 만료 안내 없음 → 다시 로그인하면 /', () => {
    const state = loginRedirectState('logout', '/settings');
    assert.deepEqual(state, {});
    assert.equal(loginTarget(state.from), '/');
  });

  test('로그인 뒤 401(만료): 보던 경로를 기억하고 만료 안내', () => {
    const state = loginRedirectState('expired', '/events/applications/1043?tab=result');
    assert.deepEqual(state, { from: '/events/applications/1043?tab=result', expired: true });
    assert.equal(loginTarget(state.from), '/events/applications/1043?tab=result');
  });

  test('처음 들어옴(/me 401): 가려던 경로를 기억, 만료 안내 없음', () => {
    assert.deepEqual(loginRedirectState('none', '/tenants'), { from: '/tenants' });
  });
});

describe('loginTarget (로그인 후 돌아갈 경로)', () => {
  test('앱 안의 경로는 그대로', () => {
    assert.equal(loginTarget('/events/applications/1043?tab=result'), '/events/applications/1043?tab=result');
  });

  test('없거나 외부 주소 · 로그인 화면이면 대시보드', () => {
    for (const from of [undefined, null, 42, '', 'https://evil.example', '//evil.example', '/login', '/login?x=1']) {
      assert.equal(loginTarget(from), '/', String(from));
    }
  });
});
