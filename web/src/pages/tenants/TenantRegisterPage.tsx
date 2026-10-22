import { useState, type FormEvent } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { Alert, Box, Button, Card, CircularProgress, Stack, TextField, Typography } from '@mui/material';
import CheckRoundedIcon from '@mui/icons-material/CheckRounded';
import ErrorOutlineRoundedIcon from '@mui/icons-material/ErrorOutlineRounded';
import PageHeader from '@/components/layout/PageHeader';
import FormField from '@/components/common/FormField';
import InfoList from '@/components/common/InfoList';
import SectionTitle from '@/components/common/SectionTitle';
import { ApiError } from '@/api/client';
import { checkEmailAvailable, registerTenant } from '@/api/tenants';
import type { RegisterRequest, RegisterResponse } from '@/api/types';
import { radius, size, tones } from '@/theme/theme';
import { toFormErrors } from '@/utils/formErrors';
import { DOMAIN_ERROR, isDomain, isEmail, normalizeDomain } from '@/utils/validation';
import { registerOutcome } from './tenantView';

type EmailCheck = 'idle' | 'checking' | 'ok' | 'taken' | 'invalid';

const FORM_FIELDS = ['name', 'email', 'rootDomain', 'contactName'] as const;
type RegisterField = (typeof FORM_FIELDS)[number];

