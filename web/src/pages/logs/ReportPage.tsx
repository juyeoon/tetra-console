import AssessmentRoundedIcon from '@mui/icons-material/AssessmentRounded';
import PageHeader from '@/components/layout/PageHeader';
import PlaceholderPanel from '@/components/common/PlaceholderPanel';

export default function ReportPage() {
  return (
    <>
      <PageHeader breadcrumbs={['로그', '리포트']} />
      <PlaceholderPanel
        icon={<AssessmentRoundedIcon />}
        title="리포트"
        lines={[
          <>
            이 영역은 <b>Tetra Portal의 리포트</b> 화면과 동일한 레이아웃을 재사용합니다.
          </>,
        ]}
      />
    </>
  );
}
