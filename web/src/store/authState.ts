import { ApiError, errorMessage } from '../api/client.ts';
import type { Operator } from '../api/auth.ts';

/**
 * 로그인 상태 판단(화면과 분리, node --test 대상).
 * - loading: 앱 시작 후 /me 응답 전. 이때 콘솔 화면을 그리지 않는다
 * - error: 서버에 닿지 못함 등(401이 아닌 실패). 로그인 화면 대신 다시 시도 안내
 */
/**
 * 로그인 전이 된 이유
 * - none: 앱 시작 시 /me 401 (처음 들어옴)
 * - expired: 로그인 뒤 API 401 (쿠키 만료 등)
 * - logout: 직접 로그아웃
 */
export type AnonymousReason = 'none' | 'expired' | 'logout';

export type AuthState =
  | { status: 'loading' }
  | { status: 'authenticated'; operator: Operator }
  | { status: 'anonymous'; reason: AnonymousReason }
  | { status: 'error'; message: string };

/** 앱 시작 시 GET /me 결과 → 상태. 401이면 로그인 전, 그 밖의 실패는 error */
export async function resolveSession(fetchMe: () => Promise<Operator>): Promise<AuthState> {
  try {
    return { status: 'authenticated', operator: await fetchMe() };
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) return { status: 'anonymous', reason: 'none' };
    return { status: 'error', message: errorMessage(e) };
  }
}

/** /login으로 넘기는 location.state. LoginPage가 읽는다 */
export interface LoginRedirectState {
  /** 로그인 후 돌아갈 경로 */
  from?: string;
  /** "로그인이 만료되었습니다" 안내 */
  expired?: boolean;
}

/**
 * 로그인 화면으로 보낼 때 넘길 값.
 * 직접 로그아웃은 돌아갈 경로를 기억하지 않는다(다시 로그인하면 /). 만료 · 처음 들어옴은 보던 경로를 기억한다.
 */
export function loginRedirectState(reason: AnonymousReason, currentPath: string): LoginRedirectState {
  if (reason === 'logout') return {};
  return reason === 'expired' ? { from: currentPath, expired: true } : { from: currentPath };
}

export type LoginResult = { ok: true; operator: Operator } | { ok: false; message: string };

/** 로그인 요청. 실패 문구는 서버 message(예: "이메일 또는 비밀번호가 일치하지 않습니다.") */
export async function attemptLogin(
  login: (email: string, password: string) => Promise<Operator>,
  email: string,
  password: string,
): Promise<LoginResult> {
  try {
    return { ok: true, operator: await login(email.trim(), password) };
  } catch (e) {
    return { ok: false, message: errorMessage(e) };
  }
}

/** 로그인 후 돌아갈 경로. 같은 앱 안의 경로만 받는다(외부 주소 · //host · /login은 '/') */
export function loginTarget(from: unknown): string {
  if (typeof from !== 'string' || !from.startsWith('/') || from.startsWith('//')) return '/';
  if (from === '/login' || from.startsWith('/login?')) return '/';
  return from;
}
