import { useState } from 'react';
import PageHeader from '@/components/layout/PageHeader';
import { QueryError, QueryLoading } from '@/components/common/QueryStatus';
import { errorMessage } from '@/api/client';
import { fetchNotifications, markAllNotificationsRead, markNotificationRead } from '@/api/notifications';
import { useApiQuery } from '@/hooks/useApiQuery';
import { useToast } from '@/store/ToastContext';
import NotificationCenter from './components/NotificationCenter';
import { readOptimistically, withAllRead } from './notificationView';

const BREADCRUMBS = ['로그', '알림센터'];

/**
 * OPS-08 알림센터 — 운영자 알림 전체(서버 최신순). 읽음은 화면을 먼저 바꾸고, 실패하면 되돌린 뒤 토스트.
 * 항목은 서버가 준 경로로 이동하는 링크다(가운데 클릭은 새 탭). 이동한 뒤 읽음이 실패해도 토스트는 뜬다(전역)
 */
export default function NotificationCenterPage() {
  const toast = useToast();
  const query = useApiQuery((signal) => fetchNotifications(signal), []);
  const [readingAll, setReadingAll] = useState(false);

  if (!query.data) {
    return (
      <>
        <PageHeader breadcrumbs={BREADCRUMBS} />
        {query.loading ? <QueryLoading /> : <QueryError error={query.error} onRetry={query.reload} />}
      </>
    );
  }

  // 항목을 누르면 이동(링크)과 함께 부른다 — 기다리지 않으므로 실패해도 이동은 그대로, 되돌리고 토스트만
  const read = (notificationId: number) => {
    void readOptimistically(
      notificationId,
      (change) => query.setData((prev) => prev && { items: change(prev.items) }),
      markNotificationRead,
      (e) => toast(errorMessage(e), 'error'),
    );
  };

  const readAll = async () => {
    const unreadIds = query.data!.items.filter((n) => !n.read).map((n) => n.notificationId);
    setReadingAll(true);
    query.setData((prev) => prev && { items: withAllRead(prev.items) });
    try {
      await markAllNotificationsRead();
      toast('모든 알림을 읽음으로 표시했습니다.');
    } catch (e) {
      // 이번에 바꾼 것만 되돌린다
      query.setData((prev) => prev && { items: prev.items.map((n) => (unreadIds.includes(n.notificationId) ? { ...n, read: false } : n)) });
      toast(errorMessage(e), 'error');
    } finally {
      setReadingAll(false);
    }
  };

  return (
    <>
      <PageHeader breadcrumbs={BREADCRUMBS} />
      <NotificationCenter notifications={query.data.items} onRead={read} onReadAll={readAll} readingAll={readingAll} />
    </>
  );
}
