import { useEffect, useState } from 'react';
import { Box, Card, Stack, Typography } from '@mui/material';
import { LineChart } from '@mui/x-charts/LineChart';
import HighlightValue from '@/components/common/HighlightValue';
import SectionTitle from '@/components/common/SectionTitle';
import { mockDashboard } from '@/mocks/dashboard';
import { chartColors, font, line, size, text, tones } from '@/theme/theme';
import { formatNumber } from '@/utils/format';

const POINTS = 20;
const TICK_MS = 2000;

const timeLabel = (d: Date) => d.toTimeString().slice(0, 8);

function seed() {
  const now = Date.now();
  const labels = Array.from({ length: POINTS }, (_, i) => timeLabel(new Date(now - (POINTS - 1 - i) * TICK_MS)));
  const series = mockDashboard.traffic.series.map((s) => {
    // 초기 데이터가 POINTS보다 짧으면 앞쪽을 첫 값 주변으로 채운다
    const padded = Array.from({ length: POINTS - s.data.length }, () => Math.round(s.data[0] * (0.94 + Math.random() * 0.12))).concat(s.data);
    return { label: s.label, base: s.base, data: padded };
  });
  return { labels, series };
}

/** TODO: 실시간 트래픽 API(SSE/WebSocket)로 교체 */
function useLiveTraffic() {
  const [state, setState] = useState(seed);
  useEffect(() => {
    const timer = setInterval(() => {
      setState((prev) => ({
        labels: [...prev.labels.slice(1), timeLabel(new Date())],
        series: prev.series.map((s) => {
          const last = s.data[s.data.length - 1];
          const drift = (s.base - last) * 0.25;
          const noise = (Math.random() - 0.5) * s.base * 0.12;
          const next = Math.max(20, Math.round(last + drift + noise));
          return { ...s, data: [...s.data.slice(1), next] };
        }),
      }));
    }, TICK_MS);
    return () => clearInterval(timer);
  }, []);
  return state;
}

export default function LiveTrafficCard() {
  const { labels, series } = useLiveTraffic();
  const current = series.map((s) => s.data[s.data.length - 1]);
  const total = current.reduce((a, b) => a + b, 0);

  return (
    <Card sx={{ p: 3 }}>
      <SectionTitle
        action={
          <Stack direction="row" alignItems="center" spacing={0.75}>
            <Box sx={{ width: size.dot, height: size.dot, borderRadius: '50%', bgcolor: tones.green.dot, animation: 'blink 1.6s ease-in-out infinite', '@keyframes blink': { '50%': { opacity: 0.3 } } }} />
            <Typography variant="body2" sx={{ color: text.tertiary }}>
              자동 갱신 · 2초
            </Typography>
          </Stack>
        }
      >
        실시간 트래픽 처리 현황
      </SectionTitle>

      <Box sx={{ display: 'grid', gap: 3, gridTemplateColumns: { xs: '1fr', lg: 'minmax(220px, 18%) 1fr' }, alignItems: 'start' }}>
        <Box>
          <Typography variant="body2" sx={{ color: text.tertiary }}>
            현재 초당 요청 수
          </Typography>
          <HighlightValue variant="h1" sx={{ mt: 1 }}>
            {formatNumber(total)}
          </HighlightValue>
          <Stack spacing={1} sx={{ mt: 2 }}>
            {series.map((s, i) => (
              <Stack key={s.label} direction="row" alignItems="center" spacing={1}>
                <Box sx={{ width: size.dot, height: size.dot, borderRadius: '50%', bgcolor: chartColors.series[i] }} />
                <Typography variant="body2" sx={{ flex: 1, color: text.secondary }}>
                  {s.label}
                </Typography>
                <Typography variant="subtitle2" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                  {formatNumber(current[i])}
                </Typography>
              </Stack>
            ))}
          </Stack>
        </Box>

        <Box sx={{ minWidth: 0, mx: -1 }}>
          <LineChart
            height={240}
            hideLegend
            skipAnimation
            grid={{ horizontal: true }}
            margin={{ top: 16, right: 8, bottom: 0, left: 0 }}
            xAxis={[{ scaleType: 'point', data: labels, tickInterval: (_v, i) => i % 4 === 0 }]}
            yAxis={[{ width: 56, valueFormatter: (v: number) => formatNumber(v) }]}
            series={series.map((s, i) => ({
              id: `t${i}`,
              data: s.data,
              label: s.label,
              color: chartColors.series[i],
              showMark: false,
              curve: 'monotoneX' as const,
            }))}
            sx={{
              '& .MuiChartsAxis-line, & .MuiChartsAxis-tick': { stroke: 'transparent !important' },
              '& .MuiChartsAxis-tickLabel': { fill: `${text.tertiary} !important`, fontSize: `${font.size.caption} !important` },
              '& .MuiChartsGrid-line': { stroke: line.base },
              '& .MuiLineElement-root': { strokeWidth: 2 },
            }}
          />
        </Box>
      </Box>
    </Card>
  );
}
