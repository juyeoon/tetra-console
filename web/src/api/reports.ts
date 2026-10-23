import { apiRequest } from './client.ts';
import type { ReportDownloadResponse, ReportListResponse } from './types.ts';

const reportPath = (reportId: number) => `/reports/${encodeURIComponent(String(reportId))}`;

/** OPS-09 리포트 목록 — 최근 생성순 */
export const fetchReports = (signal?: AbortSignal) => apiRequest<ReportListResponse>('/reports', { signal });

/** 다운로드 주소(presigned, 만료 있음). 없는 id면 404 REPORT_NOT_FOUND */
export const fetchReportDownloadUrl = (reportId: number) => apiRequest<ReportDownloadResponse>(`${reportPath(reportId)}/download`);

/** 로그인한 운영자 이메일로 리포트 안내 메일(204). 메일 실패면 503 MAIL_SEND_FAILED */
export const sendReportEmail = (reportId: number) => apiRequest<void>(`${reportPath(reportId)}/email`, { method: 'POST' });
