import { Box, Button, CircularProgress } from '@mui/material';
import ErrorOutlineRoundedIcon from '@mui/icons-material/ErrorOutlineRounded';
import StateMessage from '@/components/common/StateMessage';
import { errorMessage } from '@/api/client';

/** API 응답을 기다리는 동안 (useApiQuery의 loading) */
export function QueryLoading({ minHeight = 240 }: { minHeight?: number }) {
  return (
    <Box sx={{ minHeight, display: 'grid', placeItems: 'center' }}>
      <CircularProgress aria-label="불러오는 중" />
    </Box>
  );
}

/** 불러오기 실패 — 서버 문구(ApiError.message) + 다시 시도 */
export function QueryError({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  return (
    <StateMessage icon={<ErrorOutlineRoundedIcon />} tone="red" title="불러오지 못했습니다" description={errorMessage(error)}>
      <Button variant="outlined" onClick={onRetry} sx={{ mt: 2 }}>
        다시 시도
      </Button>
    </StateMessage>
  );
}
