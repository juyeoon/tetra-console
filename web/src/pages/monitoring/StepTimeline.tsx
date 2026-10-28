import { Fragment } from 'react';
import { Box, Typography } from '@mui/material';
import { color, font, line, text, tones } from '@/theme/theme';
import type { StepView } from './trafficMonitoringView';

const DOT = 12;

/** 칸 최소 폭. 이보다 좁으면 가로 스크롤. 아래 글자는 두 줄까지 줄바꿈해 잘리지 않는다('09:55 완료 · 2분 30초') */
const COL_MIN = 96;

const reached = (s: StepView | undefined) => s != null && (s.state === 'DONE' || s.state === 'ACTIVE' || s.state === 'FAILED');

/** 두 단계 사이 선: 뒤 단계에 닿았으면(끝남 · 진행 중 · 실패) 실선(실패면 빨강), 아직이면 점선 */
function segment(next: StepView | undefined) {
  if (!next) return 'transparent';
  return reached(next) ? `2px solid ${next.state === 'FAILED' ? tones.red.dot : color.navy}` : `2px dashed ${line.strong}`;
}

/** 점: 끝남 = 채운 남색, 진행 중 = 빈 남색(굵은 테두리), 실패 = 빨강, 아직 · 진행 안 됨 = 채운 회색 */
function dotStyle(state: StepView['state']) {
  switch (state) {
    case 'DONE':
      return { bgcolor: color.navy, border: `2px solid ${color.navy}` };
    case 'ACTIVE':
      return { bgcolor: color.white, border: `3px solid ${color.navy}` };
    case 'FAILED':
      return { bgcolor: tones.red.dot, border: `2px solid ${tones.red.dot}` };
    default:
      return { bgcolor: line.strong, border: `2px solid ${line.strong}` };
  }
}

/** C. 진행 단계 8개: 승인 → 배포 → 호스팅 시작 → 준비 → 입장 → 이벤트 진행 → 호스팅 종료 → 집계. 아래에 계획/실제 시각 · 걸린 시간 */
export default function StepTimeline({ steps }: { steps: StepView[] }) {
  return (
    <Box sx={{ overflowX: 'auto', pb: 0.5 }}>
      <Box sx={{ display: 'grid', gridTemplateColumns: `repeat(${steps.length || 8}, minmax(${COL_MIN}px, 1fr))`, minWidth: (steps.length || 8) * COL_MIN }}>
        {steps.map((s, i) => (
          <Box key={s.key} sx={{ textAlign: 'center', minWidth: 0 }}>
            <Box sx={{ position: 'relative', height: DOT + 8, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              {/* 앞 단계에서 오는 선 · 다음 단계로 가는 선 */}
              <Box sx={{ position: 'absolute', left: 0, right: '50%', top: '50%', borderTop: i === 0 ? 'none' : segment(s) }} />
              <Box sx={{ position: 'absolute', left: '50%', right: 0, top: '50%', borderTop: segment(steps[i + 1]) }} />
              <Box sx={{ position: 'relative', width: DOT, height: DOT, borderRadius: '50%', boxSizing: 'border-box', ...dotStyle(s.state) }} />
            </Box>
            <Typography variant="body2" noWrap sx={{ mt: 0.5, px: 0.5, fontWeight: s.state === 'ACTIVE' ? font.weight.bold : font.weight.medium }}>
              {s.label}
            </Typography>
            <Typography variant="caption" component="p" sx={{ px: 0.5, color: s.state === 'FAILED' ? tones.red.fg : text.tertiary, wordBreak: 'keep-all', overflowWrap: 'anywhere' }}>
              {/* ' · '로 나눈 덩어리(시각 · 걸린 시간)째 줄을 바꾼다 — '2분 30초'가 갈라지지 않게 */}
              {s.sub.split(' · ').map((part, j) => (
                <Fragment key={j}>
                  {j > 0 && ' '}
                  <Box component="span" sx={{ display: 'inline-block' }}>
                    {j > 0 ? `· ${part}` : part}
                  </Box>
                </Fragment>
              ))}
            </Typography>
          </Box>
        ))}
      </Box>
    </Box>
  );
}
