import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import { isDomain, normalizeDomain } from './validation.ts';

// API TenantInputRules 테스트와 같은 예시 표 — 두 쪽 규칙이 어긋나지 않게 같이 고친다
const VALID: [input: string, normalized: string][] = [
  ['e-corp.com', 'e-corp.com'],
  ['E-Corp.COM', 'e-corp.com'],
  ['shop.e-corp.co.kr', 'shop.e-corp.co.kr'],
  ['a.io', 'a.io'],
  ['  e-corp.com  ', 'e-corp.com'],
];

const INVALID: [input: string, why: string][] = [
  ['https://e-corp.com', '스킴'],
  ['http://e-corp.com', '스킴'],
  ['e-corp.com/shop', '경로'],
  ['e-corp.com:8080', '포트'],
  ['e corp.com', '공백'],
  ['e_corp.com', '밑줄'],
  ['e-corp.com.', '끝의 점'],
  ['-e.com', '-로 시작하는 라벨'],
  ['e-.com', '-로 끝나는 라벨'],
  ['e.123', '숫자 TLD'],
  ['회사.한국', '한글 도메인'],
  ['e-corp.xn--3e0b707e', '퓨니코드 TLD(MVP 제외)'],
  ['localhost', '점 없음'],
  [`${'a'.repeat(64)}.com`, '64자 라벨'],
  [`${'a'.repeat(63)}.${'b'.repeat(63)}.${'c'.repeat(63)}.${'d'.repeat(58)}.com`, '254자'],
  ['', '빈 값'],
];

describe('회사 root 도메인 (isDomain · normalizeDomain)', () => {
  test('통과: 앞뒤 공백을 빼고 소문자로 바꿔 보낸다', () => {
    for (const [input, normalized] of VALID) {
      assert.ok(isDomain(input), input);
      assert.equal(normalizeDomain(input), normalized, input);
    }
  });

  test('63자 라벨 · 253자 전체는 경계값으로 통과', () => {
    assert.ok(isDomain(`${'a'.repeat(63)}.com`));
    const max = `${'a'.repeat(63)}.${'b'.repeat(63)}.${'c'.repeat(63)}.${'d'.repeat(57)}.com`;
    assert.equal(max.length, 253);
    assert.ok(isDomain(max));
  });

  test('거부: 스킴 · 경로 · 포트 · 공백 · 밑줄 · 끝의 점 · 라벨 규칙 · 숫자 TLD · 한글 · 퓨니코드 · 길이', () => {
    for (const [input, why] of INVALID) {
      assert.equal(isDomain(input), false, `${why}: ${input}`);
    }
    assert.equal(`${'a'.repeat(63)}.${'b'.repeat(63)}.${'c'.repeat(63)}.${'d'.repeat(58)}.com`.length, 254);
  });
});
