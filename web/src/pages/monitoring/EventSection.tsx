import type { ReactNode } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { Box, Button, Card, Link, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { QueryLoading } from '@/components/common/QueryStatus';
import StatusChip from '@/components/common/StatusChip';
import type { TrafficEventResponse, TrafficRangeKey } from '@/api/types';
import { line, radius, text, tones } from '@/theme/theme';
import { KpiGroups, KpiTiles } from './KpiPanels';
import MetricChart from './MetricChart';
import StepTimeline from './StepTimeline';
import {
  CHART_ROWS,
  chartViews,
  endedKpiTiles,
  eventBody,
  LIST_ORDER_TEXT,
  liveKpiGroups,
  NO_EVENT_TEXT,
  rangeButtons,
  sectionStatus,
  stepViews,
  type EventOption,
} from './trafficMonitoringView';

interface EventSectionProps {
  options: EventOption[];
  selected: number | null;
  onSelect: (eventId: number) => void;
  /** 고른 이벤트 상세. 아직 없으면 undefined */
  detail: TrafficEventResponse | undefined;
  /** 상세 요청이 실패했고 보여 줄 값이 없음(예: 없는 eventId) */
  failed: boolean;
  /** 드롭다운 목록을 아직 못 받음 */
  listLoading: boolean;
  onRange: (range: TrafficRangeKey) => void;
  now: Date;
}

function Notice({ title, description, tone, action }: { title: string; description: string; tone: 'grey' | 'red'; action?: ReactNode }) {
  const red = tone === 'red';
  return (
    <Stack
      direction={{ xs: 'column', sm: 'row' }}
      alignItems={{ sm: 'center' }}
      justifyContent="space-between"
      spacing={1}
      sx={{ mt: 2, p: 3, borderRadius: radius.lg, border: `1px ${red ? 'solid' : 'dashed'} ${red ? tones.red.dot : line.strong}`, bgcolor: red ? tones.red.bg : 'transparent', textAlign: red ? 'left' : 'center' }}
    >
      <Box sx={{ flex: 1 }}>
        <Typography variant="subtitle1" sx={{ color: red ? tones.red.fg : text.primary }}>
          {title}
        </Typography>
        <Typography variant="body2" sx={{ color: text.tertiary, mt: 0.5 }}>
          {description}
        </Typography>
      </Box>
      {action}
    </Stack>
  );
}

/** C · D 이벤트 구역 — 드롭다운 · 진행 단계 7개 · (상태에 따라) KPI · 그래프 7개 */
export default function EventSection({ options, selected, onSelect, detail, failed, listLoading, onRange, now }: EventSectionProps) {
  const status = sectionStatus(detail);
  const noEvents = !listLoading && options.length === 0;
  return (
    <Card sx={{ p: 3 }}>
      <Stack direction={{ xs: 'column', md: 'row' }} alignItems={{ md: 'center' }} justifyContent="space-between" spacing={1.5}>
        <Stack direction="row" alignItems="center" spacing={1.5} sx={{ minWidth: 0 }}>
          <Typography variant="h4" component="h3">
            이벤트
          </Typography>
          <TextField
            select
            size="small"
            value={selected ?? ''}
            disabled={noEvents || listLoading}
            onChange={(e) => onSelect(Number(e.target.value))}
            sx={{ minWidth: { xs: 0, sm: 360 }, flex: { xs: 1, sm: 'none' } }}
            slotProps={{ select: { displayEmpty: true, renderValue: (v) => (v === '' ? (noEvents ? NO_EVENT_TEXT : '불러오는 중') : options.find((o) => o.value === v)?.label ?? String(v)) } }}
          >
            {options.map((o) => (
              <MenuItem key={o.value} value={o.value}>
                {o.label}
              </MenuItem>
            ))}
          </TextField>
        </Stack>
        <Stack direction="row" alignItems="center" spacing={1} flexWrap="wrap" useFlexGap>
          <Typography variant="caption" sx={{ color: text.tertiary }}>
            {LIST_ORDER_TEXT}
          </Typography>
          {status.chips.map((c) => (
            <StatusChip key={c.label} label={c.label} tone={c.tone} />
          ))}
          {detail && (
            <Typography variant="caption" sx={{ color: text.tertiary }}>
              {status.refresh}
            </Typography>
          )}
        </Stack>
      </Stack>

      {noEvents && <Notice tone="grey" title={NO_EVENT_TEXT} description="진행 중 · 오늘 예정 · 최근 7일 안에 종료된 이벤트가 없어요" />}
      {!noEvents && !detail && (failed ? <Notice tone="red" title="이벤트를 불러오지 못했습니다" description="이벤트 번호를 확인하거나 목록에서 다시 골라 주세요" /> : <QueryLoading minHeight={160} />)}
      {detail && <EventDetail detail={detail} onRange={onRange} now={now} />}
    </Card>
  );
}

function EventDetail({ detail, onRange, now }: { detail: TrafficEventResponse; onRange: (range: TrafficRangeKey) => void; now: Date }) {
  const body = eventBody(detail);
  return (
    <>
      <Box sx={{ mt: 3 }}>
        <StepTimeline steps={stepViews(detail.steps, now)} />
      </Box>
      {body.kind === 'scheduled' && <Notice tone="grey" title={body.title} description={body.description} />}
      {body.kind === 'failed' && (
        <Notice
          tone="red"
          title={body.title}
          description={body.description}
          action={
            <Link component={RouterLink} to={body.link} underline="always" sx={{ color: tones.red.fg, whiteSpace: 'nowrap' }}>
              실패 상세
            </Link>
          }
        />
      )}
      {body.kind === 'metrics' && <Metrics detail={detail} onRange={onRange} />}
    </>
  );
}

function Metrics({ detail, onRange }: { detail: TrafficEventResponse; onRange: (range: TrafficRangeKey) => void }) {
  const ended = detail.event.phase === 'ENDED';
  const charts = detail.charts
    ? chartViews(
        detail.charts,
        { entryAt: detail.event.entryAt, startAt: detail.event.startAt, endAt: ended ? detail.event.endAt : null, soldOutAt: detail.kpi?.summary?.soldOutAt ?? null },
        detail.aws.stale,
      )
    : [];
  const byKey = new Map(charts.map((c) => [c.key, c]));
  return (
    <Box sx={{ mt: 3 }}>
      <Stack direction={{ xs: 'column', sm: 'row' }} alignItems={{ sm: 'center' }} justifyContent="space-between" spacing={1} sx={{ mb: 1.5 }}>
        <Typography variant="h4" component="h4">
          이벤트 그래프{' '}
          <Typography component="span" variant="caption" sx={{ color: text.tertiary }}>
            · 앱 지표 15초 · ALB·RDS 60초 갱신{detail.prometheus.stale ? ' · 데이터 지연됨' : ''}
          </Typography>
        </Typography>
        <Stack direction="row" spacing={0.75}>
          {rangeButtons(detail.range).map((b) => (
            <Button key={b.key} size="small" variant={b.selected ? 'contained' : 'outlined'} onClick={() => !b.selected && onRange(b.key)} sx={{ borderRadius: 999, whiteSpace: 'nowrap' }}>
              {b.label}
            </Button>
          ))}
        </Stack>
      </Stack>

      {ended ? <KpiTiles tiles={endedKpiTiles(detail.kpi?.summary)} /> : <KpiGroups groups={liveKpiGroups(detail.kpi, detail.event.phase)} />}

      {detail.charts == null ? (
        <Typography variant="body2" sx={{ color: text.tertiary, mt: 2 }}>
          그래프 데이터를 받지 못했어요(Prometheus 지연)
        </Typography>
      ) : (
        <Stack spacing={1.5} sx={{ mt: 1.5 }}>
          {CHART_ROWS.map((row) => (
            <Box key={row.join('-')} sx={{ display: 'grid', gap: 1.5, gridTemplateColumns: { xs: '1fr', md: `repeat(${row.length}, minmax(0, 1fr))` } }}>
              {row.map((key) => {
                const c = byKey.get(key);
                if (!c) return null;
                const { key: chartKey, ...chart } = c;
                return <MetricChart key={chartKey} {...chart} height={row.length === 3 ? 160 : 200} />;
              })}
            </Box>
          ))}
        </Stack>
      )}
    </Box>
  );
}
