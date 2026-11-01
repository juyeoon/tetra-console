import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { setWriteHandler } from '@/api/client';
import { fetchUnreadCount } from '@/api/notifications';
import type { UnreadCount } from '@/api/types';
import { useAuth } from './AuthContext';
import {
  NO_UNREAD,
  UNREAD_POLL_MS,
  WRITE_REFRESH_DELAY_MS,
  applyUnread,
  clearUnread,
  coalesce,
  fetchOnRoute,
  refreshAfterWrite,
  shouldPoll,
} from './unreadCount';

interface UnreadValue {
  /** 안 읽은 알림 수 · 긴급 여부 — 모르면(로그아웃 · 아직 못 받음) null */
  unread: UnreadCount | null;
  /** 지금 다시 받는다(읽음 처리 직후 등) */
  refresh: () => void;
  /** 이미 받은 값으로 맞춘다(알림센터 목록 응답의 unreadCount · unreadUrgent) */
  report: (unread: UnreadCount) => void;
}

const UnreadCountContext = createContext<UnreadValue | null>(null);

/**
 * 사이드바 안 읽은 알림 배지 값 — 앱 전체에 하나(Tetra Portal과 같은 갱신 규칙).
 * 받는 때: 로그인 상태가 될 때(앱 열림 · 로그인 직후) · 라우트 이동 · 보이는 동안 60초마다(숨겨지면 멈추고 다시 보이면 바로) · refresh ·
 * 쓰기 요청이 끝난 뒤(읽음 · 모두 읽음 · 로그인 · 로그아웃 제외, 300ms 묶음 — refreshAfterWrite).
 * 실패하면 마지막 값 그대로(토스트 · 콘솔 없음). 로그아웃하면 멈추고 지운다. 401은 client가 로그아웃 상태로 바꾼다.
 */
export function UnreadCountProvider({ children }: { children: ReactNode }) {
  const { state: auth, operator } = useAuth();
  const authenticated = auth.status === 'authenticated';
  const { pathname } = useLocation();
  const [state, setState] = useState(NO_UNREAD);
  const requested = useRef(0);
  const live = useRef(authenticated);
  live.current = authenticated;

  const refresh = useCallback(() => {
    if (!live.current) return;
    const id = ++requested.current;
    fetchUnreadCount().then(
      (r) => setState((prev) => applyUnread(prev, id, r)),
      () => setState((prev) => applyUnread(prev, id, null)),
    );
  }, []);

  const report = useCallback((unread: UnreadCount) => {
    if (!live.current) return;
    const id = ++requested.current;
    setState((prev) => applyUnread(prev, id, unread));
  }, []);

  // 쓰기 요청(API 클라이언트 공통)이 끝나면 300ms 묶어 한 번 — 승인 · 반려 · 테넌트 등록 직후 그 화면에 머물러도 배지가 바로 바뀐다
  const writeBatch = useRef<ReturnType<typeof coalesce> | null>(null);
  useEffect(() => {
    const batch = coalesce(refresh, WRITE_REFRESH_DELAY_MS);
    writeBatch.current = batch;
    setWriteHandler((method, path) => {
      if (live.current && refreshAfterWrite(method, path)) batch.trigger();
    });
    return () => {
      setWriteHandler(null);
      batch.cancel();
    };
  }, [refresh]);

  // 로그아웃 — 지우고, 보내 둔 요청의 응답도 버린다(묶어 둔 쓰기 뒤 다시 받기도)
  useEffect(() => {
    if (!authenticated) {
      writeBatch.current?.cancel();
      setState(clearUnread(++requested.current));
    }
  }, [authenticated]);

  // 앱이 열릴 때 · 로그인 직후 · 라우트 이동 때
  useEffect(() => {
    if (authenticated && fetchOnRoute(pathname)) refresh();
  }, [authenticated, operator?.email, pathname, refresh]);

  // 보이는 동안 60초마다, 숨겨졌다 다시 보이면 바로
  useEffect(() => {
    if (!authenticated) return;
    const tick = () => {
      if (shouldPoll({ authenticated: live.current, hidden: document.hidden })) refresh();
    };
    const timer = window.setInterval(tick, UNREAD_POLL_MS);
    document.addEventListener('visibilitychange', tick);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', tick);
    };
  }, [authenticated, refresh]);

  const value = useMemo(() => ({ unread: state.unread, refresh, report }), [state.unread, refresh, report]);
  return <UnreadCountContext.Provider value={value}>{children}</UnreadCountContext.Provider>;
}

export function useUnreadCount() {
  const ctx = useContext(UnreadCountContext);
  if (!ctx) throw new Error('useUnreadCount must be used within UnreadCountProvider');
  return ctx;
}
