import { createContext, useContext } from 'react';

/**
 * 고정 사이드바를 보이는 최소 폭(MUI breakpoint). 이보다 좁으면 사이드바를 숨기고 햄버거 + 오버레이로 연다.
 * 페이지 격자는 화면 폭 기준(md 900 · lg 1200)으로 칸 수를 정한다. lg(1200)부터 사이드바(264px)를 두면
 * 본문이 936px(여백 빼고 880px) 이상이라 md 배치가 가정하는 폭(900px 안팎)이 유지된다.
 */
export const SIDEBAR_BREAKPOINT = 'lg';

/** 오버레이 메뉴(Drawer) id — 햄버거의 aria-controls */
export const NAV_DRAWER_ID = 'console-nav';

interface LayoutValue {
  openNav: () => void;
  /** 오버레이 메뉴가 열려 있는지(좁은 화면) */
  navOpen: boolean;
  /** 상단바 영역 — PageHeader가 portal로 렌더링한다 */
  topbar: HTMLElement | null;
}

export const LayoutContext = createContext<LayoutValue>({ openNav: () => {}, navOpen: false, topbar: null });

export const useLayout = () => useContext(LayoutContext);
