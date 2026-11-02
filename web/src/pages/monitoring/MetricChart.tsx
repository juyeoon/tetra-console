import { createContext, useContext, useLayoutEffect, useRef, useState } from 'react';
import { Box, Stack, Typography } from '@mui/material';
import { LineChart } from '@mui/x-charts/LineChart';
import { ChartsReferenceLine } from '@mui/x-charts/ChartsReferenceLine';
import { ChartsAxisTooltipContent, ChartsTooltipContainer, useAxesTooltip, type ChartsTooltipProps } from '@mui/x-charts/ChartsTooltip';
import StatusChip from '@/components/common/StatusChip';
import { chartColors, font, line, radius, text, tones } from '@/theme/theme';
import { formatClock, formatClockMinute } from '@/utils/datetime';
import { MARKER_LABEL_OFFSET, MARKER_LEVEL_PX, markerLabels, timeTicks, tooltipValue, Y_AXIS_WIDTH, type ChartView, type SeriesRole } from './trafficMonitoringView';

/** 주 지표 진보라, 보조 지표 연보라. 이벤트 그래프에는 빨강을 쓰지 않는다 */
export const ROLE_COLOR: Record<SeriesRole, string> = { primary: chartColors.primary, secondary: chartColors.secondary };

const THRESHOLD_COLOR = tones.amber.dot;

const GUIDE_COLOR = text.tertiary;

/** 범례 한 칸 — 실선(시리즈) 또는 점선(기준선) */
function LegendItem({ label, color, dashed }: { label: string; color: string; dashed?: boolean }) {
  return (
    <Stack direction="row" alignItems="center" spacing={0.75}>
      <Box sx={{ width: 14, borderTop: `2px ${dashed ? 'dashed' : 'solid'} ${color}` }} />
      <Typography variant="caption" sx={{ color: text.secondary, whiteSpace: 'nowrap' }}>
        {label}
      </Typography>
    </Stack>
  );
}

/** 점선 시리즈(보조선)의 선 모양 — 기준선(5 4) · 세로 점선(3 3)보다 길게 */
const SERIES_DASH = '6 4';

/** 툴팁 아래 안내(ChartView.tooltipNote). 툴팁은 Portal에 그려지지만 context는 그대로 이어진다 */
const TooltipNoteContext = createContext<string | undefined>(undefined);

/** 기본 축 툴팁(머리 시각 · 값 표) + 아래 안내 한 줄. 바탕 · 테두리는 기본 툴팁과 같게 둘러싼다 */
function NoteTooltipContent() {
  const note = useContext(TooltipNoteContext);
  const data = useAxesTooltip();
  if (data === null) return null;
  return (
    <Box sx={{ bgcolor: 'background.paper', color: 'text.primary', border: 1, borderColor: 'divider', borderRadius: 1 }}>
      <ChartsAxisTooltipContent sx={{ border: 'none', bgcolor: 'transparent' }} />
      <Typography variant="caption" component="p" sx={{ px: 1.5, pb: 1, maxWidth: 320, wordBreak: 'keep-all', color: text.tertiary }}>
        {note}
      </Typography>
    </Box>
  );
}

function NoteTooltip(props: ChartsTooltipProps) {
  return (
    <ChartsTooltipContainer {...props}>
      <NoteTooltipContent />
    </ChartsTooltipContainer>
  );
}

type MetricChartProps = Pick<ChartView, 'title' | 'unit' | 'x' | 'series' | 'thresholds' | 'guides' | 'markers' | 'empty' | 'delayed'> &
  Partial<Pick<ChartView, 'current' | 'currentUnit' | 'yMax' | 'tooltipNote'>> & { height?: number };

/** 요소 폭(px). 크기가 바뀌면 다시 잰다 — x축 눈금 수를 폭에 맞추는 데 쓴다 */
function useWidth<T extends HTMLElement>() {
  const ref = useRef<T | null>(null);
  const [width, setWidth] = useState(0);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return undefined;
    setWidth(el.getBoundingClientRect().width);
    if (typeof ResizeObserver === 'undefined') return undefined;
    const observer = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width));
    observer.observe(el);
    return () => observer.disconnect();
  }, []);
  return [ref, width] as const;
}

/**
 * 머리글(제목 · 단위 · 오른쪽 현재 값) + 선 그래프(축 눈금, 기준 점선, 세로 점선 5종 — 이름은 겹치면 단을 엇갈림) + 범례.
 * x축 눈금은 그래프 폭에 맞춰 직접 정한다(timeTicks — 좁은 3칸 줄에서 겹치지 않게), 축은 데이터 범위 그대로(strict).
 * 툴팁 머리는 'HH:mm:ss'(15초 점), 값이 없으면 '–'. tooltipNote가 있으면 툴팁 아래에 안내 한 줄, dashed 시리즈는 점선
 */
