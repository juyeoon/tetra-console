import { ApiError, errorMessage } from '../api/client.ts';

/**
 * API 오류 → 폼 표시. node --test 대상.
 * - 400 INVALID_INPUT: fields의 항목별 사유를 입력란 helperText로
 * - 409 EMAIL_DUPLICATED: email 입력란 오류로 (서버 문구 그대로)
 * - 폼 항목에 없는 사유(예: fields.body)나 그 밖의 오류: 폼 위 Alert(message)
 */
export interface FormErrors<K extends string> {
  fields: Partial<Record<K, string>>;
  /** 입력란에 붙이지 못한 오류. 없으면 null */
  message: string | null;
}

export function toFormErrors<K extends string>(error: unknown, formFields: readonly K[]): FormErrors<K> {
  const fields: Partial<Record<K, string>> = {};
  if (!(error instanceof ApiError)) return { fields, message: errorMessage(error) };

  if (error.code === 'EMAIL_DUPLICATED' && (formFields as readonly string[]).includes('email')) {
    fields['email' as K] = error.message;
    return { fields, message: null };
  }

  let unmatched = false;
  for (const [key, reason] of Object.entries(error.fields)) {
    if ((formFields as readonly string[]).includes(key)) fields[key as K] = reason;
    else unmatched = true;
  }
  const matchedAny = Object.keys(fields).length > 0;
  // fields가 하나도 입력란에 붙지 않았거나 붙지 못한 사유가 있으면 서버 문구도 함께 보여 준다
  return { fields, message: matchedAny && !unmatched ? null : error.message };
}
