import NotificationsRoundedIcon from '@mui/icons-material/NotificationsRounded';
import PageHeader from '@/components/layout/PageHeader';
import PlaceholderPanel from '@/components/common/PlaceholderPanel';
import StatusChip from '@/components/common/StatusChip';

export default function NotificationCenterPage() {
  return (
    <>
      <PageHeader breadcrumbs={['로그', '알림센터']} />
      <PlaceholderPanel
        icon={<NotificationsRoundedIcon />}
        title="알림센터"
        lines={[
          <>
            이 영역은 <b>Tetra Portal의 알림센터</b> 화면과 동일한 레이아웃을 재사용합니다.
          </>,
          'Slack 연동 — 모든 알림에 대해 메시지를 보내되, 우선순위에 따라 채널을 분리하여 발송할 예정입니다.',
        ]}
      >
        <StatusChip label="Slack 연동 예정" tone="blue" />
      </PlaceholderPanel>
    </>
  );
}
