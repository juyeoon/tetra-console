import { useState } from "react";
import { Box, Link, Stack, Typography } from "@mui/material";
import StatusChip from "@/components/common/StatusChip";
import { color, line, text, tones } from "@/theme/theme";
import type { StatusLogItem } from "../eventView";

/** 원인 · 반려 사유 — 설명 아래 짧게, 길면 말줄임 + [펼치기]. 실패 계열은 빨강 */
function Detail({ detail }: { detail: NonNullable<StatusLogItem["detail"]> }) {
  const [open, setOpen] = useState(false);
  return (
    <Typography
      variant="body2"
      sx={{
        mt: 0.5,
        pr: { md: 1 },
        color: detail.failure ? tones.red.fg : text.secondary,
        wordBreak: "break-word",
      }}
    >
      {detail.label}: {open ? detail.text : detail.preview}
      {detail.clipped && (
        <Link
          component="button"
          type="button"
          variant="body2"
          onClick={() => setOpen((v) => !v)}
          sx={{ ml: 0.5, verticalAlign: "baseline" }}
        >
          {open ? "접기" : "펼치기"}
        </Link>
      )}
    </Typography>
  );
}

/**
 * 상태 로그 — 신청 · 호스팅 · 이벤트 상태 전이를 시각순으로 (개수 제한 없음, OPS-03-1 · 04-1 공용).
 * 넓은 화면은 가로 한 줄 + 가로 스크롤(칸 최소 260px), 좁은 화면은 세로. 현재 상태는 상단 상태 영역의 배지로 확인한다.
 */
export default function StatusTimeline({ logs }: { logs: StatusLogItem[] }) {
  return (
    <Box sx={{ overflowX: { md: "auto" } }}>
      <Box
        component="ol"
        sx={{
          listStyle: "none",
          m: 0,
          p: 0,
          display: "grid",
          gap: { xs: 0, md: 2 },
          gridTemplateColumns: {
            xs: "1fr",
            md: `repeat(${Math.max(logs.length, 3)}, minmax(260px, 1fr))`,
          },
        }}
      >
        {logs.map((item, i) => {
          const meta = item.status;
          const last = i === logs.length - 1;
          return (
            <Box
              component="li"
              key={`${item.at}-${i}`}
              sx={{
                position: "relative",
                pl: { xs: 3, md: 0 },
                pb: { xs: last ? 0 : 2.5, md: 0 },
              }}
            >
              <Box
                sx={{
                  position: { xs: "absolute", md: "relative" },
                  left: { xs: 4, md: "auto" },
                  top: { xs: 6, md: "auto" },
                  bottom: { xs: 0, md: "auto" },
                  height: { md: 8 },
                  mb: { md: 1.5 },
                  display: "flex",
                  flexDirection: { xs: "column", md: "row" },
                  alignItems: "center",
                }}
              >
                <Box
                  sx={{
                    width: 8,
                    height: 8,
                    borderRadius: "50%",
                    flexShrink: 0,
                    bgcolor: last ? tones[meta.tone].dot : color.navy,
                  }}
                />
                {!last && (
                  <Box
                    sx={{
                      flex: 1,
                      bgcolor: line.base,
                      width: { xs: "1px", md: "auto" },
                      height: { xs: "auto", md: "1px" },
                      ml: { md: 1 },
                    }}
                  />
                )}
              </Box>
              <Typography variant="caption">
                {item.at}
                <Box component="span" sx={{ color: text.tertiary }}>
                  {" · "}
                  {item.actor}
                </Box>
              </Typography>
              <Stack
                direction="row"
                alignItems="center"
                spacing={1}
                sx={{ mt: 0.25, mb: 0.5, flexWrap: "wrap", rowGap: 0.5 }}
              >
                <Typography variant="subtitle1">
                  <Box
                    component="span"
                    sx={{
                      mr: 0.5,
                      typography: "caption",
                      color: text.tertiary,
                    }}
                  >
                    {item.typeLabel}
                  </Box>
                  {item.title}
                </Typography>
                <StatusChip label={meta.label} tone={meta.tone} />
              </Stack>
              <Typography
                variant="body2"
                sx={{ color: text.secondary, pr: { md: 1 } }}
              >
                {item.description}
              </Typography>
              {item.detail && <Detail detail={item.detail} />}
            </Box>
          );
        })}
      </Box>
    </Box>
  );
}
