import { useState } from 'react';
import PageHeader from '@/components/layout/PageHeader';
import { QueryError, QueryLoading } from '@/components/common/QueryStatus';
import { errorMessage } from '@/api/client';
import { fetchNotifications, markAllNotificationsRead, markNotificationRead } from '@/api/notifications';
import { useApiQuery } from '@/hooks/useApiQuery';
import { useToast } from '@/store/ToastContext';
import NotificationCenter from './components/NotificationCenter';
import { withAllRead, withRead } from './notificationView';

const BREADCRUMBS = ['로그', '알림센터'];

/** OPS-08 알림센터 — 운영자 알림 전체(서버 최신순). 읽음은 화면을 먼저 바꾸고, 실패하면 되돌린 뒤 토스트 */
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

  const read = async (notificationId: number) => {
    query.setData((prev) => prev && { items: withRead(prev.items, notificationId, true) });
    try {
      await markNotificationRead(notificationId);
    } catch (e) {
      query.setData((prev) => prev && { items: withRead(prev.items, notificationId, false) });
      toast(errorMessage(e), 'error');
    }
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
