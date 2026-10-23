import type { ApplicationStatus } from '@/types';

/** 모니터링 영역의 이벤트 상세 경로 — 승인된 건은 진행 현황, 그 외는 신청 현황 */
export const eventPath = (eventId: number, applicationStatus: ApplicationStatus) =>
  applicationStatus === 'APPROVED' ? `/events/progress/${eventId}` : `/events/applications/${eventId}`;

/** 10000 → '1만', 15000 → '1.5만', 5000 → '5,000' (Tetra Portal utils/event.ts와 같은 규칙) */
export function formatCapacity(n: number) {
  if (n < 10000) return n.toLocaleString('ko-KR');
  const man = n / 10000;
  return `${Number.isInteger(man) ? man : man.toFixed(1)}만`;
}

/** 'M 플랜 (3만 명)' — 수용 인원을 모르면 코드만 */
export const planLabel = (code: string, maxCapacity?: number | null) =>
  maxCapacity != null ? `${code} 플랜 (${formatCapacity(maxCapacity)} 명)` : `${code} 플랜`;
