import { apiRequest } from './client.ts';
import type { MonitoringOverviewResponse } from './types.ts';

/** OPS-02 홈 모니터링 카드 3개(클러스터 · 실시간 트래픽 · 테넌트 별 처리 현황) — Prometheus, 서버 15초 캐시 */
export const fetchMonitoringOverview = (signal?: AbortSignal) => apiRequest<MonitoringOverviewResponse>('/monitoring/overview', { signal });
