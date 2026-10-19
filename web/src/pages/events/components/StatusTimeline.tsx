import { Box, Stack, Typography } from '@mui/material';
import StatusChip from '@/components/common/StatusChip';
import { color, line, text, tones } from '@/theme/theme';
import type { ApplicationLog } from '@/types';
import { applicationStatusMeta } from '@/utils/status';

/**
 * 신청 상태 로그 — 실제 상태 전이 기록만 표시 (개수 제한 없음).
 * 현재 상태는 상단 상태 영역의 신청상태 배지로 확인한다.
 */
export default function StatusTimeline({ logs }: { logs: ApplicationLog[] }) {
  return (
    <Box sx={{ overflowX: { md: 'auto' } }}>
      <Box
        component="ol"
        sx={{
          listStyle: 'none',
          m: 0,
          p: 0,
          display: 'grid',
          gap: { xs: 0, md: 2 },
          gridTemplateColumns: { xs: '1fr', md: `repeat(${Math.max(logs.length, 3)}, minmax(200px, 1fr))` },
        }}
      >
        {logs.map((item, i) => {
          const meta = applicationStatusMeta[item.status];
          const last = i === logs.length - 1;
          return (
            <Box component="li" key={`${item.at}-${i}`} sx={{ position: 'relative', pl: { xs: 3, md: 0 }, pb: { xs: last ? 0 : 2.5, md: 0 } }}>
              <Box
                sx={{
                  position: { xs: 'absolute', md: 'relative' },
                  left: { xs: 4, md: 'auto' },
                  top: { xs: 6, md: 'auto' },
                  bottom: { xs: 0, md: 'auto' },
                  height: { md: 8 },
                  mb: { md: 1.5 },
                  display: 'flex',
                  flexDirection: { xs: 'column', md: 'row' },
                  alignItems: 'center',
                }}
              >
                <Box sx={{ width: 8, height: 8, borderRadius: '50%', flexShrink: 0, bgcolor: last ? tones[meta.tone].dot : color.navy }} />
                {!last && <Box sx={{ flex: 1, bgcolor: line.base, width: { xs: '1px', md: 'auto' }, height: { xs: 'auto', md: '1px' }, ml: { md: 1 } }} />}
              </Box>
              <Typography variant="caption">{item.at}</Typography>
              <Stack direction="row" alignItems="center" spacing={1} sx={{ mt: 0.25, mb: 0.5, flexWrap: 'wrap', rowGap: 0.5 }}>
                <Typography variant="subtitle1">{item.title}</Typography>
                <StatusChip label={meta.label} tone={meta.tone} />
              </Stack>
              <Typography variant="body2" sx={{ color: text.secondary, pr: { md: 1 } }}>
                {item.description}
              </Typography>
            </Box>
          );
        })}
      </Box>
    </Box>
  );
}
