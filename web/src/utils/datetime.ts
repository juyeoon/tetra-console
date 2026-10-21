/**
 * API 일시(ISO-8601, +09:00) → 화면 표시. 항상 KST 기준이라 브라우저 · 테스트 환경의 시간대와 무관하다.
 * node --test 대상이라 import 없이 둔다.
 */

const parts = new Intl.DateTimeFormat('en-US', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

function kst(iso: string) {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return null;
  const p = Object.fromEntries(parts.formatToParts(date).map((x) => [x.type, x.value]));
  return { y: p.year, mo: p.month, d: p.day, h: p.hour, mi: p.minute };
}

/** '2026-10-20T18:09:11+09:00' → '2026.10.20 18:09'. 값이 없으면 '-' */
export function formatDateTime(iso: string | null | undefined): string {
  const t = iso ? kst(iso) : null;
  return t ? `${t.y}.${t.mo}.${t.d} ${t.h}:${t.mi}` : '-';
}

/** '2026-10-20T18:09:11+09:00' → '2026.10.20'. 값이 없으면 '-' */
export function formatDate(iso: string | null | undefined): string {
  const t = iso ? kst(iso) : null;
  return t ? `${t.y}.${t.mo}.${t.d}` : '-';
}
