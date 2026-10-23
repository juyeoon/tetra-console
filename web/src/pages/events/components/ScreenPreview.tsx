import { Box, Stack, Typography } from '@mui/material';
import ImageOutlinedIcon from '@mui/icons-material/ImageOutlined';
import type { HAlign, LayoutConfig, ScreenWidget, VAlign } from '@/api/types';
import { color, font, ink, line, previewThemes, radius, size, type } from '@/theme/theme';
import { WIDGET_ORDER } from '../applicationSummaryView';

/*
 * Tetra Portal components/event/ScreenPreview.tsx를 옮긴 것(저장소가 달라 복사). 표기 · 모양을 바꾸면 Portal과 같이 맞춘다.
 */

const justify: Record<HAlign, string> = { LEFT: 'flex-start', CENTER: 'center', RIGHT: 'flex-end' };

function Widget({ widget, accent, fg }: { widget: ScreenWidget; accent: string; fg: string }) {
  switch (widget) {
    case 'title':
      return (
        <Typography sx={{ ...type.caption, fontWeight: font.weight.bold, color: fg, px: 1.5, py: 0.25, borderRadius: radius.sm, bgcolor: ink[5] }}>
          이벤트 제목
        </Typography>
      );
    case 'banner':
      return (
        <Box sx={{ width: '44%', minWidth: 120, aspectRatio: '1200 / 420', display: 'grid', placeItems: 'center', borderRadius: radius.sm, border: '1px dashed', borderColor: fg, color: fg, opacity: 0.45 }}>
          <ImageOutlinedIcon sx={{ fontSize: size.iconMd }} />
        </Box>
      );
    case 'couponList':
      return (
        <Stack direction="row" spacing={0.75}>
          {[0, 1].map((i) => (
            <Box key={i} sx={{ width: 56, height: 26, px: 0.75, borderRadius: radius.sm, border: `1px dashed ${accent}`, display: 'grid', alignContent: 'center', gap: '3px' }}>
              <Box sx={{ height: 2, width: '80%', bgcolor: fg, opacity: 0.4 }} />
              <Box sx={{ height: 2, width: '55%', bgcolor: fg, opacity: 0.25 }} />
            </Box>
          ))}
        </Stack>
      );
    case 'issueButton':
      return (
        <Box sx={{ px: 2, py: 0.5, bgcolor: accent, color: color.white, ...type.caption, fontWeight: font.weight.bold }}>
          쿠폰 받기
        </Box>
      );
  }
}

interface ScreenPreviewProps {
  screen: LayoutConfig;
  /** 브라우저 주소창에 표시할 도메인 */
  domain?: string;
  height?: number;
}

/**
 * 이벤트 화면 미리보기 (예시 그림) — 위치와 테마만 반영하고 배너 이미지 · 쿠폰 정보는 반영하지 않는다 (Portal UI-TP-09 ②)
 */
export default function ScreenPreview({ screen, domain = 'event.tetra-shop.com', height = 440 }: ScreenPreviewProps) {
  const t = previewThemes[screen.theme];
  const zones: VAlign[] = ['TOP', 'MIDDLE', 'BOTTOM'];

  return (
    <Box sx={{ borderRadius: radius.lg, overflow: 'hidden', border: `1px solid ${line.base}` }}>
      {/* 브라우저 상단 바 */}
      <Stack direction="row" alignItems="center" spacing={1} sx={{ px: 1.5, py: 1, bgcolor: ink[5], borderBottom: `1px solid ${line.base}` }}>
        <Stack direction="row" spacing={0.5}>
          {[0, 1, 2].map((i) => (
            <Box key={i} sx={{ width: 7, height: 7, bgcolor: ink[20] }} />
          ))}
        </Stack>
        <Typography sx={{ ...type.caption, color: ink[40] }}>{domain}</Typography>
      </Stack>

      <Box sx={{ height, display: 'grid', gridTemplateRows: 'repeat(3, 1fr)', bgcolor: t.bg, color: t.fg, transition: 'background-color .2s' }}>
        {zones.map((zone) => (
          <Stack key={zone} justifyContent="center" spacing={1.25} sx={{ px: 3, py: 1.5 }}>
            {WIDGET_ORDER.filter((w) => screen[w].v === zone).map((w) => (
              <Box key={w} sx={{ display: 'flex', justifyContent: justify[screen[w].h] }}>
                <Widget widget={w} accent={t.accent} fg={t.fg} />
              </Box>
            ))}
          </Stack>
        ))}
      </Box>
    </Box>
  );
}
