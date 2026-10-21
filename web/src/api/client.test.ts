import { afterEach, beforeEach, describe, test } from 'node:test';
import assert from 'node:assert/strict';
import { ApiError, apiRequest, errorMessage, NETWORK_ERROR_MESSAGE, setUnauthorizedHandler, UNKNOWN_ERROR_MESSAGE } from './client.ts';
import { fetchMe, login, logout } from './auth.ts';

interface Call {
  url: string;
  init: RequestInit;
}

const realFetch = globalThis.fetch;
let calls: Call[];
let unauthorized: number;

/** 다음 fetch 응답을 정한다 */
function respond(response: Response | (() => never)) {
  globalThis.fetch = (async (url: string, init: RequestInit) => {
    calls.push({ url, init });
    if (typeof response === 'function') response();
    return response;
  }) as typeof fetch;
}

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

beforeEach(() => {
  calls = [];
  unauthorized = 0;
  setUnauthorizedHandler(() => unauthorized++);
});

afterEach(() => {
  globalThis.fetch = realFetch;
  setUnauthorizedHandler(null);
});

describe('apiRequest', () => {
  test('/api/ops 아래 상대 경로로 같은 출처 쿠키를 붙여 보낸다', async () => {
    respond(json(200, { ok: true }));

    assert.deepEqual(await apiRequest('/events?page=0'), { ok: true });
    assert.equal(calls[0].url, '/api/ops/events?page=0');
    assert.equal(calls[0].init.credentials, 'same-origin');
    assert.equal(calls[0].init.method, 'GET');
    assert.equal(calls[0].init.body, undefined);
  });

  test('본문은 JSON으로 보낸다', async () => {
    respond(json(200, {}));

    await apiRequest('/tenants', { method: 'POST', body: { name: 'E사' } });
    assert.equal(calls[0].init.body, '{"name":"E사"}');
    assert.equal((calls[0].init.headers as Record<string, string>)['Content-Type'], 'application/json');
  });

  test('204는 본문 없이 끝난다', async () => {
    respond(new Response(null, { status: 204 }));

    assert.equal(await apiRequest('/auth/logout', { method: 'POST' }), undefined);
  });

  test('오류 JSON은 ApiError(code · message · fields)로 바꾼다', async () => {
    respond(json(400, { code: 'INVALID_INPUT', message: '입력 값을 확인해 주세요.', fields: { reason: '필수 입력입니다.' } }));

    await assert.rejects(apiRequest('/events/1/reject', { method: 'POST', body: {} }), (e: unknown) => {
      assert.ok(e instanceof ApiError);
      assert.equal(e.status, 400);
      assert.equal(e.code, 'INVALID_INPUT');
      assert.equal(e.message, '입력 값을 확인해 주세요.');
      assert.deepEqual(e.fields, { reason: '필수 입력입니다.' });
      return true;
    });
  });

  test('401이면 처리기(로그인 화면 이동)를 부른다', async () => {
    respond(json(401, { code: 'UNAUTHENTICATED', message: '로그인이 필요합니다.' }));

    await assert.rejects(apiRequest('/dashboard'), ApiError);
    assert.equal(unauthorized, 1);
  });

  test('skipUnauthorizedHandler면 401이어도 처리기를 부르지 않는다', async () => {
    respond(json(401, { code: 'UNAUTHENTICATED', message: '로그인이 필요합니다.' }));

    await assert.rejects(apiRequest('/me', { skipUnauthorizedHandler: true }), ApiError);
    assert.equal(unauthorized, 0);
  });

  test('403 · 409 등은 처리기를 부르지 않고 서버 문구를 그대로 준다', async () => {
    respond(json(403, { code: 'FORBIDDEN', message: '접근 권한이 없습니다.' }));

    await assert.rejects(apiRequest('/x'), { message: '접근 권한이 없습니다.', code: 'FORBIDDEN' });
    assert.equal(unauthorized, 0);
  });

  test('JSON이 아닌 502(프록시 · API 꺼짐)는 연결 실패 문구', async () => {
    respond(new Response('<html>Bad Gateway</html>', { status: 502, headers: { 'Content-Type': 'text/html' } }));

    await assert.rejects(apiRequest('/me'), { code: 'NETWORK_ERROR', message: NETWORK_ERROR_MESSAGE, status: 502 });
  });

  test('JSON이 아닌 그 밖의 오류는 공통 문구', async () => {
    respond(new Response('oops', { status: 500 }));

    await assert.rejects(apiRequest('/me'), { code: 'UNKNOWN_ERROR', message: UNKNOWN_ERROR_MESSAGE });
  });

  test('서버에 닿지 못하면(fetch 예외) status 0, 연결 실패 문구', async () => {
    respond(() => {
      throw new TypeError('Failed to fetch');
    });

    await assert.rejects(apiRequest('/me'), { code: 'NETWORK_ERROR', status: 0, message: NETWORK_ERROR_MESSAGE });
  });

  test('errorMessage: ApiError는 서버 문구, 그 밖은 공통 문구', () => {
    assert.equal(errorMessage(new ApiError(409, 'EMAIL_DUPLICATED', '이미 등록된 이메일입니다.')), '이미 등록된 이메일입니다.');
    assert.equal(errorMessage(new Error('x')), UNKNOWN_ERROR_MESSAGE);
  });
});

describe('auth API', () => {
  test('로그인 · 내 정보 · 로그아웃 경로와 401 처리기 제외', async () => {
    respond(json(401, { code: 'LOGIN_FAILED', message: '이메일 또는 비밀번호가 일치하지 않습니다.' }));
    await assert.rejects(login('a@b.c', 'pw'), { code: 'LOGIN_FAILED' });
    await assert.rejects(fetchMe(), ApiError);
    await assert.rejects(logout(), ApiError);

    assert.deepEqual(
      calls.map((c) => `${c.init.method} ${c.url}`),
      ['POST /api/ops/auth/login', 'GET /api/ops/me', 'POST /api/ops/auth/logout'],
    );
    assert.equal(calls[0].init.body, '{"email":"a@b.c","password":"pw"}');
    assert.equal(unauthorized, 0);
  });
});
