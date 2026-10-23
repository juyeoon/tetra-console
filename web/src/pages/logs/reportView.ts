import type { ReportItem } from '../../api/types.ts';
import { formatDate } from '../../utils/datetime.ts';
import { formatPeriod } from '../../utils/format.ts';

/** 리포트(OPS-09) 표 한 줄 (화면과 분리, node --test 대상). 서버가 최근 생성순으로 준다 */
export interface ReportRow {
  id: number;
  name: string;
  /** 날짜만 — '2026.09.01 ~ 09.30'(같은 해면 끝 날짜의 연도 생략) */
  period: string;
  createdAt: string;
}

export function toReportRow(r: ReportItem): ReportRow {
  return { id: r.reportId, name: r.name, period: formatPeriod(formatDate(r.periodStart), formatDate(r.periodEnd)), createdAt: formatDate(r.createdAt) };
}

/** 이메일 전송 성공 토스트. 받는 사람은 서버가 로그인한 운영자 이메일로 정한다 */
export function emailSentMessage(email: string, reportName: string) {
  return `${email}로 "${reportName}" 리포트 안내 메일을 보냈습니다.`;
}

export const DOWNLOAD_STARTED_MESSAGE = '리포트 다운로드를 시작했습니다.';
