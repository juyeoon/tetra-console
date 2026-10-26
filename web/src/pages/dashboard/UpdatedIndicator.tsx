import { Box, Stack, Typography } from '@mui/material';
import StatusChip from '@/components/common/StatusChip';
import { size, text, tones } from '@/theme/theme';
import { POLL_MS, type UpdatedView } from './monitoringView';

/** 카드 제목 오른쪽 "● 자동 갱신 · 15초 · HH:mm:ss" — 지연(stale · 폴링 실패)이면 회색 점 + "지연" */
export default function UpdatedIndicator({ updated }: { updated: UpdatedView }) {
  return (
    <Stack direction="row" alignItems="center" spacing={0.75} sx={{ flexShrink: 0 }}>
      <Box
        sx={{
          width: size.dot,
          height: size.dot,
          borderRadius: '50%',
          bgcolor: updated.delayed ? tones.grey.dot : tones.green.dot,
          animation: updated.delayed ? 'none' : 'blink 1.6s ease-in-out infinite',
          '@keyframes blink': { '50%': { opacity: 0.3 } },
        }}
      />
      <Typography variant="body2" sx={{ color: text.tertiary, whiteSpace: 'nowrap' }}>
        자동 갱신 · {POLL_MS / 1000}초 · {updated.asOf}
      </Typography>
      {updated.delayed && <StatusChip label="지연" tone="grey" />}
    </Stack>
  );
}
