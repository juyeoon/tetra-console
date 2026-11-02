import type { ReactNode } from 'react';
import { Box, Typography } from '@mui/material';
import { font, kpi, radius, text } from '@/theme/theme';
import HighlightValue from './HighlightValue';

interface StatCardProps {
  label: ReactNode;
  value: ReactNode;
  sub?: ReactNode;
  children?: ReactNode;
}

/**
 * KPI 카드(요약 카드) — 연한 바탕 + 테두리. 제목은 카드 제목(h4)과 같은 스타일, 값은 일반 글자(글자 배경 없음).
 * 바탕 · 테두리 · 값 스타일은 tokens.css의 --kpi-* 변수로 조정한다. 바탕이 100 이상이라 흐린 글자는 secondary
 */
export default function StatCard({ label, value, sub, children }: StatCardProps) {
  return (
    <Box sx={{ p: 3, height: '100%', borderRadius: radius.xl, bgcolor: kpi.cardBg, border: `1px solid ${kpi.cardBorder}`, containerType: 'inline-size' }}>
      <Typography variant="h4" component="h3" sx={{ color: kpi.labelColor }}>
        {label}
      </Typography>
      <Box sx={{ mt: 1.5 }}>
        <HighlightValue sx={{ whiteSpace: 'nowrap', fontSize: `min(${font.size.page}, ${kpi.valueFluid})` }}>{value}</HighlightValue>
      </Box>
      {sub && (
        <Typography variant="body2" sx={{ mt: 1, color: text.secondary }}>
          {sub}
        </Typography>
      )}
      {children}
    </Box>
  );
}
