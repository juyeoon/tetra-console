import { useState } from 'react';
import { Alert, Box, Button, CircularProgress, Dialog, DialogActions, DialogContent, DialogTitle, Stack, TextField, Typography } from '@mui/material';
import FormField from '@/components/common/FormField';
import StatusChip from '@/components/common/StatusChip';
import { color, radius, size, text } from '@/theme/theme';
import type { ApplicationStatus, EventStatus, HostingStatus, TetraEvent } from '@/types';
import { formatPeriod } from '@/utils/format';
import { applicationStatusMeta, eventStatusMeta, hostingStatusMeta } from '@/utils/status';

const Row = ({ label, children }: { label: string; children: React.ReactNode }) => (
  <Stack direction="row" alignItems="center" spacing={2}>
    <Typography variant="body2" sx={{ width: size.infoLabelMobile, flexShrink: 0, color: text.tertiary }}>
      {label}
    </Typography>
    <Box sx={{ typography: 'body2' }}>{children}</Box>
  </Stack>
);

/** 대상 이벤트 카드 — 이벤트명 + 이벤트ID · 테넌트명 / 현재 상태 · 이벤트 기간 · 호스팅 기간 */
function EventTarget({ event, tenantName }: { event: TetraEvent; tenantName: string }) {
  const meta = applicationStatusMeta[event.applicationStatus];
  return (
    <Box sx={{ px: 2, py: 1.5, borderRadius: radius.lg, bgcolor: color.card }}>
      <Typography variant="subtitle1">{event.name}</Typography>
      <Typography variant="caption">
        {event.id} · {tenantName}
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

/** 승인 후 상태 요약 (개인키 메일 발송 실패 화면용) */
function ApprovedTarget({ event, tenantName }: { event: TetraEvent; tenantName: string }) {
  const rows: [string, { label: string; tone: Parameters<typeof StatusChip>[0]['tone'] }][] = [
    ['신청상태', applicationStatusMeta['APPROVED' as ApplicationStatus]],
    ['호스팅상태', hostingStatusMeta['PENDING' as HostingStatus]],
    ['이벤트상태', eventStatusMeta['SCHEDULED' as EventStatus]],
  ];
  return (
    <Box sx={{ px: 2, py: 1.5, borderRadius: radius.lg, bgcolor: color.card }}>
      <Typography variant="subtitle1">{event.name}</Typography>
      <Typography variant="caption">
        {event.id} · {tenantName}
      </Typography>
      <Stack spacing={0.75} sx={{ mt: 1.5 }}>
        {rows.map(([label, m]) => (
          <Row key={label} label={label}>
            <StatusChip label={m.label} tone={m.tone} />
          </Row>
        ))}
      </Stack>
    </Box>
  );
}

interface DialogProps {
  open: boolean;
  event: TetraEvent;
  tenantName: string;
  onClose: () => void;
}

/**
 * OPS-03-M1 승인 확인 모달.
 * 승인 응답에 개인키 메일 발송 결과가 포함되며, 실패 시 모달이 실패 안내 화면으로 바뀐다(확인을 눌러야 닫힘).
 */
export function ApproveDialog({ open, event, tenantName, onClose, onApprove }: DialogProps & { onApprove: () => Promise<{ keyMailSent: boolean }> }) {
  const [phase, setPhase] = useState<'confirm' | 'loading' | 'keyMailFailed'>('confirm');
  // 실패 화면에서는 승인 전 이벤트 정보를 그대로 보여주기 위해 보관
  const [snapshot, setSnapshot] = useState<TetraEvent | null>(null);

  const close = () => {
    setPhase('confirm');
    setSnapshot(null);
    onClose();
  };

  const approve = async () => {
    setSnapshot(event);
    setPhase('loading');
    const { keyMailSent } = await onApprove();
    if (keyMailSent) close();
    else setPhase('keyMailFailed');
  };

  if (phase === 'keyMailFailed' && snapshot) {
    return (
      <Dialog open={open} maxWidth="xs" fullWidth>
        <DialogTitle>이벤트 신청 승인</DialogTitle>
        <DialogContent>
          <Alert severity="error" sx={{ mb: 2 }}>
            승인되었지만 개인키 메일 발송에 실패했습니다. 키 재발급이 필요합니다.
          </Alert>
          <ApprovedTarget event={snapshot} tenantName={tenantName} />
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
        <Typography variant="body1" sx={{ mb: 2 }}>
          아래 신청 건을 승인하시겠습니까?
        </Typography>
        <EventTarget event={snapshot ?? event} tenantName={tenantName} />
      </DialogContent>
      <DialogActions>
        <Button variant="outlined" onClick={close} disabled={loading}>
          취소
        </Button>
        <Button variant="contained" color="success" onClick={approve} disabled={loading} startIcon={loading ? <CircularProgress size={14} color="inherit" /> : undefined}>
          승인하기
        </Button>
      </DialogActions>
    </Dialog>
  );
}

/** OPS-03-M2 반려 확인 모달 — 사유 필수, 미입력 시 반려하기 비활성 */
export function RejectDialog({ open, event, tenantName, onClose, onConfirm }: DialogProps & { onConfirm: (reason: string) => void }) {
  const [reason, setReason] = useState('');
  const empty = reason.trim().length === 0;

  const close = () => {
    setReason('');
    onClose();
  };

  return (
    <Dialog open={open} onClose={close} maxWidth="xs" fullWidth>
      <DialogTitle>이벤트 신청 반려</DialogTitle>
      <DialogContent>
        <Typography variant="body1" sx={{ mb: 2 }}>
          아래 신청 건을 반려하시겠습니까?
        </Typography>
        <EventTarget event={event} tenantName={tenantName} />
        <Box sx={{ mt: 2 }}>
          <FormField label="반려 사유" htmlFor="reject-reason" required>
            <TextField id="reject-reason" fullWidth multiline minRows={3} placeholder="반려 사유를 입력해주세요" value={reason} onChange={(e) => setReason(e.target.value)} />
          </FormField>
        </Box>
      </DialogContent>
      <DialogActions>
        <Button variant="outlined" onClick={close}>
          취소
        </Button>
        <Button
          variant="contained"
          color="error"
          disabled={empty}
          onClick={() => {
            onConfirm(reason.trim());
            setReason('');
          }}
        >
          반려하기
        </Button>
      </DialogActions>
    </Dialog>
  );
}
