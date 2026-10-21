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
