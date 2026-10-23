import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import type { ApplicationSummaryResponse } from '../../api/types.ts';
import { formatCapacity, planLabel } from '../../utils/event.ts';
import { formatPeriod } from '../../utils/format.ts';
import { bannerText, hostingHours, previewDomain, screenSummary, toSummaryItems } from './applicationSummaryView.ts';

const LAYOUT = {
  title: { h: 'CENTER', v: 'TOP' },
  banner: { h: 'CENTER', v: 'TOP' },
  couponList: { h: 'CENTER', v: 'MIDDLE' },
  issueButton: { h: 'CENTER', v: 'BOTTOM' },
  theme: 'WHITE',
} as const;

const summary = (over: Partial<ApplicationSummaryResponse> = {}): ApplicationSummaryResponse => ({
  eventId: 1043,
  eventType: 'COUPON',
  name: '가을 맞이 선착순 쿠폰',
  startAt: '2026-11-01T10:00:00+09:00',
  endAt: '2026-11-01T12:00:00+09:00',
  hostingStartAt: '2026-11-01T08:00:00+09:00',
  hostingEndAt: '2026-11-01T14:00:00+09:00',
  trafficPlan: { code: 'M', maxCapacity: 30000 },
  applicationStatus: 'APPROVED',
  bannerFileName: 'banner.png',
  endpointUrl: 'https://shop.example.com/tetra-entry',
  coupons: [
    { couponId: 1, name: '10% 할인 쿠폰' },
    { couponId: 2, name: ' 무료배송 쿠폰 ' },
  ],
  stockCount: 15000,
  layoutConfig: LAYOUT,
  ...over,
});

describe('표기 함수 (Portal SubmitStep과 같은 결과)', () => {
  test('기간 — 같은 해면 끝 날짜의 연도 생략, 다른 해면 그대로', () => {
    assert.equal(formatPeriod('2026.11.01 10:00', '2026.11.01 12:00'), '2026.11.01 10:00 ~ 11.01 12:00');
    assert.equal(formatPeriod('2026.12.31 22:00', '2027.01.01 02:00'), '2026.12.31 22:00 ~ 2027.01.01 02:00');
  });

  test('호스팅 시간 올림 — 5시간 1분 → 6, 정확히 6시간 → 6, 거꾸로면 0', () => {
    assert.equal(hostingHours('2026-11-01T08:00:00+09:00', '2026-11-01T13:01:00+09:00'), 6);
    assert.equal(hostingHours('2026-11-01T08:00:00+09:00', '2026-11-01T14:00:00+09:00'), 6);
    assert.equal(hostingHours('2026-11-01T14:00:00+09:00', '2026-11-01T08:00:00+09:00'), 0);
  });

  test('수용 인원 · 플랜', () => {
    assert.equal(formatCapacity(30000), '3만');
    assert.equal(formatCapacity(15000), '1.5만');
    assert.equal(formatCapacity(5000), '5,000');
    assert.equal(planLabel('M', 30000), 'M 플랜 (3만 명)');
    assert.equal(planLabel('M', null), 'M 플랜');
  });

  test('화면 설정 요약 기본값', () => {
    assert.equal(screenSummary(LAYOUT), '제목 상-중 / 배너 상-중 / 쿠폰 목록 중-중 / 발급 버튼 하-중 · 테마 화이트');
    assert.equal(
      screenSummary({ ...LAYOUT, banner: { h: 'LEFT', v: 'MIDDLE' }, issueButton: { h: 'RIGHT', v: 'BOTTOM' }, theme: 'WARM_ORANGE' }),
      '제목 상-중 / 배너 중-좌 / 쿠폰 목록 중-중 / 발급 버튼 하-우 · 테마 웜 오렌지',
    );
  });

  test('배너 — 신청 상태 문구 + 저장 경로 파일명', () => {
    assert.equal(bannerText('APPLYING', null), '업로드 확인 중');
    assert.equal(bannerText('APPLY_FAILED', null), '등록되지 않음');
    assert.equal(bannerText('APPLIED', 'banner.png'), '등록 완료 (banner.png)');
    assert.equal(bannerText('APPROVED', null), '등록 완료');
  });
});

describe('신청 요약 항목', () => {
  test('쿠폰 이벤트 — 10개, 순서 · 라벨 · 값', () => {
    assert.deepEqual(toSummaryItems(summary()), [
      { label: '이벤트 타입', value: '쿠폰' },
      { label: '이벤트명', value: '가을 맞이 선착순 쿠폰' },
      { label: '이벤트 기간', value: '2026.11.01 10:00 ~ 11.01 12:00' },
      { label: '호스팅 기간', value: '2026.11.01 08:00 ~ 11.01 14:00 (6시간)' },
      { label: '목표 동시 접속 규모', value: 'M 플랜 (3만 명)' },
      { label: '이벤트 대표 배너', value: '등록 완료 (banner.png)' },
      { label: '이벤트 엔드포인트 URL', value: 'https://shop.example.com/tetra-entry', mono: true },
      { label: '발급 쿠폰', value: '10% 할인 쿠폰, 무료배송 쿠폰 (2종)' },
      { label: '총 발급 재고', value: '15,000매' },
      { label: '이벤트 화면 설정', value: '제목 상-중 / 배너 상-중 / 쿠폰 목록 중-중 / 발급 버튼 하-중 · 테마 화이트' },
    ]);
  });

  test('쿠폰이 아닌 타입은 발급 쿠폰 행이 없다', () => {
    const items = toSummaryItems(summary({ eventType: 'TIMESALE', coupons: [] }));
    assert.equal(items.length, 9);
    assert.equal(items[0].value, '타임세일');
    assert.equal(items.some((i) => i.label === '발급 쿠폰'), false);
  });

  test('일시는 KST 기준 (UTC로 와도 같은 표기)', () => {
    const items = toSummaryItems(summary({ startAt: '2026-11-01T01:00:00Z', endAt: '2026-11-01T03:00:00Z' }));
    assert.equal(items[2].value, '2026.11.01 10:00 ~ 11.01 12:00');
  });

  test('미리보기 도메인 — 할당 도메인이 없으면 기본값', () => {
    assert.equal(previewDomain('abcd1234.k7f2m9qx4b1t.tetraops.cloud'), 'abcd1234.k7f2m9qx4b1t.tetraops.cloud');
    assert.equal(previewDomain(null), 'event.tetra-shop.com');
  });
});
