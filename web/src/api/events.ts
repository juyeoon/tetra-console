import { apiRequest } from './client.ts';
import type {
  ApplicationListResponse,
  ApplicationSummaryResponse,
  ApproveResponse,
  CsvDownloadResponse,
  EventDetailResponse,
  EventResultResponse,
  LiveEventListResponse,
  RejectResponse,
} from './types.ts';

const eventPath = (eventId: number) => `/events/${encodeURIComponent(String(eventId))}`;

/** OPS-03 이벤트 신청 현황 */
export const fetchApplications = (signal?: AbortSignal) => apiRequest<ApplicationListResponse>('/applications', { signal });

/** OPS-04 이벤트 진행 현황 (승인된 이벤트) */
export const fetchLiveEvents = (signal?: AbortSignal) => apiRequest<LiveEventListResponse>('/live-events', { signal });

/** OPS-03-1 · 04-1 상세. 404 EVENT_NOT_FOUND, 409 TENANT_NOT_READY */
export const fetchEventDetail = (eventId: number, signal?: AbortSignal) =>
  apiRequest<EventDetailResponse>(eventPath(eventId), { signal });

/** OPS-03-M1 승인 */
export const approveEvent = (eventId: number) => apiRequest<ApproveResponse>(`${eventPath(eventId)}/approve`, { method: 'POST' });

/** OPS-03-M2 반려 */
export const rejectEvent = (eventId: number, reason: string) =>
  apiRequest<RejectResponse>(`${eventPath(eventId)}/reject`, { method: 'POST', body: { reason } });

/** OPS-03-2 · 04-2 신청 내용(신청 요약). 404 EVENT_NOT_FOUND, 409 TENANT_NOT_READY */
export const fetchApplicationSummary = (eventId: number, signal?: AbortSignal) =>
  apiRequest<ApplicationSummaryResponse>(`${eventPath(eventId)}/application-summary`, { signal });

/** OPS-04-3 진행 결과 */
export const fetchEventResult = (eventId: number, signal?: AbortSignal) =>
  apiRequest<EventResultResponse>(`${eventPath(eventId)}/result`, { signal });

/** 발급 이력 CSV 내려받기 주소(presigned, 만료 있음). 결과 전이면 409 RESULT_NOT_READY */
export const fetchHistoryCsvUrl = (eventId: number) =>
  apiRequest<CsvDownloadResponse>(`${eventPath(eventId)}/result/history-csv`);
