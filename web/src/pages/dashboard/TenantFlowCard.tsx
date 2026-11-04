import { Box, Card, Stack, Tooltip, Typography } from '@mui/material';
import { keyframes } from '@mui/material/styles';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import SectionTitle from '@/components/common/SectionTitle';
import { chartColors, color, line, radius, size, text, tones } from '@/theme/theme';
import { LANE_SECONDS, NO_EVENT_TEXT, type TenantFlowView, type UpdatedView } from './monitoringView';
import UpdatedIndicator from './UpdatedIndicator';

const flow = keyframes`
  0% { left: 0%; opacity: 0; }
  10% { opacity: 1; }
  90% { opacity: 1; }
  100% { left: 100%; opacity: 0; }
`;

// TEST-LEAD: 서비스 값 60분
// const CUMULATIVE_HELP = '입장 열림(이벤트 시작 60분 전)부터 누적한 요청 수입니다. 실패는 서버 오류(5xx) 응답입니다.';
const CUMULATIVE_HELP = '입장 열림(이벤트 시작 15분 전)부터 누적한 요청 수입니다. 실패는 서버 오류(5xx) 응답입니다.'; // TEST-LEAD

/** 점이 같은 속도(LANE_SECONDS)로 지나가고, 개수(= 생성 빈도)만 rps에 따라 바뀐다. 0개면 빈 레인 */
function Lane({ dot, dots }: { dot: string; dots: number }) {
  return (
    <Box sx={{ position: 'relative', height: 2, borderRadius: 1, bgcolor: line.base }}>
      {Array.from({ length: dots }, (_, i) => (
        <Box
          key={`${dots}-${i}`}
          sx={{
            position: 'absolute',
            top: '50%',
            width: size.dot,
            height: size.dot,
            mt: `calc(${size.dot} / -2)`,
            ml: `calc(${size.dot} / -2)`,
            borderRadius: '50%',
            bgcolor: dot,
            animation: `${flow} ${LANE_SECONDS}s linear infinite`,
            animationDelay: `${(LANE_SECONDS / dots) * i}s`,
            animationFillMode: 'backwards',
          }}
        />
      ))}
    </Box>
  );
}

function FlowRowView({ row }: { row: TenantFlowView }) {
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
        // 카드도 흰색이라 테두리로 행을 구분
        border: `1px solid ${line.base}`,
      }}
    >
      <Box sx={{ minWidth: 0, maxWidth: 140 }}>
        <Typography variant="h4" noWrap title={row.name}>
          {row.name}
        </Typography>
        {row.events && (
          <Typography variant="caption" component="p" noWrap title={row.events} sx={{ color: text.tertiary }}>
            {row.events}
          </Typography>
        )}
      </Box>

      <Lane dot={chartColors.primary} dots={row.dots.inflow} />

      {/* 네임스페이스 원 — 이름은 툴팁, 안에는 api Pod ready/max. 바탕은 100 */}
      <Tooltip title={row.namespaceTitle}>
        <Box sx={{ width: size.flowNode, height: size.flowNode, borderRadius: '50%', display: 'grid', placeItems: 'center', textAlign: 'center', bgcolor: color.card, px: 1 }}>
          <Box sx={{ minWidth: 0 }}>
            <Typography variant="h3" component="p" sx={{ whiteSpace: 'nowrap', fontVariantNumeric: 'tabular-nums', lineHeight: 1.2 }}>
              {row.podCount}
            </Typography>
            <Typography variant="caption" sx={{ color: text.secondary }}>
              Pod
            </Typography>
          </Box>
        </Box>
      </Tooltip>

      <Stack spacing={4.5}>
        <Lane dot={tones.green.dot} dots={row.dots.completed} />
        <Lane dot={tones.red.dot} dots={row.dots.failed} />
      </Stack>

      <Tooltip title={CUMULATIVE_HELP} placement="left">
        <Stack spacing={1} sx={{ textAlign: 'right' }}>
          <Box>
            <Typography variant="caption">처리 완료</Typography>
            <Typography variant="h3" component="p" sx={{ color: tones.green.fg, fontVariantNumeric: 'tabular-nums' }}>
              {row.completed}
            </Typography>
          </Box>
          <Box>
            <Typography variant="caption">실패</Typography>
            <Typography variant="h3" component="p" sx={{ color: tones.red.fg, fontVariantNumeric: 'tabular-nums' }}>
              {row.failed}
            </Typography>
          </Box>
        </Stack>
      </Tooltip>
    </Box>
  );
}

/** 진행 중(OPEN) 이벤트가 있는 테넌트 전부 (클릭 이동 없음, 읽기 전용) */
export default function TenantFlowCard({ rows, updated }: { rows: TenantFlowView[]; updated: UpdatedView }) {
  return (
    <Card sx={{ p: 3, height: '100%' }}>
      <SectionTitle action={<UpdatedIndicator updated={updated} />}>테넌트 별 처리 현황</SectionTitle>
      {rows.length === 0 ? (
        <Typography variant="body2" sx={{ color: text.tertiary, py: 4, textAlign: 'center' }}>
          {NO_EVENT_TEXT}
        </Typography>
      ) : (
        <Stack spacing={1}>
          {rows.map((row) => (
            <FlowRowView key={row.key} row={row} />
          ))}
        </Stack>
      )}
      <Stack direction="row" alignItems="center" spacing={0.5} sx={{ mt: 1.5 }}>
        <Typography variant="caption" component="p" sx={{ color: text.tertiary }}>
          요청 유입 → 네임스페이스 처리 → 처리 완료 / 실패
        </Typography>
        <Tooltip title={`숫자 기준: ${CUMULATIVE_HELP}`}>
          <InfoOutlinedIcon sx={{ fontSize: size.iconSm, color: text.tertiary, cursor: 'help' }} />
        </Tooltip>
      </Stack>
    </Card>
  );
}
