import type { DashboardResponse } from '../../api/types.ts';
import { formatRelative } from '../../utils/datetime.ts';

/** 대시보드 최근 알림 한 줄 (화면과 분리, node --test 대상) */
export interface AlertView {
  id: number;
  title: string;
  ago: string;
  /** 아이콘 · 색 — URGENT 빨강, WARNING 주황, 그 밖 파랑 */
  level: 'urgent' | 'warning' | 'info';
}

export function alertView(n: DashboardResponse['recentNotifications'][number], now: Date): AlertView {
  const level = n.priority === 'URGENT' ? 'urgent' : n.priority === 'WARNING' ? 'warning' : 'info';
  return { id: n.notificationId, title: n.content, ago: formatRelative(n.occurredAt, now), level };
}
