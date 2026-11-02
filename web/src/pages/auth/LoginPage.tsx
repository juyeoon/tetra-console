import { useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { Alert, Box, Button, IconButton, InputAdornment, Stack, TextField, Typography } from '@mui/material';
import VisibilityOffOutlinedIcon from '@mui/icons-material/VisibilityOffOutlined';
import VisibilityOutlinedIcon from '@mui/icons-material/VisibilityOutlined';
import CheckerMark from '@/components/common/CheckerMark';
import FormField from '@/components/common/FormField';
import { useAuth } from '@/store/AuthContext';
import { loginTarget, type LoginRedirectState } from '@/store/authState';
import { color, mono, onNavy, radius, size, text } from '@/theme/theme';

export default function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { state, login } = useAuth();
  // RequireAuth가 넘겨 준 값(loginRedirectState). 직접 로그아웃이면 비어 있다
  const { from, expired } = (location.state ?? {}) as LoginRedirectState;
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPw, setShowPw] = useState(false);
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  // 이미 로그인한 상태로 들어오면 돌려보낸다
  if (state.status === 'authenticated' && !submitting) return <Navigate to={loginTarget(from)} replace />;

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!email.trim() || !password) {
      setError('이메일과 비밀번호를 모두 입력해주세요.');
      return;
    }
    // 불일치 시 서버 문구만 노출 (계정 잠금 없음)
    setSubmitting(true);
    const result = await login(email, password);
    setSubmitting(false);
    if (result.ok) navigate(loginTarget(from), { replace: true });
    else setError(result.message);
  };

  return (
    <Box sx={{ minHeight: '100vh', display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'minmax(400px, 36%) 1fr' } }}>
      {/* 좌측 브랜드 패널 — Canva 표지 스타일 */}
      <Box
        sx={{
          position: 'relative',
          overflow: 'hidden',
          bgcolor: color.navy,
          color: onNavy.primary,
          px: { xs: 3, md: 6 },
          py: { xs: 4, md: 6 },
          display: 'flex',
          flexDirection: 'column',
          minHeight: { xs: 260, md: 'auto' },
        }}
      >
        <CheckerMark corner="top-right" cell={14} base={mono[700]} tone={mono[800]} sx={{ position: 'absolute', top: 0, right: 0 }} />
        <CheckerMark corner="bottom-left" cell={14} base={mono[800]} tone={mono[700]} sx={{ position: 'absolute', bottom: 0, left: 0 }} />

        <Typography variant="overline" sx={{ color: onNavy.tertiary }}>
          OPERATOR CONSOLE
        </Typography>

        <Box sx={{ my: 'auto', py: 5 }}>
          <Typography variant="h1" component="p" sx={{ color: onNavy.primary }}>
            운영 콘솔
          </Typography>
          <Typography variant="body1" sx={{ mt: 1, color: onNavy.secondary }}>
            플랫폼 통합 관리 시스템 · Platform Operator Console
          </Typography>
        </Box>

        <Box sx={{ display: { xs: 'none', md: 'block' }, pl: { md: 6 } }}>
          <Box sx={{ p: 2, borderRadius: radius.lg, bgcolor: onNavy.hover, border: `1px solid ${onNavy.line}` }}>
            <Typography variant="subtitle1">내부망 전용 시스템</Typography>
            <Typography variant="body2" sx={{ mt: 0.5, color: onNavy.secondary }}>
              본 콘솔은 플랫폼 운영팀 전용이며, 모든 접속 및 조회 기록은 감사 로그로 저장됩니다.
            </Typography>
          </Box>
          <Typography variant="body2" sx={{ mt: 2, color: onNavy.tertiary }}>
            v1.0.0 · Internal Use Only
          </Typography>
          <Typography variant="body2" sx={{ color: onNavy.tertiary }}>
            © 2026 Tetra Operation. All rights reserved.
          </Typography>
        </Box>
      </Box>

      {/* 우측 로그인 폼 */}
      <Box sx={{ display: 'grid', placeItems: 'center', px: { xs: 3, sm: 6 }, py: 6 }}>
        <Box component="form" onSubmit={handleSubmit} noValidate sx={{ width: '100%', maxWidth: size.contentNarrow }}>
          <Typography variant="h2" component="h1">
            운영자 로그인
          </Typography>
          <Typography variant="body1" sx={{ mt: 0.5, mb: 4, color: text.secondary }}>
            권한이 부여된 운영자 계정으로만 접속할 수 있습니다.
          </Typography>

          {error ? (
            <Alert severity="error" sx={{ mb: 2 }}>
              {error}
            </Alert>
          ) : (
            expired && (
              <Alert severity="info" sx={{ mb: 2 }}>
                로그인이 만료되었습니다. 다시 로그인해 주세요.
              </Alert>
            )
          )}

          <Stack spacing={2}>
            <FormField label="이메일" htmlFor="login-email">
              <TextField
                id="login-email"
                fullWidth
                type="email"
                autoComplete="username"
                placeholder="operator@tetra-operation.com"
                value={email}
                onChange={(e) => {
                  setEmail(e.target.value);
                  setError('');
                }}
              />
            </FormField>
            <FormField label="비밀번호" htmlFor="login-pw">
              <TextField
                id="login-pw"
                fullWidth
                type={showPw ? 'text' : 'password'}
                autoComplete="current-password"
                placeholder="비밀번호 입력"
                value={password}
                onChange={(e) => {
                  setPassword(e.target.value);
                  setError('');
                }}
                slotProps={{
                  input: {
                    endAdornment: (
                      <InputAdornment position="end">
                        <IconButton size="small" onClick={() => setShowPw((v) => !v)} edge="end" aria-label="비밀번호 보기 전환">
                          {showPw ? <VisibilityOffOutlinedIcon sx={{ fontSize: size.iconMd }} /> : <VisibilityOutlinedIcon sx={{ fontSize: size.iconMd }} />}
                        </IconButton>
                      </InputAdornment>
                    ),
                  },
                }}
              />
            </FormField>
          </Stack>

          <Button type="submit" variant="contained" size="large" fullWidth loading={submitting} sx={{ mt: 3 }}>
            로그인
          </Button>
        </Box>
      </Box>
    </Box>
  );
}
