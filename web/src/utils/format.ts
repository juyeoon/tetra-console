export const formatNumber = (n: number) => n.toLocaleString('ko-KR');

export const formatPercent = (part: number, total: number, digits = 1) =>
  total === 0 ? '0%' : `${((part / total) * 100).toFixed(digits)}%`;

/** '2026.10.03 00:00' ~ '2026.10.14 23:59' → '2026.10.03 00:00 ~ 10.14 23:59' (같은 해면 연도 생략) */
export function formatPeriod(start: string, end: string) {
  const sameYear = start.slice(0, 4) === end.slice(0, 4);
  return `${start} ~ ${sameYear ? end.slice(5) : end}`;
}

export async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

/** 인증키는 평문으로 노출하지 않는다 */
export const maskKey = () => '●●●●●●●●';
