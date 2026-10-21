/**
 * Console API 응답 타입 — 서버 DTO(api/src/main/java/com/tetra/ops/**) 이름 그대로 손으로 옮긴다.
 * 일시는 ISO-8601(+09:00) 문자열. 화면 표시는 utils/datetime.ts로 바꾼다.
 * /v3/api-docs(로컬 API_DOCS_ENABLED=true)로 교차 확인한다.
 */

export type AccountStatus = 'ACTIVE' | 'INACTIVE';
export type ProvisioningStatus = 'PROVISIONING' | 'READY' | 'FAILED';
export type ApplicationStatus = 'APPLYING' | 'APPLIED' | 'APPLY_FAILED' | 'APPROVED' | 'REJECTED';
export type HostingStatus = 'PENDING' | 'DEPLOYED' | 'DEPLOY_FAILED' | 'HOSTING' | 'HOSTING_FAILED' | 'ENDED' | 'END_FAILED';
export type EventStatus = 'SCHEDULED' | 'OPEN' | 'OPEN_FAILED' | 'EXPIRED';
export type EventType = 'COUPON' | 'TIMESALE' | 'LIVE_COMMERCE';

// ---------------------------------------------------------------- 테넌트 (TenantQueryService · TenantCommandService)

/** GET /tenants — 가입일시 내림차순 */
export interface TenantListResponse {
  counts: Record<AccountStatus, number>;
  items: TenantItem[];
}

export interface TenantItem {
  tenantId: string;
  name: string;
  email: string;
  accountStatus: AccountStatus;
  provisioningStatus: ProvisioningStatus;
  createdAt: string;
}

/** GET /tenants/{id}의 basic, PATCH /tenants/{id} 응답 (문서 스키마 TenantBasic) */
export interface TenantBasic {
  tenantId: string;
  name: string;
  contactName: string;
  email: string;
  accountStatus: AccountStatus;
  createdAt: string;
  provisioningStatus: ProvisioningStatus;
}

/** 계정 상태 로그 사유 배지. REGISTERED = 최초 기록(테넌트 등록) */
export type StatusLogBadge = 'REGISTERED' | 'APPLIED' | 'REJECTED' | 'ENDED';

export interface StatusLogItem {
  changedAt: string;
  /** 최초 기록(REGISTERED)은 null */
  prevStatus: AccountStatus | null;
  newStatus: AccountStatus;
  badge: StatusLogBadge;
  reason: string | null;
  relatedEvent: { eventId: number; name: string; applicationStatus: ApplicationStatus } | null;
}

/** GET /tenants/{id} */
export interface TenantOverviewResponse {
  basic: TenantBasic;
  buckets: { content: string; auditLog: string };
  /** 최신순 */
  statusLogs: StatusLogItem[];
}

/** GET /tenants/{id}/events — 신청시각 내림차순. 승인 전에는 hostingStatus · eventStatus가 null */
export interface TenantEventItem {
  eventId: number;
  name: string;
  eventType: EventType;
  applicationStatus: ApplicationStatus;
  hostingStatus: HostingStatus | null;
  eventStatus: EventStatus | null;
  trafficPlanCode: string;
  appliedAt: string;
}

/** GET /tenants/{id}/resources — 승인된 이벤트만. 만료(EXPIRED)면 subdomain · publicKey가 null */
export interface TenantResourceItem {
  eventId: number;
  name: string;
  eventStatus: EventStatus | null;
  subdomain: string | null;
  publicKey: string | null;
}

export interface RegisterRequest {
  name: string;
  email: string;
  rootDomain: string;
  contactName: string;
}

/** POST /tenants (201) */
export interface RegisterResponse {
  tenantId: string;
  /** 비밀번호 설정 메일 발송 요청 성공 여부. 스키마 생성 실패면 보내지 않으므로 false */
  mailSent: boolean;
  /** 응답 시점의 환경 준비 상태. 실패면 FAILED */
  provisioningStatus: ProvisioningStatus;
}

/** PATCH /tenants/{id} — 정확히 하나만 보낸다 */
export type UpdateRequest = { name: string } | { contactName: string } | { email: string };
