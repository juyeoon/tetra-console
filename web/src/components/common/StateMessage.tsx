import type { ReactNode } from 'react';
import { Box, Stack, Typography } from '@mui/material';
import { color, radius, size, text, tones, type Tone } from '@/theme/theme';

interface StateMessageProps {
  icon: ReactNode;
  /** 상태 톤. 없으면 중립(연한 바탕 + 보조 글자색) */
  tone?: Tone;
  title: string;
  description?: ReactNode;
  children?: ReactNode;
}

const NEUTRAL = { bg: color.card, fg: text.secondary };

/** 결과 대기/빈 상태 안내 */
export default function StateMessage({ icon, tone, title, description, children }: StateMessageProps) {
  const t = tone ? tones[tone] : NEUTRAL;
  return (
    <Stack alignItems="center" spacing={1} sx={{ py: { xs: 8, md: 12 }, textAlign: 'center' }}>
      <Box sx={{ width: size.iconBoxLg, height: size.iconBoxLg, mb: 1, borderRadius: radius.lg, display: 'grid', placeItems: 'center', bgcolor: t.bg, color: t.fg, '& svg': { fontSize: size.iconLg } }}>
        {icon}
      </Box>
      <Typography variant="h3">{title}</Typography>
      {description && (
        <Typography variant="body1" sx={{ color: text.secondary }}>
          {description}
        </Typography>
      )}
      {children}
    </Stack>
  );
}
