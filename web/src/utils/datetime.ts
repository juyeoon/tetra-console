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
  second: '2-digit',
  hourCycle: 'h23',
});

function kst(iso: string | number) {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return null;
  const p = Object.fromEntries(parts.formatToParts(date).map((x) => [x.type, x.value]));
  return { y: p.year, mo: p.month, d: p.day, h: p.hour, mi: p.minute, s: p.second };
}

/** '2026-10-20T18:09:11+09:00' → '2026.10.20 18:09'. 값이 없으면 '-' */
export function formatDateTime(iso: string | null | undefined): string {
  const t = iso ? kst(iso) : null;
  return t ? `${t.y}.${t.mo}.${t.d} ${t.h}:${t.mi}` : '-';
}

/** '2026-10-20T18:09:11+09:00' → '10.20 18:09' (연도 생략). 값이 없으면 '-' */
export function formatMonthDayTime(iso: string | null | undefined): string {
  const t = iso ? kst(iso) : null;
  return t ? `${t.mo}.${t.d} ${t.h}:${t.mi}` : '-';
}

/** '2026-10-20T18:09:11+09:00' → '18:09' (차트 축). 값이 없으면 '-' */
export function formatTime(iso: string | null | undefined): string {
  const t = iso ? kst(iso) : null;
  return t ? `${t.h}:${t.mi}` : '-';
}

/** '2026-10-20T18:09:11+09:00' 또는 epoch 밀리초 → '18:09:11' (마지막 갱신 시각). 값이 없으면 '–' */
export function formatClock(value: string | number | null | undefined): string {
  const t = value == null || value === '' ? null : kst(value);
  return t ? `${t.h}:${t.mi}:${t.s}` : '–';
}

/** epoch 밀리초 → '18:09' (시간 축 눈금) */
export function formatClockMinute(epochMillis: number): string {
  const t = kst(epochMillis);
  return t ? `${t.h}:${t.mi}` : '';
}

/** 지금 기준 경과 — '방금 전' · 'N분 전' · 'N시간 전' · 'N일 전'(7일 미만), 그보다 오래면 날짜. 미래 값은 '방금 전' */
export function formatRelative(iso: string | null | undefined, now: Date): string {
  const date = iso ? new Date(iso) : null;
  if (!date || Number.isNaN(date.getTime())) return '-';
  const minutes = Math.floor((now.getTime() - date.getTime()) / 60_000);
  if (minutes < 1) return '방금 전';
  if (minutes < 60) return `${minutes}분 전`;
  if (minutes < 60 * 24) return `${Math.floor(minutes / 60)}시간 전`;
  if (minutes < 60 * 24 * 7) return `${Math.floor(minutes / (60 * 24))}일 전`;
  return formatDate(iso);
}

/** '2026-10-20T18:09:11+09:00' → '2026.10.20'. 값이 없으면 '-' */
export function formatDate(iso: string | null | undefined): string {
  const t = iso ? kst(iso) : null;
  return t ? `${t.y}.${t.mo}.${t.d}` : '-';
}
