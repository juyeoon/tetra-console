import { useMemo } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { Link, TableBody, TableCell, TableHead, TableRow } from '@mui/material';
import PageHeader from '@/components/layout/PageHeader';
import ListInfoBar from '@/components/common/ListInfoBar';
import PlanBadge from '@/components/common/PlanBadge';
import StatusChip, { EmptyValue } from '@/components/common/StatusChip';
import TableCard from '@/components/common/TableCard';
import { useAppData } from '@/store/AppDataContext';
import { text } from '@/theme/theme';
import type { HostingStatus } from '@/types';
import { countBy, eventStatusMeta, eventTypeLabel, hostingStatusMeta } from '@/utils/status';

/** 건수는 기본 3종(배포 대기·호스팅 중·호스팅 종료) + 해당 건이 있는 나머지 상태 */
const BASE_HOSTING: HostingStatus[] = ['PENDING', 'HOSTING', 'ENDED'];

/** OPS-04 이벤트 진행 현황 - 목록 */
export default function EventProgressListPage() {
  const { events, getTenant } = useAppData();

  // 신청상태가 '승인됨'인 이벤트만, 이벤트 시작 시각 내림차순
  const rows = useMemo(
    () => events.filter((e) => e.applicationStatus === 'APPROVED').sort((a, b) => b.eventPeriod.start.localeCompare(a.eventPeriod.start)),
    [events],
  );
  const counts = countBy(
    rows.map((e) => e.hostingStatus).filter((s): s is HostingStatus => !!s),
    hostingStatusMeta,
  ).filter((c) => BASE_HOSTING.includes(c.key) || c.count > 0);

  return (
    <>
      <PageHeader breadcrumbs={['모니터링', '이벤트 진행 현황']} />
      <ListInfoBar groups={[{ items: counts }]} />

      <TableCard minWidth={1080}>
        <TableHead>
          <TableRow>
            <TableCell>테넌트명</TableCell>
            <TableCell>이벤트타입</TableCell>
            <TableCell>이벤트명</TableCell>
            <TableCell>호스팅 시작일</TableCell>
            <TableCell>이벤트 시작일</TableCell>
            <TableCell>호스팅상태</TableCell>
            <TableCell>이벤트상태</TableCell>
            <TableCell align="center">구매 플랜</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {rows.map((e) => {
            const h = e.hostingStatus && hostingStatusMeta[e.hostingStatus];
            const s = e.eventStatus && eventStatusMeta[e.eventStatus];
            return (
              <TableRow key={e.id}>
                <TableCell>{getTenant(e.tenantId)?.name}</TableCell>
                <TableCell>{eventTypeLabel[e.type]}</TableCell>
                <TableCell>
                  <Link component={RouterLink} to={`/events/progress/${e.id}`}>
                    {e.name}
                  </Link>
                </TableCell>
                <TableCell sx={{ color: text.secondary, fontVariantNumeric: 'tabular-nums' }}>{e.hostingPeriod.start}</TableCell>
                <TableCell sx={{ color: text.secondary, fontVariantNumeric: 'tabular-nums' }}>{e.eventPeriod.start}</TableCell>
                <TableCell>{h ? <StatusChip label={h.label} tone={h.tone} /> : <EmptyValue />}</TableCell>
                <TableCell>{s ? <StatusChip label={s.label} tone={s.tone} /> : <EmptyValue />}</TableCell>
                <TableCell align="center">
                  <PlanBadge plan={e.plan} />
                </TableCell>
              </TableRow>
            );
          })}
        </TableBody>
      </TableCard>
    </>
  );
}
