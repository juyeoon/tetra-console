import { useMemo } from 'react';
import { Typography } from '@mui/material';
import AssessmentOutlinedIcon from '@mui/icons-material/AssessmentOutlined';
import PageHeader from '@/components/layout/PageHeader';
import { QueryError, QueryLoading } from '@/components/common/QueryStatus';
import SectionTitle from '@/components/common/SectionTitle';
import StateMessage from '@/components/common/StateMessage';
import { errorMessage } from '@/api/client';
import { fetchReportDownloadUrl, fetchReports, sendReportEmail } from '@/api/reports';
import { useApiQuery } from '@/hooks/useApiQuery';
import { useOperator } from '@/store/AuthContext';
import { useToast } from '@/store/ToastContext';
import { text } from '@/theme/theme';
import ReportTable from './components/ReportTable';
import { DOWNLOAD_STARTED_MESSAGE, emailSentMessage, toReportRow, type ReportRow } from './reportView';

const BREADCRUMBS = ['로그', '리포트'];

/**
 * OPS-09 리포트 — 최근 생성된 리포트 목록, 다운로드(presigned URL), 이메일 전송(로그인한 운영자에게).
 * 리포트 생성은 이 화면 · API 범위 밖이라 생성 주기 안내 문구는 두지 않는다.
 */
export default function ReportPage() {
  const toast = useToast();
  const operator = useOperator();
  const query = useApiQuery((signal) => fetchReports(signal), []);
  const rows = useMemo(() => (query.data ? query.data.items.map(toReportRow) : []), [query.data]);

  // 요청할 때마다 새로 받은 5분짜리 주소로 바로 내려받는다(응답이 첨부 파일이라 화면은 그대로)
  const download = async (row: ReportRow) => {
    try {
      const { url } = await fetchReportDownloadUrl(row.id);
      window.location.assign(url);
      toast(DOWNLOAD_STARTED_MESSAGE);
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  const send = async (row: ReportRow) => {
    try {
      await sendReportEmail(row.id);
      toast(emailSentMessage(operator.email, row.name));
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  return (
    <>
      <PageHeader breadcrumbs={BREADCRUMBS} />
      <SectionTitle>최근 생성된 리포트</SectionTitle>
      {!query.data ? (
        query.loading ? <QueryLoading /> : <QueryError error={query.error} onRetry={query.reload} />
      ) : rows.length === 0 ? (
        <StateMessage icon={<AssessmentOutlinedIcon />} tone="grey" title="생성된 리포트가 없습니다" />
      ) : (
        <>
          <ReportTable rows={rows} onDownload={download} onSendEmail={send} />
          <Typography variant="body2" sx={{ mt: 2, color: text.tertiary }}>
            ※ 이메일은 로그인한 계정({operator.email})으로 전송됩니다.
          </Typography>
        </>
      )}
    </>
  );
}
