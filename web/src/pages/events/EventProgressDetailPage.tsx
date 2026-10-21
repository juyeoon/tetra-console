import { useEffect, useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Button, Stack, Tab, Tabs, Tooltip, Typography } from '@mui/material';
import FileDownloadRoundedIcon from '@mui/icons-material/FileDownloadRounded';
import PageHeader from '@/components/layout/PageHeader';
import NotFoundView from '@/components/common/NotFoundView';
import { QueryError, QueryLoading } from '@/components/common/QueryStatus';
import { useTabParam } from '@/components/common/useTabParam';
import { errorMessage } from '@/api/client';
import { fetchEventResult, fetchHistoryCsvUrl } from '@/api/events';
import { useApiQuery } from '@/hooks/useApiQuery';
import { useToast } from '@/store/ToastContext';
import { size, text } from '@/theme/theme';
import ApplicationContentPlaceholder from './components/ApplicationContentPlaceholder';
import EventOverview from './components/EventOverview';
import EventResultPanel from './components/EventResultPanel';
import { toResultView, type ResultView } from './eventView';
import { useEventDetail } from './useEventDetail';

const TABS = ['overview', 'application', 'result'] as const;
const BREADCRUMBS = ['모니터링', '이벤트 진행 현황', '상세'];
const BACK = { to: '/events/progress', label: '이벤트 진행 현황 목록으로 돌아가기' };

/**
 * 발급 이력 CSV — 이벤트 종료 처리(WF4)가 만든 파일의 presigned URL을 받아 내려받는다.
 * 결과 전이면 서버가 409 RESULT_NOT_READY를 주고, 그 문구를 토스트로 보여 준다.
 */
function ResultActions({ eventId, result }: { eventId: number; result: ResultView | undefined }) {
  const toast = useToast();
  const [loading, setLoading] = useState(false);
  const ready = !!result?.csvReady;

  const download = async () => {
    setLoading(true);
    try {
      const { url } = await fetchHistoryCsvUrl(eventId);
      window.location.assign(url);
      toast('발급 이력 CSV 다운로드를 시작했습니다.');
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} alignItems={{ xs: 'flex-start', sm: 'center' }}>
      <Typography variant="body2" sx={{ color: text.tertiary }}>
        이벤트 오픈 {result?.openAt ?? '-'} · 집계 기준 {result?.aggregatedAt ?? '-'}
      </Typography>
      <Tooltip title={ready ? '' : '결과가 집계된 뒤 내려받을 수 있습니다.'}>
        <span>
          <Button
            variant="outlined"
            startIcon={<FileDownloadRoundedIcon sx={{ fontSize: `${size.iconMd} !important` }} />}
            disabled={!ready}
            loading={loading}
            onClick={() => void download()}
          >
            발급 이력 CSV 다운로드
          </Button>
        </span>
      </Tooltip>
    </Stack>
  );
}

/** 진행 결과 탭 — 탭을 열 때 결과 API를 부른다 */
function ResultTab({ eventId, onLoaded }: { eventId: number; onLoaded: (result: ResultView) => void }) {
  const query = useApiQuery((signal) => fetchEventResult(eventId, signal), [eventId]);
  const result = useMemo(() => (query.data ? toResultView(query.data, new Date()) : undefined), [query.data]);
  // 머리글(오픈 · 집계 시각, CSV 버튼)도 같은 결과를 쓴다
  useEffect(() => {
    if (result) onLoaded(result);
  }, [result, onLoaded]);
  if (query.loading && !result) return <QueryLoading />;
  if (!result) return <QueryError error={query.error} onRetry={query.reload} />;
  return <EventResultPanel result={result} />;
}

/** OPS-04-1 / 04-2 / 04-3 이벤트 진행 현황 상세 */
export default function EventProgressDetailPage() {
  const { eventId: rawEventId } = useParams();
  const detail = useEventDetail(rawEventId);
  const [tab, setTab] = useTabParam(TABS, 'overview');
  const [result, setResult] = useState<ResultView | undefined>(undefined);
  const { event } = detail;

  // 승인되지 않은 이벤트는 진행 현황에 없다
  if (detail.notFound || (event && event.applicationStatus !== 'APPROVED')) {
    return <NotFoundView breadcrumbs={BREADCRUMBS} back={BACK} message="이벤트를 찾을 수 없습니다" />;
  }
  if (!event) {
    return (
      <>
        <PageHeader breadcrumbs={BREADCRUMBS} back={BACK} title="상세" />
        {detail.loading ? <QueryLoading /> : <QueryError error={detail.error} onRetry={detail.reload} />}
      </>
    );
  }

  return (
    <>
      <PageHeader
        breadcrumbs={BREADCRUMBS}
        back={BACK}
        title={event.name}
        actions={tab === 'result' ? <ResultActions eventId={event.id} result={result} /> : undefined}
      />
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 3 }}>
        <Tab value="overview" label="개요" />
        <Tab value="application" label="신청 내용" />
        <Tab value="result" label="이벤트 진행 결과" />
      </Tabs>
      {tab === 'overview' && <EventOverview event={event} />}
      {tab === 'application' && <ApplicationContentPlaceholder />}
      {tab === 'result' && <ResultTab eventId={event.id} onLoaded={setResult} />}
    </>
  );
}
