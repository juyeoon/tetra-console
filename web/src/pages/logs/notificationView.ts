import type {
  AccountStatus,
  ApplicationStatus,
  EventStatus,
  HostingStatus,
  NotificationChip,
  NotificationItem,
  NotificationPriority,
  NotificationState,
  ProvisioningStatus,
} from '../../api/types.ts';
import type { Tone } from '../../theme/tokens.ts';
import { formatNumber } from '../../utils/format.ts';
import { accountStatusMeta, applicationStatusMeta, eventStatusMeta, hostingStatusMeta, priorityOf } from '../../utils/status.ts';
import { passwordSetMeta, provisioningStatusMeta } from '../tenants/tenantView.ts';

/**
 * 알림센터(OPS-08) 필터 · 검색 · 건수 · 표시 · 읽음 처리 (화면과 분리, node --test 대상).
 * 서버는 전체를 최신순으로 주고, 항목마다 칩 · 이동 경로 · 현재 상태를 계산해 준다
 */

export type NotificationFilter = 'ALL' | NotificationPriority | 'UNREAD';

/** 탭 5종 — Tetra Portal 알림센터(UI-TP-13)와 같은 순서 */
export const NOTIFICATION_FILTERS: { key: NotificationFilter; label: string }[] = [
  { key: 'ALL', label: '전체보기' },
  { key: 'URGENT', label: '긴급' },
  { key: 'WARNING', label: '주의' },
  { key: 'INFO', label: '정보' },
  { key: 'UNREAD', label: '안읽음' },
];

export type ChipFilter = 'ALL' | NotificationChip;

export const CHIP_LABEL: Record<NotificationChip, string> = {
  APPLICATION: '신청',
  EVENT: '이벤트',
  TENANT: '테넌트',
  REPORT: '리포트',
  SYSTEM: '시스템',
};

/** 칩 필터(탭 아래 둘째 줄) — 전체 + 5종. 칩이 없는(모르는 종류) 알림은 '전체'에서만 보인다 */
export const NOTIFICATION_CHIPS: { key: ChipFilter; label: string }[] = [
  { key: 'ALL', label: '전체' },
  ...(Object.keys(CHIP_LABEL) as NotificationChip[]).map((key) => ({ key, label: CHIP_LABEL[key] })),
];

export function matchesFilter(n: NotificationItem, filter: NotificationFilter): boolean {
  if (filter === 'ALL') return true;
  if (filter === 'UNREAD') return !n.read;
  return priorityOf(n.priority) === filter;
}

export function matchesChip(n: NotificationItem, chip: ChipFilter): boolean {
  return chip === 'ALL' || n.chip === chip;
}

/** 탭 건수 — 검색어 · 칩과 무관하게 전체 목록 기준 */
export function countByFilter(items: NotificationItem[]): Record<NotificationFilter, number> {
  return Object.fromEntries(
    NOTIFICATION_FILTERS.map((f) => [f.key, items.filter((n) => matchesFilter(n, f.key)).length]),
  ) as Record<NotificationFilter, number>;
}

/** 칩 건수 — 지금 탭 안에서(탭과 AND), 검색어와 무관 */
export function countByChip(items: NotificationItem[], filter: NotificationFilter): Record<ChipFilter, number> {
  const inTab = items.filter((n) => matchesFilter(n, filter));
  return Object.fromEntries(
    NOTIFICATION_CHIPS.map((c) => [c.key, inTab.filter((n) => matchesChip(n, c.key)).length]),
  ) as Record<ChipFilter, number>;
}

/** 보이는 목록 — 탭 AND 칩 AND 내용 검색(앞뒤 공백 무시, 대소문자 무시, 입력 즉시). 최신순(같은 시각은 나중 알림 먼저) */
export function visibleNotifications(
  items: NotificationItem[],
  filter: NotificationFilter,
  chip: ChipFilter,
  query: string,
): NotificationItem[] {
  const q = query.trim().toLowerCase();
  return items
    .filter((n) => matchesFilter(n, filter) && matchesChip(n, chip) && (q === '' || n.content.toLowerCase().includes(q)))
    .sort((a, b) => b.occurredAt.localeCompare(a.occurredAt) || b.notificationId - a.notificationId);
}

