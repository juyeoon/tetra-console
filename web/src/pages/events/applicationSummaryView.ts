import type { ApplicationStatus, ApplicationSummaryResponse, HAlign, LayoutConfig, ScreenTheme, ScreenWidget, VAlign } from '../../api/types.ts';
import { formatDateTime } from '../../utils/datetime.ts';
import { planLabel } from '../../utils/event.ts';
import { formatNumber, formatPeriod } from '../../utils/format.ts';
import { eventTypeLabel } from '../../utils/status.ts';

/**
 * 신청 내용(OPS-03-2 · 04-2) — Tetra Portal 이벤트 신청 4단계(UI-TP-10, SubmitStep) 「신청 요약」과 같은 순서 · 라벨 · 표기.
 * Portal은 신청 draft 값으로, Console은 API 원본 값으로 같은 문자열을 만든다 (화면과 분리, node --test 대상).
 */

// ---------------------------------------------------------------- 화면 설정 (Portal components/event/ScreenPreview.tsx)

export const WIDGET_ORDER: ScreenWidget[] = ['title', 'banner', 'couponList', 'issueButton'];
export const widgetLabel: Record<ScreenWidget, string> = { title: '제목', banner: '배너', couponList: '쿠폰 목록(카드)', issueButton: '발급 버튼' };
export const hLabel: Record<HAlign, string> = { LEFT: '좌', CENTER: '중', RIGHT: '우' };
export const vLabel: Record<VAlign, string> = { TOP: '상', MIDDLE: '중', BOTTOM: '하' };
export const themeLabel: Record<ScreenTheme, string> = { WHITE: '화이트', WARM_ORANGE: '웜 오렌지', DARK: '다크', BLUE: '블루' };

/** 선택 요약 — "제목 상-중 / 배너 상-중 / … · 테마 화이트" */
export const screenSummary = (s: LayoutConfig) =>
  `${WIDGET_ORDER.map((w) => `${widgetLabel[w].replace('(카드)', '')} ${vLabel[s[w].v]}-${hLabel[s[w].h]}`).join(' / ')} · 테마 ${themeLabel[s.theme]}`;

// ---------------------------------------------------------------- 요약 항목

/** 호스팅 시간 — 시간 단위 올림 (서버의 비용 산정과 같은 기준) */
export function hostingHours(start: string, end: string) {
  const ms = new Date(end).getTime() - new Date(start).getTime();
  return ms > 0 ? Math.ceil(ms / 3_600_000) : 0;
}

/**
 * 대표 배너 — DB에 원본 파일명이 없어 Portal 상세(UI-TP-06)처럼 상태 문구 + 저장 경로의 파일명.
 * TODO(미정): 원본 파일명 저장 여부(ERD 변경 필요)
 */
export function bannerText(status: ApplicationStatus, fileName: string | null) {
  if (status === 'APPLYING') return '업로드 확인 중';
  if (status === 'APPLY_FAILED') return '등록되지 않음';
  return fileName ? `등록 완료 (${fileName})` : '등록 완료';
}

export interface SummaryItem {
  label: string;
  value: string;
  /** 고정폭 글꼴 · 줄바꿈 break-all (엔드포인트 URL) */
  mono?: boolean;
}

/** 「신청 요약」 항목 — 발급 쿠폰은 쿠폰 타입일 때만 */
export function toSummaryItems(s: ApplicationSummaryResponse): SummaryItem[] {
  const hostingStart = formatDateTime(s.hostingStartAt);
  const hostingEnd = formatDateTime(s.hostingEndAt);
  return [
    { label: '이벤트 타입', value: eventTypeLabel[s.eventType] ?? s.eventType },
    { label: '이벤트명', value: s.name },
    { label: '이벤트 기간', value: formatPeriod(formatDateTime(s.startAt), formatDateTime(s.endAt)) },
    { label: '호스팅 기간', value: `${formatPeriod(hostingStart, hostingEnd)} (${formatNumber(hostingHours(s.hostingStartAt, s.hostingEndAt))}시간)` },
    { label: '목표 동시 접속 규모', value: planLabel(s.trafficPlan.code, s.trafficPlan.maxCapacity) },
    { label: '이벤트 대표 배너', value: bannerText(s.applicationStatus, s.bannerFileName) },
    { label: '이벤트 엔드포인트 URL', value: s.endpointUrl, mono: true },
    ...(s.eventType === 'COUPON'
      ? [{ label: '발급 쿠폰', value: s.coupons.map((c) => c.name.trim()).join(', ') + ` (${s.coupons.length}종)` }]
      : []),
    { label: '총 발급 재고', value: `${formatNumber(s.stockCount)}매` },
    { label: '이벤트 화면 설정', value: screenSummary(s.layoutConfig) },
  ];
}

/** 미리보기 상단 바 도메인 — 승인 후 할당 도메인(fullDomain), 없으면 Portal 기본값 */
export const previewDomain = (fullDomain: string | null | undefined) => fullDomain || 'event.tetra-shop.com';
