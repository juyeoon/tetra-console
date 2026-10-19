import { useMemo } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { Link, TableBody, TableCell, TableHead, TableRow } from '@mui/material';
import PageHeader from '@/components/layout/PageHeader';
import ListInfoBar from '@/components/common/ListInfoBar';
import StatusChip from '@/components/common/StatusChip';
import TableCard from '@/components/common/TableCard';
import { useAppData } from '@/store/AppDataContext';
import { type } from '@/theme/theme';
import { accountStatusMeta, countBy } from '@/utils/status';

export default function TenantListPage() {
  const { tenants } = useAppData();
  // 가입 순서 내림차순 (최근 가입 테넌트가 상단)
  const rows = useMemo(() => [...tenants].sort((a, b) => b.joinedAt.localeCompare(a.joinedAt)), [tenants]);
  const counts = countBy(
    tenants.map((t) => t.status),
    accountStatusMeta,
  );

  return (
    <>
      <PageHeader breadcrumbs={['테넌트 관리', '테넌트 현황']} />
      <ListInfoBar groups={[{ items: counts }]} />

      <TableCard minWidth={720}>
        <TableHead>
          <TableRow>
            <TableCell sx={{ pl: 3 }}>테넌트ID</TableCell>
            <TableCell>테넌트명</TableCell>
            <TableCell>계정ID</TableCell>
            <TableCell>계정상태</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {rows.map((t) => {
            const meta = accountStatusMeta[t.status];
            const to = `/tenants/${t.id}`;
            return (
              <TableRow key={t.id}>
                <TableCell sx={{ pl: 3 }}>
                  <Link component={RouterLink} to={to} sx={type.mono}>
                    {t.id}
                  </Link>
                </TableCell>
                <TableCell>
                  <Link component={RouterLink} to={to}>
                    {t.name}
                  </Link>
                </TableCell>
                <TableCell>{t.email}</TableCell>
                <TableCell>
                  <StatusChip label={meta.label} tone={meta.tone} />
                </TableCell>
              </TableRow>
            );
          })}
        </TableBody>
      </TableCard>
    </>
  );
}
