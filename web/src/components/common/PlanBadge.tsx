import { Box } from '@mui/material';
import { color, font, mono, radius, size, text, type } from '@/theme/theme';
import type { Plan } from '@/types';

/** 플랜이 클수록 진하게 — S 100 · M 200 · L 700 · XL 900 */
const planStyle: Record<Plan, { bg: string; fg: string }> = {
  S: { bg: mono[100], fg: text.primary },
  M: { bg: mono[200], fg: text.primary },
  L: { bg: mono[700], fg: color.white },
  XL: { bg: mono[900], fg: color.white },
};

/** API의 trafficPlanCode는 문자열이라 모르는 값이면 S 모양으로 표시한다 */
export default function PlanBadge({ plan }: { plan: Plan | string }) {
  const s = planStyle[plan as Plan] ?? planStyle.S;
  return (
    <Box
      component="span"
      sx={{
        display: 'inline-grid',
        placeItems: 'center',
        width: size.planBadge,
        height: size.planBadge,
        borderRadius: radius.sm,
        bgcolor: s.bg,
        color: s.fg,
        ...type.caption,
        fontWeight: font.weight.semibold,
      }}
    >
      {plan}
    </Box>
  );
}
