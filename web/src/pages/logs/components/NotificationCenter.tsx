import { useEffect, useId, useRef, useState, type MouseEvent } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import {
  Box,
  Button,
  ButtonBase,
  Checkbox,
  FormControlLabel,
  InputAdornment,
  Menu,
  MenuItem,
  Pagination,
  Stack,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from '@mui/material';
import DoneAllRoundedIcon from '@mui/icons-material/DoneAllRounded';
import KeyboardArrowDownRoundedIcon from '@mui/icons-material/KeyboardArrowDownRounded';
import NotificationsOffOutlinedIcon from '@mui/icons-material/NotificationsOffOutlined';
import SearchRoundedIcon from '@mui/icons-material/SearchRounded';
import StateMessage from '@/components/common/StateMessage';
import StatusChip from '@/components/common/StatusChip';
import type { NotificationItem, NotificationPage } from '@/api/types';
import { color, font, ink, line, shadow, size, text, tones, type } from '@/theme/theme';
import { formatDate, formatTime } from '@/utils/datetime';
import { priorityMeta, priorityOf } from '@/utils/status';
import {
  CHIP_LABEL,
  PRIORITY_FILTERS,
  QUERY_MAX,
  SEARCH_DELAY_MS,
  canReadAll,
  chipButtonLabel,
  chipMenuItems,
  emptyState,
  itemHref,
  pressItem,
  splitContent,
  stateView,
  type ChipFilter,
  type ChipMenuItem,
  type NotificationFilters,
  type PriorityFilter,
} from '../notificationView';

interface Props {
  /** 지금 조건 · 페이지의 서버 응답 */
  data: NotificationPage;
  filters: NotificationFilters;
  /** 1부터 */
  page: number;
  /** 다른 조건 · 페이지를 받는 중 — 지금 목록을 흐리게 */
  loading: boolean;
  /** 조건 바꿈(1페이지로). 검색어는 입력이 멈춘 뒤 */
  onFiltersChange: (change: Partial<NotificationFilters>) => void;
  onPageChange: (page: number) => void;
  /** 읽음 요청(결과를 기다리지 않음 — 실패하면 페이지가 되돌리고 토스트) */
  onRead: (notificationId: number) => void;
  /** 모두 읽음(안 읽은 알림 전부) — 요청 중이면 readingAll */
  onReadAll: () => void;
  readingAll: boolean;
}

/** 우선순위 세그먼트 한 칸 — 선택이면 진한 바탕(900) + 흰 글자 */
const SEGMENT_SX = {
  gap: 0.75,
  height: size.controlSm,
  px: { xs: 1, sm: 1.5 },
  py: 0,
  border: `1px solid ${line.base}`,
  color: text.secondary,
  ...type.caption,
  fontWeight: font.weight.medium,
  whiteSpace: 'nowrap',
  '&:hover': { bgcolor: ink[5] },
  '&.Mui-selected, &.Mui-selected:hover': { bgcolor: color.navy, borderColor: color.navy, color: color.white, fontWeight: font.weight.semibold },
  '&.Mui-focusVisible': { boxShadow: shadow.focus, zIndex: 1 },
} as const;

/** 분류 드롭다운 — '분류: 전체 ▾'. 전체가 아니면 테두리 · 글자를 진한 색(900)으로. 메뉴는 이름 + 건수, 0건은 흐리게(고를 수는 있다) */
function ChipMenu({ value, items, onChange }: { value: ChipFilter; items: ChipMenuItem[]; onChange: (chip: ChipFilter) => void }) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const menuId = useId();
  const active = value !== 'ALL';

  return (
    <>
      <Button
        variant="outlined"
        size="small"
        aria-haspopup="true"
        aria-expanded={!!anchor}
        aria-controls={anchor ? menuId : undefined}
        endIcon={<KeyboardArrowDownRoundedIcon />}
        onClick={(e) => setAnchor(e.currentTarget)}
        sx={{
          height: size.controlSm,
          whiteSpace: 'nowrap',
          ...(active && { borderColor: color.navy, color: color.navy, '&:hover': { borderColor: color.navy, bgcolor: color.card } }),
        }}
      >
        {chipButtonLabel(value)}
      </Button>
      <Menu
        id={menuId}
        anchorEl={anchor}
        open={!!anchor}
        onClose={() => setAnchor(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'left' }}
        transformOrigin={{ vertical: 'top', horizontal: 'left' }}
        slotProps={{ paper: { sx: { mt: 0.5, minWidth: 180 } } }}
      >
        {items.map((c) => (
          <MenuItem
            key={c.key}
            role="menuitemradio"
            aria-checked={c.key === value}
            selected={c.key === value}
            onClick={() => {
              setAnchor(null);
              onChange(c.key);
            }}
            sx={{ justifyContent: 'space-between', gap: 3, color: c.dim ? text.tertiary : text.primary }}
          >
            {c.label}
            {/* 선택 항목 바탕(900 8%)은 100보다 진해 흐린 글자는 secondary */}
            <Box component="span" sx={{ ...type.caption, fontWeight: font.weight.semibold, color: c.dim ? text.disabled : c.key === value ? text.secondary : text.tertiary }}>
              {c.count}
            </Box>
          </MenuItem>
        ))}
      </Menu>
    </>
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
  // 경로 없는 항목은 <button> — 브라우저 기본 버튼 글꼴 대신 본문 글꼴을 물려받게(안쪽 칩이 글꼴을 따로 정하지 않는다)
  font: 'inherit',
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
 * 검색 — 입력칸은 바로 바뀌고, 조건 반영(주소 · 요청)은 입력이 SEARCH_DELAY_MS 멈춘 뒤.
 * 조건이 밖에서 바뀌면(사이드바로 다시 열기 · 뒤로 가기 등) 입력칸도 맞춘다.
 */
function SearchField({ value, onCommit }: { value: string; onCommit: (query: string) => void }) {
  const [input, setInput] = useState(value);
  const committed = useRef(value);

  useEffect(() => {
    if (value === committed.current) return;
    committed.current = value;
    setInput(value);
  }, [value]);

  useEffect(() => {
    if (input === committed.current) return;
    const timer = setTimeout(() => {
      committed.current = input;
      onCommit(input);
    }, SEARCH_DELAY_MS);
    return () => clearTimeout(timer);
    // onCommit은 매번 새 함수 — 입력이 바뀔 때만 다시 잰다
  }, [input]);

  return (
    <TextField
      size="small"
      placeholder="알림 내용 검색"
      value={input}
      onChange={(e) => setInput(e.target.value)}
      sx={{
        width: { xs: '100%', sm: 240 },
        // 좁은 화면에서는 맨 아래 줄(모두 읽음이 분류 · 안 읽은 것만 옆에 남게)
        order: { xs: 1, sm: 0 },
        // 한 줄의 다른 칸(세그먼트 · 분류 버튼)과 같은 높이
        '& .MuiOutlinedInput-input': { paddingBlock: `calc((${size.controlSm} - ${font.lineHeight.body}) / 2)` },
      }}
      slotProps={{
        htmlInput: { 'aria-label': '알림 내용 검색', maxLength: QUERY_MAX },
        input: {
          startAdornment: (
            <InputAdornment position="start">
              <SearchRoundedIcon sx={{ fontSize: size.iconMd, color: text.tertiary }} />
            </InputAdornment>
          ),
        },
      }}
    />
  );
}

/** 페이지 버튼 읽기 이름 */
function pageItemLabel(kind: string, page: number | null, selected: boolean) {
  if (kind === 'page') return selected ? `${page}페이지, 현재 페이지` : `${page}페이지로 이동`;
  if (kind === 'previous') return '이전 페이지';
  if (kind === 'next') return '다음 페이지';
  if (kind === 'first') return '첫 페이지';
  if (kind === 'last') return '마지막 페이지';
  return '';
}

/**
 * 알림센터 본문(OPS-08, Tetra Portal UI-TP-13과 같은 구조) — 필터 한 줄(우선순위 세그먼트 · 분류 드롭다운 · 안 읽은 것만 · 검색 · 모두 읽음)
 * + 알림 리스트(최신순) + 페이지. 조건 · 페이지 · 목록 · 건수는 props로만 받는다(페이지가 주소 쿼리로 들고 서버에서 받는다).
 * 운영 콘솔은 종류와 무관하게 모든 알림을 보인다. 누르면 읽음 + 관련 화면으로 이동(경로가 없으면 읽음만) — 읽은 항목은 다음 조회 때 목록에서 빠진다.
 */
export default function NotificationCenter({ data, filters, page, loading, onFiltersChange, onPageChange, onRead, onReadAll, readingAll }: Props) {
  const empty = emptyState(filters);

  return (
    <Box>
      {/* ① 필터 한 줄 — 왼쪽 우선순위, 오른쪽 분류 · 안 읽은 것만 · 검색 · 모두 읽음(좁으면 오른쪽 묶음이 다음 줄로) */}
      <Stack direction="row" flexWrap="wrap" useFlexGap justifyContent="space-between" alignItems="center" spacing={1.5} sx={{ mb: 1.5 }}>
        <ToggleButtonGroup
          exclusive
          size="small"
          value={filters.priority}
          onChange={(_, next: PriorityFilter | null) => {
            if (next) onFiltersChange({ priority: next });
          }}
          aria-label="알림 우선순위"
        >
          {PRIORITY_FILTERS.map((p) => (
            <ToggleButton key={p.key} value={p.key} sx={SEGMENT_SX}>
              {p.label}
              <Box
                component="span"
                sx={{ minWidth: 20, px: 0.5, fontWeight: font.weight.bold }}
              >
                {data.counts.priority[p.key]}
              </Box>
            </ToggleButton>
          ))}
        </ToggleButtonGroup>

        <Stack direction="row" flexWrap="wrap" useFlexGap alignItems="center" spacing={1.5} sx={{ width: { xs: '100%', sm: 'auto' } }}>
          <ChipMenu value={filters.chip} items={chipMenuItems(data.counts.chip)} onChange={(chip) => onFiltersChange({ chip })} />
          <FormControlLabel
            label="안 읽은 것만"
            control={
              <Checkbox
                size="small"
                checked={filters.unreadOnly}
                onChange={(e) => onFiltersChange({ unreadOnly: e.target.checked })}
                sx={{ '&.Mui-focusVisible': { boxShadow: shadow.focus } }}
              />
            }
            slotProps={{ typography: { variant: 'body2', sx: { color: text.secondary, whiteSpace: 'nowrap' } } }}
            sx={{ m: 0 }}
          />
          <SearchField value={filters.query} onCommit={(query) => onFiltersChange({ query })} />
          <Button
            variant="outlined"
            size="small"
            startIcon={<DoneAllRoundedIcon />}
            disabled={!canReadAll(data, readingAll)}
            onClick={onReadAll}
            sx={{ height: size.controlSm, whiteSpace: 'nowrap' }}
          >
            모두 읽음
          </Button>
        </Stack>
      </Stack>

      {/* ② 알림 리스트 — 다른 조건 · 페이지를 받는 동안은 지금 목록을 흐리게 */}
      {data.items.length === 0 ? (
        <StateMessage icon={<NotificationsOffOutlinedIcon />} tone="grey" title={empty.title} description={empty.description}>
          {empty.showAll && (
            <Button variant="outlined" size="small" onClick={() => onFiltersChange({ unreadOnly: false })} sx={{ mt: 1 }}>
              모든 알림 보기
            </Button>
          )}
        </StateMessage>
      ) : (
        <Box
          component="ul"
          aria-busy={loading}
          sx={{ listStyle: 'none', m: 0, p: 0, borderTop: `1px solid ${line.strong}`, opacity: loading ? 0.6 : 1, transition: 'opacity .15s' }}
        >
          {data.items.map((n) => (
            <Box component="li" key={n.notificationId} sx={{ borderBottom: `1px solid ${line.base}` }}>
              <NotificationRow n={n} onRead={onRead} />
            </Box>
          ))}
        </Box>
      )}

      {/* ③ 페이지 — 1페이지뿐이면 숨김 */}
      {data.totalPages > 1 && (
        <Stack alignItems="center" sx={{ mt: 3 }}>
          <Pagination
            count={data.totalPages}
            page={Math.min(page, data.totalPages)}
            onChange={(_, next) => onPageChange(next)}
            color="primary"
            shape="rounded"
            aria-label="알림 페이지"
            getItemAriaLabel={pageItemLabel}
          />
        </Stack>
      )}
    </Box>
  );
}
