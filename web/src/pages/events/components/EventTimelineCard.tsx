import { useEffect, useMemo, useState } from 'react';
import { Box, Card, Stack, ToggleButton, ToggleButtonGroup, Typography } from '@mui/material';
import { fetchLiveEvents } from '@/api/events';
import { usePolling } from '@/hooks/usePolling';
import { color, font, mono, radius, shadow, size, text, timeline, type } from '@/theme/theme';
import { DEFAULT_PERIOD, LEGEND, nextHostingText, periodOf, periodText, PERIODS, POLL_MS, TICK_MS, type PeriodKey } from '../timelineView';
import TimelineTrack, { SEGMENT_BG } from './TimelineTrack';

/** 브라우저 시계 — TICK_MS마다 다시 읽는다(지금 선 · 기간 · 지난 이벤트) */
function useNow(): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), TICK_MS);
    return () => clearInterval(id);
  }, []);
  return now;
}

/** 기간 버튼 — 그룹 바탕 100 · 안쪽 3px · 테두리 없음, 선택 = 900 바탕 + 흰 글자 */
const GROUP_SX = {
  ml: 'auto',
  p: '3px',
  bgcolor: mono[100],
  borderRadius: radius.lg,
  '& .MuiToggleButtonGroup-grouped': { m: 0, border: 0, borderRadius: radius.md },
} as const;

const BUTTON_SX = {
  height: size.controlSm,
  px: 1.5,
  py: 0,
  border: 0,
  borderRadius: radius.md,
  bgcolor: 'transparent',
  color: mono[700],
  ...type.caption,
  fontWeight: font.weight.medium,
  whiteSpace: 'nowrap',
  '&:hover': { bgcolor: mono[200] },
  '&.Mui-selected, &.Mui-selected:hover': { bgcolor: mono[900], color: color.white, fontWeight: font.weight.semibold },
  '&.Mui-focusVisible': { boxShadow: shadow.focus, zIndex: 1 },
} as const;

/** 범례 견본 18×10 — 실패는 속이 빈 칸 + 빨간 점선 */
function Swatch({ kind }: { kind: (typeof LEGEND)[number]['kind'] }) {
  return (
    <Box
      sx={{
        width: 18,
        height: 10,
        flexShrink: 0,
        borderRadius: '3px',
        ...(kind === 'failed' ? { border: `1.5px dashed ${timeline.failed}` } : { background: SEGMENT_BG[kind] }),
      }}
    />
  );
}

/**
 * OPS-04 이벤트 진행 현황 상단 "이벤트 타임라인" 카드. 목록 표와 따로 GET /live-events를 60초마다 받는다
 * (탭이 숨겨지면 멈춤, 실패하면 지난 값 유지 — 표는 들어올 때 한 번이라 둘이 잠깐 다를 수 있다).
 * 첫 응답 전에는 빈 트랙(1줄), 첫 응답이 실패하면 트랙 자리에 안내 한 줄(다음 폴링 때 다시 시도).
 */
export default function EventTimelineCard() {
  const live = usePolling((signal) => fetchLiveEvents(signal), POLL_MS);
  const [key, setKey] = useState<PeriodKey>(DEFAULT_PERIOD);
  const now = useNow();
  const period = useMemo(() => periodOf(key, now), [key, now]);
  const items = live.data?.items;
  const next = items ? nextHostingText(items, now) : null;

  return (
    <Card sx={{ p: 3, mb: 2 }}>
      <Stack direction="row" flexWrap="wrap" alignItems="center" useFlexGap columnGap={2} rowGap={1.5} sx={{ mb: 2 }}>
        <Stack direction="row" flexWrap="wrap" alignItems="baseline" useFlexGap columnGap={1.5} rowGap={0.5} sx={{ minWidth: 0 }}>
          <Typography variant="h4" component="h3" sx={{ whiteSpace: 'nowrap' }}>
            이벤트 타임라인
          </Typography>
          <Typography variant="body2" sx={{ color: text.secondary, fontVariantNumeric: 'tabular-nums' }}>
            {periodText(period)}
            {next && (
              <Box component="span" sx={{ ml: 1, color: text.primary, fontWeight: font.weight.semibold }}>
                {next}
              </Box>
            )}
          </Typography>
        </Stack>
        <ToggleButtonGroup
          exclusive
          size="small"
          value={key}
          onChange={(_, v: PeriodKey | null) => {
            if (v) setKey(v);
          }}
          aria-label="타임라인 기간"
          sx={GROUP_SX}
        >
          {PERIODS.map((p) => (
            <ToggleButton key={p.key} value={p.key} sx={BUTTON_SX}>
              {p.label}
            </ToggleButton>
          ))}
        </ToggleButtonGroup>
      </Stack>

      {!live.data && live.failed ? (
        <Typography variant="body2" sx={{ color: text.secondary, py: 1 }}>
          타임라인을 불러오지 못했어요
        </Typography>
      ) : (
        <TimelineTrack items={items} period={period} now={now} />
      )}

      <Stack direction="row" flexWrap="wrap" useFlexGap columnGap={2.5} rowGap={0.75} sx={{ mt: 2, px: 2, py: 1.25, bgcolor: mono[100], borderRadius: radius.lg }}>
        {LEGEND.map((l) => (
          <Stack key={l.kind} direction="row" alignItems="center" spacing={0.75}>
            <Swatch kind={l.kind} />
            <Typography variant="caption" sx={{ color: mono[700], whiteSpace: 'nowrap' }}>
              {l.label}
            </Typography>
          </Stack>
        ))}
      </Stack>
    </Card>
  );
}
