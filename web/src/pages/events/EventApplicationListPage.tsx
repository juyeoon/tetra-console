import { useMemo } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { Link, TableBody, TableCell, TableHead, TableRow } from '@mui/material';
import PageHeader from '@/components/layout/PageHeader';
import ListInfoBar from '@/components/common/ListInfoBar';
import StatusChip from '@/components/common/StatusChip';
import TableCard from '@/components/common/TableCard';
import { useAppData } from '@/store/AppDataContext';
import { font, text, tones, type } from '@/theme/theme';
import type { TetraEvent } from '@/types';
import { applicationSortRank, applicationStatusMeta, countBy, eventTypeLabel } from '@/utils/status';

function note(e: TetraEvent) {
  if (e.applicationStatus === 'APPLIED') return '운영자 승인 대기 중';
  if (e.applicationStatus === 'APPLYING') return '자동 검수 중...';
  return e.processedAt ?? '-';
}

/** OPS-03 이벤트 신청 현황 - 목록 */
export default function EventApplicationListPage() {
  const { events, getTenant } = useAppData();

  // 승인 대기 중 → 신청됨(처리 중) → 신청 실패·승인됨·반려됨(상태 구분 없이 신청시각 최신순)
  const rows = useMemo(
    () => [...events].sort((a, b) => applicationSortRank[a.applicationStatus] - applicationSortRank[b.applicationStatus] || b.appliedAt.localeCompare(a.appliedAt)),
    [events],
  );
  const counts = countBy(
    events.map((e) => e.applicationStatus),
    applicationStatusMeta,
  );

  return (
    <>
      <PageHeader breadcrumbs={['모니터링', '이벤트 신청 현황']} />
      <ListInfoBar groups={[{ items: counts }]} />

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
          {rows.map((e) => {
            const meta = applicationStatusMeta[e.applicationStatus];
            const pending = e.applicationStatus === 'APPLIED';
            return (
              <TableRow key={e.id}>
                <TableCell>{getTenant(e.tenantId)?.name}</TableCell>
                <TableCell>{eventTypeLabel[e.type]}</TableCell>
                <TableCell>
                  <Link component={RouterLink} to={`/events/applications/${e.id}`}>
                    {e.name}
                  </Link>
                </TableCell>
                <TableCell sx={{ color: text.secondary, fontVariantNumeric: 'tabular-nums' }}>{e.appliedAt}</TableCell>
                <TableCell>
                  <StatusChip label={meta.label} tone={meta.tone} />
                </TableCell>
                {/* 처리가 필요한 건(승인 대기 중)만 강조 */}
                <TableCell sx={{ ...type.caption, color: pending ? tones.blue.fg : text.tertiary, fontWeight: pending ? font.weight.semibold : font.weight.regular }}>{note(e)}</TableCell>
              </TableRow>
            );
          })}
        </TableBody>
      </TableCard>
    </>
  );
}
