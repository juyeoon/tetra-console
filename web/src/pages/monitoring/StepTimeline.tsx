import { Box, Typography } from '@mui/material';
import { color, font, line, text, tones } from '@/theme/theme';
import type { StepView } from './trafficMonitoringView';

const DOT = 12;

const reached = (s: StepView | undefined) => s != null && (s.state === 'DONE' || s.state === 'ACTIVE' || s.state === 'FAILED');

/** 두 단계 사이 선: 뒤 단계에 닿았으면 실선(실패면 빨강), 아니면 점선 */
function segment(next: StepView | undefined) {
  if (!next) return 'transparent';
  return reached(next) ? `2px solid ${next.state === 'FAILED' ? tones.red.dot : color.navy}` : `2px dashed ${line.strong}`;
}

function dotStyle(state: StepView['state']) {
  switch (state) {
    case 'DONE':
      return { bgcolor: color.navy, border: `2px solid ${color.navy}` };
    case 'ACTIVE':
      return { bgcolor: color.white, border: `3px solid ${color.navy}` };
    case 'FAILED':
      return { bgcolor: tones.red.dot, border: `2px solid ${tones.red.dot}` };
    default:
      return { bgcolor: color.white, border: `2px solid ${line.strong}` };
  }
}

/** C. 진행 단계 7개: 승인 → 호스팅 시작 → 준비(T−90) → 입장(T−60) → 시작 → 종료 → 집계. 아래에 계획/실제 시각 · 걸린 시간 */
export default function StepTimeline({ steps }: { steps: StepView[] }) {
  return (
    <Box sx={{ overflowX: 'auto', pb: 0.5 }}>
      <Box sx={{ display: 'grid', gridTemplateColumns: `repeat(${steps.length || 7}, minmax(96px, 1fr))`, minWidth: 672 }}>
        {steps.map((s, i) => (
          <Box key={s.key} sx={{ textAlign: 'center', minWidth: 0 }}>
            <Box sx={{ position: 'relative', height: DOT + 8, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              {/* 앞 단계에서 오는 선 · 다음 단계로 가는 선 */}
              <Box sx={{ position: 'absolute', left: 0, right: '50%', top: '50%', borderTop: i === 0 ? 'none' : segment(s) }} />
              <Box sx={{ position: 'absolute', left: '50%', right: 0, top: '50%', borderTop: segment(steps[i + 1]) }} />
              <Box sx={{ position: 'relative', width: DOT, height: DOT, borderRadius: '50%', boxSizing: 'border-box', ...dotStyle(s.state) }} />
            </Box>
            <Typography variant="body2" sx={{ mt: 0.5, fontWeight: s.state === 'ACTIVE' ? font.weight.bold : font.weight.medium }}>
              {s.label}
            </Typography>
            <Typography variant="caption" component="p" sx={{ color: s.state === 'FAILED' ? tones.red.fg : text.tertiary }}>
              {s.sub}
            </Typography>
          </Box>
        ))}
      </Box>
    </Box>
  );
}
