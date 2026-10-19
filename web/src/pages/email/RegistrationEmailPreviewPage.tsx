import { useSearchParams } from 'react-router-dom';
import { Avatar, Box, Button, Divider, Stack, Typography } from '@mui/material';
import { color, font, line, radius, size, text, type } from '@/theme/theme';

/**
 * 테넌트 계정 생성 시 발송되는 등록 안내 메일 미리보기 (OPS-07-1).
 * 실제 메일 템플릿(인라인 스타일 HTML)은 서버에서 렌더링하며, 이 화면은 문구/구성 확인용이다.
 */
export default function RegistrationEmailPreviewPage() {
  const [params] = useSearchParams();
  const tenant = params.get('tenant') ?? 'E사';
  const name = params.get('name') ?? '박민호';
  const email = params.get('email') ?? 'minho.park@e-corp.com';

  return (
    <Box sx={{ minHeight: '100vh', bgcolor: color.card, py: { xs: 0, sm: 5 }, px: { xs: 0, sm: 2 } }}>
      <Box sx={{ maxWidth: size.contentText, mx: 'auto', bgcolor: color.surface, borderRadius: { sm: radius.xl }, overflow: 'hidden' }}>
        <Box sx={{ px: { xs: 2.5, sm: 4 }, py: 1.5, borderBottom: `1px solid ${line.base}` }}>
          <Typography variant="caption">EMAIL PREVIEW · 테넌트 등록 안내</Typography>
        </Box>
        <Box sx={{ px: { xs: 2.5, sm: 4 }, py: 4 }}>
          <Typography variant="h2" component="h1" sx={{ fontFamily: font.body, fontWeight: font.weight.regular }}>
            [Tetra] 테넌트 계정이 등록되었습니다
          </Typography>
          <Stack direction="row" spacing={1.5} alignItems="center" sx={{ mt: 2 }}>
            <Avatar sx={{ width: size.avatarMd, height: size.avatarMd, bgcolor: color.navy, color: color.white, ...type.body, fontWeight: font.weight.semibold, fontFamily: font.heading }}>T</Avatar>
            <Box sx={{ minWidth: 0 }}>
              <Typography variant="body1">
                <b>Tetra Operation</b>{' '}
                <Box component="span" sx={{ color: text.tertiary }}>
                  &lt;no-reply@tetra-operation.com&gt;
                </Box>
              </Typography>
              <Typography variant="caption">받는사람: {email}</Typography>
            </Box>
          </Stack>
          <Divider sx={{ my: 3 }} />

          {/* 메일 본문 */}
          <Box>
            <Typography variant="body1" sx={{ lineHeight: font.lineHeight.title }}>
              안녕하세요, {name}님.
              <br />
              <br />
              Tetra에 {tenant} 테넌트 계정이 등록되었습니다.
              <br />
              <br />
              아래 버튼을 눌러 비밀번호를 설정해 주세요.
            </Typography>
            <Button variant="contained" size="large" href={`/password/setup?token=preview&email=${encodeURIComponent(email)}`} sx={{ mt: 3 }}>
              비밀번호 설정하기
            </Button>
            <Typography variant="body2" sx={{ mt: 1.5, color: text.secondary }}>
              이 링크는 발송 후 24시간 동안 유효하며, 비밀번호를 설정하면 다시 사용할 수 없습니다.
            </Typography>
            <Divider sx={{ my: 3 }} />
            <Typography variant="caption" component="p">
              ※ 링크가 만료되었다면 Tetra 운영자에게 재발송을 요청해 주세요.
            </Typography>
            <Typography variant="caption" component="p">
              ※ 본 메일은 발신 전용이며 회신되지 않습니다.
            </Typography>
          </Box>
        </Box>
      </Box>
    </Box>
  );
}
