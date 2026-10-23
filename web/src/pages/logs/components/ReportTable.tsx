import { useState } from 'react';
import { Button, TableBody, TableCell, TableHead, TableRow } from '@mui/material';
import FileDownloadRoundedIcon from '@mui/icons-material/FileDownloadRounded';
import MailOutlineRoundedIcon from '@mui/icons-material/MailOutlineRounded';
import TableCard from '@/components/common/TableCard';
import { size } from '@/theme/theme';
import type { ReportRow } from '../reportView';

interface Props {
  rows: ReportRow[];
  onDownload: (row: ReportRow) => Promise<void>;
  onSendEmail: (row: ReportRow) => Promise<void>;
}

/**
 * "최근 생성된 리포트" 표 — 리포트명 / 기간 / 생성일 / 다운로드 / 이메일 전송 (Tetra Portal UI-TP-12 구조).
 * 요청 중인 행의 버튼은 로딩, 그동안 같은 종류의 다른 버튼은 막는다(중복 요청 방지).
 */
export default function ReportTable({ rows, onDownload, onSendEmail }: Props) {
  const [downloadingId, setDownloadingId] = useState<number | null>(null);
  const [sendingId, setSendingId] = useState<number | null>(null);

  const run = async (row: ReportRow, setId: (id: number | null) => void, action: (row: ReportRow) => Promise<void>) => {
    setId(row.id);
    try {
      await action(row);
    } finally {
      setId(null);
    }
  };

  return (
    <TableCard minWidth={760}>
      <TableHead>
        <TableRow>
          <TableCell>리포트명</TableCell>
          <TableCell>기간</TableCell>
          <TableCell>생성일</TableCell>
          <TableCell align="center" width={150}>
            다운로드
          </TableCell>
          <TableCell align="center" width={170}>
            이메일 전송
          </TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {rows.map((r) => (
          <TableRow key={r.id}>
            <TableCell>{r.name}</TableCell>
            <TableCell sx={{ whiteSpace: 'nowrap' }}>{r.period}</TableCell>
            <TableCell sx={{ whiteSpace: 'nowrap' }}>{r.createdAt}</TableCell>
            <TableCell align="center">
              <Button
                size="small"
                variant="outlined"
                startIcon={<FileDownloadRoundedIcon sx={{ fontSize: `${size.iconSm} !important` }} />}
                loading={downloadingId === r.id}
                disabled={downloadingId !== null && downloadingId !== r.id}
                onClick={() => void run(r, setDownloadingId, onDownload)}
              >
                다운로드
              </Button>
            </TableCell>
            <TableCell align="center">
              <Button
                size="small"
                variant="contained"
                startIcon={<MailOutlineRoundedIcon sx={{ fontSize: `${size.iconSm} !important` }} />}
                loading={sendingId === r.id}
                disabled={sendingId !== null && sendingId !== r.id}
                onClick={() => void run(r, setSendingId, onSendEmail)}
              >
                이메일 전송
              </Button>
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </TableCard>
  );
}
