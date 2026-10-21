import { Link as RouterLink } from 'react-router-dom';
import { Link, TableBody, TableCell, TableHead, TableRow } from '@mui/material';
import PageHeader from '@/components/layout/PageHeader';
import ListInfoBar from '@/components/common/ListInfoBar';
import PlanBadge from '@/components/common/PlanBadge';
import { QueryError, QueryLoading } from '@/components/common/QueryStatus';
import StatusChip, { EmptyValue } from '@/components/common/StatusChip';
import TableCard from '@/components/common/TableCard';
import { fetchLiveEvents } from '@/api/events';
import type { HostingStatus } from '@/api/types';
import { useApiQuery } from '@/hooks/useApiQuery';
import { text } from '@/theme/theme';
import { formatDateTime } from '@/utils/datetime';
import { countBy, eventStatusMeta, eventTypeLabel, hostingStatusMeta } from '@/utils/status';

const BREADCRUMBS = ['모니터링', '이벤트 진행 현황'];

/** 건수는 기본 3종(배포 대기·호스팅 중·호스팅 종료) + 해당 건이 있는 나머지 상태 */
const BASE_HOSTING: HostingStatus[] = ['PENDING', 'HOSTING', 'ENDED'];

/** OPS-04 이벤트 진행 현황 - 목록 (승인된 이벤트, 순서는 서버) */
export default function EventProgressListPage() {
  const query = useApiQuery((signal) => fetchLiveEvents(signal), []);

  if (!query.data) {
    return (
      <>
        <PageHeader breadcrumbs={BREADCRUMBS} />
        {query.loading ? <QueryLoading /> : <QueryError error={query.error} onRetry={query.reload} />}
      </>
    );
  }

  const { items } = query.data;
  // 서버 counts는 기본 3종뿐이라, 실패 계열도 보이도록 목록에서 직접 센다
  const counts = countBy(
    items.map((e) => e.hostingStatus).filter((s): s is HostingStatus => !!s),
    hostingStatusMeta,
  ).filter((c) => BASE_HOSTING.includes(c.key) || c.count > 0);

  return (
    <>
      <PageHeader breadcrumbs={BREADCRUMBS} />
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
          {items.map((e) => {
            const h = e.hostingStatus && hostingStatusMeta[e.hostingStatus];
            const s = e.eventStatus && eventStatusMeta[e.eventStatus];
            return (
              <TableRow key={e.eventId}>
                <TableCell>{e.tenantName}</TableCell>
                <TableCell>{eventTypeLabel[e.eventType] ?? e.eventType}</TableCell>
                <TableCell>
                  <Link component={RouterLink} to={`/events/progress/${e.eventId}`}>
                    {e.name}
                  </Link>
                </TableCell>
                <TableCell sx={{ color: text.secondary, fontVariantNumeric: 'tabular-nums' }}>{formatDateTime(e.hostingStartAt)}</TableCell>
                <TableCell sx={{ color: text.secondary, fontVariantNumeric: 'tabular-nums' }}>{formatDateTime(e.startAt)}</TableCell>
                <TableCell>{h ? <StatusChip label={h.label} tone={h.tone} /> : <EmptyValue />}</TableCell>
                <TableCell>{s ? <StatusChip label={s.label} tone={s.tone} /> : <EmptyValue />}</TableCell>
                <TableCell align="center">
                  <PlanBadge plan={e.trafficPlanCode} />
                </TableCell>
              </TableRow>
            );
          })}
          {items.length === 0 && (
            <TableRow>
              <TableCell colSpan={8} align="center" sx={{ py: 8, color: text.tertiary }}>
                승인된 이벤트가 없습니다.
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </TableCard>
    </>
  );
}
