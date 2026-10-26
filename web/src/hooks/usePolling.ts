import { useEffect, useState } from 'react';

/**
 * 일정 간격으로 API를 다시 부른다. 탭이 숨겨지면 멈추고, 다시 보이면 즉시 1회 부른 뒤 간격을 이어 간다.
 * 실패해도 마지막 데이터는 그대로 두고 failed만 켠다(화면은 지난 값 + "지연").
 * 401은 client.ts가 로그인 화면으로 보내므로 여기서는 다루지 않는다.
 */
export interface Polling<T> {
  data: T | undefined;
  /** 마지막 요청이 실패함 */
  failed: boolean;
}

export function usePolling<T>(fetcher: (signal: AbortSignal) => Promise<T>, intervalMs: number): Polling<T> {
  const [data, setData] = useState<T | undefined>(undefined);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    let controller: AbortController | undefined;
    let stopped = false;

    const schedule = () => {
      clearTimeout(timer);
      if (!stopped && !document.hidden) timer = setTimeout(tick, intervalMs);
    };

    async function tick() {
      controller?.abort();
      const current = new AbortController();
      controller = current;
      try {
        const next = await fetcher(current.signal);
        if (current.signal.aborted) return;
        setData(next);
        setFailed(false);
      } catch {
        if (current.signal.aborted) return;
        setFailed(true);
      }
      schedule();
    }

    const onVisibility = () => {
      if (document.hidden) {
        clearTimeout(timer);
        controller?.abort();
      } else {
        void tick();
      }
    };

    void tick();
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      stopped = true;
      clearTimeout(timer);
      controller?.abort();
      document.removeEventListener('visibilitychange', onVisibility);
    };
    // fetcher는 매번 새 함수라 넣지 않는다(화면에 들어올 때 한 번 시작)
  }, [intervalMs]);

  return { data, failed };
}
