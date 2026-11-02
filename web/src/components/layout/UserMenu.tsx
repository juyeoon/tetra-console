import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Avatar, Box, ButtonBase, Divider, ListItemIcon, Menu, MenuItem, Typography } from '@mui/material';
import KeyboardArrowDownRoundedIcon from '@mui/icons-material/KeyboardArrowDownRounded';
import LogoutOutlinedIcon from '@mui/icons-material/LogoutOutlined';
import SettingsOutlinedIcon from '@mui/icons-material/SettingsOutlined';
import { useAuth, useOperator } from '@/store/AuthContext';
import { color, font, mono, radius, size, text, tones, type } from '@/theme/theme';

export default function UserMenu() {
  const navigate = useNavigate();
  const operator = useOperator();
  const { logout } = useAuth();
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);

  return (
    <>
      {/* 상단바(바탕 100) 안이라 hover는 200 */}
      <ButtonBase
        onClick={(e) => setAnchor(e.currentTarget)}
        sx={{ gap: 1, px: 1, py: 0.5, borderRadius: radius.md, '&:hover': { bgcolor: mono[200] } }}
      >
        <Avatar sx={{ width: size.avatarSm, height: size.avatarSm, bgcolor: mono[200], color: color.navy, ...type.caption, fontWeight: font.weight.semibold }}>{operator.name.slice(0, 1)}</Avatar>
        <Typography variant="body1" sx={{ display: { xs: 'none', sm: 'block' }, whiteSpace: 'nowrap', fontWeight: font.weight.medium }}>
          {operator.name}님
        </Typography>
        <KeyboardArrowDownRoundedIcon sx={{ fontSize: size.iconSm, color: text.tertiary }} />
      </ButtonBase>
      <Menu
        anchorEl={anchor}
        open={!!anchor}
        onClose={() => setAnchor(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
        slotProps={{ paper: { sx: { mt: 0.5, minWidth: 220 } } }}
      >
        <Box sx={{ px: 2, py: 1 }}>
          <Typography variant="subtitle1">{operator.name}</Typography>
          <Typography variant="caption">{operator.email}</Typography>
        </Box>
        <Divider sx={{ my: 0.5 }} />
        <MenuItem
          onClick={() => {
            setAnchor(null);
            navigate('/settings');
          }}
        >
          <ListItemIcon>
            <SettingsOutlinedIcon sx={{ fontSize: size.iconMd }} />
          </ListItemIcon>
          시스템 설정
        </MenuItem>
        <MenuItem
          onClick={() => {
            setAnchor(null);
            void logout();
          }}
          sx={{ color: tones.red.fg }}
        >
          <ListItemIcon sx={{ color: 'inherit' }}>
            <LogoutOutlinedIcon sx={{ fontSize: size.iconMd }} />
          </ListItemIcon>
          로그아웃
        </MenuItem>
      </Menu>
    </>
  );
}
