import { apiRequest } from './client.ts';
import type { NotificationListResponse } from './types.ts';

/** OPS-08 알림 목록 — 전체, 최신순 */
export const fetchNotifications = (signal?: AbortSignal) => apiRequest<NotificationListResponse>('/notifications', { signal });

/** 알림 읽음(204). 이미 읽었어도 204, 없는 id면 404 NOTIFICATION_NOT_FOUND */
export const markNotificationRead = (notificationId: number) =>
  apiRequest<void>(`/notifications/${encodeURIComponent(String(notificationId))}/read`, { method: 'PATCH' });

/** 모두 읽음(204) — 안읽음만 바뀐다 */
export const markAllNotificationsRead = () => apiRequest<void>('/notifications/read-all', { method: 'PATCH' });
