import type { ReactNode } from 'react';
import { Box, Card, Stack, Tooltip, Typography } from '@mui/material';
import SectionTitle from '@/components/common/SectionTitle';
import StatusChip from '@/components/common/StatusChip';
import { font, line, text, tones } from '@/theme/theme';
import GrafanaLinks from './GrafanaLinks';
import { EMPTY, grafanaHint, platformGrafanaLink, type AddonsView, type LcuView, type UpcomingView } from './trafficMonitoringView';

const LCU_COLOR: Record<LcuView['tone'], string> = { normal: text.primary, amber: tones.amber.fg, red: tones.red.fg, muted: text.tertiary };

/** 발급 ALB LCU 예약 한 줄. 마우스를 올리면 AZ별 상태 · 실효 · 마지막 변경 · 오늘 남은 줄이기 */
function LcuLine({ lcu }: { lcu: LcuView }) {
  const body = (
    <Typography variant="body2" component="span" sx={{ color: LCU_COLOR[lcu.tone], fontWeight: lcu.tone === 'normal' ? font.weight.semibold : undefined }}>
      {lcu.text}
    </Typography>
  );
  return (
    <>
      {lcu.tooltip.length > 0 ? (
        <Tooltip
          arrow
          placement="bottom-start"
          title={
            <Box>
              {lcu.tooltip.map((l) => (
                <Box key={l}>{l}</Box>
              ))}
            </Box>
          }
        >
          {body}
        </Tooltip>
      ) : (
        body
      )}
      {lcu.problems.length > 0 && (
        <Stack direction="row" flexWrap="wrap" useFlexGap spacing={0.5} sx={{ mt: 0.75 }}>
          {lcu.problems.map((p) => (
            <StatusChip key={p} label={p} tone="red" />
          ))}
        </Stack>
      )}
    </>
  );
}

function Row({ label, children, delayed }: { label: string; children: ReactNode; delayed?: boolean }) {
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: '84px 1fr', gap: 1.5, py: 1.25, borderTop: `1px solid ${line.soft}`, '&:first-of-type': { borderTop: 0 } }}>
      <Stack spacing={0.5} alignItems="flex-start">
        <Typography variant="body2" sx={{ color: text.tertiary }}>
          {label}
        </Typography>
        {/* AWS 값만 지난 값이면 그 칸에만 작게 */}
        {delayed && <StatusChip label="지연" tone="grey" />}
      </Stack>
      <Box sx={{ minWidth: 0 }}>{children}</Box>
    </Box>
  );
}

/**
 * B-2 플랫폼 — 애드온(평소 "정상 6/6" 한 줄, 문제 있는 것만 이름 + Ready 수) · RDS · 발급 ALB(LCU 예약) · Portal · Console.
 * 제목 줄 오른쪽에 Grafana 플랫폼 대시보드(새 탭). grafana는 grafanaBase로 거른 주소(null이면 링크 없음)
 */
export function PlatformCard({ addons, rds, lcu, services, awsDelayed, grafana }: { addons: AddonsView; rds: string; lcu: LcuView; services: { key: string; text: string }[]; awsDelayed: boolean; grafana: string | null }) {
  const link = platformGrafanaLink(grafana);
  return (
    <Card sx={{ p: 3 }}>
      <SectionTitle action={link && <GrafanaLinks links={[link]} hint={grafanaHint(grafana)} />}>플랫폼</SectionTitle>
      <Row label="애드온">
        <Typography variant="body2">
          <Box component="span" sx={{ fontWeight: font.weight.semibold }}>
            {addons.summary}
          </Box>
          {addons.names && (
            <Box component="span" sx={{ color: text.tertiary }}>
              {' '}
              · {addons.names}
            </Box>
          )}
        </Typography>
        {addons.problems.length > 0 && (
          <Stack direction="row" flexWrap="wrap" useFlexGap spacing={0.5} sx={{ mt: 0.75 }}>
            {addons.problems.map((p) => (
              <StatusChip key={p} label={p} tone="red" />
            ))}
          </Stack>
        )}
      </Row>
      <Row label="RDS" delayed={awsDelayed}>
        <Typography variant="body2">{rds}</Typography>
      </Row>
      <Row label="발급 ALB" delayed={lcu.delayed}>
        <LcuLine lcu={lcu} />
      </Row>
      <Row label="Portal · Console" delayed={awsDelayed}>
        {services.length === 0 ? (
          <Typography variant="body2">{EMPTY}</Typography>
        ) : (
          services.map((s) => (
            <Typography key={s.key} variant="body2">
              {s.text}
            </Typography>
          ))
        )}
      </Row>
    </Card>
  );
}

/** B-3 다가오는 이벤트 — 준비 · 입장 · 시작 · 종료 계획 시각 */
export function UpcomingCard({ items }: { items: UpcomingView[] }) {
  return (
    <Card sx={{ p: 3, flex: 1 }}>
      <SectionTitle>다가오는 이벤트</SectionTitle>
      {items.length === 0 ? (
        <Typography variant="body2" sx={{ color: text.tertiary }}>
          7일 안에 시작하는 이벤트 없음
        </Typography>
      ) : (
        <Stack spacing={1.5}>
          {items.map((u) => (
            <Box key={u.key}>
              <Typography variant="subtitle2">{u.title}</Typography>
              <Typography variant="caption" sx={{ color: text.tertiary }}>
                {u.times}
              </Typography>
            </Box>
          ))}
        </Stack>
      )}
    </Card>
  );
}
