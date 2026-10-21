import { useState } from 'react';
import { Alert, Box, Button, CircularProgress, Dialog, DialogActions, DialogContent, DialogTitle, Stack, TextField, Typography } from '@mui/material';
import FormField from '@/components/common/FormField';
import StatusChip from '@/components/common/StatusChip';
import type { ApproveResponse } from '@/api/types';
import { color, radius, size, text } from '@/theme/theme';
import { formatPeriod } from '@/utils/format';
import { applicationStatusMeta, eventStatusMeta, hostingStatusMeta } from '@/utils/status';
import type { EventView } from '../eventView';

const Row = ({ label, children }: { label: string; children: React.ReactNode }) => (
  <Stack direction="row" alignItems="center" spacing={2}>
    <Typography variant="body2" sx={{ width: size.infoLabelMobile, flexShrink: 0, color: text.tertiary }}>
      {label}
    </Typography>
    <Box sx={{ typography: 'body2' }}>{children}</Box>
  </Stack>
);

/** 대상 이벤트 카드 — 이벤트명 + 이벤트ID · 테넌트명 / 현재 상태 · 이벤트 기간 · 호스팅 기간 */
function EventTarget({ event }: { event: EventView }) {
  const meta = applicationStatusMeta[event.applicationStatus];
  return (
    <Box sx={{ px: 2, py: 1.5, borderRadius: radius.lg, bgcolor: color.card }}>
      <Typography variant="subtitle1">{event.name}</Typography>
      <Typography variant="caption">
        {event.id} · {event.tenantName}
      </Typography>
      <Stack spacing={0.75} sx={{ mt: 1.5 }}>
        <Row label="현재 상태">
          <StatusChip label={meta.label} tone={meta.tone} />
        </Row>
        <Row label="이벤트 기간">{formatPeriod(event.eventPeriod.start, event.eventPeriod.end)}</Row>
        <Row label="호스팅 기간">{formatPeriod(event.hostingPeriod.start, event.hostingPeriod.end)}</Row>
      </Stack>
    </Box>
  );
}

/** 승인 후 상태 요약 (개인키 메일 발송 실패 화면용) — 승인 응답의 상태 그대로 */
function ApprovedTarget({ event, result }: { event: EventView; result: ApproveResponse }) {
  const rows = [
    { label: '신청상태', meta: applicationStatusMeta[result.applicationStatus] },
    { label: '호스팅상태', meta: result.hostingStatus ? hostingStatusMeta[result.hostingStatus] : null },
    { label: '이벤트상태', meta: result.eventStatus ? eventStatusMeta[result.eventStatus] : null },
  ];
  return (
    <Box sx={{ px: 2, py: 1.5, borderRadius: radius.lg, bgcolor: color.card }}>
      <Typography variant="subtitle1">{event.name}</Typography>
      <Typography variant="caption">
        {event.id} · {event.tenantName}
      </Typography>
      <Stack spacing={0.75} sx={{ mt: 1.5 }}>
        {rows.map(({ label, meta }) => (
          <Row key={label} label={label}>
            {meta ? <StatusChip label={meta.label} tone={meta.tone} /> : '-'}
          </Row>
        ))}
      </Stack>
    </Box>
  );
}

interface DialogProps {
  open: boolean;
  event: EventView;
  onClose: () => void;
}

/** 승인 · 반려 요청 결과. 실패면 대화상자에 보여 줄 서버 문구 */
export type DecisionOutcome<T> = { ok: true; value: T } | { ok: false; message: string };

/**
 * OPS-03-M1 승인 확인 모달.
 * - 개인키 메일 발송 실패(privateKeyMailSent=false)면 "개인키 메일 발송 실패" 상태 화면으로 바뀐다(확인을 눌러야 닫힘)
 * - 409(이미 처리됨 · 호스팅 기간 지남 · 환경 준비 전)는 서버 문구를 모달 안에 보여 준다
 */
