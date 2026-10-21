import { Link as RouterLink } from 'react-router-dom';
import { Link, Stack, TableBody, TableCell, TableHead, TableRow } from '@mui/material';
import PageHeader from '@/components/layout/PageHeader';
import ListInfoBar from '@/components/common/ListInfoBar';
import { QueryError, QueryLoading } from '@/components/common/QueryStatus';
import StatusChip from '@/components/common/StatusChip';
import TableCard from '@/components/common/TableCard';
import { fetchTenants } from '@/api/tenants';
import type { AccountStatus } from '@/api/types';
import { useApiQuery } from '@/hooks/useApiQuery';
import { text, type } from '@/theme/theme';
import { formatDate } from '@/utils/datetime';
import { accountStatusMeta } from '@/utils/status';
import { provisioningStatusMeta } from './tenantView';

const BREADCRUMBS = ['테넌트 관리', '테넌트 현황'];

/** OPS-06 테넌트 현황 — 서버가 가입일시 내림차순으로 준다 */
export default function TenantListPage() {
  const query = useApiQuery((signal) => fetchTenants(signal), []);

  if (query.loading) {
    return (
      <>
        <PageHeader breadcrumbs={BREADCRUMBS} />
        <QueryLoading />
      </>
    );
  }
  if (!query.data) {
    return (
      <>
        <PageHeader breadcrumbs={BREADCRUMBS} />
        <QueryError error={query.error} onRetry={query.reload} />
      </>
    );
  }

  const { counts, items } = query.data;
  const countItems = (Object.keys(accountStatusMeta) as AccountStatus[]).map((key) => ({
    key,
    label: accountStatusMeta[key].label,
    tone: accountStatusMeta[key].tone,
    count: counts[key] ?? 0,
  }));

  return (
    <>
      <PageHeader breadcrumbs={BREADCRUMBS} />
      <ListInfoBar groups={[{ items: countItems }]} />

      <TableCard minWidth={820}>
        <TableHead>
          <TableRow>
            <TableCell sx={{ pl: 3 }}>테넌트ID</TableCell>
            <TableCell>테넌트명</TableCell>
            <TableCell>계정ID</TableCell>
            <TableCell>계정상태</TableCell>
            <TableCell>가입일</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {items.map((t) => {
            const meta = accountStatusMeta[t.accountStatus];
            const provisioning = provisioningStatusMeta[t.provisioningStatus];
            const to = `/tenants/${t.tenantId}`;
            return (
              <TableRow key={t.tenantId}>
                <TableCell sx={{ pl: 3 }}>
                  <Link component={RouterLink} to={to} sx={type.mono}>
                    {t.tenantId}
                  </Link>
                </TableCell>
                <TableCell>
                  <Link component={RouterLink} to={to}>
                    {t.name}
                  </Link>
                </TableCell>
                <TableCell>{t.email}</TableCell>
                <TableCell>
                  <Stack direction="row" spacing={0.75} sx={{ flexWrap: 'wrap', rowGap: 0.5 }}>
                    <StatusChip label={meta.label} tone={meta.tone} />
                    {provisioning && <StatusChip label={provisioning.label} tone={provisioning.tone} />}
                  </Stack>
                </TableCell>
                {/* 설계서 v0.5에는 없지만 추가하기로 한 열. 목록은 날짜만 */}
                <TableCell sx={{ color: text.secondary, fontVariantNumeric: 'tabular-nums' }}>{formatDate(t.createdAt)}</TableCell>
              </TableRow>
            );
          })}
          {items.length === 0 && (
            <TableRow>
              <TableCell colSpan={5} align="center" sx={{ py: 8, color: text.tertiary }}>
                등록된 테넌트가 없습니다.
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </TableCard>
    </>
  );
}
