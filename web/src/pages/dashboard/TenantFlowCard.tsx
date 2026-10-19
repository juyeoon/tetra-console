import { Box, Card, Stack, Typography } from '@mui/material';
import { keyframes } from '@mui/material/styles';
import SectionTitle from '@/components/common/SectionTitle';
import { mockDashboard } from '@/mocks/dashboard';
import { color, line, radius, size, text, tones } from '@/theme/theme';

const flow = keyframes`
  0% { left: 0%; opacity: 0; }
  10% { opacity: 1; }
  90% { opacity: 1; }
  100% { left: 100%; opacity: 0; }
`;

function Lane({ dot, dots = 3, duration = 2.4 }: { dot: string; dots?: number; duration?: number }) {
  return (
    <Box sx={{ position: 'relative', height: 2, borderRadius: 1, bgcolor: line.base }}>
      {Array.from({ length: dots }, (_, i) => (
        <Box
          key={i}
          sx={{
            position: 'absolute',
            top: '50%',
            width: size.dot,
            height: size.dot,
            mt: `calc(${size.dot} / -2)`,
            ml: `calc(${size.dot} / -2)`,
            borderRadius: '50%',
            bgcolor: dot,
            animation: `${flow} ${duration}s linear infinite`,
            animationDelay: `${(duration / dots) * i}s`,
          }}
        />
      ))}
    </Box>
  );
}

type FlowRow = (typeof mockDashboard.topTenants)[number];

function FlowRowView({ row }: { row: FlowRow }) {
  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: { xs: `auto 1fr ${size.flowNode} 1fr auto`, sm: `minmax(56px, auto) 1fr ${size.flowNode} 1fr minmax(84px, auto)` },
        alignItems: 'center',
        columnGap: { xs: 1, sm: 2 },
        p: 2,
        borderRadius: radius.lg,
        bgcolor: color.surface,
      }}
    >
      <Typography variant="h4">{row.tenant}</Typography>

      <Lane dot={color.peri} dots={4} duration={2} />

      <Stack alignItems="center" spacing={0.75}>
        <Box sx={{ width: size.flowNode, height: size.flowNode, borderRadius: '50%', display: 'grid', placeItems: 'center', textAlign: 'center', bgcolor: color.periTint }}>
          <Box>
            <Typography variant="subtitle2">{row.namespace}</Typography>
            <Typography variant="caption">네임스페이스</Typography>
          </Box>
        </Box>
        <Stack direction="row" alignItems="center" spacing={0.75}>
          <Stack direction="row" spacing={0.25}>
            {Array.from({ length: row.maxPods }, (_, i) => (
              <Box key={i} sx={{ width: 8, height: 6, borderRadius: '2px', bgcolor: i < row.pods ? color.navy : line.base }} />
            ))}
          </Stack>
          <Typography variant="caption">
            Pod {row.pods}/{row.maxPods}
          </Typography>
        </Stack>
      </Stack>

      <Stack spacing={4.5}>
        <Lane dot={tones.green.dot} dots={3} duration={2.2} />
        <Lane dot={tones.red.dot} dots={1} duration={3.2} />
      </Stack>

      <Stack spacing={1} sx={{ textAlign: 'right' }}>
        <Box>
          <Typography variant="caption">처리 완료</Typography>
          <Typography variant="h3" component="p" sx={{ color: tones.green.fg }}>
            {row.done}
          </Typography>
        </Box>
        <Box>
          <Typography variant="caption">대기·실패</Typography>
          <Typography variant="h3" component="p" sx={{ color: tones.red.fg }}>
            {row.waitingOrFailed}
          </Typography>
        </Box>
      </Stack>
    </Box>
  );
}

/** 상위 2개 테넌트 고정 노출 (클릭 이동 없음, 읽기 전용) */
export default function TenantFlowCard() {
  return (
    <Card sx={{ p: 3, height: '100%' }}>
      <SectionTitle>테넌트 별 처리 현황 (상위 2개)</SectionTitle>
      <Stack spacing={1}>
        {mockDashboard.topTenants.map((row) => (
          <FlowRowView key={row.namespace} row={row} />
        ))}
      </Stack>
      <Typography variant="caption" component="p" sx={{ mt: 1.5, color: text.tertiary }}>
        요청 유입 → 네임스페이스 처리 → 처리 완료 / 대기·실패
      </Typography>
    </Card>
  );
}
