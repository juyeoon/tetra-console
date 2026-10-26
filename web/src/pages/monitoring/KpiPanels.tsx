import { Box, Typography } from '@mui/material';
import { line, radius, text, tones } from '@/theme/theme';
import type { KpiGroup, KpiTile } from './trafficMonitoringView';

/** KPI 하나 — 하이라이트 블록 없이 숫자만, 주의 값만 황토색 글씨 */
function Tile({ tile }: { tile: KpiTile }) {
  return (
    <Box sx={{ minWidth: 0 }}>
      <Typography variant="caption" sx={{ color: text.tertiary }} noWrap component="p">
        {tile.label}
      </Typography>
      <Typography variant="h3" component="p" sx={{ mt: 0.25, color: tile.warn ? tones.amber.fg : text.primary, fontVariantNumeric: 'tabular-nums' }} noWrap>
        {tile.value}
      </Typography>
      {tile.sub && (
        <Typography variant="caption" component="p" sx={{ color: tile.warn ? tones.amber.fg : text.tertiary }} noWrap title={tile.sub}>
          {tile.sub}
        </Typography>
      )}
    </Box>
  );
}

const boxSx = { p: 2, border: `1px solid ${line.base}`, borderRadius: radius.lg, minWidth: 0 } as const;

/** D-1 KPI 4묶음(요청 처리 · 앱 자원 · 이벤트 진행 · Redis) — 2 × 2 */
export function KpiGroups({ groups }: { groups: KpiGroup[] }) {
  return (
    <Box sx={{ display: 'grid', gap: 1.5, gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' } }}>
      {groups.map((g) => (
        <Box key={g.title} sx={boxSx}>
          <Typography variant="subtitle2" sx={{ mb: 1 }}>
            {g.title}
          </Typography>
          <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: `repeat(${g.tiles.length}, minmax(0, 1fr))` }}>
            {g.tiles.map((t) => (
              <Tile key={t.label} tile={t} />
            ))}
          </Box>
        </Box>
      ))}
    </Box>
  );
}

/** 종료된 이벤트 최종값 10칸 */
export function KpiTiles({ tiles }: { tiles: KpiTile[] }) {
  return (
    <Box sx={{ display: 'grid', gap: 1.5, gridTemplateColumns: { xs: 'repeat(2, minmax(0, 1fr))', md: 'repeat(5, minmax(0, 1fr))' } }}>
      {tiles.map((t) => (
        <Box key={t.label} sx={boxSx}>
          <Tile tile={t} />
        </Box>
      ))}
    </Box>
  );
}
