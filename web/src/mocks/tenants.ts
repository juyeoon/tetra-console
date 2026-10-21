import type { Tenant } from '@/types';

/** 계정 상태 로그는 최신순. 상태가 바뀐 시점에만 기록된다 */
export const mockTenants: Tenant[] = [
  {
    id: 'k7f2m9qx4b1t',
    name: 'A사',
    contactName: '김지현',
    email: 'jane.kim@a-corp.com',
    rootDomain: 'a-corp.com',
    status: 'ACTIVE',
    joinedAt: '2026.03.02',
    accountLogs: [
      { at: '2026.09.15 11:21', from: 'INACTIVE', to: 'ACTIVE', reason: 'APPLIED', eventId: 1042, eventName: '9월 얼리버드 쿠폰' },
      { at: '2026.07.10 00:00', from: 'ACTIVE', to: 'INACTIVE', reason: 'ENDED', eventId: 890, eventName: '여름 시즌오프 쿠폰' },
      { at: '2026.06.20 10:01', from: 'INACTIVE', to: 'ACTIVE', reason: 'APPLIED', eventId: 890, eventName: '여름 시즌오프 쿠폰' },
      { at: '2026.03.02 10:15', to: 'INACTIVE' },
    ],
  },
  {
    id: 'p3n8w2c6j0ra',
    name: 'B사',
    contactName: '이서준',
    email: 'ops@b-mart.co.kr',
    rootDomain: 'b-mart.co.kr',
    status: 'ACTIVE',
    joinedAt: '2026.04.11',
    accountLogs: [
      { at: '2026.09.01 10:01', from: 'INACTIVE', to: 'ACTIVE', reason: 'APPLIED', eventId: 1036, eventName: '9월 주말 특가 쿠폰' },
      { at: '2026.04.11 14:20', to: 'INACTIVE' },
    ],
  },
  {
    id: 'x9d4h7t1m5qe',
    name: 'C사',
    contactName: '최유나',
    email: 'manager@c-store.com',
    rootDomain: 'c-store.com',
    status: 'ACTIVE',
    joinedAt: '2026.05.20',
    accountLogs: [
      { at: '2026.09.05 10:01', from: 'INACTIVE', to: 'ACTIVE', reason: 'APPLIED', eventId: 1038, eventName: '여름 신상품 쿠폰' },
      { at: '2026.05.20 09:40', to: 'INACTIVE' },
    ],
  },
  {
    id: 'b6r0y3k8v2nz',
    name: 'D사',
    contactName: '정하늘',
    email: 'biz@d-brand.net',
    rootDomain: 'd-brand.net',
    status: 'INACTIVE',
    joinedAt: '2026.02.14',
    accountLogs: [
      { at: '2026.09.11 09:15', from: 'ACTIVE', to: 'INACTIVE', reason: 'REJECTED', eventId: 1031, eventName: '여름 시즌오프 쿠폰' },
      { at: '2026.09.10 10:01', from: 'INACTIVE', to: 'ACTIVE', reason: 'APPLIED', eventId: 1031, eventName: '여름 시즌오프 쿠폰' },
      { at: '2026.08.31 00:00', from: 'ACTIVE', to: 'INACTIVE', reason: 'ENDED', eventId: 1021, eventName: '8월 썸머 페스타 쿠폰' },
      { at: '2026.08.01 10:01', from: 'INACTIVE', to: 'ACTIVE', reason: 'APPLIED', eventId: 1021, eventName: '8월 썸머 페스타 쿠폰' },
      { at: '2026.04.11 00:00', from: 'ACTIVE', to: 'INACTIVE', reason: 'ENDED', eventId: 1012, eventName: '봄맞이 클리어런스 쿠폰' },
      { at: '2026.03.20 10:01', from: 'INACTIVE', to: 'ACTIVE', reason: 'APPLIED', eventId: 1012, eventName: '봄맞이 클리어런스 쿠폰' },
      { at: '2026.02.14 11:05', to: 'INACTIVE' },
    ],
  },
];
