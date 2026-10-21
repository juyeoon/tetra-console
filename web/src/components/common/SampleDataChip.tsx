import { Box } from '@mui/material';
import StatusChip from '@/components/common/StatusChip';

/** API가 아직 없어 목 데이터로 그리는 카드 표시 — 실제 숫자와 섞여 오해하지 않게 (대시보드 클러스터 · 트래픽 · 상위 테넌트) */
export default function SampleDataChip() {
  return (
    <Box component="span" sx={{ display: 'inline-flex', verticalAlign: 'middle', ml: 1 }}>
      <StatusChip label="예시 데이터" tone="amber" />
    </Box>
  );
}
