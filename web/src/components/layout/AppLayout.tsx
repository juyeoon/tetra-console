import { useEffect, useMemo, useState } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { Box, Drawer, useMediaQuery, useTheme } from '@mui/material';
import { layout } from '@/theme/theme';
import { LayoutContext, NAV_DRAWER_ID, SIDEBAR_BREAKPOINT } from './LayoutContext';
import Sidebar from './Sidebar';

export default function AppLayout() {
  const theme = useTheme();
  // noSsr: 첫 렌더부터 실제 폭으로 판단(넓은 화면에서 사이드바가 한 박자 늦게 나타나지 않게)
  const desktop = useMediaQuery(theme.breakpoints.up(SIDEBAR_BREAKPOINT), { noSsr: true });
  const [mobileOpen, setMobileOpen] = useState(false);
  const [topbar, setTopbar] = useState<HTMLElement | null>(null);
  const { pathname } = useLocation();

  // 페이지가 바뀌면(메뉴 · 본문 링크 · 뒤로 가기) 맨 위로, 오버레이 메뉴는 닫는다
  useEffect(() => {
    window.scrollTo(0, 0);
    setMobileOpen(false);
  }, [pathname]);

  // 창을 넓혀 고정 사이드바로 바뀌면 오버레이 상태를 지운다(다시 좁혔을 때 저절로 열리지 않게)
  useEffect(() => {
    if (desktop) setMobileOpen(false);
  }, [desktop]);

  const layoutCtx = useMemo(() => ({ openNav: () => setMobileOpen(true), navOpen: mobileOpen, topbar }), [mobileOpen, topbar]);

  return (
    <LayoutContext.Provider value={layoutCtx}>
      <Box sx={{ display: 'flex', minHeight: '100vh' }}>
        {desktop ? (
          <Box component="aside" sx={{ width: layout.sidebarWidth, flexShrink: 0 }}>
            <Box sx={{ position: 'fixed', inset: '0 auto 0 0', width: layout.sidebarWidth }}>
              <Sidebar />
            </Box>
          </Box>
        ) : (
          // 좁은 화면: 본문 위 오버레이. 바깥(배경) 클릭 · ESC는 onClose, 메뉴 선택은 onNavigate로 닫힌다
          <Drawer id={NAV_DRAWER_ID} open={mobileOpen} onClose={() => setMobileOpen(false)} slotProps={{ paper: { sx: { border: 0 } } }}>
            <Sidebar onNavigate={() => setMobileOpen(false)} />
          </Drawer>
        )}
        <Box component="main" sx={{ flex: 1, minWidth: 0 }}>
          {/* 상단바 — 콘텐츠 폭과 무관하게 가로 전체를 채운다 (내용은 PageHeader가 채움) */}
          <Box ref={setTopbar} component="header" sx={{ bgcolor: layout.topbarBg, borderBottom: `1px solid ${layout.topbarBorder}` }} />
          <Box sx={{ maxWidth: layout.pageMaxWidth, mx: 'auto', px: { xs: layout.pagePaddingXMobile, md: layout.pagePaddingX }, pt: 3, pb: 6 }}>
            <Outlet />
          </Box>
        </Box>
      </Box>
    </LayoutContext.Provider>
  );
}
