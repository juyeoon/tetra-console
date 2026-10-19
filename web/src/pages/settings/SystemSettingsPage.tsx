import { useNavigate } from 'react-router-dom';
import { Avatar, Box, Button, Card, Stack, Typography } from '@mui/material';
import PageHeader from '@/components/layout/PageHeader';
import InfoList from '@/components/common/InfoList';
import SectionTitle from '@/components/common/SectionTitle';
import { mockOperator } from '@/mocks/dashboard';
import { color, font, size, text, type } from '@/theme/theme';

/** Tetra Portal의 시스템 설정 레이아웃을 재사용 (계정 정보 + 로그아웃) */
export default function SystemSettingsPage() {
  const navigate = useNavigate();
  return (
    <>
      <PageHeader breadcrumbs={['설정', '시스템 설정']} />
      <Box sx={{ maxWidth: size.contentForm }}>
        <Card sx={{ p: 3 }}>
          <Stack direction="row" alignItems="center" spacing={1.5} sx={{ mb: 3 }}>
            <Avatar sx={{ width: size.avatarMd, height: size.avatarMd, bgcolor: color.periTint, color: color.navy, ...type.body, fontWeight: font.weight.semibold }}>{mockOperator.name.slice(0, 1)}</Avatar>
            <Box>
              <Typography variant="subtitle1">{mockOperator.name}</Typography>
              <Typography variant="body2" sx={{ color: text.tertiary }}>
                플랫폼 운영자
              </Typography>
            </Box>
          </Stack>
          <SectionTitle>계정 정보</SectionTitle>
          <InfoList
            items={[
              { label: '계정 이메일', value: mockOperator.email },
              { label: '담당 사원 이름', value: mockOperator.name },
            ]}
          />
          <Stack direction="row" justifyContent="flex-end" sx={{ mt: 3 }}>
            <Button variant="outlined" color="error" onClick={() => navigate('/login')}>
              로그아웃
            </Button>
          </Stack>
        </Card>
        <Typography variant="caption" component="p" sx={{ mt: 1.5 }}>
          ※ 이 영역은 Tetra Portal의 시스템 설정 화면과 동일한 레이아웃을 재사용합니다.
        </Typography>
      </Box>
    </>
  );
}
