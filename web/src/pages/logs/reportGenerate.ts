import { ApiError, errorMessage } from '../../api/client.ts';
import type { ReportGenerateRequest } from '../../api/types.ts';

/**
 * OPS-09 [리포트 만들기] 모달 — 입력 검사 · 요청 본문 · 결과 문구 (화면과 분리, node --test 대상).
 * 서버(POST /reports/generate)와 같은 규칙을 먼저 본다: 운영자 접속기록은 시작일 ≤ 종료일 ≤ 오늘(KST), 최대 31일.
 */

export type GenerateKind = 'OPERATOR_AUDIT' | 'EVENT_RESULT';

export const GENERATE_KINDS: readonly { key: GenerateKind; label: string; hint: string }[] = [
  { key: 'OPERATOR_AUDIT', label: '운영자 접속기록', hint: '최근 15~20분 기록은 아직 포함되지 않을 수 있습니다.' },
  { key: 'EVENT_RESULT', label: '이벤트 결과', hint: '결과 집계가 끝난 이벤트만 만들 수 있습니다.' },
];

/** 운영자 접속기록 기간 상한(양끝 포함 일수) — 서버 ReportGenerateService.AUDIT_MAX_DAYS와 같음 */
export const AUDIT_MAX_DAYS = 31;

export interface GenerateForm {
  kind: GenerateKind;
  /** 'YYYY-MM-DD' (날짜 입력 값) */
  from: string;
  to: string;
  /** 입력한 글자 그대로 */
  eventId: string;
}

export type GenerateField = 'from' | 'to' | 'eventId';

export type GenerateFieldErrors = Partial<Record<GenerateField, string>>;

const kstDate = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' });

/** KST 오늘 'YYYY-MM-DD' — 브라우저 시간대와 상관없이 */
export function kstToday(now: Date): string {
  return kstDate.format(now);
}

/** 기본 기간: 지난달 1일 ~ 말일(KST) */
export function lastMonthRange(now: Date): { from: string; to: string } {
  const [y, m] = kstToday(now).split('-').map(Number);
  const year = m === 1 ? y - 1 : y;
  const month = m === 1 ? 12 : m - 1;
  const lastDay = new Date(Date.UTC(year, month, 0)).getUTCDate();
  const mm = String(month).padStart(2, '0');
  return { from: `${year}-${mm}-01`, to: `${year}-${mm}-${String(lastDay).padStart(2, '0')}` };
}

const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** 'YYYY-MM-DD'의 UTC 자정(ms). 없는 날짜(2026-02-30 등)면 NaN */
function dayValue(value: string): number {
  if (!DATE.test(value)) return NaN;
  const [y, m, d] = value.split('-').map(Number);
  const t = Date.UTC(y, m - 1, d);
  const back = new Date(t);
  return back.getUTCFullYear() === y && back.getUTCMonth() === m - 1 && back.getUTCDate() === d ? t : NaN;
}

const DAY_MS = 24 * 60 * 60 * 1000;

/** 화면 검사. 문제가 없으면 빈 객체 */
export function validateGenerateForm(form: GenerateForm, today: string): GenerateFieldErrors {
  if (form.kind === 'EVENT_RESULT') {
    const id = form.eventId.trim();
    if (!id) return { eventId: '이벤트 ID를 입력해주세요.' };
    if (!/^\d{1,15}$/.test(id) || Number(id) <= 0) return { eventId: '이벤트 ID는 숫자로 입력해주세요.' };
    return {};
  }
  const errors: GenerateFieldErrors = {};
  if (!form.from) errors.from = '시작일을 입력해주세요.';
  else if (Number.isNaN(dayValue(form.from))) errors.from = '날짜 형식이 올바르지 않습니다.';
  if (!form.to) errors.to = '종료일을 입력해주세요.';
  else if (Number.isNaN(dayValue(form.to))) errors.to = '날짜 형식이 올바르지 않습니다.';
  if (errors.from || errors.to) return errors;

  const from = dayValue(form.from);
  const to = dayValue(form.to);
  if (from > to) return { to: '종료일은 시작일과 같거나 뒤여야 합니다.' };
  if (to > dayValue(today)) return { to: `종료일은 오늘(${today})까지 고를 수 있습니다.` };
  if ((to - from) / DAY_MS + 1 > AUDIT_MAX_DAYS) return { to: `기간은 최대 ${AUDIT_MAX_DAYS}일입니다.` };
  return {};
}

/** 요청 본문 — 종류에 쓰지 않는 값은 넣지 않는다(서버가 400) */
export function toGenerateRequest(form: GenerateForm): ReportGenerateRequest {
  return form.kind === 'OPERATOR_AUDIT'
    ? { category: 'OPERATOR_AUDIT', from: form.from, to: form.to }
    : { category: 'EVENT_RESULT', eventId: Number(form.eventId.trim()) };
}

const ERROR_MESSAGES: Record<string, string> = {
  REPORT_BUSY: '다른 리포트를 만드는 중입니다. 잠시 뒤 다시 시도하세요.',
  RESULT_NOT_READY: '이벤트 결과 집계가 아직 끝나지 않았습니다.',
  TENANT_NOT_READY: '테넌트 환경 준비가 끝나지 않아 만들 수 없습니다.',
  AUDIT_BUCKET_NOT_CONFIGURED: '감사 로그 위치가 설정되지 않아 운영자 접속기록을 만들 수 없습니다.',
};

/** 실패 → 모달 안 문구. 코드별 한글 문구, 404는 이벤트 없음, 400은 입력 확인, 그 밖은 서버 · 공통 문구 */
export function generateErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    const known = ERROR_MESSAGES[error.code];
    if (known) return known;
    if (error.status === 404) return '이벤트를 찾을 수 없습니다.';
    if (error.status === 400) return '입력을 확인하세요.';
  }
  return errorMessage(error);
}

/** 400의 칸별 사유(서버 fields) — 이 모달의 입력란 것만 */
export function generateFieldErrors(error: unknown): GenerateFieldErrors {
  if (!(error instanceof ApiError) || error.status !== 400) return {};
  const errors: GenerateFieldErrors = {};
  for (const field of ['from', 'to', 'eventId'] as const) {
    if (error.fields[field]) errors[field] = error.fields[field];
  }
  return errors;
}

/** 성공 토스트 — 같은 리포트가 이미 있었으면(created false) 파일만 새로 썼다는 문구 */
export function generatedMessage(created: boolean): string {
  return created ? '리포트를 만들었습니다.' : '이미 있는 리포트를 새로 썼습니다.';
}
