import { ApiError, errorMessage } from '../../api/client.ts';

/**
 * 승인 · 반려 실패 → 대화상자 표시 (화면과 분리, node --test 대상).
 * - 409 APPLICATION_NOT_APPLIED(이미 처리된 신청): 서버 문구 + 상세를 다시 받아 현재 상태를 보여 준다
 * - 409 HOSTING_PERIOD_ENDED · TENANT_NOT_READY 등: 서버 문구만
 * - 400 fields.reason(반려 사유): 사유 입력란 오류
 */
export interface DecisionFailure {
  message: string;
  reloadDetail: boolean;
  reasonError?: string;
}

export function decisionFailure(error: unknown): DecisionFailure {
  const reasonError = error instanceof ApiError ? error.fields.reason : undefined;
  return {
    message: errorMessage(error),
    reloadDetail: error instanceof ApiError && error.code === 'APPLICATION_NOT_APPLIED',
    ...(reasonError ? { reasonError } : {}),
  };
}