/**
 * 문구를 첫 문장(첫 줄)과 나머지(둘째 줄, 2줄 말줄임)로 — 문장 끝(. ! ?) 뒤 공백에서 한 번 나눈다.
 * 예: '[A사] [1043:봄 쿠폰] 이벤트 배포에 실패했습니다. 확인이 필요합니다. 원인: …' → 첫 문장 / '확인이 필요합니다. 원인: …'
 */
export function splitContent(content: string): { head: string; rest: string } {
  const end = /[.!?]\s+/.exec(content);
  if (!end) return { head: content.trim(), rest: '' };
  return { head: content.slice(0, end.index + 1), rest: content.slice(end.index + end[0].length).trim() };
}

/** 환경 준비 완료 — 테넌트 화면 칩(provisioningStatusMeta)은 READY를 표시하지 않아 알림센터에만 둔다 */
const PROVISIONING_READY_LABEL = '환경 준비 완료';

/** 현재 상태 라벨 — 화면의 기존 상태 라벨을 그대로 쓴다. 모르는 값은 DB 값 그대로 */
export function stateLabel(state: NotificationState): string {
  const v = state.value;
  switch (state.kind) {
    case 'APPLICATION_STATUS':
      return applicationStatusMeta[v as ApplicationStatus]?.label ?? v;
    case 'HOSTING_STATUS':
      return hostingStatusMeta[v as HostingStatus]?.label ?? v;
    case 'EVENT_STATUS':
      return eventStatusMeta[v as EventStatus]?.label ?? v;
    case 'ACCOUNT_STATUS':
      return accountStatusMeta[v as AccountStatus]?.label ?? v;
    case 'PROVISIONING_STATUS':
      return v === 'READY' ? PROVISIONING_READY_LABEL : (provisioningStatusMeta[v as ProvisioningStatus]?.label ?? v);
    case 'PASSWORD_SETUP':
      return v === 'SET' || v === 'NOT_SET' ? passwordSetMeta(v === 'SET').label : v;
    case 'DLQ_MESSAGES':
      return /^\d+$/.test(v) ? `DLQ 메시지 ${formatNumber(Number(v))}건` : v;
    default:
      return v;
  }
}

/** '현재: {라벨}' — alert면 빨강(tone), 아니면 기본 글자색(null). 상태가 없으면 표시하지 않음 */
export function stateView(state: NotificationState | null): { text: string; tone: Tone | null } | null {
  if (!state) return null;
  return { text: `현재: ${stateLabel(state)}`, tone: state.alert ? 'red' : null };
}

/** 이동 경로 — 같은 앱 안의 경로('/'로 시작, '//'는 아님)만 링크로. 없으면 null(누르면 읽음만) */
export function itemHref(n: NotificationItem): string | null {
  const link = n.link;
  return link && link.startsWith('/') && !link.startsWith('//') ? link : null;
}

/**
 * 항목 클릭(왼쪽) · 가운데 클릭(새 탭): 안 읽은 것만 읽음 요청. 요청을 기다리지 않고 기본 동작(링크 이동 · 새 탭)도 막지 않는다 —
 * 읽음 요청이 실패해도 이동은 그대로(되돌리기 · 토스트는 readOptimistically). 오른쪽 클릭(메뉴)은 읽음이 아니다
 */
export function pressItem(n: NotificationItem, event: { button: number }, onRead: (notificationId: number) => void): void {
  if (!n.read && (event.button === 0 || event.button === 1)) onRead(n.notificationId);
}

/** 한 건의 읽음 여부를 바꾼 새 목록 (클릭 시 먼저 바꾸고, 실패하면 되돌린다) */
export function withRead(items: NotificationItem[], notificationId: number, read: boolean): NotificationItem[] {
  return items.map((n) => (n.notificationId === notificationId ? { ...n, read } : n));
}

/** 읽음: 화면을 먼저 바꾸고 요청, 실패하면 되돌린 뒤 onError(토스트) */
export async function readOptimistically(
  notificationId: number,
  update: (change: (items: NotificationItem[]) => NotificationItem[]) => void,
  request: (notificationId: number) => Promise<void>,
  onError: (error: unknown) => void,
): Promise<void> {
  update((items) => withRead(items, notificationId, true));
  try {
    await request(notificationId);
  } catch (e) {
    update((items) => withRead(items, notificationId, false));
    onError(e);
  }
}

/** 모두 읽음 */
export function withAllRead(items: NotificationItem[]): NotificationItem[] {
  return items.map((n) => (n.read ? n : { ...n, read: true }));
}
