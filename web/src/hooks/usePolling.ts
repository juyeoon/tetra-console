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

export interface PollingOptions<T> {
  /** 바뀌면 데이터를 비우고 처음부터 다시 부른다(예: 고른 이벤트 · 그래프 범위). 원시 값으로 준다 */
  key?: string | number | null;
  /** false면 부르지 않는다(데이터도 비움) */
  enabled?: boolean;
  /** true를 돌려주면 그 응답 뒤로는 다시 부르지 않는다(예: 종료된 이벤트 — 값이 바뀌지 않음) */
  stopWhen?: (data: T) => boolean;
  /** 바뀌면 데이터는 그대로 두고 바로 다시 부른다(stopWhen으로 멈췄어도 다시 시작). key와 달리 비우지 않아 화면이 깜빡이지 않는다. 원시 값으로 준다 */
  reload?: string | number | null;
}

export function usePolling<T>(
  fetcher: (signal: AbortSignal) => Promise<T>,
  intervalMs: number,
  { key = null, enabled = true, stopWhen, reload = null }: PollingOptions<T> = {},
): Polling<T> {
  const [state, setState] = useState<{ key: string | number | null; data: T | undefined; failed: boolean }>({
    key,
    data: undefined,
    failed: false,
  });

  useEffect(() => {
    if (!enabled) return undefined;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let controller: AbortController | undefined;
    let stopped = false;
    let done = false;

    const schedule = () => {
      clearTimeout(timer);
      if (!stopped && !done && !document.hidden) timer = setTimeout(tick, intervalMs);
    };

    async function tick() {
      controller?.abort();
      const current = new AbortController();
      controller = current;
      try {
        const next = await fetcher(current.signal);
        if (current.signal.aborted) return;
        setState({ key, data: next, failed: false });
        if (stopWhen?.(next)) done = true;
      } catch {
        if (current.signal.aborted) return;
        setState((prev) => ({ key, data: prev.key === key ? prev.data : undefined, failed: true }));
      }
      schedule();
    }

    const onVisibility = () => {
      if (document.hidden) {
        clearTimeout(timer);
        controller?.abort();
      } else if (!done) {
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
    // fetcher · stopWhen은 매번 새 함수라 넣지 않는다(화면에 들어올 때 · key · reload가 바뀔 때 한 번 시작)
  }, [intervalMs, key, enabled, reload]);

  // key가 바뀐 직후(새 응답 전)에는 이전 key의 데이터를 보여 주지 않는다
  const current = state.key === key && enabled;
  return { data: current ? state.data : undefined, failed: current ? state.failed : false };
}
