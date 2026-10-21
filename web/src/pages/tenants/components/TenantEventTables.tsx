import { Link as RouterLink } from 'react-router-dom';
import { Link, TableBody, TableCell, TableHead, TableRow } from '@mui/material';
import CopyButton from '@/components/common/CopyButton';
import PlanBadge from '@/components/common/PlanBadge';
import { QueryError, QueryLoading } from '@/components/common/QueryStatus';
import StatusChip, { EmptyValue } from '@/components/common/StatusChip';
import TableCard from '@/components/common/TableCard';
import { fetchTenantEvents, fetchTenantResources } from '@/api/tenants';
import type { ApplicationStatus } from '@/api/types';
import { useApiQuery } from '@/hooks/useApiQuery';
import { text, type } from '@/theme/theme';
import { formatDateTime } from '@/utils/datetime';
import { eventPath } from '@/utils/event';
import { applicationStatusMeta, eventStatusMeta, eventTypeLabel, hostingStatusMeta } from '@/utils/status';

function EmptyRow({ colSpan, label }: { colSpan: number; label: string }) {
  return (
    <TableRow>
      <TableCell colSpan={colSpan} align="center" sx={{ py: 8, color: text.tertiary }}>
        {label}
      </TableCell>
    </TableRow>
  );
}

/** 이벤트ID·이벤트명 링크 — 모니터링 영역의 이벤트 상세로 이동(사이드바도 전환) */
function EventLinks({ eventId, name, applicationStatus }: { eventId: number; name: string; applicationStatus: ApplicationStatus }) {
  const to = eventPath(eventId, applicationStatus);
  return (
    <>
      <TableCell>
        <Link component={RouterLink} to={to}>
          {eventId}
        </Link>
      </TableCell>
      <TableCell>
        <Link component={RouterLink} to={to}>
          {name}
        </Link>
      </TableCell>
    </>
  );
}

/** OPS-06-2 — 신청·진행 상태를 하나의 목록으로 통합 (서버가 신청시각 최신순으로 준다) */
export function TenantEventsTab({ tenantId }: { tenantId: string }) {
  const query = useApiQuery((signal) => fetchTenantEvents(tenantId, signal), [tenantId]);
  if (query.loading) return <QueryLoading />;
  if (!query.data) return <QueryError error={query.error} onRetry={query.reload} />;
  const events = query.data;

  return (
    <TableCard minWidth={1080}>
      <TableHead>
        <TableRow>
          <TableCell>이벤트ID</TableCell>
          <TableCell>이벤트명</TableCell>
          <TableCell>이벤트타입</TableCell>
          <TableCell>신청상태</TableCell>
          <TableCell>호스팅상태</TableCell>
          <TableCell>이벤트상태</TableCell>
          <TableCell align="center">구매 플랜</TableCell>
          <TableCell>신청시각</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {events.map((e) => {
          const a = applicationStatusMeta[e.applicationStatus];
          const h = e.hostingStatus && hostingStatusMeta[e.hostingStatus];
          const s = e.eventStatus && eventStatusMeta[e.eventStatus];
          return (
            <TableRow key={e.eventId}>
              <EventLinks eventId={e.eventId} name={e.name} applicationStatus={e.applicationStatus} />
              <TableCell>{eventTypeLabel[e.eventType] ?? e.eventType}</TableCell>
              <TableCell>
                <StatusChip label={a.label} tone={a.tone} />
              </TableCell>
              {/* 승인 전에는 호스팅·이벤트상태 없음 */}
              <TableCell>{h ? <StatusChip label={h.label} tone={h.tone} /> : <EmptyValue />}</TableCell>
              <TableCell>{s ? <StatusChip label={s.label} tone={s.tone} /> : <EmptyValue />}</TableCell>
              <TableCell align="center">
                <PlanBadge plan={e.trafficPlanCode} />
              </TableCell>
              <TableCell sx={{ color: text.secondary, fontVariantNumeric: 'tabular-nums' }}>{formatDateTime(e.appliedAt)}</TableCell>
            </TableRow>
          );
        })}
        {events.length === 0 && <EmptyRow colSpan={8} label="신청한 이벤트가 없습니다." />}
      </TableBody>
    </TableCard>
  );
}

/** OPS-06-3 — 승인된 이벤트만. 서브 도메인·인증키는 승인 시 발급, 만료 시 회수(서버가 null로 준다) */
export function TenantResourcesTab({ tenantId }: { tenantId: string }) {
  const query = useApiQuery((signal) => fetchTenantResources(tenantId, signal), [tenantId]);
  if (query.loading) return <QueryLoading />;
  if (!query.data) return <QueryError error={query.error} onRetry={query.reload} />;
  const resources = query.data;

  return (
    <TableCard minWidth={900}>
      <TableHead>
        <TableRow>
          <TableCell>이벤트ID</TableCell>
          <TableCell>이벤트명</TableCell>
          <TableCell>이벤트상태</TableCell>
          <TableCell>할당 서브 도메인</TableCell>
          <TableCell>인증키</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {resources.map((r) => {
          const s = r.eventStatus && eventStatusMeta[r.eventStatus];
          return (
            <TableRow key={r.eventId}>
              <EventLinks eventId={r.eventId} name={r.name} applicationStatus="APPROVED" />
              <TableCell>{s ? <StatusChip label={s.label} tone={s.tone} /> : <EmptyValue />}</TableCell>
              <TableCell sx={type.mono}>{r.subdomain ?? <EmptyValue />}</TableCell>
              {/* RS256 공개키 — 값은 노출하지 않고 복사 버튼만 */}
              <TableCell>{r.publicKey ? <CopyButton value={r.publicKey} label="공개키 복사" toast="공개키가 복사되었습니다." /> : <EmptyValue />}</TableCell>
            </TableRow>
          );
        })}
        {resources.length === 0 && <EmptyRow colSpan={5} label="할당된 리소스가 없습니다." />}
      </TableBody>
    </TableCard>
  );
}
