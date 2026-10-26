import { Box, Stack, Typography } from '@mui/material';
import { LineChart } from '@mui/x-charts/LineChart';
import { ChartsReferenceLine } from '@mui/x-charts/ChartsReferenceLine';
import StatusChip from '@/components/common/StatusChip';
import { chartColors, font, line, radius, text, tones } from '@/theme/theme';
import { formatClockMinute } from '@/utils/datetime';
import type { ChartView, SeriesRole } from './trafficMonitoringView';

/** 주 지표 남색, 보조 지표 연보라. 이벤트 그래프에는 빨강을 쓰지 않는다 */
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

type MetricChartProps = Pick<ChartView, 'title' | 'unit' | 'x' | 'series' | 'thresholds' | 'guides' | 'markers' | 'empty' | 'delayed'> &
  Partial<Pick<ChartView, 'current' | 'currentUnit' | 'yMax'>> & { height?: number };

/** 머리글(제목 · 단위 · 오른쪽 현재 값) + 선 그래프(축 눈금, 기준 점선, 입장 · 시작 세로 점선) + 범례 */
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
  height = 180,
}: MetricChartProps) {
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

      <Box sx={{ mx: -1, mt: 1 }}>
        {empty ? (
          <Stack sx={{ height }} alignItems="center" justifyContent="center">
            <Typography variant="body2" sx={{ color: text.tertiary }}>
              데이터 없음
            </Typography>
          </Stack>
        ) : (
          <LineChart
            height={height}
            hideLegend
            skipAnimation
            grid={{ horizontal: true }}
            margin={{ top: 12, right: 24, bottom: 0, left: 0 }}
            xAxis={[{ scaleType: 'time', data: x, valueFormatter: (v: number) => formatClockMinute(v), tickNumber: 4 }]}
            yAxis={[{ width: 48, min: 0, max: yMax, tickNumber: 3, valueFormatter: (v: number) => v.toLocaleString('ko-KR') }]}
            series={series.map((s) => ({
              id: s.id,
              data: s.data,
              label: s.label,
              color: ROLE_COLOR[s.role],
              showMark: false,
              connectNulls: true,
              curve: 'monotoneX' as const,
            }))}
            sx={{
              '& .MuiChartsAxis-line, & .MuiChartsAxis-tick': { stroke: 'transparent !important' },
              '& .MuiChartsAxis-tickLabel': { fill: `${text.tertiary} !important`, fontSize: `${font.size.caption} !important` },
              '& .MuiChartsGrid-line': { stroke: line.soft },
              '& .MuiLineElement-root': { strokeWidth: 2 },
            }}
          >
            {thresholds.map((t) => (
              <ChartsReferenceLine key={`t-${t.y}`} y={t.y} lineStyle={{ stroke: THRESHOLD_COLOR, strokeDasharray: '5 4', strokeWidth: 1.5 }} />
            ))}
            {guides.map((g) => (
              <ChartsReferenceLine key={`g-${g.label}`} y={g.y} lineStyle={{ stroke: GUIDE_COLOR, strokeDasharray: '4 4', strokeWidth: 1 }} />
            ))}
            {markers.map((m) => (
              <ChartsReferenceLine
                key={`m-${m.label}`}
                x={m.x}
                label={m.label}
                labelAlign="start"
                lineStyle={{ stroke: GUIDE_COLOR, strokeDasharray: '3 3', strokeWidth: 1 }}
                labelStyle={{ fill: text.tertiary, fontSize: font.size.caption }}
              />
            ))}
          </LineChart>
        )}
      </Box>

      <Stack direction="row" flexWrap="wrap" columnGap={1.5} rowGap={0.5} sx={{ mt: 1 }}>
        {series.map((s) => (
          <LegendItem key={s.id} label={s.label} color={ROLE_COLOR[s.role]} />
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
