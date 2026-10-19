import { Stack } from '@mui/material';
import ShowChartRoundedIcon from '@mui/icons-material/ShowChartRounded';
import PageHeader from '@/components/layout/PageHeader';
import PlaceholderPanel from '@/components/common/PlaceholderPanel';
import StatusChip from '@/components/common/StatusChip';

export default function TrafficMonitoringPage() {
  return (
    <>
      <PageHeader breadcrumbs={['모니터링', '트래픽 모니터링']} />
      <PlaceholderPanel
        icon={<ShowChartRoundedIcon />}
        title="트래픽 모니터링"
        lines={[
          <>
            이 화면은 <b>모니터링 기획 단계</b>에서 세부 내용을 구체화할 예정입니다.
          </>,
        ]}
      >
        <Stack direction="row" spacing={1} sx={{ pt: 1 }}>
          {['그래프 종류', '필터', '갱신 주기'].map((t) => (
            <StatusChip key={t} label={`${t} 미확정`} tone="grey" />
          ))}
        </Stack>
      </PlaceholderPanel>
    </>
  );
}
