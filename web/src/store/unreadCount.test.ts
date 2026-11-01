import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import {
  NO_UNREAD,
  applyUnread,
  badgeText,
  badgeTone,
  clearUnread,
  fetchOnRoute,
  navAriaLabel,
  shouldPoll,
  shouldReloadDashboard,
} from './unreadCount.ts';

describe('사이드바 안 읽은 알림 배지 — 글자 · 읽기 이름 · 색', () => {
  test('0 · 모름이면 배지 없음, 1~99는 그대로, 100 이상은 99+', () => {
    assert.equal(badgeText(0), null);
    assert.equal(badgeText(null), null);
    assert.equal(badgeText(1), '1');
    assert.equal(badgeText(99), '99');
    assert.equal(badgeText(100), '99+');
    assert.equal(badgeText(1234), '99+');
  });

  test('메뉴 링크 읽기 이름(마우스를 올렸을 때도 같은 문구) — 99+여도 실제 수, 긴급이 있으면 (긴급 포함)', () => {
    assert.equal(navAriaLabel(null), '알림센터');
    assert.equal(navAriaLabel({ count: 0, urgent: false }), '알림센터');
    assert.equal(navAriaLabel({ count: 5, urgent: false }), '알림센터, 안 읽은 알림 5건');
    assert.equal(navAriaLabel({ count: 5, urgent: true }), '알림센터, 안 읽은 알림 5건(긴급 포함)');
    assert.equal(navAriaLabel({ count: 120, urgent: true }), '알림센터, 안 읽은 알림 120건(긴급 포함)');
  });

  test('색 — 긴급이 있으면 어디서든 빨강, 아니면 페리윙클(선택 줄에서도 — 남색 배지는 선택 줄과 1.33:1)', () => {
    assert.equal(badgeTone({ urgent: true, active: false }), 'urgent');
    assert.equal(badgeTone({ urgent: true, active: true }), 'urgent');
    assert.equal(badgeTone({ urgent: false, active: false }), 'normal');
    assert.equal(badgeTone({ urgent: false, active: true }), 'normal');
  });
});

describe('사이드바 안 읽은 알림 배지 — 갱신 판단', () => {
  test('주기 · 다시 보일 때 — 탭이 숨겨졌거나 로그아웃이면 건너뛴다', () => {
    assert.equal(shouldPoll({ authenticated: true, hidden: false }), true);
    assert.equal(shouldPoll({ authenticated: true, hidden: true }), false);
    assert.equal(shouldPoll({ authenticated: false, hidden: false }), false);
  });

  test('라우트 이동 — 알림센터(목록 응답으로 맞춤) · 로그인 · 미리보기는 따로 받지 않는다', () => {
    assert.equal(fetchOnRoute('/'), true);
    assert.equal(fetchOnRoute('/events/applications'), true);
    assert.equal(fetchOnRoute('/monitoring/traffic'), true);
    assert.equal(fetchOnRoute('/logs/reports'), true);
    assert.equal(fetchOnRoute('/logs/notifications'), false);
    assert.equal(fetchOnRoute('/login'), false);
    assert.equal(fetchOnRoute('/preview/tenant-registration-email'), false);
  });

  test('실패하면 이전 값 그대로', () => {
    const got = applyUnread(NO_UNREAD, 1, { count: 5, urgent: true });
    assert.deepEqual(got, { unread: { count: 5, urgent: true }, applied: 1 });
    assert.deepEqual(applyUnread(got, 2, null), got);
    // 아직 한 번도 못 받았으면 모름(배지 없음)
    assert.deepEqual(applyUnread(NO_UNREAD, 1, null), NO_UNREAD);
  });

  test('앞선 요청의 늦은 응답은 버린다(목록 응답으로 맞춘 값이 이긴다)', () => {
    // 요청 1(배지 조회) 보냄 → 목록 응답으로 2번 값 반영 → 요청 1의 응답이 늦게 옴
    const reported = applyUnread(NO_UNREAD, 2, { count: 3, urgent: false });
    assert.deepEqual(applyUnread(reported, 1, { count: 5, urgent: true }), reported);
    assert.deepEqual(applyUnread(reported, 3, { count: 4, urgent: true }), { unread: { count: 4, urgent: true }, applied: 3 });
  });

  test('로그아웃 — 값을 지우고 그 전에 보낸 요청의 응답은 버린다', () => {
    const before = applyUnread(NO_UNREAD, 1, { count: 7, urgent: true });
    const cleared = clearUnread(3);
    assert.equal(cleared.unread, null);
    assert.equal(navAriaLabel(cleared.unread), '알림센터');
    assert.deepEqual(applyUnread(cleared, 2, { count: 9, urgent: false }), cleared);
    assert.notDeepEqual(cleared, before);
    // 다시 로그인한 뒤 요청은 반영
    assert.deepEqual(applyUnread(cleared, 4, { count: 2, urgent: false }), { unread: { count: 2, urgent: false }, applied: 4 });
  });

  test('홈 최근 알림 — 배지 수가 바뀌었을 때만 다시 받는다(처음 받은 값 · 로그아웃은 아님)', () => {
    assert.equal(shouldReloadDashboard(3, 4), true);
    assert.equal(shouldReloadDashboard(4, 3), true);
    assert.equal(shouldReloadDashboard(3, 3), false);
    assert.equal(shouldReloadDashboard(null, 3), false);
    assert.equal(shouldReloadDashboard(3, null), false);
    assert.equal(shouldReloadDashboard(null, null), false);
  });
});
