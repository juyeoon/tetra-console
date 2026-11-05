import { useEffect, useState } from 'react';
import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, Stack, TextField, ToggleButton, ToggleButtonGroup, Typography } from '@mui/material';
import FormField from '@/components/common/FormField';
import { generateReport } from '@/api/reports';
import { color, font, mono, radius, shadow, size, text, type } from '@/theme/theme';
import {
  GENERATE_KINDS,
  generateErrorMessage,
  generateFieldErrors,
  kstToday,
  lastMonthRange,
  toGenerateRequest,
  validateGenerateForm,
  type GenerateField,
  type GenerateFieldErrors,
  type GenerateForm,
  type GenerateKind,
} from '../reportGenerate';

/** 종류 버튼 — 그룹 바탕 100 · 안쪽 3px · 테두리 없음, 선택 = 900 바탕 + 흰 글자(이벤트 타임라인 기간 버튼과 같음) */
const GROUP_SX = {
  p: '3px',
  bgcolor: mono[100],
  borderRadius: radius.lg,
  '& .MuiToggleButtonGroup-grouped': { m: 0, border: 0, borderRadius: radius.md },
} as const;

const KIND_SX = {
  flex: 1,
  height: size.controlSm,
  px: 1.5,
  py: 0,
  border: 0,
  borderRadius: radius.md,
  bgcolor: 'transparent',
  color: mono[700],
  ...type.caption,
  fontWeight: font.weight.medium,
  whiteSpace: 'nowrap',
  '&:hover': { bgcolor: mono[200] },
  '&.Mui-selected, &.Mui-selected:hover': { bgcolor: mono[900], color: color.white, fontWeight: font.weight.semibold },
  '&.Mui-focusVisible': { boxShadow: shadow.focus, zIndex: 1 },
} as const;

function initialForm(now: Date): GenerateForm {
  return { kind: 'OPERATOR_AUDIT', ...lastMonthRange(now), eventId: '' };
}

interface ReportGenerateDialogProps {
  open: boolean;
  onClose: () => void;
  /** 만들기 성공 — created = 새로 만들었으면 true, 같은 리포트를 새로 썼으면 false */
  onGenerated: (created: boolean) => void;
}

/**
 * OPS-09 리포트 만들기 모달 — 운영자 접속기록(기간, 기본 지난달) · 이벤트 결과(이벤트 ID).
 * 화면에서 먼저 검사하고(서버와 같은 규칙), 실패는 코드별 문구를 모달 안에 보여 준다. 요청 중에는 버튼 · 입력을 막는다.
 */
export default function ReportGenerateDialog({ open, onClose, onGenerated }: ReportGenerateDialogProps) {
  const [form, setForm] = useState<GenerateForm>(() => initialForm(new Date()));
  const [fieldErrors, setFieldErrors] = useState<GenerateFieldErrors>({});
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [today, setToday] = useState(() => kstToday(new Date()));

  // 열 때마다 기본값(지난달 1일 ~ 말일)으로
  useEffect(() => {
    if (!open) return;
    const now = new Date();
    setForm(initialForm(now));
    setToday(kstToday(now));
    setFieldErrors({});
    setError(null);
    setLoading(false);
  }, [open]);

  const kind = GENERATE_KINDS.find((k) => k.key === form.kind) ?? GENERATE_KINDS[0];

  const change = (field: GenerateField, value: string) => {
    setForm((f) => ({ ...f, [field]: value }));
    setFieldErrors((e) => ({ ...e, [field]: undefined }));
    setError(null);
  };

  const changeKind = (next: GenerateKind | null) => {
    if (!next || next === form.kind) return;
    setForm((f) => ({ ...f, kind: next }));
    setFieldErrors({});
    setError(null);
  };

  const submit = async () => {
    if (loading) return;
    const invalid = validateGenerateForm(form, today);
    if (Object.keys(invalid).length > 0) {
      setFieldErrors(invalid);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const result = await generateReport(toGenerateRequest(form));
      setLoading(false);
      onGenerated(result.created);
    } catch (e) {
      setLoading(false);
      setError(generateErrorMessage(e));
      setFieldErrors(generateFieldErrors(e));
    }
  };

  const dateField = (field: 'from' | 'to', label: string) => (
    <Box sx={{ flex: 1, minWidth: 0 }}>
      <FormField label={label} htmlFor={`report-${field}`} required>
        <TextField
          id={`report-${field}`}
          type="date"
          fullWidth
          value={form[field]}
          disabled={loading}
          onChange={(e) => change(field, e.target.value)}
          error={!!fieldErrors[field]}
          helperText={fieldErrors[field]}
          slotProps={{ htmlInput: { max: today } }}
        />
      </FormField>
    </Box>
  );

  return (
    <Dialog open={open} onClose={loading ? undefined : onClose} slotProps={{ paper: { sx: { width: size.modalSm, maxWidth: 'calc(100% - 32px)' } } }}>
      <DialogTitle>리포트 만들기</DialogTitle>
      <DialogContent sx={{ pt: '8px !important' }}>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        <Stack spacing={2.5}>
          <Box>
            <Typography variant="body1" sx={{ mb: 1, fontWeight: font.weight.medium }}>
              종류
            </Typography>
            <ToggleButtonGroup exclusive fullWidth size="small" value={form.kind} onChange={(_, v: GenerateKind | null) => changeKind(v)} disabled={loading} aria-label="리포트 종류" sx={GROUP_SX}>
              {GENERATE_KINDS.map((k) => (
                <ToggleButton key={k.key} value={k.key} sx={KIND_SX}>
                  {k.label}
                </ToggleButton>
              ))}
            </ToggleButtonGroup>
          </Box>

          {form.kind === 'OPERATOR_AUDIT' ? (
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
              {dateField('from', '시작일')}
              {dateField('to', '종료일')}
            </Stack>
          ) : (
            <FormField label="이벤트 ID" htmlFor="report-event-id" required>
              <TextField
                id="report-event-id"
                fullWidth
                autoFocus
                placeholder="예: 1043"
                value={form.eventId}
                disabled={loading}
                onChange={(e) => change('eventId', e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && void submit()}
                error={!!fieldErrors.eventId}
                helperText={fieldErrors.eventId}
                slotProps={{ htmlInput: { inputMode: 'numeric' } }}
              />
            </FormField>
          )}

          <Typography variant="body2" sx={{ color: text.tertiary }}>
            ※ {kind.hint}
            {form.kind === 'EVENT_RESULT' && (
              <>
                <br />※ 이벤트 결과 리포트는 테넌트 Portal 리포트 화면에 올라갑니다.
              </>
            )}
          </Typography>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button variant="outlined" onClick={onClose} disabled={loading}>
          취소
        </Button>
        <Button variant="contained" onClick={() => void submit()} loading={loading}>
          만들기
        </Button>
      </DialogActions>
    </Dialog>
  );
}
