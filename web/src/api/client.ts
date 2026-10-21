/**
 * Console API(/api/ops/...) 호출 공통.
 * - 같은 출처 쿠키(TETRA_OPS_TOKEN)로 인증한다. 개발 서버는 Vite 프록시로 :8080에 넘긴다.
 * - 오류 응답 {code, message, fields?}는 ApiError로 바꾼다. message는 화면에 그대로 보여 줘도 되는 문구.
 * - 401이면 등록된 처리기(로그인 화면으로 이동)를 부른다. 로그인 · 내 정보처럼 호출한 쪽이 직접 판단하는 요청은 빼 달라고 할 수 있다.
 * - CSRF는 API에서 꺼져 있다(SameSite=Strict 쿠키). 켜지면 여기서 헤더를 붙인다.
 * node --test로 바로 실행하므로 import는 상대 경로 + .ts 확장자만 쓴다.
 */

export const API_BASE = '/api/ops';

export const NETWORK_ERROR_MESSAGE = '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.';

export const UNKNOWN_ERROR_MESSAGE = '처리 중 오류가 발생했습니다.';

export class ApiError extends Error {
  /** HTTP 상태. 서버에 닿지 못했으면 0 */
  readonly status: number;
  /** 서버 오류 코드(UNAUTHENTICATED 등). 서버 응답이 아니면 NETWORK_ERROR · UNKNOWN_ERROR */
  readonly code: string;
  /** 400일 때 입력 항목별 사유 */
  readonly fields: Record<string, string>;

  constructor(status: number, code: string, message: string, fields: Record<string, string> = {}) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.fields = fields;
  }
}

let unauthorizedHandler: (() => void) | null = null;

/** 401을 받았을 때 부를 처리기. AuthProvider가 등록한다. */
export function setUnauthorizedHandler(handler: (() => void) | null) {
  unauthorizedHandler = handler;
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';
  body?: unknown;
  /** true면 401이어도 처리기를 부르지 않는다(로그인 실패 · 앱 시작 시 내 정보 확인) */
  skipUnauthorizedHandler?: boolean;
  signal?: AbortSignal;
}

/** @param path API_BASE 아래 경로. 예: '/me' → /api/ops/me */
export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, skipUnauthorizedHandler = false, signal } = options;
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  let response: Response;
  try {
    response = await fetch(API_BASE + path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      credentials: 'same-origin',
      signal,
    });
  } catch (e) {
    if (e instanceof Error && e.name === 'AbortError') throw e;
    throw new ApiError(0, 'NETWORK_ERROR', NETWORK_ERROR_MESSAGE);
  }

  if (response.ok) {
    if (response.status === 204) return undefined as T;
    const text = await response.text();
    return (text ? JSON.parse(text) : undefined) as T;
  }

  const error = await toApiError(response);
  if (response.status === 401 && !skipUnauthorizedHandler) unauthorizedHandler?.();
  throw error;
}

/** 서버 오류 JSON이면 그대로, 아니면(프록시 502 HTML 등) 공통 문구로 */
async function toApiError(response: Response): Promise<ApiError> {
  try {
    const body: unknown = JSON.parse(await response.text());
    if (isErrorBody(body)) return new ApiError(response.status, body.code, body.message, body.fields ?? {});
  } catch {
    // JSON이 아니면 아래로
  }
  if (response.status === 502 || response.status === 503 || response.status === 504) {
    return new ApiError(response.status, 'NETWORK_ERROR', NETWORK_ERROR_MESSAGE);
  }
  return new ApiError(response.status, 'UNKNOWN_ERROR', UNKNOWN_ERROR_MESSAGE);
}

function isErrorBody(value: unknown): value is { code: string; message: string; fields?: Record<string, string> } {
  if (typeof value !== 'object' || value === null) return false;
  const v = value as Record<string, unknown>;
  return typeof v.code === 'string' && typeof v.message === 'string';
}

/** 화면에 보여 줄 오류 문구 */
export function errorMessage(error: unknown): string {
  return error instanceof ApiError ? error.message : UNKNOWN_ERROR_MESSAGE;
}
