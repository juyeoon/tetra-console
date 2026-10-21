import { Link as RouterLink } from 'react-router-dom';
import { Link, TableBody, TableCell, TableHead, TableRow } from '@mui/material';
import PageHeader from '@/components/layout/PageHeader';
import ListInfoBar from '@/components/common/ListInfoBar';
import { QueryError, QueryLoading } from '@/components/common/QueryStatus';
import StatusChip from '@/components/common/StatusChip';
import TableCard from '@/components/common/TableCard';
import { fetchApplications } from '@/api/events';
import type { ApplicationItem, ApplicationStatus } from '@/api/types';
import { useApiQuery } from '@/hooks/useApiQuery';
import { font, text, tones, type } from '@/theme/theme';
import { formatDateTime } from '@/utils/datetime';
import { applicationStatusMeta, eventTypeLabel } from '@/utils/status';

const BREADCRUMBS = ['모니터링', '이벤트 신청 현황'];

function note(e: ApplicationItem) {
  if (e.applicationStatus === 'APPLIED') return '운영자 승인 대기 중';
  if (e.applicationStatus === 'APPLYING') return '자동 검수 중...';
  return e.processedAt ? formatDateTime(e.processedAt) : '-';
}

/** OPS-03 이벤트 신청 현황 - 목록. 정렬은 서버가 한다(승인 대기 중 → 신청됨(처리 중) → 나머지 신청시각 최신순) */
export default function EventApplicationListPage() {
  const query = useApiQuery((signal) => fetchApplications(signal), []);

  if (!query.data) {
    return (
      <>
        <PageHeader breadcrumbs={BREADCRUMBS} />
        {query.loading ? <QueryLoading /> : <QueryError error={query.error} onRetry={query.reload} />}
      </>
    );
  }

  const { counts, items } = query.data;
  const countItems = (Object.keys(applicationStatusMeta) as ApplicationStatus[]).map((key) => ({
    key,
    label: applicationStatusMeta[key].label,
    tone: applicationStatusMeta[key].tone,
    count: counts[key] ?? 0,
  }));

  return (
    <>
      <PageHeader breadcrumbs={BREADCRUMBS} />
      <ListInfoBar groups={[{ items: countItems }]} />

      <TableCard minWidth={960}>
        <TableHead>
          <TableRow>
            <TableCell>테넌트명</TableCell>
            <TableCell>이벤트타입</TableCell>
            <TableCell>이벤트명</TableCell>
            <TableCell>신청시각</TableCell>
            <TableCell>신청상태</TableCell>
            <TableCell>비고</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {items.map((e) => {
            const meta = applicationStatusMeta[e.applicationStatus];
            const pending = e.applicationStatus === 'APPLIED';
            return (
              <TableRow key={e.eventId}>
                <TableCell>{e.tenantName}</TableCell>
                <TableCell>{eventTypeLabel[e.eventType] ?? e.eventType}</TableCell>
                <TableCell>
                  <Link component={RouterLink} to={`/events/applications/${e.eventId}`}>
                    {e.name}
                  </Link>
                </TableCell>
                <TableCell sx={{ color: text.secondary, fontVariantNumeric: 'tabular-nums' }}>{formatDateTime(e.appliedAt)}</TableCell>
                <TableCell>
                  <StatusChip label={meta.label} tone={meta.tone} />
                </TableCell>
                {/* 처리가 필요한 건(승인 대기 중)만 강조 */}
                <TableCell sx={{ ...type.caption, color: pending ? tones.blue.fg : text.tertiary, fontWeight: pending ? font.weight.semibold : font.weight.regular }}>{note(e)}</TableCell>
              </TableRow>
            );
          })}
          {items.length === 0 && (
            <TableRow>
              <TableCell colSpan={6} align="center" sx={{ py: 8, color: text.tertiary }}>
                신청된 이벤트가 없습니다.
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </TableCard>
    </>
  );
}
