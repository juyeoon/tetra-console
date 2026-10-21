import type { ApplicationStatus } from '@/types';

/** 모니터링 영역의 이벤트 상세 경로 — 승인된 건은 진행 현황, 그 외는 신청 현황 */
export const eventPath = (eventId: number, applicationStatus: ApplicationStatus) =>
  applicationStatus === 'APPROVED' ? `/events/progress/${eventId}` : `/events/applications/${eventId}`;
