import type { UnreadCount } from '../api/types.ts';

/**
 * 사이드바 안 읽은 알림 배지 — 글자 · 읽기 이름 · 색 · 갱신 판단(화면과 분리, node --test 대상). Tetra Portal과 같은 규칙.
 * 값은 UnreadCountContext 하나가 들고, 알림센터 목록 응답의 unreadCount · unreadUrgent로도 맞춘다.
 */

/** 화면이 보이는 동안 다시 받는 간격 */
export const UNREAD_POLL_MS = 60_000;

/** 알림센터 — 목록 응답이 안 읽은 수를 함께 주므로 라우트 이동 때 따로 받지 않는다 */
export const NOTIFICATIONS_PATH = '/logs/notifications';

/** 로그인 전 화면 — 로그인 · 미리보기(/preview/…) */
const LOGIN_PATH = '/login';
const PREVIEW_PREFIX = '/preview/';

/** 배지 글자 — 0 · 모름이면 없음(null), 100 이상이면 99+ */
export function badgeText(count: number | null): string | null {
  if (count === null || count <= 0) return null;
  return count >= 100 ? '99+' : String(count);
}

/**
 * 알림센터 메뉴 링크의 읽기 이름 — 배지는 aria-hidden이라 수는 여기에만(99+가 아닌 실제 수). 마우스를 올렸을 때도 같은 문구.
 * 예: '알림센터, 안 읽은 알림 5건(긴급 포함)'
 */
export function navAriaLabel(unread: UnreadCount | null): string {
  if (!unread || !badgeText(unread.count)) return '알림센터';
  return `알림센터, 안 읽은 알림 ${unread.count}건${unread.urgent ? '(긴급 포함)' : ''}`;
}

export type BadgeTone = 'urgent' | 'normal';

/**
 * 배지 색 — 안 읽은 긴급이 있으면 빨강(어디서든), 아니면 페리윙클 바탕 + 남색 숫자.
 * Portal은 선택 줄(흰 바탕)에서 남색 바탕으로 바꾸지만, Console 선택 줄 바탕(남색 위 흰 10%)과 남색 배지는 1.33:1이라
 * 구분되지 않아 선택 줄에서도 페리윙클을 쓴다(선택 줄과 4.47:1)
 */
export function badgeTone({ urgent }: { urgent: boolean; active: boolean }): BadgeTone {
  return urgent ? 'urgent' : 'normal';
}

/** 주기 · 다시 보일 때 받을지 — 로그인 상태이고 화면이 보일 때만(숨겨진 탭은 건너뛴다) */
export function shouldPoll({ authenticated, hidden }: { authenticated: boolean; hidden: boolean }): boolean {
  return authenticated && !hidden;
}

/** 라우트 이동 때 받을지 — 알림센터는 목록 응답으로 맞추고, 로그인 전 화면은 받지 않는다 */
export function fetchOnRoute(pathname: string): boolean {
  return pathname !== NOTIFICATIONS_PATH && pathname !== LOGIN_PATH && !pathname.startsWith(PREVIEW_PREFIX);
}

/**
 * 홈 최근 알림을 다시 받을지 — 배지 수가 바뀌었을 때만(새 알림 · 다른 화면에서 읽음). 처음 받은 값(모름 → 수)과
 * 로그아웃(수 → 모름)은 아니다. 따로 주기 요청을 만들지 않고 배지 갱신을 따라간다
 */
export function shouldReloadDashboard(prev: number | null, next: number | null): boolean {
  return prev !== null && next !== null && prev !== next;
}

export interface UnreadState {
  /** 모르면(로그아웃 · 아직 못 받음) null */
  unread: UnreadCount | null;
  /** 마지막으로 반영한 요청 번호 — 이보다 앞선 요청의 늦은 응답은 버린다 */
  applied: number;
}

export const NO_UNREAD: UnreadState = { unread: null, applied: 0 };

/** 응답 반영 — 앞선 요청의 늦은 응답은 버리고, 실패(null)면 이전 값 그대로 */
export function applyUnread(state: UnreadState, requestId: number, unread: UnreadCount | null): UnreadState {
  if (unread === null || requestId < state.applied) return state;
  return { unread, applied: requestId };
}

/** 로그아웃 — 값을 지우고, 그 전에 보낸 요청(번호 < nextRequestId)의 응답은 모두 버린다 */
export function clearUnread(nextRequestId: number): UnreadState {
  return { unread: null, applied: nextRequestId };
}
