import type { ReactNode } from 'react';
import { Box, Stack, Typography } from '@mui/material';
import { color, radius, size, text, type Tone, tones } from '@/theme/theme';

export interface CountItem {
  key: string;
  label: string;
  count: number;
  tone: Tone;
}

interface ListInfoBarProps {
  /** 좌측 안내 문구 */
  note?: ReactNode;
  /** 우측 상태별 건수 — 그룹마다 한 줄 (필터 기능 없음, 단순 표시) */
  groups: { title?: string; items: CountItem[] }[];
}

function Counts({ title, items }: { title?: string; items: CountItem[] }) {
  return (
    <Stack direction="row" alignItems="center" flexWrap="wrap" useFlexGap columnGap={2} rowGap={0.5}>
      {title && <Typography variant="caption">{title}</Typography>}
      {items.map((item) => (
        <Stack key={item.key} direction="row" alignItems="center" spacing={0.75}>
          <Box sx={{ width: size.dot, height: size.dot, borderRadius: '50%', bgcolor: tones[item.tone].dot }} />
          <Typography variant="body2" sx={{ color: text.secondary }}>
            {item.label}
          </Typography>
          <Typography variant="subtitle2">{item.count}건</Typography>
        </Stack>
      ))}
    </Stack>
  );
}

export default function ListInfoBar({ note, groups }: ListInfoBarProps) {
  return (
    <Stack
      direction={{ xs: 'column', md: 'row' }}
      alignItems={{ xs: 'flex-start', md: 'center' }}
      justifyContent="space-between"
      spacing={{ xs: 1, md: 3 }}
      sx={{ mb: 2, px: 2, py: 1.5, borderRadius: radius.lg, bgcolor: color.card }}
    >
      {note ? (
        <Typography variant="body2" sx={{ color: text.secondary, flex: 1, minWidth: 0 }}>
          ※ {note}
        </Typography>
      ) : (
        <Box sx={{ flex: 1 }} />
      )}
      <Stack spacing={0.5} alignItems={{ xs: 'flex-start', md: 'flex-end' }} sx={{ flexShrink: 0 }}>
        {groups.map((g, i) => (
          <Counts key={g.title ?? i} title={g.title} items={g.items} />
        ))}
      </Stack>
    </Stack>
  );
}
