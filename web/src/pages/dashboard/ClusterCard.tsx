import { Box } from '@mui/material';
import StatCard from '@/components/common/StatCard';
import StatusChip from '@/components/common/StatusChip';
import { text } from '@/theme/theme';
import { NO_EVENT_TEXT, type ClusterView } from './monitoringView';

/** 클러스터 사용량(전체) — CPU · 메모리 실사용률, 풀별 Ready 노드 수, api · redis Pod Ready / 전체 */
export default function ClusterCard({ view, delayed }: { view: ClusterView; delayed: boolean }) {
  return (
    <StatCard
      fill="peri"
      label={
        <>
          클러스터 사용량 (전체)
          {delayed && (
            <Box component="span" sx={{ display: 'inline-flex', verticalAlign: 'middle', ml: 1 }}>
              <StatusChip label="지연" tone="grey" />
            </Box>
          )}
        </>
      }
      value={`CPU ${view.cpu} · Mem ${view.memory}`}
      sub={
        <>
          <Box component="span" sx={{ display: 'block' }}>
            노드 app {view.appNodes} · redis {view.redisNodes} · system {view.systemNodes}
            {view.noEvent && (
              <Box component="span" sx={{ color: text.tertiary }}>
                {' '}
                ({NO_EVENT_TEXT})
              </Box>
            )}
          </Box>
          <Box component="span" sx={{ display: 'block' }}>
            Pod api {view.apiPods} · redis {view.redisPods}
          </Box>
        </>
      }
    />
  );
}
