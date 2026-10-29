import { useState } from 'react';
import ExpandMoreRoundedIcon from '@mui/icons-material/ExpandMoreRounded';
import { Box, Button, ButtonBase, Collapse, Stack, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import { fetchEventExecutions } from '@/api/monitoring';
import { QueryLoading } from '@/components/common/QueryStatus';
import StatusChip from '@/components/common/StatusChip';
import TableCard from '@/components/common/TableCard';
import { usePolling } from '@/hooks/usePolling';
import { font, line, radius, text, tones, type } from '@/theme/theme';
import { EXECUTION_PANEL_TITLE, executionPanel, NO_EXECUTION_TEXT, stopExecutionPolling, type ExecutionView } from './executionView';
import { POLL_MS } from './trafficMonitoringView';

const Chevron = ({ open }: { open: boolean }) => (
  <ExpandMoreRoundedIcon fontSize="small" sx={{ transform: open ? 'rotate(180deg)' : 'none', transition: 'transform .15s', color: text.tertiary }} />
);

/**
 * C 진행 단계 아래 자동화 실행 상세(Step Functions — WF1-2 · WF2-1 · WF2-2 · WF4). 기본 접힘.
 * 펼쳤을 때만 부르고, 실행 중이 있을 때만 15초마다 다시 부른다(끝난 실행은 바뀌지 않음).
 */
export default function ExecutionPanel({ eventId, now }: { eventId: number; now: Date }) {
  const [open, setOpen] = useState(false);
  const { data, failed } = usePolling((signal) => fetchEventExecutions(eventId, signal), POLL_MS, {
    key: eventId,
    enabled: open,
    stopWhen: stopExecutionPolling,
  });
  const panel = executionPanel(data, failed, now);
  return (
    <Box sx={{ mt: 2 }}>
      <Button size="small" onClick={() => setOpen((v) => !v)} aria-expanded={open} endIcon={<Chevron open={open} />} sx={{ color: text.secondary, px: 1, ml: -1 }}>
        {EXECUTION_PANEL_TITLE}
      </Button>
      <Collapse in={open} unmountOnExit>
        <Box sx={{ mt: 1, border: `1px solid ${line.soft}`, borderRadius: radius.lg, px: 2, py: 1 }}>
          {panel.notice && (
            <Typography variant="caption" component="p" sx={{ color: tones[panel.notice.tone].fg, py: 1 }}>
              {panel.notice.text}
            </Typography>
          )}
          {panel.flows.length === 0 && !panel.notice && <QueryLoading minHeight={120} />}
          {panel.flows.map((flow, i) => (
            <Box key={flow.key} sx={{ py: 1.5, borderTop: i === 0 && !panel.notice ? 'none' : `1px solid ${line.soft}` }}>
              <Stack direction="row" alignItems="baseline" spacing={1}>
                <Typography variant="subtitle2">{flow.title}</Typography>
                {flow.executions.length === 0 && (
                  <Typography variant="caption" sx={{ color: text.tertiary }}>
                    {NO_EXECUTION_TEXT}
                  </Typography>
                )}
              </Stack>
              {flow.executions.map((e) => (
                <ExecutionRow key={e.key} view={e} />
              ))}
            </Box>
          ))}
        </Box>
      </Collapse>
    </Box>
  );
}

function ExecutionRow({ view }: { view: ExecutionView }) {
  const [open, setOpen] = useState(view.defaultOpen);
  return (
    <Box sx={{ mt: 1 }}>
      <ButtonBase
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        sx={{ width: '100%', justifyContent: 'flex-start', textAlign: 'left', borderRadius: radius.sm, py: 0.5, px: 0.5, '&:hover': { bgcolor: tones.grey.bg } }}
      >
        <Stack direction="row" alignItems="center" spacing={1} flexWrap="wrap" useFlexGap sx={{ minWidth: 0 }}>
          <Chevron open={open} />
          <StatusChip label={view.status.label} tone={view.status.tone} />
          <Typography component="span" sx={{ ...type.monoSm, color: text.secondary, overflowWrap: 'anywhere' }}>
            {view.name}
          </Typography>
          {view.badges.map((b) => (
            <StatusChip key={b} label={b} tone="grey" />
          ))}
          <Typography variant="caption" component="span" sx={{ color: text.tertiary, whiteSpace: 'nowrap' }}>
            {view.time}
          </Typography>
        </Stack>
      </ButtonBase>
      <Collapse in={open}>
        <Box sx={{ pl: 4, pt: 1 }}>
          {view.failure && <FailureBox failure={view.failure} />}
          {view.states.length > 0 && (
            <TableCard minWidth={560}>
              <TableHead>
                <TableRow>
                  <TableCell>단계</TableCell>
                  <TableCell sx={{ whiteSpace: 'nowrap' }}>시작~끝</TableCell>
                  <TableCell align="right">소요</TableCell>
                  <TableCell>비고</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {view.states.map((s) => (
                  <TableRow key={s.key}>
                    <TableCell sx={{ ...type.monoSm, color: s.failed ? tones.red.fg : text.primary, fontWeight: s.running ? font.weight.bold : undefined, overflowWrap: 'anywhere' }}>
                      {s.name}
                    </TableCell>
                    <TableCell sx={{ whiteSpace: 'nowrap', color: text.secondary }}>{s.time}</TableCell>
                    <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                      {s.duration}
                    </TableCell>
                    <TableCell sx={{ whiteSpace: 'nowrap', color: text.tertiary }}>{[s.badge, s.retries].filter(Boolean).join(' · ')}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </TableCard>
          )}
          {view.note && (
            <Typography variant="caption" component="p" sx={{ color: text.tertiary, mt: 1 }}>
              {view.note}
            </Typography>
          )}
        </Box>
      </Collapse>
    </Box>
  );
}

function FailureBox({ failure }: { failure: NonNullable<ExecutionView['failure']> }) {
  const [showCause, setShowCause] = useState(false);
  return (
    <Box sx={{ mb: 1.5, p: 2, borderRadius: radius.md, border: `1px solid ${tones.red.dot}`, bgcolor: tones.red.bg }}>
      <Typography variant="subtitle2" sx={{ color: tones.red.fg, overflowWrap: 'anywhere' }}>
        {failure.title}
      </Typography>
      {failure.reason && (
        <Typography variant="body2" sx={{ color: text.secondary, mt: 0.5, overflowWrap: 'anywhere' }}>
          {failure.reason}
        </Typography>
      )}
      {failure.cause && (
        <>
          <Button size="small" onClick={() => setShowCause((v) => !v)} sx={{ mt: 0.5, px: 0, minWidth: 0, color: text.tertiary }}>
            {showCause ? '원문 닫기' : '원문 보기'}
          </Button>
          {showCause && (
            <Typography component="pre" sx={{ ...type.monoSm, m: 0, mt: 0.5, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', color: text.secondary }}>
              {failure.cause}
            </Typography>
          )}
        </>
      )}
    </Box>
  );
}
