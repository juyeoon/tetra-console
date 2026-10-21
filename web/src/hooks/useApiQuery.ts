import { useCallback, useEffect, useState, type DependencyList } from 'react';

/**
 * 화면에 들어올 때 API를 한 번 부르고 결과를 들고 있는다(캐시 없음 — 목록은 들어올 때마다 새로 받는다).
 * deps가 바뀌거나 reload()하면 다시 부르고, 이전 요청은 취소한다.
 * 401은 client.ts가 로그인 화면으로 보내므로 여기서는 다루지 않는다.
 */
export interface ApiQuery<T> {
  data: T | undefined;
  loading: boolean;
  /** 마지막 요청의 오류(ApiError 등). 성공하면 undefined */
  error: unknown;
  reload: () => void;
  /** 수정 응답 등으로 다시 부르지 않고 바로 바꿀 때 */
  setData: (update: (prev: T | undefined) => T | undefined) => void;
}

export function useApiQuery<T>(fetcher: (signal: AbortSignal) => Promise<T>, deps: DependencyList): ApiQuery<T> {
  const [data, setDataState] = useState<T | undefined>(undefined);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(undefined);
  const [nonce, setNonce] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(undefined);
    fetcher(controller.signal).then(
      (next) => {
        setDataState(next);
        setLoading(false);
      },
      (e: unknown) => {
        if (controller.signal.aborted) return;
        setError(e);
        setLoading(false);
      },
    );
    return () => controller.abort();
    // fetcher는 매번 새 함수라 넣지 않고 deps로 대신한다
  }, [...deps, nonce]);

  const reload = useCallback(() => setNonce((n) => n + 1), []);
  const setData = useCallback((update: (prev: T | undefined) => T | undefined) => setDataState(update), []);

  return { data, loading, error, reload, setData };
}
