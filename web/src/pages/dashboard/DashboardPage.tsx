import { useMemo, type ReactNode } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { Box, ButtonBase, Card, Link, Stack, Typography } from '@mui/material';
import ErrorOutlineRoundedIcon from '@mui/icons-material/ErrorOutlineRounded';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import WarningAmberRoundedIcon from '@mui/icons-material/WarningAmberRounded';
import PageHeader from '@/components/layout/PageHeader';
import { QueryError, QueryLoading } from '@/components/common/QueryStatus';
import SampleDataChip from '@/components/common/SampleDataChip';
import SectionTitle from '@/components/common/SectionTitle';
import StatCard from '@/components/common/StatCard';
import { fetchDashboard } from '@/api/dashboard';
import type { NotificationPriority } from '@/api/types';
import { useApiQuery } from '@/hooks/useApiQuery';
import { mockDashboard } from '@/mocks/dashboard';
import { ink, layout, radius, size, text, tones } from '@/theme/theme';
import { formatDateTime } from '@/utils/datetime';
import { priorityMeta } from '@/utils/status';
import { alertView } from './dashboardView';
import LiveTrafficCard from './LiveTrafficCard';
import TenantFlowCard from './TenantFlowCard';

const BREADCRUMBS = ['대시보드 (홈)'];

/** 우선순위별 아이콘. 색은 알림센터 태그와 같은 priorityMeta */
const alertIcon: Record<NotificationPriority, ReactNode> = {
  URGENT: <ErrorOutlineRoundedIcon />,
  WARNING: <WarningAmberRoundedIcon />,
  INFO: <InfoOutlinedIcon />,
};

/** OPS-02 대시보드 — 테넌트 수 · 진행 중 이벤트 · 최근 알림은 API, 클러스터 · 트래픽 · 상위 테넌트는 예시 데이터(API 없음) */
export default function DashboardPage() {
  const query = useApiQuery((signal) => fetchDashboard(signal), []);
  const alerts = useMemo(() => (query.data ? query.data.recentNotifications.map((n) => alertView(n, new Date())) : []), [query.data]);

  if (!query.data) {
    return (
      <>
        <PageHeader breadcrumbs={BREADCRUMBS} />
        {query.loading ? <QueryLoading /> : <QueryError error={query.error} onRetry={query.reload} />}
      </>
    );
  }

  const { tenants, openEvents } = query.data;
  const cluster = mockDashboard.cluster;
  return (
    <>
      <PageHeader breadcrumbs={BREADCRUMBS} />

      <Box sx={{ display: 'grid', gap: layout.cardGap, gridTemplateColumns: { xs: '1fr', md: 'repeat(3, 1fr)' }, mb: layout.cardGap }}>
        <StatCard fill="peri" label="활성 테넌트 수 / 전체 테넌트 수" value={`${tenants.active}개사 / ${tenants.total}개사`} sub={`이번 달 신규 ${tenants.newThisMonth}개사`} />
        {/* 숫자만 표시, 기준 시각은 카드 제목 옆 괄호 */}
        <StatCard
          fill="blue"
          label={
            <>
              현재 진행 중인 이벤트{' '}
              <Box component="span" sx={{ fontWeight: 400, color: text.tertiary, whiteSpace: 'nowrap' }}>
                ({formatDateTime(openEvents.asOf)} 기준)
              </Box>
            </>
          }
          value={`${openEvents.count}건`}
        />
        <StatCard
          fill="peri"
          label={
            <>
              클러스터 사용량 (전체)
              <SampleDataChip />
            </>
          }
          value={`CPU ${cluster.cpu}% · Mem ${cluster.mem}%`}
          sub={`노드 ${cluster.nodes}/${cluster.maxNodes} · ${cluster.autoscale}`}
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
          {/* 운영자 알림 최신 5건 — 클릭 시 알림센터로 이동 */}
          <Stack spacing={0.5}>
            {alerts.map((a) => {
              const tone = tones[priorityMeta[a.priority].tone];
              return (
                <ButtonBase
                  key={a.id}
                  component={RouterLink}
                  to="/logs/notifications"
                  sx={{ justifyContent: 'flex-start', alignItems: 'flex-start', gap: 1, p: 1, mx: -1, borderRadius: radius.md, textAlign: 'left', '&:hover': { bgcolor: ink[5] } }}
                >
                  <Box sx={{ width: size.iconBox, height: size.iconBox, flexShrink: 0, borderRadius: radius.md, display: 'grid', placeItems: 'center', bgcolor: tone.bg, color: tone.fg, '& svg': { fontSize: size.iconSm } }}>
                    {alertIcon[a.priority]}
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
            {alerts.length === 0 && (
              <Typography variant="body2" sx={{ color: text.tertiary, py: 2 }}>
                새 알림이 없습니다.
              </Typography>
            )}
          </Stack>
        </Card>
        <TenantFlowCard />
      </Box>
    </>
  );
}
