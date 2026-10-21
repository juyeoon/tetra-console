import { useMemo } from 'react';
import { ApiError } from '@/api/client';
import { fetchEventDetail } from '@/api/events';
import { useApiQuery } from '@/hooks/useApiQuery';
import { toEventView } from './eventView';

/** 이벤트 상세(신청 · 진행 공용). eventId가 숫자가 아니면 부르지 않고 없는 이벤트로 본다 */
export function useEventDetail(rawEventId: string | undefined) {
  const eventId = Number(rawEventId);
  const valid = Number.isInteger(eventId) && eventId > 0;
  const query = useApiQuery(
    (signal) => (valid ? fetchEventDetail(eventId, signal) : Promise.reject(new ApiError(404, 'EVENT_NOT_FOUND', '이벤트를 찾을 수 없습니다.'))),
    [eventId, valid],
  );
  const event = useMemo(() => (query.data ? toEventView(query.data) : undefined), [query.data]);
  const notFound = query.error instanceof ApiError && query.error.status === 404;
  return { eventId, event, notFound, loading: query.loading, error: query.error, reload: query.reload };
}
