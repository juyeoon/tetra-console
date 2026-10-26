import { apiRequest } from './client.ts';
import type { MonitoringOverviewResponse, TrafficEventResponse, TrafficEventsResponse, TrafficPlatformResponse, TrafficRangeKey } from './types.ts';

/** OPS-02 홈 모니터링 카드 3개(클러스터 · 실시간 트래픽 · 테넌트 별 처리 현황) — Prometheus, 서버 15초 캐시 */
export const fetchMonitoringOverview = (signal?: AbortSignal) => apiRequest<MonitoringOverviewResponse>('/monitoring/overview', { signal });

/** OPS-05 상태 요약 · 노드 · 플랫폼 · 다가오는 이벤트 · 최근 자동화 실패 — Prometheus 15초 · AWS 60초 캐시 */
export const fetchTrafficPlatform = (signal?: AbortSignal) => apiRequest<TrafficPlatformResponse>('/monitoring/platform', { signal });

/** OPS-05 이벤트 드롭다운 목록 + 기본 선택 */
export const fetchTrafficEvents = (signal?: AbortSignal) => apiRequest<TrafficEventsResponse>('/monitoring/events', { signal });

/** OPS-05 이벤트 진행 단계 · KPI · 그래프. range를 비우면 상태별 기본 범위 */
export const fetchTrafficEvent = (eventId: number, range: TrafficRangeKey | null, signal?: AbortSignal) =>
  apiRequest<TrafficEventResponse>(`/monitoring/events/${eventId}${range ? `?range=${encodeURIComponent(range)}` : ''}`, { signal });
