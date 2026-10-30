import { useMemo, useState, type MouseEvent, type ReactNode } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { Box, Button, ButtonBase, Card, InputAdornment, Stack, TextField, Typography } from '@mui/material';
import DoneAllRoundedIcon from '@mui/icons-material/DoneAllRounded';
import NotificationsOffOutlinedIcon from '@mui/icons-material/NotificationsOffOutlined';
import SearchRoundedIcon from '@mui/icons-material/SearchRounded';
import SectionTitle from '@/components/common/SectionTitle';
import StateMessage from '@/components/common/StateMessage';
import StatusChip from '@/components/common/StatusChip';
import type { NotificationItem } from '@/api/types';
import { color, font, line, onNavy, size, text, tones, type } from '@/theme/theme';
import { formatDate, formatTime } from '@/utils/datetime';
import { priorityMeta, priorityOf } from '@/utils/status';
import {
  CHIP_LABEL,
  NOTIFICATION_CHIPS,
  NOTIFICATION_FILTERS,
  countByChip,
  countByFilter,
  itemHref,
  pressItem,
  splitContent,
  stateView,
  visibleNotifications,
  type ChipFilter,
  type NotificationFilter,
} from '../notificationView';

interface Props {
  notifications: NotificationItem[];
  /** 읽음 요청(결과를 기다리지 않음 — 실패하면 페이지가 되돌리고 토스트) */
  onRead: (notificationId: number) => void;
  onReadAll: () => void;
  /** 모두 읽음 요청 중 */
  readingAll: boolean;
}

/** 탭 · 칩 버튼 하나(선택이면 남색) */
function FilterButton({ selected, count, onClick, children, ...aria }: {
  selected: boolean;
  count: number;
  onClick: () => void;
  children: ReactNode;
  role?: string;
  'aria-selected'?: boolean;
  'aria-pressed'?: boolean;
}) {
  return (
    <ButtonBase
      {...aria}
      onClick={onClick}
      sx={{
        gap: 0.75,
        height: size.controlSm,
        px: 1.5,
        bgcolor: selected ? color.navy : 'transparent',
        color: selected ? color.white : text.secondary,
        border: `1px solid ${selected ? color.navy : line.base}`,
        ...type.caption,
        fontWeight: selected ? font.weight.semibold : font.weight.medium,
        '&:hover': { borderColor: selected ? color.navy : line.strong },
      }}
    >
      {children}
      <Box component="span" sx={{ minWidth: 20, px: 0.5, bgcolor: selected ? onNavy.active : color.card, fontWeight: font.weight.bold }}>
        {count}
      </Box>
    </ButtonBase>
  );
}

const ROW_SX = {
  width: '100%',
  justifyContent: 'flex-start',
  alignItems: 'flex-start',
  textAlign: 'left',
  gap: 1.5,
  px: 1.5,
  py: 1.75,
  color: 'inherit',
  '&:hover': { bgcolor: color.card },
} as const;

/** 알림 한 줄 — 첫 줄 [우선순위] [칩] 첫 문장 · 둘째 줄 나머지(2줄 말줄임) · 현재 상태 · 오른쪽 시각 */
function NotificationRow({ n, onRead }: { n: NotificationItem; onRead: (notificationId: number) => void }) {
  const meta = priorityMeta[priorityOf(n.priority)];
  const { head, rest } = splitContent(n.content);
  const state = stateView(n.state);
  const href = itemHref(n);
  const press = (e: MouseEvent) => pressItem(n, e, onRead);

  const body = (
    <>
      <Box aria-label={n.read ? undefined : '안읽음'} sx={{ width: 7, height: 7, mt: 1, flexShrink: 0, bgcolor: n.read ? 'transparent' : tones.blue.dot }} />
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Stack direction="row" spacing={0.75} alignItems="center" sx={{ minWidth: 0 }}>
          <StatusChip label={meta.label} tone={meta.tone} />
          {n.chip && <StatusChip label={CHIP_LABEL[n.chip]} tone="grey" />}
          <Typography
            variant="body1"
            noWrap
            title={n.content}
            sx={{ minWidth: 0, fontWeight: n.read ? font.weight.regular : font.weight.semibold, color: n.read ? text.secondary : text.primary }}
          >
            {head}
          </Typography>
        </Stack>
        {rest && (
          <Typography
            variant="body2"
            sx={{
              mt: 0.5,
              color: text.secondary,
              overflowWrap: 'anywhere',
              display: '-webkit-box',
              WebkitLineClamp: 2,
              WebkitBoxOrient: 'vertical',
              overflow: 'hidden',
            }}
          >
            {rest}
          </Typography>
        )}
        {state && (
          <Typography
            variant="body2"
            sx={{ mt: 0.5, color: state.tone ? tones[state.tone].fg : text.tertiary, fontWeight: state.tone ? font.weight.semibold : font.weight.regular }}
          >
            {state.text}
          </Typography>
        )}
      </Box>
      <Box sx={{ flexShrink: 0, textAlign: 'right' }}>
        <Typography variant="body2" sx={{ color: text.tertiary }}>
          {formatDate(n.occurredAt)}
        </Typography>
        <Typography variant="body2" sx={{ color: text.tertiary }}>
          {formatTime(n.occurredAt)}
        </Typography>
      </Box>
    </>
  );

  // 경로가 있으면 링크(가운데 클릭 · 새 탭 가능), 없으면 읽음만
  return href ? (
    <ButtonBase component={RouterLink} to={href} onClick={press} onAuxClick={press} sx={ROW_SX}>
      {body}
    </ButtonBase>
  ) : (
    <ButtonBase onClick={press} onAuxClick={press} sx={ROW_SX}>
      {body}
    </ButtonBase>
  );
}

