import { apiRequest } from './client.ts';

/** GET /api/ops/me · 로그인 응답 */
export interface Operator {
  email: string;
  /** 담당자 이름 (헤더의 "홍길동님") */
  name: string;
}

/** 로그인 여부 확인. 401은 호출한 쪽이 anonymous로 처리한다. */
export const fetchMe = () => apiRequest<Operator>('/me', { skipUnauthorizedHandler: true });

/** 성공하면 서버가 HttpOnly 쿠키를 내려 준다. 401 LOGIN_FAILED는 로그인 화면이 문구로 보여 준다. */
export const login = (email: string, password: string) =>
  apiRequest<Operator>('/auth/login', { method: 'POST', body: { email, password }, skipUnauthorizedHandler: true });

/** 쿠키 삭제(204). 이미 만료돼 401이어도 화면은 로그아웃 처리한다. */
export const logout = () => apiRequest<void>('/auth/logout', { method: 'POST', skipUnauthorizedHandler: true });
