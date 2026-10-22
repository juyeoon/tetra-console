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
  kind: 'done' | 'failed';
  tone: Tone;
  title: string;
  body: string;
}

/**
 * OPS-07 등록 결과 화면 (POST /tenants의 provisioningStatus).
 * 비밀번호 설정 메일은 등록 때가 아니라 환경 준비가 끝나면(READY) 서버가 보낸다.
 * - FAILED(스키마 생성 · StartExecution 실패): 환경 준비 실패, 메일을 보내지 않음
 * - 그 밖(PROVISIONING · READY): 등록 완료, 환경 준비가 끝나면 메일 발송
 */
export function registerOutcome(provisioningStatus: ProvisioningStatus, tenantName: string, email: string): RegisterOutcome {
  if (provisioningStatus === 'FAILED') {
    return {
      kind: 'failed',
      tone: 'red',
      title: '테넌트 환경 준비에 실패했습니다',
      body: `${tenantName} 계정은 등록되었지만 환경 준비에 실패해 비밀번호 설정 메일을 보내지 않았습니다. 운영자 알림에서 원인을 확인해 주세요.`,
    };
  }
  return {
    kind: 'done',
    tone: 'green',
    title: '테넌트 등록이 완료되었습니다',
    body: `환경 준비가 끝나면 ${email}로 비밀번호 설정 메일이 발송됩니다.`,
  };
}

/** 개요의 비밀번호 설정 여부 */
export const passwordSetMeta = (passwordSet: boolean): ChipMeta =>
  passwordSet ? { label: '설정 완료', tone: 'green' } : { label: '비밀번호 미설정', tone: 'grey' };
