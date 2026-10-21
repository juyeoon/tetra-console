import { apiRequest } from './client.ts';
import type { DashboardResponse } from './types.ts';

/** OPS-02 대시보드 — 테넌트 수 · 진행 중 이벤트 수 · 최근 알림 5건 */
export const fetchDashboard = (signal?: AbortSignal) => apiRequest<DashboardResponse>('/dashboard', { signal });
