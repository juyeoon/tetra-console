import type { DashboardResponse, NotificationPriority } from '../../api/types.ts';
import { formatRelative } from '../../utils/datetime.ts';
import { priorityOf } from '../../utils/status.ts';

/** 대시보드 최근 알림 한 줄 (화면과 분리, node --test 대상) */
export interface AlertView {
  id: number;
  title: string;
  ago: string;
  /** 아이콘 · 색 — 알림센터와 같은 우선순위(utils/status priorityMeta). 모르는 값은 INFO */
  priority: NotificationPriority;
}

export function alertView(n: DashboardResponse['recentNotifications'][number], now: Date): AlertView {
  return { id: n.notificationId, title: n.content, ago: formatRelative(n.occurredAt, now), priority: priorityOf(n.priority) };
}
