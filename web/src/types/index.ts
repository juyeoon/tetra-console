/**
 * 도메인 타입 — 화면설계서 v0.5 / ERD 기준
 */

/** 신청상태 (event.application_status) */
export type ApplicationStatus = 'APPLYING' | 'APPLIED' | 'APPLY_FAILED' | 'APPROVED' | 'REJECTED';
/** 호스팅상태 — 인프라 배포·호스팅 단계 */
export type HostingStatus = 'PENDING' | 'DEPLOYED' | 'DEPLOY_FAILED' | 'HOSTING' | 'HOSTING_FAILED' | 'ENDED' | 'END_FAILED';
/** 이벤트상태 — 이벤트 자체의 진행 단계 */
export type EventStatus = 'SCHEDULED' | 'OPEN' | 'OPEN_FAILED' | 'EXPIRED';
export type AccountStatus = 'ACTIVE' | 'INACTIVE';
/** 트래픽 플랜 (traffic_plan.plan_code). XL은 화면 노출 안 함(is_visible 0)이지만 값으로는 올 수 있다 */
export type Plan = 'S' | 'M' | 'L' | 'XL';
/** 1차 범위는 쿠폰만 해당 */
export type EventType = 'COUPON' | 'TIMESALE' | 'LIVE_COMMERCE';

export interface Period {
  start: string;
  end: string;
}

/** 신청 상태 로그 — 실제 상태 전이 기록만 */
export interface ApplicationLog {
  at: string;
  title: string;
  status: ApplicationStatus;
  description: string;
}

/** 이벤트 진행 결과 (집계) */
export type EventResult =
  | { state: 'NONE' }
  | { state: 'ONGOING' }
  | { state: 'AGGREGATING' }
  | { state: 'FAILED'; cause: string; occurredAt: string }
  | {
      state: 'DONE';
      aggregatedAt: string;
      issued: number;
      stock: number;
      soldOut: number;
      dropped: number;
      peakConcurrent: number;
      peakAt: string;
      planCapacity: number;
      perMinute: { minute: string; issued: number; soldOut: number }[];
    };

export interface TetraEvent {
  /** 정수 ID (예: 1043) */
  id: number;
  tenantId: string;
  type: EventType;
  name: string;
  appliedAt: string;
  applicationStatus: ApplicationStatus;
  /** 신청 실패·승인·반려 처리 일시 */
  processedAt?: string;
  rejectReason?: string;
  /** 승인 이후에만 존재 */
  hostingStatus?: HostingStatus;
  eventStatus?: EventStatus;
  plan: Plan;
  hostingPeriod: Period;
  eventPeriod: Period;
  /** 발급 가능 수량 (stock_count) */
  quantity: number;
  /** 테넌트사 사이트에서 대기방으로 넘어오는 출발 URL */
  endpointUrl: string;
  templateId: number;
  /** 승인 시 발급되는 slug — 할당 서브 도메인은 {slug}.{tenantId} */
  slug?: string;
  /** RS256 공개키 (승인 시 발급, 만료 시 회수) */
  publicKey?: string;
  logs: ApplicationLog[];
  result: EventResult;
}

/** 계정 상태 전환 사유 */
export type AccountLogReason = 'APPLIED' | 'REJECTED' | 'ENDED';

/** 계정 상태 로그 (tenant_status_log) — 상태가 바뀐 시점마다 한 줄 */
export interface AccountLog {
  at: string;
  /** 없으면 최초 기록(테넌트 등록) */
  from?: AccountStatus;
  to: AccountStatus;
  reason?: AccountLogReason;
  eventId?: number;
  eventName?: string;
}

export interface Tenant {
  /** 소문자·숫자 12자 (예: k7f2m9qx4b1t) */
  id: string;
  name: string;
  /** 담당자 이름 (tenant.contact_name) */
  contactName: string;
  email: string;
  rootDomain: string;
  status: AccountStatus;
  joinedAt: string;
  /** 최신순 */
  accountLogs: AccountLog[];
}