/**
 * 알림센터 본문 — Tetra Portal 알림센터(UI-TP-13) 구조: Slack 영역 · 우선순위 탭 5종(건수) · 칩(종류, 지금 탭 기준 건수) · 검색 ·
 * 알림 리스트(최신순). 운영 콘솔은 종류와 무관하게 모든 알림을 보인다. 누르면 읽음 + 관련 화면으로 이동(경로가 없으면 읽음만).
 */
export default function NotificationCenter({ notifications, onRead, onReadAll, readingAll }: Props) {
  const [filter, setFilter] = useState<NotificationFilter>('ALL');
  const [chip, setChip] = useState<ChipFilter>('ALL');
  const [query, setQuery] = useState('');

  const counts = useMemo(() => countByFilter(notifications), [notifications]);
  const chipCounts = useMemo(() => countByChip(notifications, filter), [notifications, filter]);
  const visible = useMemo(() => visibleNotifications(notifications, filter, chip, query), [notifications, filter, chip, query]);

  return (
    <Stack spacing={3}>
      {/* Slack 연동은 예정(OPS-08 No.2) — 우선순위별 채널 분리 기준 미정 */}
      <Card sx={{ p: 3 }}>
        <SectionTitle mb={0} action={<StatusChip label="연동 예정" tone="grey" />}>
          Slack 연동 · 우선순위별 채널
        </SectionTitle>
      </Card>

      <Box>
        {/* 우선순위 탭 · 모두 읽음 · 검색 */}
        <Stack direction={{ xs: 'column', md: 'row' }} justifyContent="space-between" alignItems={{ md: 'center' }} spacing={1.5} sx={{ mb: 1 }}>
          <Stack role="tablist" aria-label="알림 우선순위" direction="row" flexWrap="wrap" useFlexGap spacing={0.75}>
            {NOTIFICATION_FILTERS.map((f) => (
              <FilterButton key={f.key} role="tab" aria-selected={f.key === filter} selected={f.key === filter} count={counts[f.key]} onClick={() => setFilter(f.key)}>
                {f.label}
              </FilterButton>
            ))}
          </Stack>
          <Stack direction="row" spacing={1} alignItems="center">
            <Button
              variant="outlined"
              size="small"
              startIcon={<DoneAllRoundedIcon />}
              onClick={onReadAll}
              disabled={counts.UNREAD === 0 || readingAll}
              sx={{ flexShrink: 0, height: size.controlSm }}
            >
              모두 읽음
            </Button>
            <TextField
              size="small"
              placeholder="알림 내용 검색"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              sx={{ width: { xs: '100%', md: 280 } }}
              slotProps={{
                htmlInput: { 'aria-label': '알림 내용 검색' },
                input: {
                  startAdornment: (
                    <InputAdornment position="start">
                      <SearchRoundedIcon sx={{ fontSize: size.iconMd, color: text.tertiary }} />
                    </InputAdornment>
                  ),
                },
              }}
            />
          </Stack>
        </Stack>

        {/* 칩(종류) — 탭과 함께 거르고, 건수는 지금 탭 기준 */}
        <Stack role="group" aria-label="알림 종류" direction="row" flexWrap="wrap" useFlexGap spacing={0.75} sx={{ mb: 1.5 }}>
          {NOTIFICATION_CHIPS.map((c) => (
            <FilterButton key={c.key} aria-pressed={c.key === chip} selected={c.key === chip} count={chipCounts[c.key]} onClick={() => setChip(c.key)}>
              {c.label}
            </FilterButton>
          ))}
        </Stack>

        {/* 알림 리스트 */}
        {visible.length === 0 ? (
          <StateMessage icon={<NotificationsOffOutlinedIcon />} tone="grey" title={query.trim() ? '검색 결과가 없습니다' : '알림이 없습니다'} />
        ) : (
          <Box component="ul" sx={{ listStyle: 'none', m: 0, p: 0, borderTop: `1px solid ${line.strong}` }}>
            {visible.map((n) => (
              <Box component="li" key={n.notificationId} sx={{ borderBottom: `1px solid ${line.base}` }}>
                <NotificationRow n={n} onRead={onRead} />
              </Box>
            ))}
          </Box>
        )}
      </Box>
    </Stack>
  );
}
