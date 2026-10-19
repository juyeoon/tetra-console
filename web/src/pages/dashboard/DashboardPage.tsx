import { Link as RouterLink } from 'react-router-dom';
import { Box, ButtonBase, Card, Link, Stack, Typography } from '@mui/material';
import CheckCircleOutlineRoundedIcon from '@mui/icons-material/CheckCircleOutlineRounded';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import WarningAmberRoundedIcon from '@mui/icons-material/WarningAmberRounded';
import PageHeader from '@/components/layout/PageHeader';
import SectionTitle from '@/components/common/SectionTitle';
import StatCard from '@/components/common/StatCard';
import { mockDashboard } from '@/mocks/dashboard';
import { ink, layout, radius, size, text, tones } from '@/theme/theme';
import LiveTrafficCard from './LiveTrafficCard';
import TenantFlowCard from './TenantFlowCard';

const alertMeta = {
  warning: { icon: <WarningAmberRoundedIcon />, tone: tones.amber },
  info: { icon: <InfoOutlinedIcon />, tone: tones.blue },
  success: { icon: <CheckCircleOutlineRoundedIcon />, tone: tones.green },
};

export default function DashboardPage() {
  const d = mockDashboard;
  return (
    <>
      <PageHeader breadcrumbs={['대시보드 (홈)']} />

      <Box sx={{ display: 'grid', gap: layout.cardGap, gridTemplateColumns: { xs: '1fr', md: 'repeat(3, 1fr)' }, mb: layout.cardGap }}>
        <StatCard fill="peri" label="활성 테넌트 수 / 전체 테넌트 수" value={`${d.tenants.active}개사 / ${d.tenants.total}개사`} sub={`이번 달 신규 ${d.tenants.newThisMonth}개사`} />
        {/* 숫자만 표시, 기준 시각은 카드 제목 옆 괄호 */}
        <StatCard
          fill="blue"
          label={
            <>
              현재 진행 중인 이벤트{' '}
              <Box component="span" sx={{ fontWeight: 400, color: text.tertiary, whiteSpace: 'nowrap' }}>
                ({d.asOf} 기준)
              </Box>
            </>
          }
          value={`${d.ongoingEvents}건`}
        />
        <StatCard
          fill="peri"
          label="클러스터 사용량 (전체)"
          value={`CPU ${d.cluster.cpu}% · Mem ${d.cluster.mem}%`}
          sub={`노드 ${d.cluster.nodes}/${d.cluster.maxNodes} · ${d.cluster.autoscale}`}
        />
      </Box>

      <Box sx={{ mb: layout.cardGap }}>
        <LiveTrafficCard />
      </Box>

      <Box sx={{ display: 'grid', gap: layout.cardGap, gridTemplateColumns: { xs: '1fr', lg: 'minmax(320px, 28%) 1fr' } }}>
        <Card sx={{ p: 3 }}>
          <SectionTitle
            action={
              <Link component={RouterLink} to="/logs/notifications" variant="body2" underline="hover" sx={{ color: text.tertiary }}>
                알림센터 →
              </Link>
            }
          >
            최근 알림
          </SectionTitle>
          {/* 알림센터 최신 5건 — 클릭 시 알림센터로 이동 */}
          <Stack spacing={0.5}>
            {d.alerts.slice(0, 5).map((a) => {
              const m = alertMeta[a.level];
              return (
                <ButtonBase
                  key={a.id}
                  component={RouterLink}
                  to="/logs/notifications"
                  sx={{ justifyContent: 'flex-start', alignItems: 'flex-start', gap: 1, p: 1, mx: -1, borderRadius: radius.md, textAlign: 'left', '&:hover': { bgcolor: ink[5] } }}
                >
                  <Box sx={{ width: size.iconBox, height: size.iconBox, flexShrink: 0, borderRadius: radius.md, display: 'grid', placeItems: 'center', bgcolor: m.tone.bg, color: m.tone.fg, '& svg': { fontSize: size.iconSm } }}>
                    {m.icon}
                  </Box>
                  <Box sx={{ minWidth: 0 }}>
                    <Typography variant="body1" noWrap>
                      {a.title}
                    </Typography>
                    <Typography variant="caption">{a.ago}</Typography>
                  </Box>
                </ButtonBase>
              );
            })}
          </Stack>
        </Card>
        <TenantFlowCard />
      </Box>
    </>
  );
}
