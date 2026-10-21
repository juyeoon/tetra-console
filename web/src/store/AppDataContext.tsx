import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import { log, MOCK_KEY_MAIL_FAIL_EVENT_ID, mockEvents, mockPublicKey } from '@/mocks/events';
import { mockTenants } from '@/mocks/tenants';
import type { AccountLog, Tenant, TetraEvent } from '@/types';

/**
 * 화면 구현용 임시 데이터 저장소 — 이벤트 · 승인 화면만 쓴다(MR B에서 API로 바꾸고 삭제).
 * 테넌트 화면은 API(src/api/tenants.ts)로 옮겼다. tenants는 이벤트 화면의 테넌트명 표시용으로만 남는다.
 */
export interface ApproveResult {
  /** 승인 응답에 포함되는 개인키 메일 발송 결과 */
  keyMailSent: boolean;
}

interface AppDataValue {
  events: TetraEvent[];
  tenants: Tenant[];
  getEvent: (id: number) => TetraEvent | undefined;
  getTenant: (id: string) => Tenant | undefined;
  approveEvent: (id: number) => Promise<ApproveResult>;
  rejectEvent: (id: number, reason: string) => void;
}

const AppDataContext = createContext<AppDataValue | null>(null);

const pad = (n: number) => String(n).padStart(2, '0');
const now = () => {
  const d = new Date();
  return `${d.getFullYear()}.${pad(d.getMonth() + 1)}.${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
};
const randomId = (len: number) => Array.from({ length: len }, () => 'abcdefghijklmnopqrstuvwxyz0123456789'[Math.floor(Math.random() * 36)]).join('');
const delay = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** 계정을 활성으로 유지시키는 이벤트 — 승인 대기 중, 또는 승인 후 오픈 예정·진행 중 */
const keepsActive = (e: TetraEvent) => e.applicationStatus === 'APPLIED' || (e.applicationStatus === 'APPROVED' && (e.eventStatus === 'SCHEDULED' || e.eventStatus === 'OPEN'));

export function AppDataProvider({ children }: { children: ReactNode }) {
  const [events, setEvents] = useState<TetraEvent[]>(mockEvents);
  const [tenants, setTenants] = useState<Tenant[]>(mockTenants);

  const getEvent = useCallback((id: number) => events.find((e) => e.id === id), [events]);
  const getTenant = useCallback((id: string) => tenants.find((t) => t.id === id), [tenants]);

  const pushAccountLog = useCallback((tenantId: string, entry: AccountLog) => {
    setTenants((prev) => prev.map((t) => (t.id === tenantId ? { ...t, status: entry.to, accountLogs: [entry, ...t.accountLogs] } : t)));
  }, []);

  // 승인 즉시 신청상태 승인됨 / 호스팅상태 배포 대기 / 이벤트상태 오픈 예정, slug·키 쌍 발급
  const approveEvent = useCallback(async (id: number): Promise<ApproveResult> => {
    await delay(400);
    const at = now();
    setEvents((prev) =>
      prev.map((e) =>
        e.id === id
          ? {
              ...e,
              applicationStatus: 'APPROVED',
              processedAt: at,
              hostingStatus: 'PENDING',
              eventStatus: 'SCHEDULED',
              slug: randomId(8),
              publicKey: mockPublicKey(randomId(32)),
              logs: [...e.logs, log.approved(at)],
            }
          : e,
      ),
    );
    return { keyMailSent: id !== MOCK_KEY_MAIL_FAIL_EVENT_ID };
  }, []);

  // 반려 시, 진행 중인 다른 이벤트가 없으면 계정 비활성 전환
  const rejectEvent = useCallback(
    (id: number, reason: string) => {
      const at = now();
      const target = events.find((e) => e.id === id);
      setEvents((prev) =>
        prev.map((e) => (e.id === id ? { ...e, applicationStatus: 'REJECTED', processedAt: at, rejectReason: reason, logs: [...e.logs, log.rejected(at, reason)] } : e)),
      );
      if (!target) return;
      const tenant = tenants.find((t) => t.id === target.tenantId);
      const othersActive = events.some((e) => e.tenantId === target.tenantId && e.id !== id && keepsActive(e));
      if (tenant?.status === 'ACTIVE' && !othersActive) {
        pushAccountLog(target.tenantId, { at, from: 'ACTIVE', to: 'INACTIVE', reason: 'REJECTED', eventId: id, eventName: target.name });
      }
    },
    [events, tenants, pushAccountLog],
  );

  const value = useMemo(
    () => ({ events, tenants, getEvent, getTenant, approveEvent, rejectEvent }),
    [events, tenants, getEvent, getTenant, approveEvent, rejectEvent],
  );

  return <AppDataContext.Provider value={value}>{children}</AppDataContext.Provider>;
}

export function useAppData() {
  const ctx = useContext(AppDataContext);
  if (!ctx) throw new Error('useAppData must be used within AppDataProvider');
  return ctx;
}
