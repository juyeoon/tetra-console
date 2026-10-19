import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { Button, Stack, Tab, Tabs } from '@mui/material';
import CheckRoundedIcon from '@mui/icons-material/CheckRounded';
import CloseRoundedIcon from '@mui/icons-material/CloseRounded';
import PageHeader from '@/components/layout/PageHeader';
import NotFoundView from '@/components/common/NotFoundView';
import { useTabParam } from '@/components/common/useTabParam';
import { useAppData } from '@/store/AppDataContext';
import { useToast } from '@/store/ToastContext';
import { size } from '@/theme/theme';
import ApplicationContentPlaceholder from './components/ApplicationContentPlaceholder';
import { ApproveDialog, RejectDialog } from './components/DecisionDialogs';
import EventOverview from './components/EventOverview';

const TABS = ['overview', 'application'] as const;
const BREADCRUMBS = ['모니터링', '이벤트 신청 현황', '상세'];
const BACK = { to: '/events/applications', label: '이벤트 신청 현황 목록으로 돌아가기' };

/** OPS-03-1 / 03-2 이벤트 신청 현황 상세 */
export default function EventApplicationDetailPage() {
  const { eventId } = useParams();
  const { getEvent, getTenant, approveEvent, rejectEvent } = useAppData();
  const toast = useToast();
  const [tab, setTab] = useTabParam(TABS, 'overview');
  const [dialog, setDialog] = useState<'approve' | 'reject' | null>(null);
  const event = getEvent(Number(eventId));

  if (!event) return <NotFoundView breadcrumbs={BREADCRUMBS} back={BACK} message="이벤트를 찾을 수 없습니다" />;

  const tenantName = getTenant(event.tenantId)?.name ?? '-';
  // 승인/반려는 신청상태가 '승인 대기 중'일 때만 — 신청상태 행 오른쪽 끝에 아웃라인 버튼
  const statusActions =
    event.applicationStatus === 'APPLIED' ? (
      <Stack direction="row" spacing={1}>
        <Button size="small" variant="outlined" color="success" startIcon={<CheckRoundedIcon sx={{ fontSize: `${size.iconSm} !important` }} />} onClick={() => setDialog('approve')}>
          승인
        </Button>
        <Button size="small" variant="outlined" color="error" startIcon={<CloseRoundedIcon sx={{ fontSize: `${size.iconSm} !important` }} />} onClick={() => setDialog('reject')}>
          반려
        </Button>
      </Stack>
    ) : undefined;

  return (
    <>
      <PageHeader breadcrumbs={BREADCRUMBS} back={BACK} title={event.name} />
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 3 }}>
        <Tab value="overview" label="개요" />
        <Tab value="application" label="신청 내용" />
      </Tabs>
      {tab === 'overview' ? <EventOverview event={event} statusActions={statusActions} /> : <ApplicationContentPlaceholder />}

      {/* 처리 후 페이지 이동 없이 상세에 남아 상태가 즉시 갱신된다 */}
      <ApproveDialog
        open={dialog === 'approve'}
        event={event}
        tenantName={tenantName}
        onClose={() => setDialog(null)}
        onApprove={async () => {
          const result = await approveEvent(event.id);
          if (result.keyMailSent) toast(`'${event.name}' 신청을 승인했습니다.`);
          return result;
        }}
      />
      <RejectDialog
        open={dialog === 'reject'}
        event={event}
        tenantName={tenantName}
        onClose={() => setDialog(null)}
        onConfirm={(reason) => {
          rejectEvent(event.id, reason);
          setDialog(null);
          toast(`'${event.name}' 신청을 반려했습니다.`, 'info');
        }}
      />
    </>
  );
}
