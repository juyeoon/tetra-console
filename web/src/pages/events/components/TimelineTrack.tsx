import { useEffect, useMemo, useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { Box, Tooltip, Typography } from '@mui/material';
import type { LiveEventItem } from '@/api/types';
import { useWidth } from '@/hooks/useWidth';
import { chartColors, color, font, mono, shadow, text, timeline, type } from '@/theme/theme';
import { eventPath } from '@/utils/event';
import {
  BAR_MIN_PX,
  BAR_PX,
  CAPTION_PX,
  estimateWidth,
  laneBarTop,
  laneNameTop,
  NAME_LINE_PX,
  NOW_CHIP_PAD_X,
  TRACK_MIN_PX,
  TRACK_TOP_PX,
  trackView,
  WEEK_LABEL_INSET_PX,
  type Measure,
  type NamePart,
  type Period,
  type PlacedBar,
  type SegmentKind,
  type Tick,
} from '../timelineView';

/** 마무리 빗금 */
const WRAP_BG = `repeating-linear-gradient(135deg, ${timeline.wrapA} 0 3px, ${timeline.wrapB} 3px 6px)`;

/** 구간 바탕(범례 견본도 같이 쓴다) */
export const SEGMENT_BG: Record<SegmentKind, string> = {
  beforeEntry: timeline.beforeEntry,
  entry: timeline.entry,
  live: timeline.live,
  wrap: WRAP_BG,
};

const PART_COLOR: Record<NamePart['tone'], string> = { primary: text.primary, secondary: text.secondary, failed: timeline.failed };

const TICK_COLOR: Record<Tick['tone'], string> = { primary: text.primary, secondary: text.secondary, tertiary: text.tertiary };

/** 실패 — 1.5px 빨간 점선(막대 위에 덮음) */
const FAILED_BORDER = { content: '""', position: 'absolute', inset: 0, border: `1.5px dashed ${timeline.failed}`, borderRadius: 'inherit', pointerEvents: 'none' } as const;

/** 실제 글꼴(--font-body) · 굵기 · px로 이름 폭을 잰다. canvas를 못 쓰면 null(추정으로) */
function canvasMeasure(): Measure | null {
  const ctx = document.createElement('canvas').getContext('2d');
  if (!ctx) return null;
  const family = getComputedStyle(document.documentElement).getPropertyValue('--font-body').trim() || 'sans-serif';
  const cache = new Map<string, number>();
  return (parts) =>
    parts.reduce((w, p) => {
      const key = `${p.weight}|${p.text}`;
      let v = cache.get(key);
      if (v === undefined) {
        ctx.font = `${p.weight} ${CAPTION_PX}px ${family}`;
        v = ctx.measureText(p.text).width;
        cache.set(key, v);
      }
      return w + v;
    }, 0);
}

/** 웹 글꼴(CDN, font-display: swap)이 다 받아진 뒤 한 번 더 잰다 — 처음에는 대체 글꼴 폭일 수 있다 */
function useNameMeasure(): Measure {
  const [fontsLoaded, setFontsLoaded] = useState(0);
  useEffect(() => {
    let alive = true;
    void document.fonts.ready.then(() => {
      if (alive) setFontsLoaded((n) => n + 1);
    });
    return () => {
      alive = false;
    };
  }, []);
  return useMemo(() => canvasMeasure() ?? estimateWidth, [fontsLoaded]);
}

function TickLabels({ ticks }: { ticks: Tick[] }) {
  return (
    <Box sx={{ position: 'relative', height: NAME_LINE_PX, mb: 0.5 }}>
      {ticks
        .filter((t) => t.label)
        .map((t) => (
          <Typography
            key={t.t}
            variant="caption"
            component="span"
            sx={{
              position: 'absolute',
              top: 0,
              whiteSpace: 'nowrap',
              fontVariantNumeric: 'tabular-nums',
              color: TICK_COLOR[t.tone],
              fontWeight: t.bold ? font.weight.semibold : font.weight.regular,
              ...(t.align === 'center'
                ? { left: `${t.pct}%`, transform: 'translateX(-50%)' }
                : t.align === 'start'
                  ? { left: 0 }
                  : { left: `calc(${t.pct}% + ${WEEK_LABEL_INSET_PX}px)` }),
            }}
          >
            {t.label}
          </Typography>
        ))}
    </Box>
  );
}

/** 이벤트 하나 — 이름(막대 위, 링크지만 Tab · 읽기 제외) + 막대(링크 · 툴팁). 둘 중 어디에 올려도 이름 밑줄 + 막대 포커스 링 */
function EventBar({ bar, week }: { bar: PlacedBar; week: boolean }) {
  const to = eventPath(bar.eventId, 'APPROVED');
  const opacity = bar.past ? timeline.pastOpacity : 1;
  return (
    <Box sx={{ display: 'contents', '&:hover .tl-title': { textDecoration: 'underline' }, '&:hover .tl-bar': { boxShadow: shadow.focus } }}>
      <Box
        component={RouterLink}
        to={to}
        tabIndex={-1}
        aria-hidden
        sx={{
          position: 'absolute',
          top: laneNameTop(bar.lane),
          ...(bar.nameLeftPct == null ? { right: 0 } : { left: `${bar.nameLeftPct}%` }),
          ...type.caption,
          whiteSpace: 'nowrap',
          textDecoration: 'none',
          opacity,
        }}
      >
        {bar.nameParts.map((p, i) => (
          <Box key={i} component="span" className={i === 0 ? 'tl-title' : undefined} sx={{ fontWeight: p.weight, color: PART_COLOR[p.tone], textUnderlineOffset: 3 }}>
            {p.text}
          </Box>
        ))}
      </Box>
      <Tooltip
        describeChild
        arrow
        placement="top"
        // 단계 시각 줄이 기본 최대 폭(300px)보다 길어 글자 중간에서 줄이 바뀌지 않게
        slotProps={{ tooltip: { sx: { maxWidth: 'min(640px, calc(100vw - 32px))', wordBreak: 'keep-all' } } }}
        title={
          <Box>
            {bar.tooltip.map((line, i) => (
              <Box key={i}>{line}</Box>
            ))}
          </Box>
        }
      >
        <Box
          component={RouterLink}
          to={to}
          aria-label={bar.ariaLabel}
          className="tl-bar"
          sx={{
            position: 'absolute',
            top: laneBarTop(bar.lane),
            left: `${bar.barLeftPct}%`,
            width: `${bar.widthPct}%`,
            minWidth: BAR_MIN_PX,
            height: BAR_PX,
            display: 'flex',
            gap: week ? 0 : '1px',
            borderRadius: '4px',
            overflow: 'hidden',
            opacity,
            outline: 'none',
            transition: 'box-shadow .15s',
            '&:focus-visible': { boxShadow: shadow.focus },
            '&::after': bar.failed ? FAILED_BORDER : undefined,
          }}
        >
          {bar.segments.map((s) => (
            <Box key={s.kind} sx={{ flex: `${s.grow} 1 0`, minWidth: 0, background: SEGMENT_BG[s.kind] }} />
          ))}
        </Box>
      </Tooltip>
    </Box>
  );
}

/**
 * 눈금 줄 + 트랙. 가로 스크롤 상자 안 한 요소(minWidth 900 · width 100%)에 둘을 같이 넣어 함께 스크롤한다.
 * 트랙 px 폭은 그 요소를 재고(첫 그림은 900 가정), 줄 배치는 폭 · 데이터 · 기간 · 지금이 바뀔 때 다시 한다.
 */
export default function TimelineTrack({ items, period, now }: { items: LiveEventItem[] | undefined; period: Period; now: number }) {
  const [ref, width] = useWidth<HTMLDivElement>();
  const measure = useNameMeasure();
  const trackPx = width > 0 ? width : TRACK_MIN_PX;
  const view = useMemo(() => trackView(items, period, now, { trackPx, measure }), [items, period, now, trackPx, measure]);

  return (
    <Box sx={{ overflowX: 'auto' }}>
      <Box ref={ref} sx={{ minWidth: TRACK_MIN_PX, width: '100%' }}>
        <TickLabels ticks={view.ticks} />
        <Box sx={{ position: 'relative', height: view.height, borderTop: `1px solid ${mono[200]}`, borderBottom: `1px solid ${mono[200]}` }}>
          {view.overlaps.map((o) => (
            <Box key={`band-${o.leftPct}`} sx={{ position: 'absolute', top: 0, bottom: 0, left: `${o.leftPct}%`, width: `${o.widthPct}%`, bgcolor: timeline.overlap }} />
          ))}
          {view.ticks
            .filter((t) => t.pct < 100)
            .map((t) => (
              <Box key={`grid-${t.t}`} sx={{ position: 'absolute', top: 0, bottom: 0, left: `${t.pct}%`, width: '1px', bgcolor: t.midnight ? mono[200] : mono[100] }} />
            ))}
          {view.bars.map((b) => (
            <EventBar key={b.eventId} bar={b} week={period.week} />
          ))}
          {view.overlaps.map((o) => (
            <Typography
              key={`label-${o.leftPct}`}
              variant="caption"
              component="span"
              sx={{ position: 'absolute', top: (TRACK_TOP_PX - NAME_LINE_PX) / 2, left: `${o.labelLeftPct}%`, whiteSpace: 'nowrap', color: chartColors.primary, fontWeight: font.weight.semibold, fontVariantNumeric: 'tabular-nums' }}
            >
              {o.label}
            </Typography>
          ))}
          <Box sx={{ position: 'absolute', top: 0, bottom: 0, left: `${view.now.pct}%`, width: '2px', ml: '-1px', bgcolor: mono[900], pointerEvents: 'none' }} />
          <Box
            component="span"
            sx={{
              position: 'absolute',
              bottom: '2px',
              left: `${view.now.chipLeftPct}%`,
              px: `${NOW_CHIP_PAD_X}px`,
              py: '2px',
              borderRadius: '4px',
              bgcolor: mono[900],
              color: color.white,
              fontSize: font.size.caption,
              lineHeight: '18px',
              fontWeight: font.weight.semibold,
              whiteSpace: 'nowrap',
              fontVariantNumeric: 'tabular-nums',
              pointerEvents: 'none',
            }}
          >
            {view.now.label}
          </Box>
        </Box>
      </Box>
    </Box>
  );
}
