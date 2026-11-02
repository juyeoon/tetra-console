import type { ReactNode } from 'react';
import { Box, Stack, Typography } from '@mui/material';
import { color, line, mono, radius, size, text } from '@/theme/theme';
import CheckerMark from './CheckerMark';

interface PlaceholderPanelProps {
  icon: ReactNode;
  title: string;
  lines: ReactNode[];
  minHeight?: number | string;
  children?: ReactNode;
}

/** 다른 화면과 공유하거나 아직 상세 설계가 확정되지 않은 영역 */
export default function PlaceholderPanel({ icon, title, lines, minHeight = 'calc(100vh - 200px)', children }: PlaceholderPanelProps) {
  return (
    <Box
      sx={{
        position: 'relative',
        overflow: 'hidden',
        minHeight,
        display: 'grid',
        placeItems: 'center',
        p: 4,
        borderRadius: radius.xl,
        bgcolor: color.card,
        border: `1px dashed ${line.strong}`,
      }}
    >
      <CheckerMark corner="top-right" cell={8} base={mono[300]} tone={mono[200]} sx={{ position: 'absolute', top: 0, right: 0 }} />
      <Stack alignItems="center" spacing={1} sx={{ maxWidth: size.contentText, textAlign: 'center' }}>
        <Box sx={{ width: size.iconBoxLg, height: size.iconBoxLg, mb: 1, borderRadius: radius.lg, display: 'grid', placeItems: 'center', bgcolor: mono[200], color: color.navy, '& svg': { fontSize: size.iconLg } }}>
          {icon}
        </Box>
        <Typography variant="h3">{title}</Typography>
        {lines.map((l, i) => (
          <Typography key={i} variant="body1" sx={{ color: text.secondary }}>
            {l}
          </Typography>
        ))}
        {children}
      </Stack>
    </Box>
  );
}
