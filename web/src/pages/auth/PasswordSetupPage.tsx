import { useState, type FormEvent, type ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Alert, Box, Button, Dialog, DialogContent, Stack, TextField, Typography } from '@mui/material';
import CheckCircleOutlineRoundedIcon from '@mui/icons-material/CheckCircleOutlineRounded';
import CheckRoundedIcon from '@mui/icons-material/CheckRounded';
import RadioButtonUncheckedRoundedIcon from '@mui/icons-material/RadioButtonUncheckedRounded';
import CheckerMark from '@/components/common/CheckerMark';
import FormField from '@/components/common/FormField';
import { color, radius, size, text, tones } from '@/theme/theme';

const PORTAL_LOGIN_URL = import.meta.env.VITE_PORTAL_LOGIN_URL ?? '/login';

/** TODO: 토큰 검증 API 연동. 지금은 token이 없거나 'expired'면 만료로 처리 */
function useSetupToken() {
  const [params] = useSearchParams();
  const token = params.get('token');
  return { valid: !!token && token !== 'expired', email: params.get('email') ?? 'minho.park@e-corp.com' };
}

/** 운영 콘솔 메뉴 없이 단독 페이지 (등록·재설정 공용) */
function Shell({ children }: { children: ReactNode }) {
  return (
    <Box sx={{ minHeight: '100vh', display: 'grid', placeItems: 'center', px: 2, py: 6 }}>
      <Box sx={{ position: 'relative', overflow: 'hidden', width: '100%', maxWidth: size.contentNarrow, p: { xs: 3, sm: 4 }, borderRadius: radius.xl, bgcolor: color.card }}>
        <CheckerMark corner="top-right" cell={8} sx={{ position: 'absolute', top: 0, right: 0 }} />
        <Typography variant="h4" component="p" sx={{ color: color.navy, mb: 1 }}>Tetra</Typography>
        <Typography variant="h2" component="h1">
          비밀번호 설정
        </Typography>
        {children}
      </Box>
    </Box>
  );
}

export default function PasswordSetupPage() {
  const { valid, email } = useSetupToken();
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [done, setDone] = useState(false);

  if (!valid) {
    return (
      <Shell>
        <Alert severity="error" sx={{ mt: 3 }}>
          링크가 만료되었거나 이미 사용되었습니다.
          <br />
          운영자에게 재발송을 요청하세요.
        </Alert>
      </Shell>
    );
  }

  // 8자 이상 + 두 값 일치 시 저장 가능 (문자 조합 규칙은 미확정)
  const longEnough = password.length >= 8;
  const matches = password.length > 0 && password === confirm;
  const canSave = longEnough && matches;

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (!canSave) return;
    // TODO: 비밀번호 설정 API 연동
    setDone(true);
  };

  return (
    <Shell>
      <Typography variant="body1" sx={{ mt: 0.5, color: text.secondary }}>
        <Box component="span" sx={{ color: text.primary, fontWeight: 600 }}>
          {email}
        </Box>{' '}
        계정의 비밀번호를 설정합니다.
      </Typography>

      <Box component="form" onSubmit={handleSubmit} noValidate sx={{ mt: 3 }}>
        <Stack spacing={2}>
          <FormField label="새 비밀번호" htmlFor="new-pw" required>
            <TextField id="new-pw" type="password" fullWidth autoComplete="new-password" placeholder="8자 이상 입력" value={password} onChange={(e) => setPassword(e.target.value)} error={password.length > 0 && !longEnough} helperText={password.length > 0 && !longEnough ? '8자 이상 입력해주세요.' : undefined} />
          </FormField>
          <FormField label="비밀번호 확인" htmlFor="confirm-pw" required>
            <TextField
              id="confirm-pw"
              type="password"
              fullWidth
              autoComplete="new-password"
              placeholder="비밀번호를 한 번 더 입력"
              value={confirm}
              onChange={(e) => setConfirm(e.target.value)}
              error={confirm.length > 0 && !matches}
              helperText={confirm.length > 0 && !matches ? '비밀번호가 일치하지 않습니다.' : undefined}
            />
          </FormField>
        </Stack>

        <Stack direction="row" spacing={0.5} alignItems="center" sx={{ mt: 1.5, color: canSave ? tones.green.fg : text.tertiary }}>
          {canSave ? <CheckCircleOutlineRoundedIcon sx={{ fontSize: size.iconSm }} /> : <RadioButtonUncheckedRoundedIcon sx={{ fontSize: size.iconSm }} />}
          <Typography variant="body2" sx={{ color: 'inherit' }}>
            두 비밀번호가 일치해야 저장 버튼이 활성화됩니다.
          </Typography>
        </Stack>

        <Button type="submit" variant="contained" size="large" fullWidth disabled={!canSave} sx={{ mt: 3 }}>
          저장
        </Button>
      </Box>

      <Dialog open={done} maxWidth="xs" fullWidth>
        <DialogContent sx={{ textAlign: 'center', pt: 3 }}>
          <Box sx={{ width: size.iconBoxLg, height: size.iconBoxLg, mx: 'auto', mb: 2, borderRadius: radius.lg, display: 'grid', placeItems: 'center', bgcolor: tones.green.bg, color: tones.green.fg }}>
            <CheckRoundedIcon />
          </Box>
          <Typography variant="h3">비밀번호가 저장되었습니다</Typography>
          <Typography variant="body1" sx={{ mt: 0.5, color: text.secondary }}>
            확인을 누르면 Tetra Portal 로그인 화면으로 이동합니다.
          </Typography>
          <Button variant="contained" fullWidth sx={{ mt: 3 }} onClick={() => window.location.assign(PORTAL_LOGIN_URL)}>
            확인
          </Button>
        </DialogContent>
      </Dialog>
    </Shell>
  );
}
