import { useParams } from 'react-router-dom';
import { Button, Stack, Tab, Tabs, Tooltip, Typography } from '@mui/material';
import FileDownloadRoundedIcon from '@mui/icons-material/FileDownloadRounded';
import PageHeader from '@/components/layout/PageHeader';
import { useTabParam } from '@/components/common/useTabParam';
import { useAppData } from '@/store/AppDataContext';
import { useToast } from '@/store/ToastContext';
import { size, text } from '@/theme/theme';
import type { TetraEvent } from '@/types';
import { withSeconds } from '@/utils/event';
import ApplicationContentPlaceholder from './components/ApplicationContentPlaceholder';
import NotFoundView from '@/components/common/NotFoundView';
import EventOverview from './components/EventOverview';
import EventResultPanel from './components/EventResultPanel';

const TABS = ['overview', 'application', 'result'] as const;
const BREADCRUMBS = ['모니터링', '이벤트 진행 현황', '상세'];
const BACK = { to: '/events/progress', label: '이벤트 진행 현황 목록으로 돌아가기' };

/** TODO: 발급 이력 CSV 다운로드 API(이벤트 종료 처리 WF4에서 생성된 파일)로 교체. 현재는 분당 집계를 CSV로 내려준다 */
function downloadCsv(event: TetraEvent) {
  if (event.result.state !== 'DONE') return;
  const lines = ['minute,issued,sold_out', ...event.result.perMinute.map((m) => `${m.minute},${m.issued},${m.soldOut}`)];
  const blob = new Blob(['﻿' + lines.join('\n')], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `issuance_${event.id}.csv`;
  a.click();
  URL.revokeObjectURL(url);
}

function ResultActions({ event }: { event: TetraEvent }) {
  const toast = useToast();
  const done = event.result.state === 'DONE';
  const aggregatedAt = event.result.state === 'DONE' ? event.result.aggregatedAt : '-';
  return (
    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} alignItems={{ xs: 'flex-start', sm: 'center' }}>
      <Typography variant="body2" sx={{ color: text.tertiary }}>
        이벤트 오픈 {withSeconds(event.eventPeriod.start)} · 집계 기준 {aggregatedAt}
      </Typography>
      <Tooltip title={done ? '' : '결과가 집계된 뒤 내려받을 수 있습니다.'}>
        <span>
          <Button
            variant="outlined"
            startIcon={<FileDownloadRoundedIcon sx={{ fontSize: `${size.iconMd} !important` }} />}
            disabled={!done}
            onClick={() => {
              downloadCsv(event);
              toast('발급 이력 CSV 다운로드를 시작했습니다.');
            }}
          >
            발급 이력 CSV 다운로드
          </Button>
        </span>
      </Tooltip>
    </Stack>
  );
}

/** OPS-04-1 / 04-2 / 04-3 이벤트 진행 현황 상세 */
export default function EventProgressDetailPage() {
  const { eventId } = useParams();
  const { getEvent } = useAppData();
  const [tab, setTab] = useTabParam(TABS, 'overview');
  const event = getEvent(Number(eventId));

  if (!event || event.applicationStatus !== 'APPROVED') return <NotFoundView breadcrumbs={BREADCRUMBS} back={BACK} message="이벤트를 찾을 수 없습니다" />;

  return (
    <>
      <PageHeader
        breadcrumbs={BREADCRUMBS}
        back={BACK}
        title={event.name}
        actions={tab === 'result' ? <ResultActions event={event} /> : undefined}
      />
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 3 }}>
        <Tab value="overview" label="개요" />
        <Tab value="application" label="신청 내용" />
        <Tab value="result" label="이벤트 진행 결과" />
      </Tabs>
      {tab === 'overview' && <EventOverview event={event} />}
      {tab === 'application' && <ApplicationContentPlaceholder />}
      {tab === 'result' && <EventResultPanel event={event} />}
    </>
  );
}
