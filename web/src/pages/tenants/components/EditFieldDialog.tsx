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
  /** 저장 전 형식 확인. 오류 메시지를 반환하면 저장하지 않는다 */
  validate?: (value: string) => string | null;
  /** 저장(API). 실패하면 입력란에 보여 줄 오류 메시지를, 성공하면 null을 돌려준다 */
  onSave: (value: string) => Promise<string | null>;
}

/** OPS-06-M3 — 테넌트명 / 담당자 이름 / 계정ID 공용 수정 모달 */
export default function EditFieldDialog({ config, onClose }: { config: EditFieldConfig | null; onClose: () => void }) {
  const [value, setValue] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (config) {
      setValue(config.value);
      setError(null);
      setSaving(false);
    }
  }, [config]);

  const save = async () => {
    if (!config || saving) return;
    const trimmed = value.trim();
    const message = !trimmed ? `${config.label}을(를) 입력해주세요.` : (config.validate?.(trimmed) ?? null);
    if (message) {
      setError(message);
      return;
    }
    setSaving(true);
    const failure = await config.onSave(trimmed);
    setSaving(false);
    if (failure) setError(failure);
  };

  return (
    <Dialog open={!!config} onClose={saving ? undefined : onClose} slotProps={{ paper: { sx: { width: size.modalSm, maxWidth: 'calc(100% - 32px)' } } }}>
      <DialogTitle>{config?.title}</DialogTitle>
      <DialogContent sx={{ pt: '8px !important' }}>
        <FormField label={config?.label ?? ''} htmlFor="edit-field">
          <TextField
            id="edit-field"
            autoFocus
            fullWidth
            type={config?.type ?? 'text'}
            value={value}
            disabled={saving}
            onChange={(e) => {
              setValue(e.target.value);
              setError(null);
            }}
            onKeyDown={(e) => e.key === 'Enter' && void save()}
            error={!!error}
            helperText={error ?? config?.helperText}
          />
        </FormField>
      </DialogContent>
      <DialogActions>
        <Button variant="outlined" onClick={onClose} disabled={saving}>
          취소
        </Button>
        <Button variant="contained" onClick={() => void save()} loading={saving} disabled={value.trim() === config?.value}>
          저장
        </Button>
      </DialogActions>
    </Dialog>
  );
}
