import type { ReactNode } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { Box, Card, Link, Stack, Typography } from '@mui/material';
import { BarChart } from '@mui/x-charts/BarChart';
import EventAvailableOutlinedIcon from '@mui/icons-material/EventAvailableOutlined';
import HourglassEmptyRoundedIcon from '@mui/icons-material/HourglassEmptyRounded';
import InsightsOutlinedIcon from '@mui/icons-material/InsightsOutlined';
import MonitorHeartOutlinedIcon from '@mui/icons-material/MonitorHeartOutlined';
import WarningAmberRoundedIcon from '@mui/icons-material/WarningAmberRounded';
import SectionTitle from '@/components/common/SectionTitle';
import StatCard from '@/components/common/StatCard';
import StateMessage from '@/components/common/StateMessage';
import { chartColors, color, font, line, radius, size, text, tones, type } from '@/theme/theme';
import type { EventResult, TetraEvent } from '@/types';
import { formatNumber, formatPercent } from '@/utils/format';
import { eventStatusMeta, hostingStatusMeta } from '@/utils/status';

type DoneResult = Extract<EventResult, { state: 'DONE' }>;

function Facts({ items }: { items: { label: string; value: ReactNode }[] }) {
  return (
    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={{ xs: 0.5, sm: 3 }} sx={{ mt: 2, px: 2, py: 1.5, borderRadius: radius.lg, bgcolor: color.card }}>
      {items.map((f) => (
        <Stack key={f.label} direction="row" spacing={1} alignItems="baseline">
          <Typography variant="body2" sx={{ color: text.tertiary }}>
            {f.label}
          </Typography>
          <Typography variant="subtitle2">{f.value}</Typography>
        </Stack>
      ))}
    </Stack>
  );
}

function Legend({ fill, label }: { fill: string; label: ReactNode }) {
  return (
    <Stack direction="row" spacing={0.75} alignItems="center">
      <Box sx={{ width: size.dot, height: size.dot, borderRadius: '50%', bgcolor: fill }} />
      <Typography variant="body2" sx={{ color: text.secondary }}>
        {label}
      </Typography>
    </Stack>
  );
}

function DoneView({ r }: { r: DoneResult }) {
  const total = r.issued + r.soldOut + r.dropped;
  const parts = [
    { label: '발급 성공', value: r.issued, fill: chartColors.secondary },
    { label: '품절', value: r.soldOut, fill: chartColors.primary },
    { label: '이탈', value: r.dropped, fill: chartColors.neutral },
  ];

  return (
    <Stack spacing={3.5}>
      <Box sx={{ display: 'grid', gap: 3.5, gridTemplateColumns: { xs: '1fr', md: 'repeat(3, 1fr)' } }}>
        <StatCard fill="peri" label="발급 성공 / 총 재고" value={`${formatNumber(r.issued)} / ${formatNumber(r.stock)}`} sub={`소진율 ${formatPercent(r.issued, r.stock, 0)}`} />
        <StatCard fill="blue" label="품절로 못 받은 수" value={`${formatNumber(r.soldOut)}명`} sub="재고 소진 후 발급 시도" />
        <StatCard
          fill="peri"
          label="최대 동시 접속자 / 플랜 수용 인원"
          value={`${formatNumber(r.peakConcurrent)} / ${formatNumber(r.planCapacity)}`}
          sub={`사용률 ${formatPercent(r.peakConcurrent, r.planCapacity)} · ${r.peakAt}`}
        />
      </Box>

      <Card sx={{ p: 3 }}>
        <SectionTitle>발급 결과 구성</SectionTitle>
        <Stack direction="row" sx={{ height: 8, borderRadius: 4, overflow: 'hidden', gap: '2px' }}>
          {parts.map((p) => (
            <Box key={p.label} sx={{ width: `${(p.value / total) * 100}%`, bgcolor: p.fill }} title={p.label} />
          ))}
        </Stack>
        <Stack direction="row" flexWrap="wrap" useFlexGap spacing={3} sx={{ mt: 1.5 }}>
          {parts.map((p) => (
            <Legend
              key={p.label}
              fill={p.fill}
              label={
                <>
                  {p.label}{' '}
                  <Box component="span" sx={{ color: text.primary, fontWeight: 600 }}>
                    {formatNumber(p.value)}건
                  </Box>{' '}
                  ({formatPercent(p.value, total)})
                </>
              }
            />
          ))}
        </Stack>
      </Card>

      <Card sx={{ p: 3 }}>
        <SectionTitle
          action={
            <Stack direction="row" spacing={2}>
              <Legend fill={chartColors.secondary} label="발급 성공" />
              <Legend fill={chartColors.primary} label="품절" />
            </Stack>
          }
        >
          분당 발급 결과 (대기열 진입 시각 기준)
        </SectionTitle>
        <BarChart
          height={300}
          hideLegend
          borderRadius={8}
          grid={{ horizontal: true }}
          margin={{ top: 16, right: 0, bottom: 0, left: 0 }}
          xAxis={[{ scaleType: 'band', data: r.perMinute.map((m) => m.minute), categoryGapRatio: 0.55 }]}
          yAxis={[{ width: 64, valueFormatter: (v: number) => formatNumber(v) }]}
          series={[
            { data: r.perMinute.map((m) => m.issued), label: '발급 성공', stack: 'total', color: chartColors.secondary, valueFormatter: (v) => `${formatNumber(v ?? 0)}건` },
            { data: r.perMinute.map((m) => m.soldOut), label: '품절', stack: 'total', color: chartColors.primary, valueFormatter: (v) => `${formatNumber(v ?? 0)}건` },
          ]}
          sx={{
            '& .MuiChartsAxis-line, & .MuiChartsAxis-tick': { stroke: 'transparent !important' },
            '& .MuiChartsAxis-tickLabel': { fill: `${text.tertiary} !important`, fontSize: `${font.size.caption} !important` },
            '& .MuiChartsGrid-line': { stroke: line.base },
          }}
        />
      </Card>
    </Stack>
  );
}

