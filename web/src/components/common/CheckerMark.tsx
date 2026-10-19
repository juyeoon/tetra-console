import { Box, type SxProps, type Theme } from '@mui/material';
import { color } from '@/theme/theme';

/**
 * Canva 시안의 픽셀 체커 패턴 (브랜드 시그니처).
 * 모서리 장식으로만 사용한다 — 본문 영역이나 데이터 위에는 두지 않는다.
 */
const PATTERN = {
  // 우상단: 오른쪽 위로 갈수록 촘촘한 계단형
  'top-right': ['0011011', '0001101', '0000011', '0000001'],
  // 좌하단: 왼쪽 아래로 갈수록 촘촘한 계단형
  'bottom-left': ['1000000', '1100000', '1011000', '1101100'],
} as const;

interface CheckerMarkProps {
  corner?: keyof typeof PATTERN;
  cell?: number;
  /** 두 번째 톤(기본: 페리윙클) */
  tone?: string;
  /** 첫 번째 톤(기본: 네이비) */
  base?: string;
  sx?: SxProps<Theme>;
}

export default function CheckerMark({ corner = 'top-right', cell = 10, tone = color.peri, base = color.navy, sx }: CheckerMarkProps) {
  const rows = PATTERN[corner];
  const cols = rows[0].length;
  return (
    <Box component="svg" aria-hidden width={cols * cell} height={rows.length * cell} viewBox={`0 0 ${cols} ${rows.length}`} sx={{ display: 'block', ...sx }}>
      {rows.flatMap((row, y) =>
        [...row].map((v, x) =>
          v === '1' ? <rect key={`${x}-${y}`} x={x} y={y} width={1} height={1} style={{ fill: (x + y) % 2 === 0 ? base : tone }} /> : null,
        ),
      )}
    </Box>
  );
}
