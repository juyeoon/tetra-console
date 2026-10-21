import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { Box, Button, CircularProgress } from '@mui/material';
import CloudOffOutlinedIcon from '@mui/icons-material/CloudOffOutlined';
import StateMessage from '@/components/common/StateMessage';
import { fetchMe, login as loginApi, logout as logoutApi, type Operator } from '@/api/auth';
import { setUnauthorizedHandler } from '@/api/client';
import { attemptLogin, loginRedirectState, resolveSession, type AuthState, type LoginResult } from './authState';

/** 로그인 상태. 데이터(AppDataContext · 목)와 따로 둔다. */
interface AuthValue {
  state: AuthState;
  /** 로그인한 운영자. RequireAuth 안에서만 쓴다 */
  operator: Operator | null;
  login: (email: string, password: string) => Promise<LoginResult>;
  logout: () => Promise<void>;
  /** /me 다시 확인 (서버 연결 오류 화면의 다시 시도) */
  refresh: () => Promise<void>;
}

const AuthContext = createContext<AuthValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: 'loading' });

  const refresh = useCallback(async () => {
    setState({ status: 'loading' });
    setState(await resolveSession(fetchMe));
  }, []);

  useEffect(() => {
    let active = true;
    resolveSession(fetchMe).then((next) => active && setState(next));
    return () => {
      active = false;
    };
  }, []);

  // 로그인 뒤 API가 401(쿠키 만료 등)을 주면 로그인 화면으로 (RequireAuth가 이동)
  useEffect(() => {
    setUnauthorizedHandler(() => setState({ status: 'anonymous', reason: 'expired' }));
    return () => setUnauthorizedHandler(null);
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    const result = await attemptLogin(loginApi, email, password);
    if (result.ok) setState({ status: 'authenticated', operator: result.operator });
    return result;
  }, []);

  const logout = useCallback(async () => {
    try {
      await logoutApi();
    } catch {
      // 이미 만료됐거나 서버 오류여도 화면은 로그아웃
    }
    // 이동은 RequireAuth가 한 곳에서 한다(여기서도 navigate하면 RequireAuth가 보던 경로를 from으로 덧씌운다)
    setState({ status: 'anonymous', reason: 'logout' });
  }, []);

  const value = useMemo<AuthValue>(
    () => ({ state, operator: state.status === 'authenticated' ? state.operator : null, login, logout, refresh }),
    [state, login, logout, refresh],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}

/** 로그인한 운영자. RequireAuth 아래 화면에서만 부른다 */
export function useOperator(): Operator {
  const { operator } = useAuth();
  if (!operator) throw new Error('useOperator must be used under RequireAuth');
  return operator;
}

/** 로그인 확인 전에는 콘솔 화면(목 대시보드 포함)을 그리지 않는다 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { state, refresh } = useAuth();
  const location = useLocation();

  if (state.status === 'loading') {
    return (
      <Box sx={{ minHeight: '100vh', display: 'grid', placeItems: 'center' }}>
        <CircularProgress aria-label="로그인 확인 중" />
      </Box>
    );
  }
  if (state.status === 'error') {
    return (
      <StateMessage icon={<CloudOffOutlinedIcon />} tone="red" title="서버에 연결할 수 없습니다" description={state.message}>
        <Button variant="outlined" onClick={() => void refresh()} sx={{ mt: 2 }}>
          다시 시도
        </Button>
      </StateMessage>
    );
  }
  if (state.status === 'anonymous') {
    return <Navigate to="/login" replace state={loginRedirectState(state.reason, location.pathname + location.search)} />;
  }
  return children;
}