export default function MetricChart({
  title,
  unit,
  current,
  currentUnit,
  x,
  series,
  thresholds,
  guides,
  markers,
  yMax,
  empty,
  delayed,
  tooltipNote,
  height = 180,
}: MetricChartProps) {
  const [plotRef, width] = useWidth<HTMLDivElement>();
  return (
    <Box sx={{ p: 2, border: `1px solid ${line.base}`, borderRadius: radius.lg, bgcolor: 'background.paper', minWidth: 0 }}>
      <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={1}>
        <Box sx={{ minWidth: 0 }}>
          <Stack direction="row" alignItems="center" spacing={1}>
            <Typography variant="subtitle2">{title}</Typography>
            {delayed && <StatusChip label="지연" tone="grey" />}
          </Stack>
          <Typography variant="caption" sx={{ color: text.tertiary }}>
            {unit}
          </Typography>
        </Box>
        {current != null && (
          <Typography variant="subtitle1" sx={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
            {current}
            {currentUnit && current !== '–' && (
              <Box component="span" sx={{ ml: 0.5, fontSize: font.size.caption, color: text.tertiary, fontWeight: font.weight.regular }}>
                {currentUnit}
              </Box>
            )}
          </Typography>
        )}
      </Stack>

      <Box ref={plotRef} sx={{ mx: -1, mt: 1 }}>
        {empty ? (
          <Stack sx={{ height }} alignItems="center" justifyContent="center">
            <Typography variant="body2" sx={{ color: text.tertiary }}>
              데이터 없음
            </Typography>
          </Stack>
        ) : (
          <TooltipNoteContext.Provider value={tooltipNote}>
            <LineChart
              height={height}
              hideLegend
              skipAnimation
              slots={tooltipNote ? { tooltip: NoteTooltip } : undefined}
              grid={{ horizontal: true }}
              margin={{ top: 12, right: 24, bottom: 0, left: 0 }}
              xAxis={[
                {
                  scaleType: 'time',
                  data: x,
                  domainLimit: 'strict',
                  tickInterval: timeTicks(x[0], x[x.length - 1], width),
                  valueFormatter: (v: number, context) => (context.location === 'tick' ? formatClockMinute(v) : formatClock(v)),
                },
              ]}
              yAxis={[{ width: Y_AXIS_WIDTH, min: 0, max: yMax, tickNumber: 3, valueFormatter: (v: number) => v.toLocaleString('ko-KR') }]}
              series={series.map((s) => ({
                id: s.id,
                data: s.data,
                label: s.label,
                color: ROLE_COLOR[s.role],
                showMark: false,
                connectNulls: true,
                curve: 'monotoneX' as const,
                valueFormatter: (v: number | null) => tooltipValue(v, s.unit),
              }))}
              sx={{
                '& .MuiChartsAxis-line, & .MuiChartsAxis-tick': { stroke: 'transparent !important' },
                '& .MuiChartsAxis-tickLabel': { fill: `${text.tertiary} !important`, fontSize: `${font.size.caption} !important` },
                '& .MuiChartsGrid-line': { stroke: line.soft },
                '& .MuiLineElement-root': { strokeWidth: 2 },
                // 보조선: x-charts가 선마다 붙이는 MuiLineElement-series-<id>
                ...Object.fromEntries(series.filter((s) => s.dashed).map((s) => [`& .MuiLineElement-series-${s.id}`, { strokeDasharray: SERIES_DASH }])),
              }}
            >
              {thresholds.map((t) => (
                <ChartsReferenceLine key={`t-${t.y}`} y={t.y} lineStyle={{ stroke: THRESHOLD_COLOR, strokeDasharray: '5 4', strokeWidth: 1.5 }} />
              ))}
              {guides.map((g) => (
                <ChartsReferenceLine key={`g-${g.label}`} y={g.y} lineStyle={{ stroke: GUIDE_COLOR, strokeDasharray: '4 4', strokeWidth: 1 }} />
              ))}
              {markerLabels(markers, x[0], x[x.length - 1], width).map((m, i) => (
                <ChartsReferenceLine
                  key={`m-${m.x}-${i}`}
                  x={m.x}
                  label={m.label || undefined}
                  labelAlign="start"
                  spacing={{ x: m.side === 'left' ? -MARKER_LABEL_OFFSET : MARKER_LABEL_OFFSET, y: MARKER_LABEL_OFFSET + m.level * MARKER_LEVEL_PX }}
                  lineStyle={{ stroke: GUIDE_COLOR, strokeDasharray: '3 3', strokeWidth: 1 }}
                  labelStyle={{ fill: text.tertiary, fontSize: font.size.caption, textAnchor: m.side === 'left' ? 'end' : 'start' }}
                />
              ))}
            </LineChart>
          </TooltipNoteContext.Provider>
        )}
      </Box>

      <Stack direction="row" flexWrap="wrap" columnGap={1.5} rowGap={0.5} sx={{ mt: 1 }}>
        {series.map((s) => (
          <LegendItem key={s.id} label={s.label} color={ROLE_COLOR[s.role]} dashed={s.dashed} />
        ))}
        {thresholds.map((t) => (
          <LegendItem key={`t-${t.label}`} label={t.label} color={THRESHOLD_COLOR} dashed />
        ))}
        {guides.map((g) => (
          <LegendItem key={`g-${g.label}`} label={g.label} color={GUIDE_COLOR} dashed />
        ))}
      </Stack>
    </Box>
  );
}
