import type { ProvisioningStatus, StatusLogBadge } from '../../api/types.ts';

/**
 * 테넌트 화면 표시 규칙 (화면과 분리, node --test 대상).
 * Tone은 theme의 tones 키와 같다.
 */
type Tone = 'blue' | 'amber' | 'green' | 'red' | 'grey';

interface ChipMeta {
  label: string;
  tone: Tone;
}

/** 환경 준비 칩. READY(완료)는 표시하지 않는다 */
export const provisioningStatusMeta: Record<ProvisioningStatus, ChipMeta | null> = {
  PROVISIONING: { label: '환경 준비 중', tone: 'grey' },
  READY: null,
  FAILED: { label: '환경 준비 실패', tone: 'red' },
};

/** 계정 상태 로그 사유 배지 — [신청 완료](파랑) / [반려](빨강) / [종료](회색). REGISTERED는 배지 없이 '테넌트 등록' */
export const statusLogBadgeMeta: Record<StatusLogBadge, ChipMeta | null> = {
  REGISTERED: null,
  APPLIED: { label: '신청 완료', tone: 'blue' },
  REJECTED: { label: '반려', tone: 'red' },
  ENDED: { label: '종료', tone: 'grey' },
};

export interface RegisterOutcome {
  /** 화면 분기용 */
  kind: 'done' | 'mailFailed' | 'startFailed' | 'schemaFailed';
  tone: Tone;
  title: string;
  /** 성공 화면은 없음 */
  body: string | null;
}

/**
 * OPS-07 등록 결과 화면 (POST /tenants의 provisioningStatus × mailSent).
 * - FAILED + 메일 안 보냄: 스키마 생성 실패 → 메일을 보내지 않았음
 * - FAILED + 메일 보냄: StartExecution 실패 → 환경 준비 수동 재실행
 * - 그 밖 + 메일 실패: 환경 준비는 진행 중, 메일만 실패
 */
export function registerOutcome(provisioningStatus: ProvisioningStatus, mailSent: boolean, tenantName: string): RegisterOutcome {
  if (provisioningStatus === 'FAILED' && !mailSent) {
    return {
      kind: 'schemaFailed',
      tone: 'red',
      title: '테넌트 환경 준비에 실패했습니다',
      body: `${tenantName} 계정은 등록되었지만 환경 준비에 실패해 비밀번호 설정 메일을 보내지 않았습니다. 운영자 알림에서 원인을 확인해 주세요.`,
    };
  }
  if (provisioningStatus === 'FAILED') {
    return {
      kind: 'startFailed',
      tone: 'amber',
      title: '환경 준비를 시작하지 못했습니다',
      body: '비밀번호 설정 메일은 발송되었습니다. 환경 준비(WF5)를 수동으로 다시 실행해야 합니다.',
    };
  }
  if (!mailSent) {
    return {
      kind: 'mailFailed',
      tone: 'amber',
      title: '비밀번호 설정 메일 발송에 실패했습니다',
      body: '계정은 등록되었고 환경 준비는 진행 중입니다. 메일을 받지 못한 경우 테넌트 상세에서 확인해 주세요.',
    };
  }
  return { kind: 'done', tone: 'green', title: '테넌트 등록이 완료되었습니다', body: null };
}
