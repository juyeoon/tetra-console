import type { UnreadCount } from '../api/types.ts';

/**
 * 사이드바 안 읽은 알림 배지 — 글자 · 읽기 이름 · 색 · 갱신 판단(화면과 분리, node --test 대상). Tetra Portal과 같은 규칙 +
 * 쓰기 요청 직후 다시 받기(Console만 — 승인 · 반려 · 테넌트 등록이 운영자 알림을 만든다).
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

/** 쓰기 요청 뒤 다시 받기를 묶는 시간 — 그 안에 끝난 쓰기는 한 번만 받는다 */
export const WRITE_REFRESH_DELAY_MS = 300;

/** 쓰기 뒤 다시 받지 않는 경로 — 읽음 · 모두 읽음(성공 직후 · 목록 응답으로 따로 맞춤), 로그인 · 로그아웃 */
const WRITE_EXCLUDED = [/^\/notifications\/[^/]+\/read$/, /^\/notifications\/read-all$/, /^\/auth\/(login|logout)$/];

/**
 * 쓰기 요청(API 클라이언트 공통 처리기)이 끝난 뒤 배지를 다시 받을지 — 승인 · 반려 · 테넌트 등록처럼 운영자 알림을 만들 수 있는 쓰기.
 * GET(unread-count 자신 포함)은 아니고, 제외 경로도 아니다. path는 API_BASE 아래 경로(쿼리는 무시)
 */
export function refreshAfterWrite(method: string, path: string): boolean {
  if (method === 'GET') return false;
  const p = path.split('?')[0];
  return !WRITE_EXCLUDED.some((re) => re.test(p));
}

type TimerHandle = ReturnType<typeof setTimeout>;

export interface Timers {
  set: (fn: () => void, ms: number) => TimerHandle;
  clear: (handle: TimerHandle) => void;
}

const REAL_TIMERS: Timers = { set: (fn, ms) => setTimeout(fn, ms), clear: (handle) => clearTimeout(handle) };

/**
 * 짧게 묶기 — 처음 부른 뒤 delayMs가 지나면 fn을 한 번 부르고, 그사이 다시 불러도 늘리지 않는다(쓰기가 이어져도 첫 쓰기 뒤 delayMs 안에 받는다).
 * 그 시점에는 그사이 끝난 쓰기가 모두 커밋돼 있다(응답이 온 뒤에 부르므로)
 */
export function coalesce(fn: () => void, delayMs: number, timers: Timers = REAL_TIMERS) {
  let pending: TimerHandle | null = null;
  return {
    trigger() {
      if (pending !== null) return;
      pending = timers.set(() => {
        pending = null;
        fn();
      }, delayMs);
    },
    /** 기다리는 것을 버린다(로그아웃 · 화면 정리) */
    cancel() {
      if (pending === null) return;
      timers.clear(pending);
      pending = null;
    },
  };
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
