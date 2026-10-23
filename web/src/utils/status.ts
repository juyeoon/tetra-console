import type { Tone } from '@/theme/theme';
import type { NotificationPriority } from '@/api/types';
import type { AccountStatus, ApplicationStatus, EventStatus, EventType, HostingStatus } from '@/types';

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

/** 알림 우선순위 — 긴급(빨강) / 경고(주황) / 정보(파랑). 알림센터 태그와 대시보드 최근 알림 아이콘이 같이 쓴다 */
export const priorityMeta: Record<NotificationPriority, StatusMeta & { en: string }> = {
  URGENT: { label: '긴급', tone: 'red', en: 'Urgent' },
  WARNING: { label: '경고', tone: 'amber', en: 'Warning' },
  INFO: { label: '정보', tone: 'blue', en: 'Info' },
};

/** 서버 priority 문자열 → 우선순위. 모르는 값은 정보로 본다 */
export function priorityOf(priority: string): NotificationPriority {
  return priority === 'URGENT' || priority === 'WARNING' ? priority : 'INFO';
}

export const eventTypeLabel: Record<EventType, string> = {
  COUPON: '쿠폰',
  TIMESALE: '타임세일',
  LIVE_COMMERCE: '라이브커머스',
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
