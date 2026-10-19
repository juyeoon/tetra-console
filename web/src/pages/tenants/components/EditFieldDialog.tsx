import { useEffect, useState } from 'react';
import { Button, Dialog, DialogActions, DialogContent, DialogTitle, TextField } from '@mui/material';
import FormField from '@/components/common/FormField';
import { size } from '@/theme/theme';

export interface EditFieldConfig {
  title: string;
  label: string;
  value: string;
  helperText?: string;
  type?: 'text' | 'email';
  /** 오류 메시지를 반환하면 저장 불가 */
  validate?: (value: string) => string | null;
  onSave: (value: string) => void;
}

/** OPS-06-M3 — 테넌트명 / 담당자 이름 / 계정ID 공용 수정 모달 */
export default function EditFieldDialog({ config, onClose }: { config: EditFieldConfig | null; onClose: () => void }) {
  const [value, setValue] = useState('');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (config) {
      setValue(config.value);
      setError(null);
    }
  }, [config]);

  const save = () => {
    const trimmed = value.trim();
    const message = !trimmed ? `${config?.label}을(를) 입력해주세요.` : (config?.validate?.(trimmed) ?? null);
    if (message) {
      setError(message);
      return;
    }
    config?.onSave(trimmed);
  };

  return (
    <Dialog open={!!config} onClose={onClose} slotProps={{ paper: { sx: { width: size.modalSm, maxWidth: 'calc(100% - 32px)' } } }}>
      <DialogTitle>{config?.title}</DialogTitle>
      <DialogContent sx={{ pt: '8px !important' }}>
        <FormField label={config?.label ?? ''} htmlFor="edit-field">
          <TextField
            id="edit-field"
            autoFocus
            fullWidth
            type={config?.type ?? 'text'}
            value={value}
            onChange={(e) => {
              setValue(e.target.value);
              setError(null);
            }}
            onKeyDown={(e) => e.key === 'Enter' && save()}
            error={!!error}
            helperText={error ?? config?.helperText}
          />
        </FormField>
      </DialogContent>
      <DialogActions>
        <Button variant="outlined" onClick={onClose}>
          취소
        </Button>
        <Button variant="contained" onClick={save} disabled={value.trim() === config?.value}>
          저장
        </Button>
      </DialogActions>
    </Dialog>
  );
}
