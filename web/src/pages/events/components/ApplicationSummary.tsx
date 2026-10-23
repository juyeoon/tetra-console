import { useMemo, useState } from 'react';
import { Box, Button, Card, Dialog, DialogActions, DialogContent, DialogTitle, Typography } from '@mui/material';
import VisibilityOutlinedIcon from '@mui/icons-material/VisibilityOutlined';
import InfoList from '@/components/common/InfoList';
import { QueryError, QueryLoading } from '@/components/common/QueryStatus';
import SectionTitle from '@/components/common/SectionTitle';
import { ApiError } from '@/api/client';
import { fetchApplicationSummary } from '@/api/events';
import { useApiQuery } from '@/hooks/useApiQuery';
import { text, type } from '@/theme/theme';
import { previewDomain, toSummaryItems } from '../applicationSummaryView';
import ScreenPreview from './ScreenPreview';

const NOT_READY_MESSAGE = '테넌트 환경 준비가 끝나지 않아 신청 내용을 볼 수 없습니다.';

interface Props {
  eventId: number;
  /** 할당 도메인(개요 응답의 fullDomain). 승인 전이면 null → 미리보기는 기본 도메인 */
  domain: string | null;
}

/**
 * OPS-03-2 · 04-2 「신청 내용」 탭 — Tetra Portal 이벤트 신청 4단계(UI-TP-10)의 「신청 요약」 카드와 [이벤트 미리보기].
 * 조회 전용(공지 · 이전/신청 버튼 · 입력 요소 없음). 신청 현황 · 진행 현황 상세가 같이 쓴다.
 */
export default function ApplicationSummary({ eventId, domain }: Props) {
  const query = useApiQuery((signal) => fetchApplicationSummary(eventId, signal), [eventId]);
  const items = useMemo(
    () =>
      query.data
        ? toSummaryItems(query.data).map((i) => ({
            label: i.label,
            value: i.mono ? <Box sx={{ ...type.monoSm, wordBreak: 'break-all' }}>{i.value}</Box> : i.value,
          }))
        : [],
    [query.data],
  );
  const [previewOpen, setPreviewOpen] = useState(false);

  if (query.loading && !query.data) return <QueryLoading />;
  if (!query.data) {
    const error =
      query.error instanceof ApiError && query.error.code === 'TENANT_NOT_READY'
        ? new ApiError(query.error.status, query.error.code, NOT_READY_MESSAGE)
        : query.error;
    return <QueryError error={error} onRetry={query.reload} />;
  }

  return (
    <>
      <Card sx={{ p: 3 }}>
        <SectionTitle
          action={
            <Button size="small" variant="outlined" startIcon={<VisibilityOutlinedIcon />} onClick={() => setPreviewOpen(true)}>
              이벤트 미리보기
            </Button>
          }
        >
          신청 요약
        </SectionTitle>
        <InfoList items={items} labelWidth="wide" />
      </Card>

      <Dialog open={previewOpen} onClose={() => setPreviewOpen(false)} maxWidth="md" fullWidth>
        <DialogTitle>이벤트 미리보기</DialogTitle>
        <DialogContent>
          <ScreenPreview screen={query.data.layoutConfig} domain={previewDomain(domain)} height={480} />
          <Typography variant="body2" sx={{ mt: 1.5, color: text.tertiary, textAlign: 'center' }}>
            위치와 테마만 반영한 예시입니다. 실제 이벤트 화면은 승인 후 이 구성으로 생성됩니다.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button variant="contained" onClick={() => setPreviewOpen(false)}>
            닫기
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
