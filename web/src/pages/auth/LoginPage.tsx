import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { Alert, Box, Button, IconButton, InputAdornment, Stack, TextField, Typography } from '@mui/material';
import VisibilityOffOutlinedIcon from '@mui/icons-material/VisibilityOffOutlined';
import VisibilityOutlinedIcon from '@mui/icons-material/VisibilityOutlined';
import CheckerMark from '@/components/common/CheckerMark';
import FormField from '@/components/common/FormField';
import { color, onNavy, radius, size, text } from '@/theme/theme';

export default function LoginPage() {
  const navigate = useNavigate();
  const [id, setId] = useState('');
  const [password, setPassword] = useState('');
  const [showPw, setShowPw] = useState(false);
  const [error, setError] = useState('');

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (!id.trim() || !password) {
      setError('운영자 ID와 비밀번호를 모두 입력해주세요.');
      return;
    }
    // TODO: 로그인 API 연동 (ID/비밀번호 불일치 시 에러 메시지만 노출, 계정 잠금 없음)
    navigate('/');
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
        <CheckerMark corner="top-right" cell={14} base={color.peri} tone={color.white} sx={{ position: 'absolute', top: 0, right: 0 }} />
        <CheckerMark corner="bottom-left" cell={14} base={color.white} tone={color.peri} sx={{ position: 'absolute', bottom: 0, left: 0 }} />

        <Typography variant="overline" sx={{ color: color.peri }}>
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

          {error && (
            <Alert severity="error" sx={{ mb: 2 }}>
              {error}
            </Alert>
          )}

          <Stack spacing={2}>
            <FormField label="운영자 ID" htmlFor="login-id">
              <TextField
                id="login-id"
                fullWidth
                autoComplete="username"
                placeholder="operator@tetra-ops.internal"
                value={id}
                onChange={(e) => {
                  setId(e.target.value);
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

          <Button type="submit" variant="contained" size="large" fullWidth sx={{ mt: 3 }}>
            로그인
          </Button>
        </Box>
      </Box>
    </Box>
  );
}
