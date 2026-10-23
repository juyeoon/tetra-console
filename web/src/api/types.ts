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
  /** 비밀번호를 설정했는지(password IS NOT NULL) */
  passwordSet: boolean;
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

/** POST /tenants (201). 비밀번호 설정 메일은 환경 준비가 끝나면(READY) 서버가 보낸다 — 등록 응답에 메일 결과는 없다 */
export interface RegisterResponse {
  tenantId: string;
  /** 응답 시점의 환경 준비 상태. 스키마 생성 · StartExecution 실패면 FAILED(메일 없음) */
  provisioningStatus: ProvisioningStatus;
}

/** PATCH /tenants/{id} — 정확히 하나만 보낸다 */
export type UpdateRequest = { name: string } | { contactName: string } | { email: string };

// ---------------------------------------------------------------- 이벤트 (EventListService · EventDetailResponse · EventResultResponse)

/** GET /applications — 승인 대기 중 → 신청됨(처리 중) → 나머지(신청시각 내림차순) */
export interface ApplicationListResponse {
  counts: Record<ApplicationStatus, number>;
  items: ApplicationItem[];
}

export interface ApplicationItem {
  eventId: number;
  tenantName: string;
  eventType: EventType;
  name: string;
  appliedAt: string;
  applicationStatus: ApplicationStatus;
  /** 승인 · 반려 처리 일시. 그 밖(신청 실패 포함)은 null */
  processedAt: string | null;
}

/** GET /live-events — 승인된 이벤트. counts는 PENDING · HOSTING · ENDED만 */
export interface LiveEventListResponse {
  counts: Partial<Record<HostingStatus, number>>;
  items: LiveEventItem[];
}

export interface LiveEventItem {
  eventId: number;
  tenantName: string;
  eventType: EventType;
  name: string;
  hostingStartAt: string;
  startAt: string;
  hostingStatus: HostingStatus | null;
  eventStatus: EventStatus | null;
  trafficPlanCode: string;
}

export type StatusType = 'APPLICATION' | 'HOSTING' | 'EVENT';

/**
 * 상태 로그 한 줄 (event_status_log 전체, changed_at 오름차순 · 같은 시각은 기록 순서).
 * detail = 반려 사유(신청 REJECTED) · 실패 원인 등. actor: 운영자(이름) · 테넌트(테넌트명) · 자동 처리(name null)
 */
export interface EventStatusLog {
  changedAt: string;
  statusType: StatusType;
  /** 최초 기록이면 null. 값은 statusType별 상태(신청/호스팅/이벤트) */
  prevStatus: string | null;
  newStatus: string;
  detail: string | null;
  actor: { type: 'OPERATOR' | 'TENANT' | 'SYSTEM'; name: string | null };
}

/** GET /events/{id} (문서 스키마 EventDetail*) */
export interface EventDetailResponse {
  basic: {
    eventId: number;
    name: string;
    eventType: EventType;
    tenantId: string;
    tenantName: string;
    hostingStartAt: string;
    hostingEndAt: string;
    startAt: string;
    endAt: string;
    trafficPlanCode: string;
    stockCount: number;
    appliedAt: string;
    endpointUrl: string;
  };
  /** 승인 전에는 hostingStatus · eventStatus가 null */
  status: {
    applicationStatus: ApplicationStatus;
    hostingStatus: HostingStatus | null;
    eventStatus: EventStatus | null;
    resultAvailable: boolean;
  };
  /** domain = {slug}.{tenant_id}, fullDomain = domain.{플랫폼 도메인}. 승인 전 · 만료면 null */
  resources: {
    templateId: number;
    domain: string | null;
    fullDomain: string | null;
    publicKey: string | null;
  };
  statusLogs: EventStatusLog[];
}

/** POST /events/{id}/approve — 409 APPLICATION_NOT_APPLIED · HOSTING_PERIOD_ENDED · TENANT_NOT_READY */
export interface ApproveResponse {
  eventId: number;
  applicationStatus: ApplicationStatus;
  hostingStatus: HostingStatus | null;
  eventStatus: EventStatus | null;
  /** false면 OPS-03-M1 "개인키 메일 발송 실패" 상태 (승인은 됨) */
  privateKeyMailSent: boolean;
}

/** POST /events/{id}/reject — 400 fields.reason, 409 APPLICATION_NOT_APPLIED */
export interface RejectResponse {
  eventId: number;
  applicationStatus: ApplicationStatus;
}

/** GET /events/{id}/result — state별로 쓰지 않는 값은 null */
export interface EventResultResponse {
  eventId: number;
  /** IN_PROGRESS = 이벤트가 끝나기 전(오픈 전 포함) */
  state: 'IN_PROGRESS' | 'AGGREGATING' | 'END_FAILED' | 'READY';
  eventOpenAt: string | null;
  aggregatedAt: string | null;
  hostingStatus: HostingStatus | null;
  eventStatus: EventStatus | null;
  endAt: string | null;
  failure: { cause: string | null; occurredAt: string | null } | null;
  result: {
    metrics: {
      successCount: number;
      totalStock: number;
      selloutRate: number;
      soldoutCount: number;
      peakConcurrent: number;
      maxCapacity: number;
      capacityUsageRate: number;
      peakAt: string | null;
    };
    composition: { successCount: number; soldoutCount: number; abandonedCount: number };
    perMinute: { minute: string; successCount: number; soldoutCount: number }[];
  } | null;
}

/** GET /events/{id}/result/history-csv — 결과 전이면 409 RESULT_NOT_READY */
export interface CsvDownloadResponse {
  url: string;
  expiresAt: string;
}

// ---------------------------------------------------------------- 대시보드 (DashboardController)

export type NotificationPriority = 'URGENT' | 'WARNING' | 'INFO';

/** GET /dashboard — 최근 알림은 최신 5건 */
export interface DashboardResponse {
  tenants: { active: number; total: number; newThisMonth: number };
  openEvents: { count: number; asOf: string };
  recentNotifications: {
    notificationId: number;
    tenantId: string | null;
    eventId: number | null;
    category: string;
    priority: NotificationPriority | string;
    occurredAt: string;
    content: string;
    read: boolean;
  }[];
}

// ---------------------------------------------------------------- 알림센터 (NotificationController)

/** GET /notifications 항목. 테넌트 · 이벤트와 무관한 알림이면 tenantId · tenantName · eventId가 null */
export interface NotificationItem {
  notificationId: number;
  tenantId: string | null;
  tenantName: string | null;
  eventId: number | null;
  category: string;
  priority: NotificationPriority | string;
  occurredAt: string;
  content: string;
  read: boolean;
}

/** GET /notifications — 전체, 최신순(페이지네이션 없음). 필터 · 검색 · 건수는 화면이 계산 */
export interface NotificationListResponse {
  items: NotificationItem[];
}
