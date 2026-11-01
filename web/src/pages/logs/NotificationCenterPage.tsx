import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import PageHeader from '@/components/layout/PageHeader';
import { QueryError, QueryLoading } from '@/components/common/QueryStatus';
import { errorMessage } from '@/api/client';
import { fetchNotifications, markAllNotificationsRead, markNotificationRead } from '@/api/notifications';
import { useApiQuery } from '@/hooks/useApiQuery';
import { useToast } from '@/store/ToastContext';
import { useUnreadCount } from '@/store/UnreadCountContext';
import NotificationCenter from './components/NotificationCenter';
import { listQuery, pageToFix, paramsFromView, readOptimistically, viewFromParams, withFilters, type NotificationView } from './notificationView';

const BREADCRUMBS = ['로그', '알림센터'];

/**
 * OPS-08 알림센터 — 조건 · 페이지는 주소 쿼리에 둔다(새로고침 · 뒤로 가기에도 그대로, 바꿀 때 기록은 쌓지 않는다).
 * 읽음은 화면을 먼저 바꾸고, 실패하면 되돌린 뒤 토스트. 항목은 서버가 준 경로로 이동하는 링크다(가운데 클릭은 새 탭) —
 * 이동한 뒤 읽음이 실패해도 토스트는 뜬다(전역)
 */
export default function NotificationCenterPage() {
  const toast = useToast();
  const [params, setParams] = useSearchParams();
  const view = viewFromParams(params);
  const query = listQuery(view);
  const unread = useUnreadCount();
  // 목록 응답의 unreadCount · unreadUrgent로 사이드바 배지를 맞춘다(따로 부르지 않는다) — [모두 읽음] 뒤 다시 조회할 때도
  const list = useApiQuery(
    (signal) =>
      fetchNotifications(query, signal).then((page) => {
        unread.report({ count: page.unreadCount, urgent: page.unreadUrgent });
        return page;
      }),
    [query.toString()],
  );
  const [readingAll, setReadingAll] = useState(false);

  // 지금 주소에서 바꾼다 — 검색어가 늦게 반영돼도 그사이 바꾼 다른 조건을 덮지 않게
  const update = (change: (prev: NotificationView) => NotificationView) =>
    setParams((prev) => paramsFromView(change(viewFromParams(prev))), { replace: true });

  // 범위 밖 페이지(마지막 페이지를 다 읽은 뒤 다시 조회 · 주소를 직접 고침 등)면 마지막 페이지로
  useEffect(() => {
    const fix = list.data ? pageToFix(list.data) : null;
    if (fix) update((prev) => ({ ...prev, page: fix }));
  }, [list.data]);

  if (!list.data) {
    return (
      <>
        <PageHeader breadcrumbs={BREADCRUMBS} />
        {list.loading ? <QueryLoading /> : <QueryError error={list.error} onRetry={list.reload} />}
      </>
    );
  }

  // 항목을 누르면 이동(링크)과 함께 부른다 — 기다리지 않으므로 실패해도 이동은 그대로, 되돌리고 토스트만. 목록에서 빠지는 건 다음 조회 때,
  // 배지는 성공 직후 다시 받는다
  const read = (notificationId: number) => {
    void readOptimistically(
      notificationId,
      (change) => list.setData((prev) => prev && { ...prev, items: change(prev.items) }),
      (id) => markNotificationRead(id).then(unread.refresh),
      (e) => toast(errorMessage(e), 'error'),
    );
  };

  // 안 읽은 알림 전부(지금 페이지만이 아님) — 끝나면 지금 조건으로 다시 받는다(안 읽은 것만이면 빈 목록 · 건수 0)
  const readAll = async () => {
    setReadingAll(true);
    try {
      await markAllNotificationsRead();
      toast('모든 알림을 읽음으로 표시했습니다.');
      list.reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setReadingAll(false);
    }
  };

  return (
    <>
      <PageHeader breadcrumbs={BREADCRUMBS} />
      <NotificationCenter
        data={list.data}
        filters={view.filters}
        page={view.page}
        loading={list.loading}
        onFiltersChange={(change) => update((prev) => withFilters(prev, change))}
        onPageChange={(page) => {
          update((prev) => ({ ...prev, page }));
          window.scrollTo(0, 0);
        }}
        onRead={read}
        onReadAll={readAll}
        readingAll={readingAll}
      />
    </>
  );
}