export default function EventResultPanel({ event }: { event: TetraEvent }) {
  const r = event.result;
  const hosting = event.hostingStatus ? hostingStatusMeta[event.hostingStatus].label : '-';
  const status = event.eventStatus ? eventStatusMeta[event.eventStatus].label : '-';

  switch (r.state) {
    case 'NONE':
      return (
        <StateMessage
          icon={<EventAvailableOutlinedIcon />}
          tone="blue"
          title="이벤트 오픈 전입니다"
          description={
            <>
              이벤트가 오픈되어 종료된 뒤 발급 결과가 집계되면 이 화면에 표시됩니다.
              <br />
              오픈 전에는 발급 이력 CSV를 내려받을 수 없습니다.
            </>
          }
        >
          <Facts
            items={[
              { label: '호스팅상태', value: hosting },
              { label: '이벤트상태', value: status },
              { label: '이벤트 오픈 예정', value: event.eventPeriod.start },
            ]}
          />
        </StateMessage>
      );
    case 'ONGOING':
      return (
        <StateMessage
          icon={<MonitorHeartOutlinedIcon />}
          tone="green"
          title="이벤트가 진행 중입니다"
          description={
            <>
              이벤트 종료 후 발급 결과가 집계되면 이 화면에 표시됩니다.
              <br />
              진행 중에는 발급 이력 CSV를 내려받을 수 없습니다.
            </>
          }
        >
          <Facts
            items={[
              { label: '호스팅상태', value: hosting },
              { label: '이벤트상태', value: status },
              { label: '이벤트 종료 예정', value: event.eventPeriod.end },
            ]}
          />
          <Link component={RouterLink} to="/monitoring/traffic" variant="body1" sx={{ mt: 2 }}>
            실시간 현황은 트래픽 모니터링에서 보기 →
          </Link>
        </StateMessage>
      );
    case 'AGGREGATING':
      return (
        <StateMessage
          icon={<HourglassEmptyRoundedIcon />}
          tone="grey"
          title="결과를 집계하고 있습니다"
          description={
            <>
              이벤트 종료 처리 후 발급 결과 집계가 끝나면 이 화면에 표시됩니다.
              <br />
              집계가 끝나기 전에는 발급 이력 CSV를 내려받을 수 없습니다.
            </>
          }
        >
          <Facts
            items={[
              { label: '호스팅상태', value: hosting },
              { label: '이벤트상태', value: status },
            ]}
          />
        </StateMessage>
      );
    case 'FAILED':
      return (
        <>
          <Box sx={{ p: 3, borderRadius: radius.xl, bgcolor: tones.red.bg }}>
            <Stack direction="row" spacing={1.5} alignItems="flex-start">
              <WarningAmberRoundedIcon sx={{ fontSize: size.iconMd, color: tones.red.fg, mt: 0.25 }} />
              <Box sx={{ flex: 1 }}>
                <Typography variant="h4" sx={{ color: tones.red.fg }}>
                  종료 처리 실패
                </Typography>
                <Typography variant="body1" sx={{ mt: 0.5 }}>
                  이벤트 종료 처리 중 오류가 발생해 결과를 집계하지 못했습니다.
                </Typography>
                <Box sx={{ mt: 2, display: 'grid', gridTemplateColumns: `${size.infoLabel} 1fr`, rowGap: 1, ...type.body }}>
                  <Box sx={{ color: text.tertiary }}>원인</Box>
                  <Box>{r.cause}</Box>
                  <Box sx={{ color: text.tertiary }}>발생 시각</Box>
                  <Box>{r.occurredAt}</Box>
                  <Box sx={{ color: text.tertiary }}>호스팅상태</Box>
                  <Box>{hosting}</Box>
                </Box>
              </Box>
            </Stack>
          </Box>
          <StateMessage icon={<InsightsOutlinedIcon />} tone="grey" title="집계된 결과가 없습니다" />
        </>
      );
    case 'DONE':
      return <DoneView r={r} />;
  }
}
