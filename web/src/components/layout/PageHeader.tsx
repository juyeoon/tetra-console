import { Fragment, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { Link as RouterLink } from 'react-router-dom';
import { Box, IconButton, Link, Stack, Typography } from '@mui/material';
import ArrowBackRoundedIcon from '@mui/icons-material/ArrowBackRounded';
import MenuRoundedIcon from '@mui/icons-material/MenuRounded';
import { layout, mono, size, text, type } from '@/theme/theme';
import { NAV_DRAWER_ID, SIDEBAR_BREAKPOINT, useLayout } from './LayoutContext';
import UserMenu from './UserMenu';

interface PageHeaderProps {
  /** 현재 페이지까지의 경로. title이 없으면 마지막 항목이 제목이 된다 */
  breadcrumbs: string[];
  back?: { to: string; label: string };
  /** 페이지 제목 (상세 화면의 이벤트명·테넌트명 등) */
  title?: ReactNode;
  /** 제목 오른쪽 영역 */
  actions?: ReactNode;
}

/**
 * 상단바 — 경로(작게) · 돌아가기 · 페이지 제목(h2) · 사용자 메뉴를 한 곳에 표시.
 * 레이아웃의 상단바 영역에 portal로 렌더링되어 가로 전체를 채운다. 제목은 한 번만 표시한다.
 */
export default function PageHeader({ breadcrumbs, back, title, actions }: PageHeaderProps) {
  const { openNav, navOpen, topbar } = useLayout();
  if (!topbar) return null;

  // 제목이 따로 없으면 마지막 경로가 제목 → 경로 줄에서는 빼서 중복 표시하지 않음
  const pageTitle = title ?? breadcrumbs[breadcrumbs.length - 1];
  const path = title ? breadcrumbs : breadcrumbs.slice(0, -1);

  return createPortal(
    <Box sx={{ maxWidth: layout.pageMaxWidth, mx: 'auto', px: { xs: layout.pagePaddingXMobile, md: layout.pagePaddingX }, py: layout.topbarPaddingY }}>
      <Stack direction="row" alignItems="flex-start" spacing={1}>
        {/* 햄버거 — 고정 사이드바가 없는 좁은 화면에서만(헤더 왼쪽). 상단바(바탕 100) 안이라 hover는 200 */}
        <IconButton onClick={openNav} sx={{ display: { [SIDEBAR_BREAKPOINT]: 'none' }, ml: -1, '&:hover': { bgcolor: mono[200] } }} aria-label="메뉴 열기" aria-controls={NAV_DRAWER_ID} aria-expanded={navOpen}>
          <MenuRoundedIcon />
        </IconButton>

        <Box sx={{ flex: 1, minWidth: 0 }}>
          {path.length > 0 && (
            <Stack component="nav" aria-label="breadcrumb" direction="row" alignItems="center" flexWrap="wrap" columnGap={0.75}>
              {path.map((crumb, i) => (
                <Fragment key={crumb}>
                  {i > 0 && (
                    <Typography variant="body2" sx={{ color: text.disabled }}>
                      /
                    </Typography>
                  )}
                  {/* 상단바 바탕이 100이라 흐린 글자는 secondary */}
                  <Typography variant="body2" sx={{ color: text.secondary, whiteSpace: 'nowrap' }}>
                    {crumb}
                  </Typography>
                </Fragment>
              ))}
            </Stack>
          )}
          {back && (
            <Link
              component={RouterLink}
              to={back.to}
              underline="none"
              sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, mt: 0.5, ...type.caption, color: text.secondary, '&:hover': { color: text.primary } }}
            >
              <ArrowBackRoundedIcon sx={{ fontSize: size.iconSm }} />
              {back.label}
            </Link>
          )}
          <Typography variant="h2" component="h1" sx={{ mt: path.length > 0 || back ? 0.5 : 0 }}>
            {pageTitle}
          </Typography>
        </Box>

        <Stack alignItems="flex-end" justifyContent="space-between" spacing={1.5} sx={{ alignSelf: 'stretch' }}>
          <UserMenu />
          {actions && <Box sx={{ display: { xs: 'none', md: 'block' } }}>{actions}</Box>}
        </Stack>
      </Stack>
      {/* 좁은 화면에서는 액션을 제목 아래로 */}
      {actions && <Box sx={{ display: { xs: 'block', md: 'none' }, mt: 1.5 }}>{actions}</Box>}
    </Box>,
    topbar,
  );
}
