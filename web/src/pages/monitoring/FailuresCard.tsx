import { Link as RouterLink } from 'react-router-dom';
import { Card, Link, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import SectionTitle from '@/components/common/SectionTitle';
import TableCard from '@/components/common/TableCard';
import { text, tones } from '@/theme/theme';
import { NO_FAILURE_TEXT, type FailureRow } from './trafficMonitoringView';

/** E. 최근 자동화 실패 · 긴급 알림(7일) — *_FAILED 상태 로그 + URGENT 운영자 알림 */
export default function FailuresCard({ rows }: { rows: FailureRow[] }) {
  return (
    <Card sx={{ p: 3 }}>
      <SectionTitle>최근 자동화 실패 · 긴급 알림 (7일)</SectionTitle>
      {rows.length === 0 ? (
        <Typography variant="body2" sx={{ color: text.tertiary }}>
          {NO_FAILURE_TEXT}
        </Typography>
      ) : (
        <TableCard minWidth={720}>
          <TableHead>
            <TableRow>
              <TableCell>시각</TableCell>
              <TableCell>이벤트</TableCell>
              <TableCell>단계</TableCell>
              <TableCell>사유</TableCell>
              <TableCell align="right">상세</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.map((r) => (
              <TableRow key={r.key}>
                <TableCell sx={{ whiteSpace: 'nowrap' }}>{r.time}</TableCell>
                <TableCell>{r.event}</TableCell>
                <TableCell sx={{ color: tones.red.fg, whiteSpace: 'nowrap' }}>{r.step}</TableCell>
                <TableCell sx={{ wordBreak: 'break-all' }}>{r.reason}</TableCell>
                <TableCell align="right">
                  <Link component={RouterLink} to={r.link} underline="always" sx={{ color: text.secondary }}>
                    상세
                  </Link>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </TableCard>
      )}
    </Card>
  );
}
