import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import type { NotificationChip, NotificationItem, NotificationPage, NotificationState } from '../../api/types.ts';
import { priorityMeta, priorityOf } from '../../utils/status.ts';
import {
  NOTIFICATION_CHIPS,
  PAGE_SIZE,
  PRIORITY_FILTERS,
  canReadAll,
  chipButtonLabel,
  chipMenuItems,
  emptyState,
  itemHref,
  listQuery,
  pageToFix,
  paramsFromView,
  pressItem,
  readOptimistically,
  splitContent,
  stateLabel,
  stateView,
  viewFromParams,
  withFilters,
  withRead,
  type NotificationFilters,
  type NotificationView,
} from './notificationView.ts';

const item = (
  notificationId: number,
  read = false,
  chip: NotificationChip | null = 'EVENT',
  link: string | null = null,
  state: NotificationState | null = null,
): NotificationItem => ({
  notificationId,
  tenantId: null,
  tenantName: null,
  eventId: null,
  category: 'HOSTING',
  priority: 'URGENT',
  occurredAt: '2026-10-21T10:00:00+09:00',
  content: `알림 ${notificationId}`,
  read,
  chip,
  link,
  state,
});

const ITEMS = [item(1, false), item(2, true), item(3, false)];

const pageOf = (over: Partial<NotificationPage> = {}): NotificationPage => ({
  items: ITEMS,
  page: 1,
  size: PAGE_SIZE,
  totalCount: 3,
  totalPages: 1,
  unreadCount: 2,
  unreadUrgent: true,
  counts: {
    priority: { ALL: 3, URGENT: 3, WARNING: 0, INFO: 0 },
    chip: { ALL: 3, APPLICATION: 0, EVENT: 3, TENANT: 0, REPORT: 0, SYSTEM: 0 },
  },
  ...over,
});

const DEFAULT_FILTERS: NotificationFilters = { priority: 'ALL', chip: 'ALL', unreadOnly: true, query: '' };

describe('필터 한 줄', () => {
  test('우선순위 세그먼트는 전체 · 긴급 · 주의 · 정보', () => {
    assert.deepEqual(PRIORITY_FILTERS.map((f) => f.label), ['전체', '긴급', '주의', '정보']);
  });

  test('분류 메뉴는 전체 + 신청 · 이벤트 · 테넌트 · 리포트 · 시스템, 서버 건수, 0건은 흐리게', () => {
    assert.deepEqual(NOTIFICATION_CHIPS.map((c) => c.label), ['전체', '신청', '이벤트', '테넌트', '리포트', '시스템']);
    const items = chipMenuItems({ ALL: 5, APPLICATION: 0, EVENT: 3, TENANT: 2, REPORT: 0, SYSTEM: 0 });
    assert.deepEqual(
      items.map((c) => [c.label, c.count, c.dim]),
      [['전체', 5, false], ['신청', 0, true], ['이벤트', 3, false], ['테넌트', 2, false], ['리포트', 0, true], ['시스템', 0, true]],
    );
  });

  test('분류 버튼 글자', () => {
    assert.equal(chipButtonLabel('ALL'), '분류: 전체');
    assert.equal(chipButtonLabel('TENANT'), '분류: 테넌트');
  });
});

describe('주소 쿼리', () => {
  test('비어 있으면 기본값 — 전체 · 전체 · 안 읽은 것만 켬 · 검색 없음 · 1페이지', () => {
    assert.deepEqual(viewFromParams(new URLSearchParams()), { filters: DEFAULT_FILTERS, page: 1 });
  });

  test('값이 있으면 그대로, 모르는 값은 기본값', () => {
    assert.deepEqual(viewFromParams(new URLSearchParams('priority=URGENT&chip=SYSTEM&unreadOnly=false&q=배포&page=3')), {
      filters: { priority: 'URGENT', chip: 'SYSTEM', unreadOnly: false, query: '배포' },
      page: 3,
    });
    assert.deepEqual(viewFromParams(new URLSearchParams('priority=LOW&chip=FOO&unreadOnly=no&page=0')), { filters: DEFAULT_FILTERS, page: 1 });
    assert.equal(viewFromParams(new URLSearchParams('page=10001')).page, 1);
    assert.equal(viewFromParams(new URLSearchParams('page=2.5')).page, 1);
    assert.equal(viewFromParams(new URLSearchParams(`q=${'가'.repeat(150)}`)).filters.query.length, 100);
  });

  test('기본값은 주소에서 뺀다', () => {
    assert.equal(paramsFromView({ filters: DEFAULT_FILTERS, page: 1 }).toString(), '');
    assert.equal(paramsFromView({ filters: { ...DEFAULT_FILTERS, query: '   ' }, page: 1 }).toString(), '');
    const view: NotificationView = { filters: { priority: 'WARNING', chip: 'EVENT', unreadOnly: false, query: '쿠폰' }, page: 2 };
    assert.deepEqual(viewFromParams(paramsFromView(view)), view);
  });

  test('조건을 바꾸면 1페이지로', () => {
    const view: NotificationView = { filters: DEFAULT_FILTERS, page: 4 };
    assert.deepEqual(withFilters(view, { chip: 'TENANT' }), { filters: { ...DEFAULT_FILTERS, chip: 'TENANT' }, page: 1 });
    assert.deepEqual(withFilters(view, { unreadOnly: false }).page, 1);
  });

  test('목록 요청 — 전체는 빼고, 안 읽은 것만 · 페이지 · 크기는 늘 보낸다. 검색어는 앞뒤 공백을 지운다', () => {
    assert.equal(listQuery({ filters: DEFAULT_FILTERS, page: 1 }).toString(), 'unreadOnly=true&page=1&size=10');
    assert.equal(
      listQuery({ filters: { priority: 'URGENT', chip: 'SYSTEM', unreadOnly: false, query: '  DLQ ' }, page: 2 }).toString(),
      'priority=URGENT&chip=SYSTEM&unreadOnly=false&q=DLQ&page=2&size=10',
    );
  });
});

