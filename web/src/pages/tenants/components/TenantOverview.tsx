import { useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { Box, Button, Card, Link, Stack, Typography } from '@mui/material';
import EditOutlinedIcon from '@mui/icons-material/EditOutlined';
import EastRoundedIcon from '@mui/icons-material/EastRounded';
import CopyButton from '@/components/common/CopyButton';
import InfoList from '@/components/common/InfoList';
import SectionTitle from '@/components/common/SectionTitle';
import StatusChip from '@/components/common/StatusChip';
import { s3Buckets } from '@/mocks/tenants';
import { useAppData } from '@/store/AppDataContext';
import { useToast } from '@/store/ToastContext';
import { line, size, text, tones, type } from '@/theme/theme';
import type { AccountLog, AccountStatus, Tenant } from '@/types';
import { eventDetailPath } from '@/utils/event';
import { accountLogReasonMeta, accountStatusMeta } from '@/utils/status';
import { isEmail } from '@/utils/validation';
import EditFieldDialog, { type EditFieldConfig } from './EditFieldDialog';

function EditButton({ onClick }: { onClick: () => void }) {
  return (
    <Button size="small" variant="outlined" startIcon={<EditOutlinedIcon sx={{ fontSize: `${size.iconSm} !important` }} />} onClick={onClick}>
      수정
    </Button>
  );
}

function StatusText({ status, strong }: { status: AccountStatus; strong?: boolean }) {
  return (
    <Box component="span" sx={{ color: status === 'ACTIVE' ? tones.green.fg : text.secondary, fontWeight: strong ? 600 : 400 }}>
      {accountStatusMeta[status].label}
    </Box>
  );
}

/** 계정 상태 로그 한 줄 — 전환 일시 / 이전 → 변경 상태 / 사유 */
function AccountLogRow({ log }: { log: AccountLog }) {
  const { getEvent } = useAppData();
  const event = log.eventId ? getEvent(log.eventId) : undefined;
  const reason = log.reason && accountLogReasonMeta[log.reason];
  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', sm: 'minmax(150px, auto) minmax(140px, auto) 1fr' },
        alignItems: 'center',
        gap: { xs: 0.5, sm: 2 },
        py: 1.5,
        borderBottom: `1px solid ${line.base}`,
        '&:last-of-type': { borderBottom: 0 },
      }}
    >
      <Typography variant="body2" sx={{ color: text.tertiary, fontVariantNumeric: 'tabular-nums' }}>
        {log.at}
      </Typography>
      <Stack direction="row" alignItems="center" spacing={0.75} sx={{ typography: 'body1' }}>
        {log.from && <StatusText status={log.from} />}
        <EastRoundedIcon sx={{ fontSize: size.iconSm, color: text.tertiary }} />
        <StatusText status={log.to} strong />
      </Stack>
      {/* 사유 — [신청 완료]/[반려]/[종료] 배지 + [이벤트ID] 이벤트명, 최초 기록은 '테넌트 등록' */}
      {reason ? (
        <Stack direction="row" alignItems="center" spacing={1} sx={{ minWidth: 0, flexWrap: 'wrap', rowGap: 0.5 }}>
          <StatusChip label={reason.label} tone={reason.tone} />
          <Typography variant="body2" sx={{ color: text.secondary }}>
            [{log.eventId}]
          </Typography>
          {event ? (
            <Link component={RouterLink} to={eventDetailPath(event)} variant="body1">
              {log.eventName}
            </Link>
          ) : (
            <Typography variant="body1">{log.eventName}</Typography>
          )}
        </Stack>
      ) : (
        <Typography variant="body1" sx={{ color: text.secondary }}>
          테넌트 등록
        </Typography>
      )}
    </Box>
  );
}

/** OPS-06-1 테넌트 현황 - 상세(개요) */
export default function TenantOverview({ tenant }: { tenant: Tenant }) {
  const { updateTenant, isEmailTaken } = useAppData();
  const toast = useToast();
  const [edit, setEdit] = useState<EditFieldConfig | null>(null);
  const meta = accountStatusMeta[tenant.status];
  const buckets = s3Buckets(tenant.id);

  const save = (patch: Parameters<typeof updateTenant>[1], label: string) => {
    updateTenant(tenant.id, patch);
    setEdit(null);
    toast(`${label}이(가) 수정되었습니다.`);
  };

  const basic = [
    { label: '테넌트ID', value: <Box sx={type.mono}>{tenant.id}</Box> },
    {
      label: '테넌트명',
      value: tenant.name,
      action: <EditButton onClick={() => setEdit({ title: '테넌트명 수정', label: '테넌트명', value: tenant.name, onSave: (v) => save({ name: v }, '테넌트명') })} />,
    },
    {
      label: '담당자 이름',
      value: tenant.contactName,
      action: (
        <EditButton
          onClick={() => setEdit({ title: '담당자 이름 수정', label: '담당자 이름', value: tenant.contactName, onSave: (v) => save({ contactName: v }, '담당자 이름') })}
        />
      ),
    },
    {
      label: '계정ID',
      value: tenant.email,
      action: (
        <EditButton
          onClick={() =>
            setEdit({
              title: '계정ID 수정',
              label: '계정ID (이메일)',
              type: 'email',
              value: tenant.email,
              helperText: '로그인에 사용하는 이메일입니다. 이미 등록된 이메일은 사용할 수 없습니다.',
              validate: (v) => {
                if (!isEmail(v)) return '이메일 형식이 올바르지 않습니다.';
                if (isEmailTaken(v, tenant.id)) return '이미 등록된 이메일입니다.';
                return null;
              },
              onSave: (v) => save({ email: v }, '계정ID'),
            })
          }
        />
      ),
    },
    // 계정상태는 시스템이 자동 전환 — 배지만 표시
    { label: '계정상태', value: <StatusChip label={meta.label} tone={meta.tone} /> },
    { label: '가입일', value: tenant.joinedAt },
  ];

  const s3 = [
    { label: '콘텐츠 버킷', value: <Box sx={type.monoSm}>{buckets.content}</Box>, action: <CopyButton value={buckets.content} /> },
    { label: '로그 버킷', value: <Box sx={type.monoSm}>{buckets.log}</Box>, action: <CopyButton value={buckets.log} /> },
  ];

  return (
    <>
      <Box sx={{ display: 'grid', gap: 3.5, gridTemplateColumns: { xs: '1fr', lg: '1fr 1.15fr' }, alignItems: 'start' }}>
        <Stack spacing={3.5}>
          <Card sx={{ p: 3 }}>
            <SectionTitle>기본 정보</SectionTitle>
            <InfoList items={basic} />
          </Card>
          <Card sx={{ p: 3 }}>
            <SectionTitle>S3 버킷</SectionTitle>
            <InfoList items={s3} />
          </Card>
        </Stack>

        <Card sx={{ p: 3 }}>
          <SectionTitle>계정 상태 로그</SectionTitle>
          {tenant.accountLogs.map((log, i) => (
            <AccountLogRow key={`${log.at}-${i}`} log={log} />
          ))}
        </Card>
      </Box>

      <EditFieldDialog config={edit} onClose={() => setEdit(null)} />
    </>
  );
}
