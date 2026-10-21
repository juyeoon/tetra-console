import type { ApplicationStatus, TetraEvent } from '@/types';

/** 이벤트가 만료되면 서브 도메인·인증키는 회수된다 */
const revoked = (e: TetraEvent) => e.eventStatus === 'EXPIRED';

/** 할당 서브 도메인 — {slug}.{tenant_id} (승인 시 발급, 만료 시 회수) */
export const subdomainOf = (e: TetraEvent) => (e.slug && !revoked(e) ? `${e.slug}.${e.tenantId}` : undefined);

/** 인증키(RS256 공개키) — 승인 시 발급, 만료 시 회수 */
export const publicKeyOf = (e: TetraEvent) => (e.publicKey && !revoked(e) ? e.publicKey : undefined);

/** 모니터링 영역의 이벤트 상세 경로 — 승인된 건은 진행 현황, 그 외는 신청 현황 */
export const eventPath = (eventId: number, applicationStatus: ApplicationStatus) =>
  applicationStatus === 'APPROVED' ? `/events/progress/${eventId}` : `/events/applications/${eventId}`;

export const eventDetailPath = (e: TetraEvent) => eventPath(e.id, e.applicationStatus);

/** '2026.04.03 10:00' → '2026.04.03 10:00:00' */
export const withSeconds = (dt: string) => (dt.length === 16 ? `${dt}:00` : dt);
