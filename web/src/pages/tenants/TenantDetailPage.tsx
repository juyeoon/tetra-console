import { useMemo } from 'react';
import { useParams } from 'react-router-dom';
import { Stack, Tab, Tabs } from '@mui/material';
import PageHeader from '@/components/layout/PageHeader';
import StatusChip from '@/components/common/StatusChip';
import { useTabParam } from '@/components/common/useTabParam';
import NotFoundView from '@/components/common/NotFoundView';
import { useAppData } from '@/store/AppDataContext';
import { accountStatusMeta } from '@/utils/status';
import { TenantEventsTable, TenantResourcesTable } from './components/TenantEventTables';
import TenantOverview from './components/TenantOverview';

const TABS = ['overview', 'events', 'resources'] as const;
const BREADCRUMBS = ['테넌트 관리', '테넌트 현황', '상세'];
const BACK = { to: '/tenants', label: '테넌트 현황 목록으로 돌아가기' };

export default function TenantDetailPage() {
  const { tenantId = '' } = useParams();
  const { getTenant, events } = useAppData();
  const [tab, setTab] = useTabParam(TABS, 'overview');
  const tenant = getTenant(tenantId);
  const tenantEvents = useMemo(
    () => events.filter((e) => e.tenantId === tenantId).sort((a, b) => b.appliedAt.localeCompare(a.appliedAt)),
    [events, tenantId],
  );

  if (!tenant) return <NotFoundView breadcrumbs={BREADCRUMBS} back={BACK} message="테넌트를 찾을 수 없습니다" />;

  const meta = accountStatusMeta[tenant.status];
  return (
    <>
      <PageHeader
        breadcrumbs={BREADCRUMBS}
        back={BACK}
        title={
          <Stack direction="row" alignItems="center" spacing={1.5} component="span">
            <span>{tenant.name}</span>
            <StatusChip label={meta.label} tone={meta.tone} />
          </Stack>
        }
      />
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 3 }}>
        <Tab value="overview" label="개요" />
        <Tab value="events" label="이벤트" />
        <Tab value="resources" label="리소스" />
      </Tabs>
      {tab === 'overview' && <TenantOverview tenant={tenant} />}
      {tab === 'events' && <TenantEventsTable events={tenantEvents} />}
      {tab === 'resources' && <TenantResourcesTable events={tenantEvents} />}
    </>
  );
}