describe('페이지 · 빈 목록 · 모두 읽음', () => {
  test('범위 밖 페이지는 마지막 페이지로, 맞는 알림이 없으면 1페이지로', () => {
    assert.equal(pageToFix(pageOf({ items: [], page: 5, totalPages: 3 })), 3);
    assert.equal(pageToFix(pageOf({ items: [], page: 2, totalPages: 0, totalCount: 0 })), 1);
    assert.equal(pageToFix(pageOf({ items: [], page: 1, totalPages: 0, totalCount: 0 })), null);
    assert.equal(pageToFix(pageOf({ page: 2, totalPages: 3 })), null);
  });

  test('빈 목록 문구 3가지 — 검색 결과 없음 / 안 읽은 알림 없음 + 모든 알림 보기 / 알림 없음', () => {
    assert.deepEqual(emptyState({ ...DEFAULT_FILTERS, query: '배포' }), { title: '검색 결과가 없습니다', showAll: false });
    assert.deepEqual(emptyState(DEFAULT_FILTERS), {
      title: '안 읽은 알림이 없습니다',
      description: "'안 읽은 것만'을 끄면 읽은 알림도 볼 수 있습니다.",
      showAll: true,
    });
    assert.deepEqual(emptyState({ ...DEFAULT_FILTERS, unreadOnly: false }), { title: '알림이 없습니다', showAll: false });
    // 공백뿐인 검색어는 검색이 아니다
    assert.equal(emptyState({ ...DEFAULT_FILTERS, query: '  ' }).showAll, true);
  });

  test('모두 읽음은 안 읽은 알림이(조건과 무관하게) 있고 요청 중이 아닐 때만', () => {
    assert.equal(canReadAll(pageOf(), false), true);
    assert.equal(canReadAll(pageOf(), true), false);
    assert.equal(canReadAll(pageOf({ unreadCount: 0 }), false), false);
    // 지금 페이지에 안 읽은 항목이 없어도 다른 조건에 있으면 켠다
    assert.equal(canReadAll(pageOf({ items: [item(2, true)] }), false), true);
  });
});

describe('항목 문구', () => {
  test('첫 문장은 첫 줄, 나머지는 둘째 줄', () => {
    assert.deepEqual(splitContent('[A사] [1043:봄 쿠폰] 이벤트 배포에 실패했습니다. 확인이 필요합니다. 원인: StartExecution 실패'), {
      head: '[A사] [1043:봄 쿠폰] 이벤트 배포에 실패했습니다.',
      rest: '확인이 필요합니다. 원인: StartExecution 실패',
    });
    // 문장이 하나면 나머지 없음, 소수점 · 시각은 나누지 않는다
    assert.deepEqual(splitContent('[A사] [1043:쿠폰 2.0] 입장이 열렸습니다(10:00).'), { head: '[A사] [1043:쿠폰 2.0] 입장이 열렸습니다(10:00).', rest: '' });
    assert.deepEqual(splitContent('끝 문장 부호 없음'), { head: '끝 문장 부호 없음', rest: '' });
  });
});

