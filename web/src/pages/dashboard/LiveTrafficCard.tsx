import { Box, Card, Stack, Typography } from '@mui/material';
import { LineChart } from '@mui/x-charts/LineChart';
import { ChartsReferenceLine } from '@mui/x-charts/ChartsReferenceLine';
import HighlightValue from '@/components/common/HighlightValue';
import SectionTitle from '@/components/common/SectionTitle';
import { chartColors, font, line, size, text } from '@/theme/theme';
import { formatClockMinute } from '@/utils/datetime';
import { type TrafficView, type UpdatedView } from './monitoringView';
import UpdatedIndicator from './UpdatedIndicator';

/** 테넌트가 색 수보다 많으면 색을 반복한다 */
const seriesColor = (i: number) => chartColors.series[i % chartColors.series.length];

/** 실시간 트래픽 처리 현황 — 발급 API가 처리한 초당 요청 수(테넌트별, 최근 30분 · 15초 간격) */
export default function LiveTrafficCard({ view, updated }: { view: TrafficView; updated: UpdatedView }) {
  return (
    <Card sx={{ p: 3 }}>
      <SectionTitle action={<UpdatedIndicator updated={updated} />}>실시간 트래픽 처리 현황</SectionTitle>

      <Box sx={{ display: 'grid', gap: 3, gridTemplateColumns: { xs: '1fr', lg: 'minmax(220px, 18%) 1fr' }, alignItems: 'start' }}>
        <Box>
          <Typography variant="body2" sx={{ color: text.tertiary }}>
            서버 처리 요청 수(초당)
          </Typography>
          <HighlightValue variant="h1" sx={{ mt: 1 }}>
            {view.total}
          </HighlightValue>
          <Stack spacing={1} sx={{ mt: 2 }}>
            {view.series.map((s, i) => (
              <Stack key={s.id} direction="row" alignItems="center" spacing={1}>
                <Box sx={{ width: size.dot, height: size.dot, flexShrink: 0, borderRadius: '50%', bgcolor: seriesColor(i) }} />
                <Typography variant="body2" sx={{ flex: 1, minWidth: 0, color: text.secondary }} noWrap title={s.label}>
                  {s.label}
                </Typography>
                <Typography variant="subtitle2" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                  {s.current}
                </Typography>
              </Stack>
            ))}
          </Stack>
        </Box>

        <Box sx={{ minWidth: 0, mx: -1 }}>
          {view.empty ? (
            <Stack sx={{ height: 240 }} alignItems="center" justifyContent="center" spacing={0.5}>
              <Typography variant="body2" sx={{ color: text.tertiary }}>
                {view.empty.text}
              </Typography>
              {view.empty.events.map((label) => (
                <Typography key={label} variant="caption" sx={{ color: text.tertiary }}>
                  {label}
                </Typography>
              ))}
            </Stack>
          ) : (
            <LineChart
              height={240}
              hideLegend
              skipAnimation
              grid={{ horizontal: true }}
              margin={{ top: 16, right: 8, bottom: 0, left: 0 }}
              xAxis={[{ scaleType: 'time', data: view.timestamps, valueFormatter: (v: number) => formatClockMinute(v) }]}
              yAxis={[{ width: 56, min: 0, valueFormatter: (v: number) => v.toLocaleString('ko-KR') }]}
              series={view.series.map((s, i) => ({
                id: s.id,
                data: s.data,
                label: s.label,
                color: seriesColor(i),
                showMark: false,
                curve: 'monotoneX' as const,
              }))}
              sx={{
                '& .MuiChartsAxis-line, & .MuiChartsAxis-tick': { stroke: 'transparent !important' },
                '& .MuiChartsAxis-tickLabel': { fill: `${text.tertiary} !important`, fontSize: `${font.size.caption} !important` },
                '& .MuiChartsGrid-line': { stroke: line.base },
                '& .MuiLineElement-root': { strokeWidth: 2 },
              }}
            >
              {/* 이벤트 시작 시각 세로 점선 (그래프 범위 안의 것만) */}
              {view.eventStarts.map((e) => (
                <ChartsReferenceLine
                  key={e.key}
                  x={e.x}
                  label={e.label}
                  labelAlign="start"
                  lineStyle={{ stroke: text.tertiary, strokeDasharray: '4 4', strokeWidth: 1 }}
                  labelStyle={{ fill: text.tertiary, fontSize: font.size.caption }}
                />
              ))}
            </LineChart>
          )}
        </Box>
      </Box>
    </Card>
  );
}
