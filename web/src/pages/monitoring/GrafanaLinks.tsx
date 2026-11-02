import { Link, Stack, Tooltip } from '@mui/material';
import { font, text, tones } from '@/theme/theme';
import type { GrafanaLink } from './trafficMonitoringView';

/**
 * Grafana 바로 가기(새 탭) — 이벤트 구역 머리 · 플랫폼 카드 제목 줄. 홈 '알림센터 →'와 같은 글자 링크(밑줄은 올렸을 때),
 * 장애 대응만 빨강. hint(주소가 localhost = 포트 포워딩)가 있으면 툴팁으로 안내한다
 */
export default function GrafanaLinks({ links, hint }: { links: GrafanaLink[]; hint: string | null }) {
  if (links.length === 0) return null;
  return (
    <Stack direction="row" alignItems="center" spacing={1.5} sx={{ flexShrink: 0 }}>
      {links.map((l) => {
        const link = (
          <Link
            key={l.key}
            href={l.href}
            target="_blank"
            rel="noopener noreferrer"
            variant="body2"
            underline="hover"
            sx={{ color: l.danger ? tones.red.fg : text.secondary, fontWeight: font.weight.medium, whiteSpace: 'nowrap' }}
          >
            {l.label}
          </Link>
        );
        return hint ? (
          <Tooltip key={l.key} title={hint}>
            {link}
          </Tooltip>
        ) : (
          link
        );
      })}
    </Stack>
  );
}
