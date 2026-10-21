import { useParams } from 'react-router-dom';
import { Stack, Tab, Tabs } from '@mui/material';
import PageHeader from '@/components/layout/PageHeader';
import StatusChip from '@/components/common/StatusChip';
import { useTabParam } from '@/components/common/useTabParam';
import NotFoundView from '@/components/common/NotFoundView';
import { QueryError, QueryLoading } from '@/components/common/QueryStatus';
import { ApiError } from '@/api/client';
import { fetchTenantOverview } from '@/api/tenants';
import type { TenantBasic } from '@/api/types';
import { useApiQuery } from '@/hooks/useApiQuery';
import { accountStatusMeta } from '@/utils/status';
import { TenantEventsTab, TenantResourcesTab } from './components/TenantEventTables';
import TenantOverview from './components/TenantOverview';
import { provisioningStatusMeta } from './tenantView';

const TABS = ['overview', 'events', 'resources'] as const;
const BREADCRUMBS = ['테넌트 관리', '테넌트 현황', '상세'];
const BACK = { to: '/tenants', label: '테넌트 현황 목록으로 돌아가기' };

export default function TenantDetailPage() {
  const { tenantId = '' } = useParams();
  const [tab, setTab] = useTabParam(TABS, 'overview');
  const overview = useApiQuery((signal) => fetchTenantOverview(tenantId, signal), [tenantId]);

  if (overview.error instanceof ApiError && overview.error.status === 404) {
    return <NotFoundView breadcrumbs={BREADCRUMBS} back={BACK} message="테넌트를 찾을 수 없습니다" />;
  }
  if (overview.loading || !overview.data) {
    return (
      <>
        <PageHeader breadcrumbs={BREADCRUMBS} back={BACK} title="상세" />
        {overview.loading ? <QueryLoading /> : <QueryError error={overview.error} onRetry={overview.reload} />}
      </>
    );
  }

  const { basic } = overview.data;
  const meta = accountStatusMeta[basic.accountStatus];
  const provisioning = provisioningStatusMeta[basic.provisioningStatus];
  // 수정(PATCH) 응답으로 기본 정보만 바꾼다. 계정 상태 로그는 수정으로 바뀌지 않는다
  const onUpdated = (next: TenantBasic) => overview.setData((prev) => (prev ? { ...prev, basic: next } : prev));

  return (
    <>
      <PageHeader
        breadcrumbs={BREADCRUMBS}
        back={BACK}
        title={
          <Stack direction="row" alignItems="center" spacing={1.5} component="span">
            <span>{basic.name}</span>
            <StatusChip label={meta.label} tone={meta.tone} />
            {provisioning && <StatusChip label={provisioning.label} tone={provisioning.tone} />}
          </Stack>
        }
      />
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 3 }}>
        <Tab value="overview" label="개요" />
        <Tab value="events" label="이벤트" />
        <Tab value="resources" label="리소스" />
      </Tabs>
      {tab === 'overview' && <TenantOverview overview={overview.data} onUpdated={onUpdated} />}
      {tab === 'events' && <TenantEventsTab tenantId={tenantId} />}
      {tab === 'resources' && <TenantResourcesTab tenantId={tenantId} />}
    </>
  );
}
