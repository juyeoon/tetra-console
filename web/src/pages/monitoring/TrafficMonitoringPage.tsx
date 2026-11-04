import { useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Box } from '@mui/material';
import PageHeader from '@/components/layout/PageHeader';
import { fetchTrafficEvent, fetchTrafficEvents, fetchTrafficPlatform } from '@/api/monitoring';
import type { TrafficEventResponse, TrafficRangeKey } from '@/api/types';
import { usePolling } from '@/hooks/usePolling';
import { layout } from '@/theme/theme';
import EventSection from './EventSection';
import FailuresCard from './FailuresCard';
import NodesCard from './NodesCard';
import { PlatformCard, UpcomingCard } from './PlatformCards';
import StatusSummary, { HeaderIndicator } from './StatusSummary';
import {
  addonsView,
  awsDelayed,
  eventOptions,
  failureRows,
  grafanaBase,
  headerView,
  historyView,
  lcuView,
  poolViews,
  POLL_MS,
  rdsText,
  selectedEventId,
  serviceTexts,
  stopPollingWhen,
  summaryCells,
  upcomingViews,
} from './trafficMonitoringView';

const BREADCRUMBS = ['모니터링', '트래픽 모니터링'];

/**
 * OPS-05 트래픽 모니터링(명세 docs/ops05). 세 API를 15초마다(탭이 숨겨지면 멈춤):
 * - GET /monitoring/platform → A 상태 요약 · B 노드 · 플랫폼 · 다가오는 이벤트 · E 최근 자동화 실패
 * - GET /monitoring/events → 드롭다운(기본 선택은 서버 defaultEventId)
 * - GET /monitoring/events/{id}?range= → C 진행 단계 · D KPI · 그래프(종료된 이벤트는 한 번만)
 * 운영자가 고른 이벤트는 주소 ?eventId=에 남겨 자동 갱신 · 새로고침 · 링크 공유 때 유지한다.
 */
export default function TrafficMonitoringPage() {
  const [params, setParams] = useSearchParams();
  const platform = usePolling((signal) => fetchTrafficPlatform(signal), POLL_MS);
  const events = usePolling((signal) => fetchTrafficEvents(signal), POLL_MS);
  const eventId = selectedEventId(params.get('eventId'), events.data);

  // 그래프 범위는 이벤트마다(다른 이벤트를 고르면 그 상태의 기본 범위)
  const [range, setRange] = useState<{ eventId: number | null; key: TrafficRangeKey | null }>({ eventId: null, key: null });
  const rangeKey = range.eventId === eventId ? range.key : null;
  const detail = usePolling((signal) => fetchTrafficEvent(eventId as number, rangeKey, signal), POLL_MS, {
    key: eventId == null ? null : `${eventId}:${rangeKey ?? ''}`,
    enabled: eventId != null,
    stopWhen: stopPollingWhen,
  });

  // 범위를 바꿔 새로 받는 동안에는 같은 이벤트의 지난 화면을 그대로 둔다(구역이 깜빡이지 않게)
  const last = useRef<TrafficEventResponse | undefined>(undefined);
  if (detail.data) last.current = detail.data;
  const shown = detail.data ?? (last.current?.event.eventId === eventId ? last.current : undefined);

  const now = new Date();
  const p = platform.data;
  const header = headerView(p, platform.failed);
  const cells = useMemo(() => summaryCells(p), [p]);
  const pools = useMemo(() => poolViews(p?.nodes.pools), [p]);
  const history = useMemo(() => historyView(p), [p]);
  // Grafana 주소는 서버 설정(GRAFANA_URL) — /monitoring/platform 응답으로 받는다
  const grafana = grafanaBase(p?.grafanaUrl);

  const select = (id: number) => {
    const next = new URLSearchParams(params);
    next.set('eventId', String(id));
    setParams(next, { replace: false });
  };

  return (
    <>
      <PageHeader breadcrumbs={BREADCRUMBS} actions={<HeaderIndicator view={header} />} />
      <Box sx={{ display: 'grid', gap: layout.cardGap }}>
        <StatusSummary cells={cells} />

        <Box sx={{ display: 'grid', gap: layout.cardGap, gridTemplateColumns: { xs: '1fr', lg: 'minmax(0, 1fr) minmax(320px, 34%)' }, alignItems: 'stretch' }}>
          <NodesCard cpu={p?.nodes.cpu ?? null} memory={p?.nodes.memory ?? null} history={history} pools={pools} />
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: layout.cardGap, minWidth: 0 }}>
            <PlatformCard addons={addonsView(p)} rds={rdsText(p)} lcu={lcuView(p, now)} services={serviceTexts(p?.platform.services)} awsDelayed={awsDelayed(p?.aws)} grafana={grafana} />
            <UpcomingCard items={upcomingViews(p?.upcoming, now)} />
          </Box>
        </Box>

        <EventSection
          options={eventOptions(events.data, eventId, now)}
          selected={eventId}
          onSelect={select}
          detail={shown}
          failed={detail.failed}
          listLoading={!events.data && !events.failed}
          onRange={(key) => setRange({ eventId, key })}
          now={now}
          grafana={grafana}
        />

        <FailuresCard rows={failureRows(p?.failures)} />
      </Box>
    </>
  );
}