/** OPS-07 상태 화면 — 환경 준비 상태로 2가지 (tenantView.registerOutcome). 메일은 환경 준비가 끝나면 서버가 보낸다 */
function RegisterDone({ request, response }: { request: RegisterRequest; response: RegisterResponse }) {
  const outcome = registerOutcome(response.provisioningStatus, request.name, request.email);
  const t = tones[outcome.tone];
  return (
    <Card sx={{ maxWidth: size.contentForm, mx: 'auto', p: { xs: 3, sm: 4 } }}>
      <Box sx={{ width: size.iconBoxLg, height: size.iconBoxLg, mb: 2, borderRadius: radius.lg, display: 'grid', placeItems: 'center', bgcolor: t.bg, color: t.fg }}>
        {outcome.kind === 'done' ? <CheckRoundedIcon sx={{ fontSize: size.iconLg }} /> : <ErrorOutlineRoundedIcon sx={{ fontSize: size.iconLg }} />}
      </Box>
      <Typography variant="h3">{outcome.title}</Typography>

      <Alert severity={outcome.kind === 'done' ? 'info' : 'error'} sx={{ mt: 2 }}>
        {outcome.body}
      </Alert>

      <Box sx={{ mt: 2 }}>
        <InfoList
          items={[
            { label: '테넌트ID', value: response.tenantId },
            { label: '테넌트명', value: request.name },
            { label: '담당자 이름', value: request.contactName },
            { label: '수신 이메일', value: request.email },
          ]}
        />
      </Box>

      <Stack direction="row" justifyContent="flex-end" spacing={1} sx={{ mt: 3 }}>
        <Button variant="outlined" component={RouterLink} to={`/tenants/${response.tenantId}`}>
          테넌트 상세
        </Button>
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
 * - 등록 응답(환경 준비 상태 · 메일 발송 결과)을 완료 화면에 표시
 */
export default function TenantRegisterPage() {
  const [form, setForm] = useState<RegisterRequest>({ name: '', email: '', rootDomain: '', contactName: '' });
  const [emailCheck, setEmailCheck] = useState<EmailCheck>('idle');
  const [domainTouched, setDomainTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  // 서버가 돌려준 입력란별 오류(400 fields, 409 이메일 중복)와 입력란에 붙이지 못한 오류
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<RegisterField, string>>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [done, setDone] = useState<{ request: RegisterRequest; response: RegisterResponse } | null>(null);

  const set = (key: RegisterField) => (e: { target: { value: string } }) => {
    setForm((f) => ({ ...f, [key]: e.target.value }));
    setFieldErrors((errors) => ({ ...errors, [key]: undefined }));
    // 중복 확인 후 이메일을 수정하면 확인 상태 초기화
    if (key === 'email') setEmailCheck('idle');
  };

  const checkEmail = async () => {
    if (!isEmail(form.email)) {
      setEmailCheck('invalid');
      return;
    }
    setEmailCheck('checking');
    setFormError(null);
    try {
      setEmailCheck((await checkEmailAvailable(form.email)) ? 'ok' : 'taken');
    } catch (e) {
      const errors = toFormErrors(e, ['email'] as const);
      setEmailCheck('idle');
      if (errors.fields.email) setFieldErrors((f) => ({ ...f, email: errors.fields.email }));
      setFormError(errors.message);
    }
  };

  const domainInvalid = form.rootDomain.trim() !== '' && !isDomain(form.rootDomain);
  const canSubmit = !!form.name.trim() && !!form.contactName.trim() && isDomain(form.rootDomain) && emailCheck === 'ok' && !submitting;

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!canSubmit) return;
    const request: RegisterRequest = {
      name: form.name.trim(),
      email: form.email.trim(),
      // API와 같은 정규화(앞뒤 공백 제거 + 소문자)로 보낸다
      rootDomain: normalizeDomain(form.rootDomain),
      contactName: form.contactName.trim(),
    };
    setSubmitting(true);
    setFormError(null);
    try {
      setDone({ request, response: await registerTenant(request) });
    } catch (err) {
      const errors = toFormErrors(err, FORM_FIELDS);
      setFieldErrors(errors.fields);
      setFormError(errors.message);
      // 확인 뒤 다른 등록이 같은 이메일을 먼저 쓴 경우(409) — 다시 확인하게 한다
      if (err instanceof ApiError && err.code === 'EMAIL_DUPLICATED') setEmailCheck('taken');
    } finally {
      setSubmitting(false);
    }
  };

  const emailHelper =
    emailCheck === 'ok' ? (
      <Box component="span" sx={{ color: tones.green.fg }}>
        사용 가능한 이메일입니다.
      </Box>
    ) : emailCheck === 'taken' ? (
      (fieldErrors.email ?? '이미 등록된 이메일입니다.')
    ) : emailCheck === 'invalid' ? (
      '이메일 형식으로 입력해주세요.'
    ) : (
      fieldErrors.email
    );

  return (
    <>
      <PageHeader breadcrumbs={['테넌트 관리', '테넌트 등록']} />
      {done ? (
        <RegisterDone request={done.request} response={done.response} />
      ) : (
        <Card component="form" onSubmit={handleSubmit} noValidate sx={{ maxWidth: size.contentForm, mx: 'auto', p: { xs: 3, sm: 4 } }}>
          <SectionTitle>기본 정보</SectionTitle>
          {formError && (
            <Alert severity="error" sx={{ mb: 2 }}>
              {formError}
            </Alert>
          )}
          <Stack spacing={2.5}>
            <FormField label="테넌트명" htmlFor="tenant-name" required>
              <TextField
                id="tenant-name"
                fullWidth
                placeholder="예: E사"
                value={form.name}
                onChange={set('name')}
                error={!!fieldErrors.name}
                helperText={fieldErrors.name}
              />
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
                  error={emailCheck === 'taken' || emailCheck === 'invalid' || !!fieldErrors.email}
                  helperText={emailHelper}
                />
                <Button
                  variant="outlined"
                  onClick={() => void checkEmail()}
                  loading={emailCheck === 'checking'}
                  disabled={!form.email.trim()}
                  sx={{ flexShrink: 0, minHeight: size.control }}
                >
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
                error={(domainTouched && domainInvalid) || !!fieldErrors.rootDomain}
                helperText={domainTouched && domainInvalid ? DOMAIN_ERROR : fieldErrors.rootDomain}
              />
            </FormField>

            <FormField label="담당자 이름" htmlFor="tenant-contact" required>
              <TextField
                id="tenant-contact"
                fullWidth
                placeholder="예: 박민호"
                value={form.contactName}
                onChange={set('contactName')}
                error={!!fieldErrors.contactName}
                helperText={fieldErrors.contactName}
              />
            </FormField>
          </Stack>

          <Typography variant="caption" component="p" sx={{ mt: 1.5 }}>
            ※ 등록 후 환경 준비가 끝나면 입력한 이메일로 비밀번호 설정 메일이 발송됩니다.
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