export function ApproveDialog({ open, event, onClose, onApprove }: DialogProps & { onApprove: () => Promise<DecisionOutcome<ApproveResponse>> }) {
  const [phase, setPhase] = useState<'confirm' | 'loading' | 'keyMailFailed'>('confirm');
  const [error, setError] = useState<string | null>(null);
  // 실패 화면에서는 승인 전 이벤트 정보와 승인 응답을 그대로 보여 주기 위해 보관
  const [snapshot, setSnapshot] = useState<{ event: EventView; result: ApproveResponse } | null>(null);

  const close = () => {
    setPhase('confirm');
    setError(null);
    setSnapshot(null);
    onClose();
  };

  const approve = async () => {
    const target = event;
    setPhase('loading');
    setError(null);
    const outcome = await onApprove();
    if (!outcome.ok) {
      setPhase('confirm');
      setError(outcome.message);
      return;
    }
    if (outcome.value.privateKeyMailSent) {
      close();
    } else {
      setSnapshot({ event: target, result: outcome.value });
      setPhase('keyMailFailed');
    }
  };

  if (phase === 'keyMailFailed' && snapshot) {
    return (
      <Dialog open={open} maxWidth="xs" fullWidth>
        <DialogTitle>이벤트 신청 승인</DialogTitle>
        <DialogContent>
          <Alert severity="error" sx={{ mb: 2 }}>
            승인되었지만 개인키 메일 발송에 실패했습니다. 키 재발급이 필요합니다.
          </Alert>
          <ApprovedTarget event={snapshot.event} result={snapshot.result} />
        </DialogContent>
        <DialogActions>
          <Button variant="contained" onClick={close}>
            확인
          </Button>
        </DialogActions>
      </Dialog>
    );
  }

  const loading = phase === 'loading';
  return (
    <Dialog open={open} onClose={loading ? undefined : close} maxWidth="xs" fullWidth>
      <DialogTitle>이벤트 신청 승인</DialogTitle>
      <DialogContent>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        <Typography variant="body1" sx={{ mb: 2 }}>
          아래 신청 건을 승인하시겠습니까?
        </Typography>
        <EventTarget event={event} />
      </DialogContent>
      <DialogActions>
        <Button variant="outlined" onClick={close} disabled={loading}>
          {error ? '닫기' : '취소'}
        </Button>
        {/* 처리할 수 없는 상태가 된 신청(이미 처리됨 등)은 다시 승인하지 않게 버튼을 막는다 */}
        <Button
          variant="contained"
          color="success"
          onClick={() => void approve()}
          disabled={loading || event.applicationStatus !== 'APPLIED'}
          startIcon={loading ? <CircularProgress size={14} color="inherit" /> : undefined}
        >
          승인하기
        </Button>
      </DialogActions>
    </Dialog>
  );
}

/** OPS-03-M2 반려 확인 모달 — 사유 필수, 미입력 시 반려하기 비활성. 400 사유 오류는 입력란, 그 밖(409 등)은 모달 안 문구 */
export function RejectDialog({
  open,
  event,
  onClose,
  onReject,
}: DialogProps & { onReject: (reason: string) => Promise<{ ok: true } | { ok: false; message: string; reasonError?: string }> }) {
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [reasonError, setReasonError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const empty = reason.trim().length === 0;

  const close = () => {
    setReason('');
    setError(null);
    setReasonError(null);
    onClose();
  };

  const reject = async () => {
    setLoading(true);
    setError(null);
    setReasonError(null);
    const outcome = await onReject(reason.trim());
    setLoading(false);
    if (outcome.ok) {
      close();
      return;
    }
    if (outcome.reasonError) setReasonError(outcome.reasonError);
    else setError(outcome.message);
  };

  return (
    <Dialog open={open} onClose={loading ? undefined : close} maxWidth="xs" fullWidth>
      <DialogTitle>이벤트 신청 반려</DialogTitle>
      <DialogContent>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        <Typography variant="body1" sx={{ mb: 2 }}>
          아래 신청 건을 반려하시겠습니까?
        </Typography>
        <EventTarget event={event} />
        <Box sx={{ mt: 2 }}>
          <FormField label="반려 사유" htmlFor="reject-reason" required>
            <TextField
              id="reject-reason"
              fullWidth
              multiline
              minRows={3}
              placeholder="반려 사유를 입력해주세요"
              value={reason}
              disabled={loading}
              onChange={(e) => {
                setReason(e.target.value);
                setReasonError(null);
              }}
              error={!!reasonError}
              helperText={reasonError}
            />
          </FormField>
        </Box>
      </DialogContent>
      <DialogActions>
        <Button variant="outlined" onClick={close} disabled={loading}>
          {error ? '닫기' : '취소'}
        </Button>
        <Button
          variant="contained"
          color="error"
          disabled={empty || loading || event.applicationStatus !== 'APPLIED'}
          onClick={() => void reject()}
          startIcon={loading ? <CircularProgress size={14} color="inherit" /> : undefined}
        >
          반려하기
        </Button>
      </DialogActions>
    </Dialog>
  );
}
