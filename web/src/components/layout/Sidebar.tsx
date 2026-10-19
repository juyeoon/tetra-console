import type { ReactNode } from 'react';
import { Link, NavLink, useLocation } from 'react-router-dom';
import { Box, ButtonBase, Typography } from '@mui/material';
import SpaceDashboardOutlinedIcon from '@mui/icons-material/SpaceDashboardOutlined';
import AssignmentOutlinedIcon from '@mui/icons-material/AssignmentOutlined';
import RocketLaunchOutlinedIcon from '@mui/icons-material/RocketLaunchOutlined';
import ShowChartOutlinedIcon from '@mui/icons-material/ShowChartOutlined';
import ApartmentOutlinedIcon from '@mui/icons-material/ApartmentOutlined';
import AddBusinessOutlinedIcon from '@mui/icons-material/AddBusinessOutlined';
import NotificationsNoneOutlinedIcon from '@mui/icons-material/NotificationsNoneOutlined';
import AssessmentOutlinedIcon from '@mui/icons-material/AssessmentOutlined';
import SettingsOutlinedIcon from '@mui/icons-material/SettingsOutlined';
import logoWhite from '@/assets/logo-white.png';
import CheckerMark from '@/components/common/CheckerMark';
import { color, font, layout, onNavy, radius, size, type } from '@/theme/theme';

interface NavItem {
  label: string;
  to: string;
  icon: ReactNode;
  /** NavLink 기본 매칭 외에 추가로 활성화할 조건 */
  isActive?: (pathname: string) => boolean;
  end?: boolean;
}

const NAV: { section?: string; items: NavItem[] }[] = [
  { items: [{ label: '대시보드 (홈)', to: '/', icon: <SpaceDashboardOutlinedIcon />, end: true }] },
  {
    section: '모니터링',
    items: [
      { label: '이벤트 신청 현황', to: '/events/applications', icon: <AssignmentOutlinedIcon /> },
      { label: '이벤트 진행 현황', to: '/events/progress', icon: <RocketLaunchOutlinedIcon /> },
      { label: '트래픽 모니터링', to: '/monitoring/traffic', icon: <ShowChartOutlinedIcon /> },
    ],
  },
  {
    section: '테넌트 관리',
    items: [
      { label: '테넌트 현황', to: '/tenants', icon: <ApartmentOutlinedIcon />, end: true, isActive: (p) => p.startsWith('/tenants/') && p !== '/tenants/new' },
      { label: '테넌트 등록', to: '/tenants/new', icon: <AddBusinessOutlinedIcon /> },
    ],
  },
  {
    section: '로그',
    items: [
      { label: '알림센터', to: '/logs/notifications', icon: <NotificationsNoneOutlinedIcon /> },
      { label: '리포트', to: '/logs/reports', icon: <AssessmentOutlinedIcon /> },
    ],
  },
  { section: '설정', items: [{ label: '시스템 설정', to: '/settings', icon: <SettingsOutlinedIcon /> }] },
];

/** 네이비 고정 사이드바 (접기 없음) — 로고 형식은 Portal과 같고, 활성 메뉴는 밝은 배경 + 페리윙클 바 */
export default function Sidebar({ onNavigate }: { onNavigate?: () => void }) {
  const { pathname } = useLocation();

  return (
    <Box sx={{ position: 'relative', width: layout.sidebarWidth, height: '100%', display: 'flex', flexDirection: 'column', bgcolor: color.navy, color: onNavy.primary }}>
      {/* 브랜드 영역 — 흰 로고 + CONSOLE (Portal 사이드바와 같은 형식). 클릭 시 홈 대시보드 */}
      <ButtonBase
        component={Link}
        to="/"
        onClick={onNavigate}
        aria-label="홈 대시보드로 이동"
        sx={{ justifyContent: 'flex-start', alignItems: 'flex-end', gap: 1, mx: 2, mt: 3, mb: 3, px: 1.5, py: 1, borderRadius: radius.md, '&:hover': { bgcolor: onNavy.hover } }}
      >
        <Box component="img" src={logoWhite} alt="TETRA" sx={{ height: 26, display: 'block' }} />
        <Typography component="span" sx={{ ...type.caption, fontFamily: font.heading, fontWeight: font.weight.bold, letterSpacing: font.letterSpacing.overline, color: color.peri, lineHeight: 1, pb: '2px' }}>
          CONSOLE
        </Typography>
      </ButtonBase>

      <Box component="nav" sx={{ flex: 1, overflowY: 'auto', px: 2, pb: 2 }}>
        {NAV.map((group, gi) => (
          <Box key={group.section ?? gi} sx={{ mb: 2 }}>
            {group.section && (
              <Typography variant="body1" sx={{ px: 1.5, py: 0.5, color: onNavy.tertiary }}>
                {group.section}
              </Typography>
            )}
            {group.items.map((item) => {
              const forcedActive = item.isActive?.(pathname) ?? false;
              return (
                <ButtonBase
                  key={item.to}
                  component={NavLink}
                  to={item.to}
                  end={item.end}
                  onClick={onNavigate}
                  className={forcedActive ? 'active' : undefined}
                  sx={{
                    width: '100%',
                    height: size.navItem,
                    justifyContent: 'flex-start',
                    gap: 1,
                    px: 1.5,
                    borderRadius: radius.lg,
                    color: onNavy.secondary,
                    ...type.body,
                    fontWeight: font.weight.medium,
                    position: 'relative',
                    transition: 'background-color .15s, color .15s',
                    '& svg': { fontSize: size.iconMd, color: onNavy.tertiary },
                    '&:hover': { bgcolor: onNavy.hover, color: onNavy.primary },
                    '&.active': {
                      bgcolor: onNavy.active,
                      color: onNavy.primary,
                      fontWeight: font.weight.semibold,
                      '& svg': { color: color.peri },
                      '&::before': {
                        content: '""',
                        position: 'absolute',
                        left: 0,
                        top: 10,
                        bottom: 10,
                        width: 4,
                        borderRadius: 2,
                        bgcolor: color.peri,
                      },
                    },
                  }}
                >
                  {item.icon}
                  {item.label}
                </ButtonBase>
              );
            })}
          </Box>
        ))}
      </Box>

      <CheckerMark corner="top-right" cell={10} base={color.peri} tone={color.navyDeep} sx={{ position: 'absolute', right: 0, bottom: 0, transform: 'scaleY(-1)', opacity: 0.5 }} />
    </Box>
  );
}
