import { apiRequest } from './client.ts';
import type { NotificationPage } from './types.ts';

/** OPS-08 알림 목록 — 조건 · 페이지는 notificationView.listQuery가 만든다 */
export const fetchNotifications = (query: URLSearchParams, signal?: AbortSignal) =>
  apiRequest<NotificationPage>(`/notifications?${query.toString()}`, { signal });

/** 알림 읽음(204). 이미 읽었어도 204, 없는 id면 404 NOTIFICATION_NOT_FOUND */
export const markNotificationRead = (notificationId: number) =>
  apiRequest<void>(`/notifications/${encodeURIComponent(String(notificationId))}/read`, { method: 'PATCH' });

/** 모두 읽음(204) — 안읽음만 바뀐다 */
export const markAllNotificationsRead = () => apiRequest<void>('/notifications/read-all', { method: 'PATCH' });
