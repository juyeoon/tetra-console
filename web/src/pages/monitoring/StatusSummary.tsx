import { Box, Stack, Typography } from '@mui/material';
import SectionTitle from '@/components/common/SectionTitle';
import { color, font, layout, radius, size, text, tones, type } from '@/theme/theme';
import { LEVEL_META, type HeaderView, type SummaryCell } from './trafficMonitoringView';

/** 상단 오른쪽 "● 자동 갱신 · 15초 · 마지막 HH:mm:ss" — Prometheus가 지난 값이면 황토 띠 "데이터 지연됨 · 마지막 정상 HH:mm:ss" */
export function HeaderIndicator({ view }: { view: HeaderView }) {
  if (view.delayed) {
    return (
      <Box component="span" sx={{ px: 1.25, py: 0.5, borderRadius: radius.sm, bgcolor: tones.amber.bg, color: tones.amber.fg, ...type.caption, fontWeight: font.weight.semibold, whiteSpace: 'nowrap' }}>
        {view.text}
      </Box>
    );
  }
  return (
    <Stack direction="row" alignItems="center" spacing={0.75}>
      <Box
        sx={{
          width: size.dot,
          height: size.dot,
          borderRadius: '50%',
          bgcolor: tones.green.dot,
          animation: 'blink 1.6s ease-in-out infinite',
          '@keyframes blink': { '50%': { opacity: 0.3 } },
        }}
      />
      {/* 상단바(바탕 100)에 놓여 흐린 글자는 secondary */}
      <Typography variant="body2" sx={{ color: text.secondary, whiteSpace: 'nowrap' }}>
        {view.text}
      </Typography>
    </Stack>
  );
}

/** 상태 배지 — 진한 톤 바탕 + 흰 글자(칸 배경이 같은 톤의 연한 색이라 일반 StatusChip은 묻힌다) */
function LevelBadge({ label, tone }: { label: string; tone: SummaryCell['tone'] }) {
  return (
    <Box component="span" sx={{ px: 1, py: 0.25, borderRadius: radius.sm, bgcolor: tones[tone].fg, color: color.white, ...type.caption, fontWeight: font.weight.semibold, whiteSpace: 'nowrap' }}>
      {label}
    </Box>
  );
}

/** A. 상태 요약 — 4칸 한 줄, 배경색으로 상태(정상 초록 · 주의 황토 · 위험 빨강 · 확인 불가 회색). 클릭 없음 */
export default function StatusSummary({ cells }: { cells: SummaryCell[] }) {
  return (
    <Box>
      <SectionTitle
        action={
          <Stack direction="row" spacing={1.5}>
            {(['OK', 'WARN', 'DANGER', 'UNKNOWN'] as const).map((level) => (
              <Stack key={level} direction="row" alignItems="center" spacing={0.5}>
                <Box sx={{ width: size.dot, height: size.dot, borderRadius: radius.sm, bgcolor: tones[LEVEL_META[level].tone].bg, border: `1px solid ${tones[LEVEL_META[level].tone].dot}` }} />
                <Typography variant="caption" sx={{ color: text.tertiary }}>
                  {LEVEL_META[level].label}
                </Typography>
              </Stack>
            ))}
          </Stack>
        }
      >
        상태 요약{' '}
        <Box component="span" sx={{ ...type.caption, color: text.tertiary, fontFamily: font.body, fontWeight: font.weight.regular }}>
          영역별 지금 상태 (자세한 내용은 아래 구역)
        </Box>
      </SectionTitle>
      <Box sx={{ display: 'grid', gap: layout.cardGap, gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)', lg: 'repeat(4, 1fr)' } }}>
        {cells.map((c) => (
          <Box key={c.key} sx={{ p: 2, borderRadius: radius.lg, bgcolor: tones[c.tone].bg, minWidth: 0 }}>
            <Stack direction="row" alignItems="center" spacing={1} sx={{ minWidth: 0 }}>
              <LevelBadge label={c.level} tone={c.tone} />
              <Typography variant="subtitle2" noWrap>
                {c.title}
              </Typography>
            </Stack>
            <Typography variant="body2" sx={{ mt: 0.75, color: text.secondary }} noWrap title={c.reason}>
              {c.reason}
            </Typography>
          </Box>
        ))}
      </Box>
    </Box>
  );
}
