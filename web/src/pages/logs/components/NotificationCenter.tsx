import { useMemo, useState } from 'react';
import { Box, Button, ButtonBase, Card, InputAdornment, Stack, TextField, Typography } from '@mui/material';
import DoneAllRoundedIcon from '@mui/icons-material/DoneAllRounded';
import NotificationsOffOutlinedIcon from '@mui/icons-material/NotificationsOffOutlined';
import SearchRoundedIcon from '@mui/icons-material/SearchRounded';
import SectionTitle from '@/components/common/SectionTitle';
import StateMessage from '@/components/common/StateMessage';
import StatusChip, { EmptyValue } from '@/components/common/StatusChip';
import type { NotificationItem } from '@/api/types';
import { color, font, line, onNavy, size, text, tones, type } from '@/theme/theme';
import { formatDate, formatTime } from '@/utils/datetime';
import { priorityMeta, priorityOf } from '@/utils/status';
import { NOTIFICATION_FILTERS, countByFilter, visibleNotifications, type NotificationFilter } from '../notificationView';

interface Props {
  notifications: NotificationItem[];
  onRead: (notificationId: number) => void;
  onReadAll: () => void;
  /** 모두 읽음 요청 중 */
  readingAll: boolean;
}

/**
 * 알림센터 본문 — Tetra Portal 알림센터(UI-TP-13) 구조: Slack 영역 · 필터 탭 5종(건수) · 검색 · 알림 리스트(최신순).
 * 운영 콘솔은 종류와 무관하게 모든 알림을 보이고, 항목마다 테넌트명(없으면 -)을 붙인다. 클릭하면 읽음.
 */
export default function NotificationCenter({ notifications, onRead, onReadAll, readingAll }: Props) {
  const [filter, setFilter] = useState<NotificationFilter>('ALL');
  const [query, setQuery] = useState('');

  const counts = useMemo(() => countByFilter(notifications), [notifications]);
  const visible = useMemo(() => visibleNotifications(notifications, filter, query), [notifications, filter, query]);

  return (
    <Stack spacing={3}>
      {/* Slack 연동은 예정(OPS-08 No.2) — 우선순위별 채널 분리 기준 미정 */}
      <Card sx={{ p: 3 }}>
        <SectionTitle mb={0} action={<StatusChip label="연동 예정" tone="grey" />}>
          Slack 연동 · 우선순위별 채널
        </SectionTitle>
      </Card>

      <Box>
        {/* 필터 탭 · 모두 읽음 · 검색 */}
        <Stack direction={{ xs: 'column', md: 'row' }} justifyContent="space-between" alignItems={{ md: 'center' }} spacing={1.5} sx={{ mb: 1.5 }}>
          <Stack role="tablist" aria-label="알림 필터" direction="row" flexWrap="wrap" useFlexGap spacing={0.75}>
            {NOTIFICATION_FILTERS.map((f) => {
              const selected = f.key === filter;
              return (
                <ButtonBase
                  key={f.key}
                  role="tab"
                  aria-selected={selected}
                  onClick={() => setFilter(f.key)}
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
                  {f.label}
                  <Box component="span" sx={{ minWidth: 20, px: 0.5, bgcolor: selected ? onNavy.active : color.card, fontWeight: font.weight.bold }}>
                    {counts[f.key]}
                  </Box>
                </ButtonBase>
              );
            })}
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

        {/* 알림 리스트 */}
        {visible.length === 0 ? (
          <StateMessage icon={<NotificationsOffOutlinedIcon />} tone="grey" title={query.trim() ? '검색 결과가 없습니다' : '알림이 없습니다'} />
        ) : (
          <Box component="ul" sx={{ listStyle: 'none', m: 0, p: 0, borderTop: `1px solid ${line.strong}` }}>
            {visible.map((n) => {
              const meta = priorityMeta[priorityOf(n.priority)];
              return (
                <Box component="li" key={n.notificationId} sx={{ borderBottom: `1px solid ${line.base}` }}>
                  <ButtonBase
                    onClick={() => !n.read && onRead(n.notificationId)}
                    sx={{ width: '100%', justifyContent: 'flex-start', alignItems: 'flex-start', textAlign: 'left', gap: 1.5, px: 1.5, py: 1.75, '&:hover': { bgcolor: color.card } }}
                  >
                    <Box
                      aria-label={n.read ? undefined : '안읽음'}
                      sx={{ width: 7, height: 7, mt: 1, flexShrink: 0, bgcolor: n.read ? 'transparent' : tones.blue.dot }}
                    />
                    <StatusChip label={meta.label} tone={meta.tone} />
                    <Typography
                      variant="body1"
                      sx={{ flex: 1, minWidth: 0, overflowWrap: 'anywhere', fontWeight: n.read ? font.weight.regular : font.weight.semibold, color: n.read ? text.secondary : text.primary }}
                    >
                      {n.content}
                    </Typography>
                    <Typography variant="body2" noWrap title={n.tenantName ?? undefined} sx={{ width: { xs: 88, md: 160 }, flexShrink: 0, color: text.secondary }}>
                      {n.tenantName ?? <EmptyValue />}
                    </Typography>
                    <Box sx={{ flexShrink: 0, textAlign: 'right' }}>
                      <Typography variant="body2" sx={{ color: text.tertiary }}>
                        {formatDate(n.occurredAt)}
                      </Typography>
                      <Typography variant="body2" sx={{ color: text.tertiary }}>
                        {formatTime(n.occurredAt)}
                      </Typography>
                    </Box>
                  </ButtonBase>
                </Box>
              );
            })}
          </Box>
        )}
      </Box>
    </Stack>
  );
}
