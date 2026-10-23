import type { NotificationItem, NotificationPriority } from '../../api/types.ts';
import { priorityOf } from '../../utils/status.ts';

/** 알림센터(OPS-08) 필터 · 검색 · 건수 · 읽음 처리 (화면과 분리, node --test 대상). 서버는 전체를 최신순으로 준다 */

export type NotificationFilter = 'ALL' | NotificationPriority | 'UNREAD';

/** 탭 5종 — Tetra Portal 알림센터(UI-TP-13)와 같은 순서 */
export const NOTIFICATION_FILTERS: { key: NotificationFilter; label: string }[] = [
  { key: 'ALL', label: '전체보기' },
  { key: 'URGENT', label: '긴급' },
  { key: 'WARNING', label: '경고' },
  { key: 'INFO', label: '정보' },
  { key: 'UNREAD', label: '안읽음' },
];

export function matchesFilter(n: NotificationItem, filter: NotificationFilter): boolean {
  if (filter === 'ALL') return true;
  if (filter === 'UNREAD') return !n.read;
  return priorityOf(n.priority) === filter;
}

/** 탭 건수 — 검색어와 무관하게 전체 목록 기준 */
export function countByFilter(items: NotificationItem[]): Record<NotificationFilter, number> {
  return Object.fromEntries(
    NOTIFICATION_FILTERS.map((f) => [f.key, items.filter((n) => matchesFilter(n, f.key)).length]),
  ) as Record<NotificationFilter, number>;
}

/** 보이는 목록 — 탭 + 내용 검색(앞뒤 공백 무시, 대소문자 무시, 입력 즉시). 최신순(같은 시각은 나중 알림 먼저) */
export function visibleNotifications(items: NotificationItem[], filter: NotificationFilter, query: string): NotificationItem[] {
  const q = query.trim().toLowerCase();
  return items
    .filter((n) => matchesFilter(n, filter) && (q === '' || n.content.toLowerCase().includes(q)))
    .sort((a, b) => b.occurredAt.localeCompare(a.occurredAt) || b.notificationId - a.notificationId);
}

/** 한 건의 읽음 여부를 바꾼 새 목록 (클릭 시 먼저 바꾸고, 실패하면 되돌린다) */
export function withRead(items: NotificationItem[], notificationId: number, read: boolean): NotificationItem[] {
  return items.map((n) => (n.notificationId === notificationId ? { ...n, read } : n));
}

/** 모두 읽음 */
export function withAllRead(items: NotificationItem[]): NotificationItem[] {
  return items.map((n) => (n.read ? n : { ...n, read: true }));
}
