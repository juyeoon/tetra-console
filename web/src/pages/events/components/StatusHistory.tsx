import { Box, Stack, Typography } from '@mui/material';
import StatusChip from '@/components/common/StatusChip';
import { color, line, radius, text, tones, type } from '@/theme/theme';
import type { StatusHistoryItem } from '../eventView';

/**
 * 상태 처리 기록 — 신청 · 호스팅 · 이벤트 상태 변화를 시각순 세로 타임라인으로 (OPS-03-1 · 04-1 공용, Portal "상태 처리 기록"과 같은 정보).
 * 줄마다 구분 라벨 · 상태 배지 · 시각 · 처리자, 원인(반려 사유)이 있으면 박스로. 실패 계열은 빨강으로 강조.
 */
export default function StatusHistory({ items }: { items: StatusHistoryItem[] }) {
  if (items.length === 0) {
    return (
      <Typography variant="body2" sx={{ color: text.tertiary }}>
        기록이 없습니다.
      </Typography>
    );
  }
  return (
    <Box component="ol" sx={{ listStyle: 'none', m: 0, p: 0 }}>
      {items.map((item, i) => {
        const last = i === items.length - 1;
        const dot = item.failure ? tones.red.dot : last ? tones[item.status.tone].dot : color.navy;
        return (
          <Box component="li" key={`${item.at}-${i}`} sx={{ position: 'relative', pl: 3.5, pb: last ? 0 : 2.5 }}>
            {/* 점과 세로선 */}
            <Box sx={{ position: 'absolute', left: 4, top: 7, width: 9, height: 9, borderRadius: '50%', bgcolor: dot }} />
            {!last && <Box sx={{ position: 'absolute', left: 8, top: 20, bottom: 0, width: '1px', bgcolor: line.base }} />}

            <Stack direction="row" alignItems="center" spacing={1} sx={{ flexWrap: 'wrap', rowGap: 0.5 }}>
              <Typography variant="caption" sx={{ minWidth: 40, color: text.tertiary, fontWeight: 600 }}>
                {item.typeLabel}
              </Typography>
              <StatusChip label={item.status.label} tone={item.status.tone} />
              <Typography variant="body2" sx={{ color: text.secondary, fontVariantNumeric: 'tabular-nums' }}>
                {item.at}
              </Typography>
              <Typography variant="body2" sx={{ color: text.tertiary }}>
                · {item.actor}
              </Typography>
            </Stack>

            {item.detail && (
              <Box
                sx={{
                  mt: 1,
                  px: 1.5,
                  py: 1,
                  borderRadius: radius.md,
                  bgcolor: item.failure ? tones.red.bg : color.card,
                  border: `1px solid ${item.failure ? tones.red.dot : line.base}`,
                }}
              >
                <Typography variant="caption" sx={{ color: item.failure ? tones.red.fg : text.tertiary, fontWeight: 600 }}>
                  {item.detailLabel}
                </Typography>
                <Typography variant="body2" sx={{ mt: 0.25, ...type.body, wordBreak: 'break-word', whiteSpace: 'pre-wrap' }}>
                  {item.detail}
                </Typography>
              </Box>
            )}
          </Box>
        );
      })}
    </Box>
  );
}
