import { useLayoutEffect, useRef, useState } from 'react';

/** 요소 폭(px). 크기가 바뀌면 다시 잰다(ResizeObserver). 아직 재기 전에는 0 — OPS-05 그래프 x축 눈금 · OPS-04 타임라인 줄 배치 */
export function useWidth<T extends HTMLElement>() {
  const ref = useRef<T | null>(null);
  const [width, setWidth] = useState(0);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return undefined;
    setWidth(el.getBoundingClientRect().width);
    if (typeof ResizeObserver === 'undefined') return undefined;
    const observer = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width));
    observer.observe(el);
    return () => observer.disconnect();
  }, []);
  return [ref, width] as const;
}
