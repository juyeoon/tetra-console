import { Box, Card, LinearProgress, Stack, Tooltip, Typography } from '@mui/material';
import SectionTitle from '@/components/common/SectionTitle';
import { chartColors, font, line, radius, text, tones } from '@/theme/theme';
import MetricChart from './MetricChart';
import { percentText, USAGE_DANGER, type HistoryView, type NodeCellView, type PoolView, type UsageTone } from './trafficMonitoringView';

/** 노드 막대 색: 평소 CPU 남색 · 메모리 연보라, 70~80% 주황, 80% 이상 빨강 */
const barColor = (tone: UsageTone, normal: string) => (tone === 'danger' ? tones.red.dot : tone === 'warn' ? tones.amber.dot : normal);

const toneText = (tone: UsageTone) => (tone === 'danger' ? tones.red.fg : tone === 'warn' ? tones.amber.fg : text.primary);

/** 클러스터 CPU · 메모리 큰 숫자 + 막대 */
function UsageTile({ label, ratio }: { label: string; ratio: number | null }) {
  return (
    <Box sx={{ p: 2, border: `1px solid ${line.base}`, borderRadius: radius.lg, minWidth: 0 }}>
      <Stack direction="row" justifyContent="space-between" alignItems="baseline">
        <Typography variant="body2" sx={{ color: text.tertiary }}>
          {label}
        </Typography>
        <Typography variant="h3" component="p" sx={{ fontVariantNumeric: 'tabular-nums' }}>
          {percentText(ratio, 0)}
        </Typography>
      </Stack>
      <LinearProgress variant="determinate" value={ratio == null ? 0 : Math.min(100, ratio * 100)} sx={{ mt: 1, height: 8, borderRadius: radius.sm, bgcolor: line.soft, '& .MuiLinearProgress-bar': { bgcolor: chartColors.primary } }} />
    </Box>
  );
}

/** 노드 1대 = 칸 1개, CPU · 메모리 세로 막대(높이 = 사용률), 80% 점선. 최근 10분 안에 뜬 노드는 점선 테두리 */
function NodeCell({ node }: { node: NodeCellView }) {
  return (
    <Tooltip
      arrow
      title={
        <Box>
          {node.tooltip.map((l) => (
            <Box key={l}>{l}</Box>
          ))}
        </Box>
      }
    >
      <Box
        sx={{
          position: 'relative',
          width: 30,
          height: 44,
          px: 0.5,
          pt: 0.5,
          display: 'flex',
          alignItems: 'flex-end',
          justifyContent: 'center',
          gap: 0.5,
          border: `1px ${node.recent ? 'dashed' : 'solid'} ${node.recent ? text.secondary : line.base}`,
          borderRadius: radius.sm,
          opacity: node.ready ? 1 : 0.5,
        }}
      >
        <Box sx={{ position: 'absolute', left: 2, right: 2, bottom: `${USAGE_DANGER * 100}%`, borderTop: `1px dashed ${text.disabled}` }} />
        <Box sx={{ width: 8, height: `${node.cpu}%`, minHeight: 2, bgcolor: barColor(node.cpuTone, chartColors.primary) }} />
        <Box sx={{ width: 8, height: `${node.memory}%`, minHeight: 2, bgcolor: barColor(node.memoryTone, chartColors.secondary) }} />
      </Box>
    </Tooltip>
  );
}

function Stat({ label, avg, max, tone }: { label: string; avg: string; max: string; tone: UsageTone }) {
  return (
    <Box>
      <Typography variant="caption" sx={{ color: text.tertiary }}>
        {label} 평균 / 최고
      </Typography>
      <Typography variant="subtitle2" sx={{ fontVariantNumeric: 'tabular-nums' }}>
        {avg} /{' '}
        <Box component="span" sx={{ color: toneText(tone) }}>
          {max}
        </Box>
      </Typography>
    </Box>
  );
}

function PoolRow({ pool }: { pool: PoolView }) {
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '72px 1fr' }, gap: 1.5, py: 1.5, borderTop: `1px solid ${line.soft}` }}>
      <Box>
        <Typography variant="subtitle2">{pool.label}</Typography>
        <Typography variant="caption" sx={{ color: text.tertiary }}>
          {pool.sub}
        </Typography>
      </Box>
      <Box sx={{ minWidth: 0 }}>
        <Stack direction="row" spacing={3} flexWrap="wrap" useFlexGap>
          <Box>
            <Typography variant="caption" sx={{ color: text.tertiary }}>
              노드
            </Typography>
            <Typography variant="subtitle2">
              {pool.count}
              {pool.added && (
                <Box component="span" sx={{ ml: 0.5, color: tones.green.fg, fontSize: font.size.caption }}>
                  {pool.added}
                </Box>
              )}
            </Typography>
          </Box>
          <Stat label="CPU" avg={pool.cpuAvg} max={pool.cpuMax} tone={pool.cpuMaxTone} />
          <Stat label="메모리" avg={pool.memoryAvg} max={pool.memoryMax} tone={pool.memoryMaxTone} />
        </Stack>
        {pool.nodes.length > 0 && (
          <Stack direction="row" flexWrap="wrap" useFlexGap spacing={0.75} sx={{ mt: 1 }}>
            {pool.nodes.map((n) => (
              <NodeCell key={n.key} node={n} />
            ))}
          </Stack>
        )}
      </Box>
    </Box>
  );
}

function Legend() {
  const swatch = (bg: string) => <Box component="span" sx={{ display: 'inline-block', width: 8, height: 8, mr: 0.5, bgcolor: bg, verticalAlign: 'middle' }} />;
  return (
    <Typography variant="caption" component="p" sx={{ color: text.tertiary, mt: 1 }}>
      칸 1개 = 노드 1대 · 막대 높이 = 사용률 · {swatch(chartColors.primary)}CPU {swatch(chartColors.secondary)}메모리 {swatch(tones.red.dot)}80% 이상{' '}
      {swatch(tones.amber.dot)}70~80% · 점선 가로줄 80% 기준 · 점선 테두리 최근 10분 추가 · 마우스를 올리면 상세
    </Typography>
  );
}

/** B-1 노드 — 클러스터 CPU · 메모리(홈과 같은 식), 최근 1시간 추이(80% 점선), 풀별 요약과 노드 칸 */
export default function NodesCard({ cpu, memory, history, pools }: { cpu: number | null; memory: number | null; history: HistoryView; pools: PoolView[] }) {
  return (
    <Card sx={{ p: 3, minWidth: 0 }}>
      <SectionTitle>노드</SectionTitle>
      <Box sx={{ display: 'grid', gap: 1.5, gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' } }}>
        <UsageTile label="클러스터 CPU" ratio={cpu} />
        <UsageTile label="클러스터 메모리" ratio={memory} />
      </Box>
      <Box sx={{ mt: 1.5 }}>
        <MetricChart
          title="최근 1시간"
          unit="CPU % · 메모리 %"
          x={history.timestamps}
          series={[
            { id: 'cpu', label: 'CPU', role: 'primary', data: history.cpu },
            { id: 'memory', label: '메모리', role: 'secondary', data: history.memory },
          ]}
          thresholds={[{ y: 80, label: '80% 기준' }]}
          guides={[]}
          markers={[]}
          yMax={100}
          empty={history.timestamps.length === 0 || [...history.cpu, ...history.memory].every((v) => v == null)}
          delayed={false}
          height={150}
        />
      </Box>
      <Box sx={{ mt: 1.5 }}>
        {pools.map((p) => (
          <PoolRow key={p.key} pool={p} />
        ))}
      </Box>
      <Legend />
    </Card>
  );
}
