import type { Tone } from '@/theme/theme';
import type { AccountLogReason, AccountStatus, ApplicationStatus, EventStatus, EventType, HostingStatus } from '@/types';

interface StatusMeta {
  label: string;
  tone: Tone;
}

/** 신청상태 — 승인 대기 중(파랑) / 신청됨(처리 중)(주황) / 신청 실패(회색) / 승인됨(초록) / 반려됨(빨강) */
export const applicationStatusMeta: Record<ApplicationStatus, StatusMeta> = {
  APPLIED: { label: '승인 대기 중', tone: 'blue' },
  APPLYING: { label: '신청됨(처리 중)', tone: 'amber' },
  APPLY_FAILED: { label: '신청 실패', tone: 'grey' },
  APPROVED: { label: '승인됨', tone: 'green' },
  REJECTED: { label: '반려됨', tone: 'red' },
};

export const hostingStatusMeta: Record<HostingStatus, StatusMeta> = {
  PENDING: { label: '배포 대기', tone: 'amber' },
  DEPLOYED: { label: '배포 완료', tone: 'blue' },
  DEPLOY_FAILED: { label: '배포 실패', tone: 'red' },
  HOSTING: { label: '호스팅 중', tone: 'green' },
  HOSTING_FAILED: { label: '호스팅 준비 실패', tone: 'red' },
  ENDED: { label: '호스팅 종료', tone: 'grey' },
  END_FAILED: { label: '종료 실패', tone: 'red' },
};

export const eventStatusMeta: Record<EventStatus, StatusMeta> = {
  SCHEDULED: { label: '오픈 예정', tone: 'blue' },
  OPEN: { label: '진행 중', tone: 'green' },
  OPEN_FAILED: { label: '이벤트 준비 실패', tone: 'red' },
  EXPIRED: { label: '만료', tone: 'grey' },
};

export const accountStatusMeta: Record<AccountStatus, StatusMeta> = {
  ACTIVE: { label: '활성', tone: 'green' },
  INACTIVE: { label: '비활성', tone: 'grey' },
};

/** 계정 상태 로그 사유 배지 — [신청 완료](파랑) / [반려](빨강) / [종료](회색) */
export const accountLogReasonMeta: Record<AccountLogReason, StatusMeta> = {
  APPLIED: { label: '신청 완료', tone: 'blue' },
  REJECTED: { label: '반려', tone: 'red' },
  ENDED: { label: '종료', tone: 'grey' },
};

export const eventTypeLabel: Record<EventType, string> = {
  COUPON: '쿠폰',
  TIMESALE: '타임세일',
  LIVE_COMMERCE: '라이브커머스',
};

/** 신청 목록 기본 정렬 — 승인 대기 중 → 신청됨(처리 중) → 나머지(신청시각 최신순) */
export const applicationSortRank: Record<ApplicationStatus, number> = {
  APPLIED: 0,
  APPLYING: 1,
  APPLY_FAILED: 2,
  APPROVED: 2,
  REJECTED: 2,
};

/** 상태별 건수를 meta 정의 순서대로 집계 */
export function countBy<K extends string>(items: K[], meta: Record<K, StatusMeta>) {
  return (Object.keys(meta) as K[]).map((key) => ({
    key,
    label: meta[key].label,
    tone: meta[key].tone,
    count: items.filter((v) => v === key).length,
  }));
}
