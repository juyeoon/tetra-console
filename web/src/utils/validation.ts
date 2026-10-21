export const isEmail = (v: string) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v.trim());

/** 회사 root 도메인 정규화 — 앞뒤 공백 제거 + 소문자. 이 값을 API로 보낸다 */
export const normalizeDomain = (v: string) => v.trim().toLowerCase();

/**
 * 회사 root 도메인 형식 (RFC 1123 호스트명, 소문자 기준) — API TenantInputRules.domain과 같은 규칙.
 * 전체 253자 · 라벨 63자 이하, 라벨은 영숫자로 시작 · 끝, 점 1개 이상, 영문 TLD 2~63자.
 * http(s)://, 경로, 포트, 공백, 밑줄, 끝의 점, 한글 도메인 · 퓨니코드 TLD(MVP 제외)는 통과하지 않는다.
 */
const DOMAIN = /^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/;

export const isDomain = (v: string) => DOMAIN.test(normalizeDomain(v));

/** 화면 · API(400 fields.rootDomain) 공통 문구 */
export const DOMAIN_ERROR = '도메인 형식이 올바르지 않습니다. (예: e-corp.com — http://, 경로, 포트 없이)';
