import { useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { Box, Button, Card, Link, Stack, Typography } from '@mui/material';
import EditOutlinedIcon from '@mui/icons-material/EditOutlined';
import EastRoundedIcon from '@mui/icons-material/EastRounded';
import CopyButton from '@/components/common/CopyButton';
import InfoList from '@/components/common/InfoList';
import SectionTitle from '@/components/common/SectionTitle';
import StatusChip from '@/components/common/StatusChip';
import { updateTenant } from '@/api/tenants';
import type { AccountStatus, StatusLogItem, TenantBasic, TenantOverviewResponse, UpdateRequest } from '@/api/types';
import { useToast } from '@/store/ToastContext';
import { line, size, text, tones, type } from '@/theme/theme';
import { formatDateTime } from '@/utils/datetime';
import { eventPath } from '@/utils/event';
import { toFormErrors } from '@/utils/formErrors';
import { accountStatusMeta } from '@/utils/status';
import { isEmail } from '@/utils/validation';
import { provisioningStatusMeta, statusLogBadgeMeta } from '../tenantView';
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
function StatusLogRow({ log }: { log: StatusLogItem }) {
  const badge = statusLogBadgeMeta[log.badge];
  const event = log.relatedEvent;
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
        {formatDateTime(log.changedAt)}
      </Typography>
      <Stack direction="row" alignItems="center" spacing={0.75} sx={{ typography: 'body1' }}>
        {log.prevStatus && <StatusText status={log.prevStatus} />}
        <EastRoundedIcon sx={{ fontSize: size.iconSm, color: text.tertiary }} />
        <StatusText status={log.newStatus} strong />
      </Stack>
      {/* 사유 — [신청 완료]/[반려]/[종료] 배지 + [이벤트ID] 이벤트명, 최초 기록은 '테넌트 등록' */}
      {badge ? (
        <Stack direction="row" alignItems="center" spacing={1} sx={{ minWidth: 0, flexWrap: 'wrap', rowGap: 0.5 }}>
          <StatusChip label={badge.label} tone={badge.tone} />
          {event && (
            <>
              <Typography variant="body2" sx={{ color: text.secondary }}>
                [{event.eventId}]
              </Typography>
              <Link component={RouterLink} to={eventPath(event.eventId, event.applicationStatus)} variant="body1">
                {event.name}
              </Link>
            </>
          )}
        </Stack>
      ) : (
        <Typography variant="body1" sx={{ color: text.secondary }}>
          {log.reason ?? '테넌트 등록'}
        </Typography>
      )}
    </Box>
  );
}

/** OPS-06-1 테넌트 현황 - 상세(개요) */
export default function TenantOverview({ overview, onUpdated }: { overview: TenantOverviewResponse; onUpdated: (basic: TenantBasic) => void }) {
  const toast = useToast();
  const [edit, setEdit] = useState<EditFieldConfig | null>(null);
  const { basic, buckets, statusLogs } = overview;
  const meta = accountStatusMeta[basic.accountStatus];
  const provisioning = provisioningStatusMeta[basic.provisioningStatus];

  /** PATCH 한 항목. 실패하면 입력란에 보여 줄 문구를 돌려준다(이메일 중복 409 포함) */
  const save = (field: 'name' | 'contactName' | 'email', label: string) => async (value: string) => {
    try {
      const next = await updateTenant(basic.tenantId, { [field]: value } as UpdateRequest);
      onUpdated(next);
      setEdit(null);
      toast(`${label}이(가) 수정되었습니다.`);
      return null;
    } catch (e) {
      const errors = toFormErrors(e, [field] as const);
      return errors.fields[field] ?? errors.message;
    }
  };

  const items = [
    { label: '테넌트ID', value: <Box sx={type.mono}>{basic.tenantId}</Box> },
    {
      label: '테넌트명',
      value: basic.name,
      action: <EditButton onClick={() => setEdit({ title: '테넌트명 수정', label: '테넌트명', value: basic.name, onSave: save('name', '테넌트명') })} />,
    },
    {
      label: '담당자 이름',
      value: basic.contactName,
      action: (
        <EditButton
          onClick={() => setEdit({ title: '담당자 이름 수정', label: '담당자 이름', value: basic.contactName, onSave: save('contactName', '담당자 이름') })}
        />
      ),
    },
    {
      label: '계정ID',
      value: basic.email,
      action: (
        <EditButton
          onClick={() =>
            setEdit({
              title: '계정ID 수정',
              label: '계정ID (이메일)',
              type: 'email',
              value: basic.email,
              helperText: '로그인에 사용하는 이메일입니다. 이미 등록된 이메일은 사용할 수 없습니다.',
              // 중복은 저장할 때 서버가 409로 알려 준다
              validate: (v) => (isEmail(v) ? null : '이메일 형식이 올바르지 않습니다.'),
              onSave: save('email', '계정ID'),
            })
          }
        />
      ),
    },
    // 계정상태 · 환경 준비는 시스템이 자동 전환 — 배지만 표시
    { label: '계정상태', value: <StatusChip label={meta.label} tone={meta.tone} /> },
    ...(provisioning ? [{ label: '환경 준비', value: <StatusChip label={provisioning.label} tone={provisioning.tone} /> }] : []),
    { label: '가입일', value: formatDateTime(basic.createdAt) },
  ];

  const s3 = [
    { label: '콘텐츠 버킷', value: <Box sx={type.monoSm}>{buckets.content}</Box>, action: <CopyButton value={buckets.content} /> },
    { label: '로그 버킷', value: <Box sx={type.monoSm}>{buckets.auditLog}</Box>, action: <CopyButton value={buckets.auditLog} /> },
  ];

  return (
    <>
      <Box sx={{ display: 'grid', gap: 3.5, gridTemplateColumns: { xs: '1fr', lg: '1fr 1.15fr' }, alignItems: 'start' }}>
        <Stack spacing={3.5}>
          <Card sx={{ p: 3 }}>
            <SectionTitle>기본 정보</SectionTitle>
            <InfoList items={items} />
          </Card>
          <Card sx={{ p: 3 }}>
            <SectionTitle>S3 버킷</SectionTitle>
            <InfoList items={s3} />
          </Card>
        </Stack>

        <Card sx={{ p: 3 }}>
          <SectionTitle>계정 상태 로그</SectionTitle>
          {statusLogs.map((log, i) => (
            <StatusLogRow key={`${log.changedAt}-${i}`} log={log} />
          ))}
        </Card>
      </Box>

      <EditFieldDialog config={edit} onClose={() => setEdit(null)} />
    </>
  );
}