describe('현재 상태', () => {
  test('기존 상태 라벨을 쓴다', () => {
    assert.equal(stateLabel({ kind: 'APPLICATION_STATUS', value: 'REJECTED', alert: false }), '반려됨');
    assert.equal(stateLabel({ kind: 'HOSTING_STATUS', value: 'DEPLOY_FAILED', alert: true }), '배포 실패');
    assert.equal(stateLabel({ kind: 'EVENT_STATUS', value: 'OPEN', alert: false }), '진행 중');
    assert.equal(stateLabel({ kind: 'ACCOUNT_STATUS', value: 'INACTIVE', alert: false }), '비활성');
    assert.equal(stateLabel({ kind: 'PROVISIONING_STATUS', value: 'FAILED', alert: true }), '환경 준비 실패');
    assert.equal(stateLabel({ kind: 'PROVISIONING_STATUS', value: 'READY', alert: false }), '환경 준비 완료');
    assert.equal(stateLabel({ kind: 'PASSWORD_SETUP', value: 'SET', alert: false }), '설정 완료');
    assert.equal(stateLabel({ kind: 'PASSWORD_SETUP', value: 'NOT_SET', alert: false }), '비밀번호 미설정');
    assert.equal(stateLabel({ kind: 'DLQ_MESSAGES', value: '1200', alert: true }), 'DLQ 메시지 1,200건');
    assert.equal(stateLabel({ kind: 'DLQ_MESSAGES', value: '0', alert: false }), 'DLQ 메시지 0건');
  });

  test('모르는 값은 DB 값 그대로', () => {
    assert.equal(stateLabel({ kind: 'HOSTING_STATUS', value: 'PAUSED', alert: false }), 'PAUSED');
    assert.equal(stateLabel({ kind: 'SOMETHING', value: 'X', alert: false }), 'X');
  });

  test("'현재: 라벨', alert면 빨강", () => {
    assert.deepEqual(stateView({ kind: 'HOSTING_STATUS', value: 'END_FAILED', alert: true }), { text: '현재: 종료 실패', tone: 'red' });
    assert.deepEqual(stateView({ kind: 'HOSTING_STATUS', value: 'ENDED', alert: false }), { text: '현재: 호스팅 종료', tone: null });
    assert.equal(stateView(null), null);
  });
});

describe('링크 · 읽음', () => {
  test('href는 서버 경로 그대로, 앱 밖 주소는 링크로 만들지 않는다', () => {
    assert.equal(itemHref(item(1, false, 'EVENT', '/monitoring/traffic?eventId=1043')), '/monitoring/traffic?eventId=1043');
    assert.equal(itemHref(item(1, false, 'EVENT', '/events/progress/1043')), '/events/progress/1043');
    assert.equal(itemHref(item(1, false, 'TENANT', null)), null);
    assert.equal(itemHref(item(1, false, 'TENANT', 'https://example.com')), null);
    assert.equal(itemHref(item(1, false, 'TENANT', '//example.com')), null);
  });

  test('클릭 · 가운데 클릭은 안 읽은 것만 읽음, 오른쪽 클릭은 아님', () => {
    const calls: number[] = [];
    const onRead = (id: number) => calls.push(id);
    pressItem(ITEMS[0], { button: 0 }, onRead);
    pressItem(ITEMS[0], { button: 1 }, onRead);
    pressItem(ITEMS[0], { button: 2 }, onRead);
    pressItem(ITEMS[1], { button: 0 }, onRead); // 이미 읽음
    pressItem(ITEMS[1], { button: 1 }, onRead);
    assert.deepEqual(calls, [1, 1]);
  });

  test('읽음이 실패해도 이동은 막지 않고, 되돌린 뒤 토스트', async () => {
    let items = ITEMS;
    const errors: unknown[] = [];
    let pending: Promise<void> | undefined;
    const event = { button: 0, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } };

    pressItem(ITEMS[0], event, (id) => {
      pending = readOptimistically(id, (change) => { items = change(items); }, () => Promise.reject(new Error('네트워크 오류')), (e) => errors.push(e));
    });

    // 누른 즉시: 이동(기본 동작)을 막지 않고 화면은 먼저 읽음
    assert.equal(event.defaultPrevented, false);
    assert.equal(items.find((n) => n.notificationId === 1)?.read, true);
    await pending;
    assert.equal(items.find((n) => n.notificationId === 1)?.read, false);
    assert.equal(errors.length, 1);
  });

  test('읽음이 성공하면 그대로 읽음(안 읽은 것만이어도 목록에서 빠지는 건 다음 조회 때)', async () => {
    let items = ITEMS;
    await readOptimistically(1, (change) => { items = change(items); }, async () => {}, () => assert.fail('토스트 없음'));
    assert.equal(items.find((n) => n.notificationId === 1)?.read, true);
    assert.equal(items.length, ITEMS.length);
  });

  test('한 건만 바꾸고 되돌릴 수 있다', () => {
    const read = withRead(ITEMS, 1, true);
    assert.equal(read.find((n) => n.notificationId === 1)?.read, true);
    assert.deepEqual(read.filter((n) => n.notificationId !== 1), ITEMS.filter((n) => n.notificationId !== 1));
    assert.equal(withRead(read, 1, false).find((n) => n.notificationId === 1)?.read, false);
  });
});

describe('우선순위 표시', () => {
  test('긴급 빨강 · 주의 주황 · 정보 파랑, 모르는 값은 정보', () => {
    assert.deepEqual([priorityOf('URGENT'), priorityOf('WARNING'), priorityOf('INFO'), priorityOf('X')], ['URGENT', 'WARNING', 'INFO', 'INFO']);
    assert.deepEqual(
      (['URGENT', 'WARNING', 'INFO'] as const).map((p) => [priorityMeta[p].label, priorityMeta[p].tone]),
      [['긴급', 'red'], ['주의', 'amber'], ['정보', 'blue']],
    );
  });
});
