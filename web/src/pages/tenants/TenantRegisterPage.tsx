import { useState, type FormEvent } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { Alert, Box, Button, Card, CircularProgress, Stack, TextField, Typography } from '@mui/material';
import CheckRoundedIcon from '@mui/icons-material/CheckRounded';
import PageHeader from '@/components/layout/PageHeader';
import FormField from '@/components/common/FormField';
import InfoList from '@/components/common/InfoList';
import SectionTitle from '@/components/common/SectionTitle';
import { useAppData, type RegisterResult } from '@/store/AppDataContext';
import { radius, size, tones } from '@/theme/theme';
import { isDomain, isEmail } from '@/utils/validation';

type EmailCheck = 'idle' | 'ok' | 'taken' | 'invalid';

/** OPS-07 상태 화면 — 등록 완료 (메일 발송 성공 / 실패). 재발송 버튼 없음 */
function RegisterDone({ result }: { result: RegisterResult }) {
  const { tenant, mailSent } = result;
  return (
    <Card sx={{ maxWidth: size.contentForm, mx: 'auto', p: { xs: 3, sm: 4 } }}>
      <Box sx={{ width: size.iconBoxLg, height: size.iconBoxLg, mb: 2, borderRadius: radius.lg, display: 'grid', placeItems: 'center', bgcolor: tones.green.bg, color: tones.green.fg }}>
        <CheckRoundedIcon sx={{ fontSize: size.iconLg }} />
      </Box>
      <Typography variant="h3">테넌트 등록이 완료되었습니다</Typography>

      {mailSent ? (
        <Alert severity="success" sx={{ mt: 2 }}>
          비밀번호 설정 메일을 보냈습니다.
        </Alert>
      ) : (
        <Alert severity="error" sx={{ mt: 2 }}>
          메일 발송에 실패했습니다. 수동 재발송이 필요합니다.
        </Alert>
      )}

      <Box sx={{ mt: 2 }}>
        <InfoList
          items={[
            { label: '테넌트명', value: tenant.name },
            { label: '담당자 이름', value: tenant.contactName },
            { label: '수신 이메일', value: tenant.email },
          ]}
        />
      </Box>

      <Stack direction="row" justifyContent="flex-end" sx={{ mt: 3 }}>
        <Button variant="contained" component={RouterLink} to="/tenants">
          테넌트 목록으로
        </Button>
      </Stack>
    </Card>
  );
}

/**
 * OPS-07 테넌트 등록
 * - 필수 4개 + 이메일 중복 확인 통과 시 등록 활성
 * - 등록 시 비밀번호 설정 메일을 동기 발송하고 결과를 완료 화면에 표시
 */
export default function TenantRegisterPage() {
  const { isEmailTaken, registerTenant } = useAppData();
  const [form, setForm] = useState({ name: '', email: '', rootDomain: '', contactName: '' });
  const [emailCheck, setEmailCheck] = useState<EmailCheck>('idle');
  const [domainTouched, setDomainTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<RegisterResult | null>(null);

  const set = (key: keyof typeof form) => (e: { target: { value: string } }) => {
    setForm((f) => ({ ...f, [key]: e.target.value }));
    // 중복 확인 후 이메일을 수정하면 확인 상태 초기화
    if (key === 'email') setEmailCheck('idle');
  };

  const checkEmail = () => {
    if (!isEmail(form.email)) setEmailCheck('invalid');
    else setEmailCheck(isEmailTaken(form.email) ? 'taken' : 'ok');
  };

  const domainInvalid = form.rootDomain.trim() !== '' && !isDomain(form.rootDomain);
  const canSubmit = !!form.name.trim() && !!form.contactName.trim() && isDomain(form.rootDomain) && emailCheck === 'ok' && !submitting;

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!canSubmit) return;
    setSubmitting(true);
    // TODO: 테넌트 등록 API 연동 (응답에 비밀번호 설정 메일 발송 결과 포함)
    const res = await registerTenant({ name: form.name.trim(), email: form.email.trim(), rootDomain: form.rootDomain.trim(), contactName: form.contactName.trim() });
    setSubmitting(false);
    setResult(res);
  };

  const emailHelper =
    emailCheck === 'ok' ? (
      <Box component="span" sx={{ color: tones.green.fg }}>
        사용 가능한 이메일입니다.
      </Box>
    ) : emailCheck === 'taken' ? (
      '이미 등록된 이메일입니다.'
    ) : emailCheck === 'invalid' ? (
      '이메일 형식으로 입력해주세요.'
    ) : undefined;

  return (
    <>
      <PageHeader breadcrumbs={['테넌트 관리', '테넌트 등록']} />
      {result ? (
        <RegisterDone result={result} />
      ) : (
        <Card component="form" onSubmit={handleSubmit} noValidate sx={{ maxWidth: size.contentForm, mx: 'auto', p: { xs: 3, sm: 4 } }}>
          <SectionTitle>기본 정보</SectionTitle>
          <Stack spacing={2.5}>
            <FormField label="테넌트명" htmlFor="tenant-name" required>
              <TextField id="tenant-name" fullWidth placeholder="예: E사" value={form.name} onChange={set('name')} />
            </FormField>

            <FormField label="이메일(계정ID)" htmlFor="tenant-email" required>
              <Stack direction="row" spacing={1} alignItems="flex-start">
                <TextField
                  id="tenant-email"
                  fullWidth
                  type="email"
                  placeholder="예: minho.park@e-corp.com"
                  value={form.email}
                  onChange={set('email')}
                  error={emailCheck === 'taken' || emailCheck === 'invalid'}
                  helperText={emailHelper}
                />
                <Button variant="outlined" onClick={checkEmail} disabled={!form.email.trim()} sx={{ flexShrink: 0, minHeight: size.control }}>
                  중복 확인
                </Button>
              </Stack>
            </FormField>

            <FormField label="회사 root 도메인" htmlFor="tenant-domain" required>
              <TextField
                id="tenant-domain"
                fullWidth
                placeholder="예: e-corp.com"
                value={form.rootDomain}
                onChange={set('rootDomain')}
                onBlur={() => setDomainTouched(true)}
                error={domainTouched && domainInvalid}
                helperText={domainTouched && domainInvalid ? '도메인 형식으로 입력해주세요. (예: e-corp.com)' : undefined}
              />
            </FormField>

            <FormField label="담당자 이름" htmlFor="tenant-contact" required>
              <TextField id="tenant-contact" fullWidth placeholder="예: 박민호" value={form.contactName} onChange={set('contactName')} />
            </FormField>
          </Stack>

          <Typography variant="caption" component="p" sx={{ mt: 1.5 }}>
            ※ 등록이 완료되면 입력한 이메일로 비밀번호 설정 메일이 발송됩니다.
          </Typography>

          <Stack direction="row" justifyContent="flex-end" sx={{ mt: 3 }}>
            <Button type="submit" variant="contained" disabled={!canSubmit} startIcon={submitting ? <CircularProgress size={14} color="inherit" /> : undefined}>
              {submitting ? '등록 중…' : '등록'}
            </Button>
          </Stack>
        </Card>
      )}
    </>
  );
}
