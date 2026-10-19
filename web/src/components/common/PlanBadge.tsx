import { Box } from '@mui/material';
import { color, font, ink, radius, size, text, type } from '@/theme/theme';
import type { Plan } from '@/types';

const planStyle: Record<Plan, { bg: string; fg: string }> = {
  S: { bg: ink[5], fg: text.primary },
  M: { bg: color.periTint, fg: color.navy },
  L: { bg: color.navy, fg: color.white },
};

export default function PlanBadge({ plan }: { plan: Plan }) {
  const s = planStyle[plan];
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
