import type { ReactNode } from 'react';
import { Box, Card, Stack } from '@mui/material';
import CopyButton from '@/components/common/CopyButton';
import InfoList from '@/components/common/InfoList';
import PlanBadge from '@/components/common/PlanBadge';
import SectionTitle from '@/components/common/SectionTitle';
import StatusChip, { EmptyValue } from '@/components/common/StatusChip';
import { useAppData } from '@/store/AppDataContext';
import { type } from '@/theme/theme';
import type { TetraEvent } from '@/types';
import { publicKeyOf, subdomainOf } from '@/utils/event';
import { formatNumber, formatPeriod } from '@/utils/format';
import { applicationStatusMeta, eventStatusMeta, eventTypeLabel, hostingStatusMeta } from '@/utils/status';
import StatusTimeline from './StatusTimeline';

interface EventOverviewProps {
  event: TetraEvent;
  /** 신청상태 행 오른쪽 끝에 놓일 승인/반려 버튼 (승인 대기 중일 때만 전달) */
  statusActions?: ReactNode;
}

/**
 * 이벤트 상세 — 개요 (OPS-03-1, OPS-04-1 공용)
 * 좌측: 기본 정보 / 우측: 상태 + 리소스 / 하단: 신청 상태 로그
 */
export default function EventOverview({ event, statusActions }: EventOverviewProps) {
  const { getTenant } = useAppData();
  const appMeta = applicationStatusMeta[event.applicationStatus];
  const domain = subdomainOf(event);
  const publicKey = publicKeyOf(event);

  const basic = [
    { label: '이벤트ID', value: event.id },
    { label: '이벤트명', value: event.name },
    { label: '이벤트타입', value: eventTypeLabel[event.type] },
    { label: '테넌트ID', value: <Box sx={type.mono}>{event.tenantId}</Box> },
    { label: '테넌트명', value: getTenant(event.tenantId)?.name ?? '-' },
    { label: '호스팅 기간', value: formatPeriod(event.hostingPeriod.start, event.hostingPeriod.end) },
    { label: '이벤트 기간', value: formatPeriod(event.eventPeriod.start, event.eventPeriod.end) },
    { label: '구매 플랜', value: <PlanBadge plan={event.plan} /> },
    { label: '발급 가능 수량', value: formatNumber(event.quantity) },
    { label: '신청 시각', value: event.appliedAt },
    { label: '엔드포인트URL', value: event.endpointUrl },
  ];

  // 승인 전에는 호스팅상태·이벤트상태 값이 없어 '-'
  const status = [
    { label: '신청상태', value: <StatusChip label={appMeta.label} tone={appMeta.tone} />, action: statusActions },
    ...(event.rejectReason ? [{ label: '반려 사유', value: event.rejectReason }] : []),
    {
      label: '호스팅상태',
      value: event.hostingStatus ? <StatusChip label={hostingStatusMeta[event.hostingStatus].label} tone={hostingStatusMeta[event.hostingStatus].tone} /> : <EmptyValue />,
    },
    {
      label: '이벤트상태',
      value: event.eventStatus ? <StatusChip label={eventStatusMeta[event.eventStatus].label} tone={eventStatusMeta[event.eventStatus].tone} /> : <EmptyValue />,
    },
  ];

  // 할당 도메인·인증키는 승인 시점에 발급 (승인 전 '-', 만료 시 회수)
  const resources = [
    { label: '템플릿ID', value: event.templateId },
    { label: '할당 도메인', value: domain ?? <EmptyValue />, action: domain && <CopyButton value={domain} /> },
    { label: '인증키', value: publicKey ? <CopyButton value={publicKey} label="공개키 복사" toast="공개키가 복사되었습니다." /> : <EmptyValue /> },
  ];

  return (
    <Stack spacing={3.5}>
      <Box sx={{ display: 'grid', gap: 3.5, gridTemplateColumns: { xs: '1fr', lg: '1fr 1fr' }, alignItems: 'start' }}>
        <Card sx={{ p: 3 }}>
          <SectionTitle>기본 정보</SectionTitle>
          <InfoList items={basic} />
        </Card>
        <Stack spacing={3.5}>
          <Card sx={{ p: 3 }}>
            <SectionTitle>상태</SectionTitle>
            <InfoList items={status} />
          </Card>
          <Card sx={{ p: 3 }}>
            <SectionTitle>리소스</SectionTitle>
            <InfoList items={resources} />
          </Card>
        </Stack>
      </Box>

      <Card sx={{ p: 3 }}>
        <SectionTitle>신청 상태 로그</SectionTitle>
        <StatusTimeline logs={event.logs} />
      </Card>
    </Stack>
  );
}
