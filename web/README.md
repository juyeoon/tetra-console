# Tetra 운영 콘솔 (Frontend)

React 19 + TypeScript + Vite + MUI 7 (+ MUI X Charts)

이 폴더(`web/`)는 `console` 저장소의 화면 영역입니다. 저장소 루트에서 실행합니다.

```bash
cd web
npm install
npm run dev        # http://localhost:5173
npm run build      # 타입 체크 + 프로덕션 빌드
```

## 화면 / 라우트

| 화면 | 경로 |
| --- | --- |
| 로그인 | `/login` |
| 비밀번호 설정 (테넌트) | `/password/setup?token=...` (`token` 없음 또는 `expired` → 만료 화면) |
| 대시보드 | `/` |
| 이벤트 신청 현황 · 상세 | `/events/applications`, `/events/applications/:eventId` |
| 이벤트 진행 현황 · 상세 | `/events/progress`, `/events/progress/:eventId?tab=result` |
| 트래픽 모니터링 | `/monitoring/traffic` |
| 테넌트 현황 · 상세 | `/tenants`, `/tenants/:tenantId` |
| 테넌트 등록 | `/tenants/new` |
| 알림센터 / 리포트 | `/logs/notifications`, `/logs/reports` |
| 시스템 설정 | `/settings` |
| 등록 안내 메일 미리보기 | `/preview/tenant-registration-email` |

### 상태별 확인용 Mock 데이터

- 승인 대기 중(승인/반려 버튼): `1043` 추석맞이 전상품 쿠폰 발급(A사) — 승인 시 개인키 메일 발송 **성공**
- 승인 대기 중: `1045` 가을 신상품 할인 쿠폰(C사) — 승인 시 개인키 메일 발송 **실패** 화면(OPS-03-M1 상태 화면)
- 신청됨(처리 중) `1044` · 신청 실패 `1039` · 반려됨 `1031`
- 이벤트 진행 결과 탭: 오픈 전 `1038` · 진행 중 `1042` · 집계 중 `1036` · 종료 처리 실패 `1021` · 집계 완료 `1012`, `890`
- 테넌트 등록: 이메일에 `fail`이 들어가면 비밀번호 설정 메일 발송 실패 화면. 중복 확인은 기존 테넌트 이메일(예: `jane.kim@a-corp.com`)로 확인
- 비밀번호 설정: `?token=expired` 또는 토큰 없음 → 링크 오류 화면

## 화면설계서(v0.5) 대비 차이

기준 문서: Operations Console 화면설계서 v0.5 (2026-10-14, 저장소 밖에서 관리). 아래 항목은 의도적으로 다르게 두었거나 설계서에 정의가 없어 임의로 정한 부분입니다.

| 항목 | 설계서 | 현재 구현 | 이유 |
| --- | --- | --- | --- |
| 이벤트 진행 현황 목록 건수 | "배포 대기 · 호스팅 중 · 호스팅 종료" 3종 예시 | 3종은 항상 표시 + 나머지 호스팅상태(배포 완료·실패 등)는 건이 있을 때만 표시 | 호스팅상태가 7종이라 실패 건을 놓치지 않도록 |
| 호스팅상태 "배포 완료" 배지 색 | 정의 없음 | 파랑 | 진행 단계(중간 상태) 표시 |
| 실패 계열 배지 색 (배포 실패·호스팅 준비 실패·종료 실패·이벤트 준비 실패) | 정의 없음 | 빨강 | 상태 톤 규칙(위험) 기준 |
| 신청 상세 리소스의 인증키 | "인증키" 항목만 정의 | [공개키 복사] 버튼 (값 미노출) | OPS-06-3의 인증키 표시 규칙과 통일 |
| 신청 상세 리소스 (만료 이벤트) | 정의 없음 | 할당 도메인·인증키 "-" | OPS-06-3 "만료 시 회수" 규칙과 통일 |
| 테넌트 등록 회사 root 도메인 형식 오류 | "입력 형식만 확인" | 등록 버튼 비활성 + 입력창 아래 안내 문구 | 형식 검사 결과를 알려줄 수단이 필요 — 제거 여부 확인 필요 |
| 승인 성공 시 피드백 | "별도 안내 없이 모달이 닫힘" | 모달은 닫히고 하단 토스트("승인했습니다") 표시 | 처리 결과 인지용 — 제거 여부 확인 필요 |
| 계정 상태 자동 전환 (Mock) | 신청 완료 시 활성 / 반려·종료 시 비활성 | 반려 시점의 비활성 전환만 Mock으로 재현 (신청 완료·종료 전환은 서버 처리 영역) | API 연동 시 서버 값 사용 |
| 비밀번호 저장 완료 후 이동 | Tetra Portal 로그인(URL 미확정) | `VITE_PORTAL_LOGIN_URL`(기본 `/login`) | 도메인 확정 후 환경 변수로 지정 |
| 신청 내용(OPS-03-2·04-2), 알림센터, 리포트 | Tetra Portal 화면 재사용 | 안내 문구 placeholder | Portal 컴포넌트 미전달 |
| 대시보드 KPI·실시간 트래픽 숫자 | 일반 숫자 | 네이비 띠 강조(A안 확정, `tokens.css`의 `--kpi-*`) | 디자인 결정 |
| 상단바 | 경로(브레드크럼) + 페이지 제목 | 연한 톤 상단바 하나에 상위 경로 + 제목(h2) 1회 표시 | 제목 중복 제거 (디자인 결정) |

## 남은 작업

1. **디자인** — Canva 팔레트 + Snow 타이포·간격, 글자 125%, CSS 토큰 모듈화, KPI 강조(A안 확정), 상단바 통일 반영 완료. 스타일 가이드 갱신 완료
2. **검증** — v0.5 반영 후 승인(메일 성공·실패)·반려·테넌트 정보 수정·등록(중복 확인, 메일 성공·실패)·비밀번호 8자 규칙을 실제 조작으로 확인 완료. 모바일 폭 전 화면 확인은 남음
3. **API 연동** — API 목록 전달 후 `AppDataContext` 액션과 `TODO:` 지점 교체
4. **미확정 항목 확정 대기** — 트래픽 모니터링(OPS-05), 비밀번호 문자 조합 규칙, Portal 로그인 URL, Portal 재사용 화면 3종

## 구조

```
src/
  styles/         # tokens.css(디자인 토큰 — 수정은 여기서), fonts.css
  theme/          # tokens.ts(CSS 변수 → TS 이름 연결), theme.ts(MUI 테마)
  components/
    layout/       # 사이드바, 페이지 헤더, 사용자 메뉴
    common/       # 상태칩, 정보목록, 통계카드, 테이블 카드 등
  pages/          # 화면별 페이지
  store/          # AppDataContext(임시 데이터 저장소), Toast
  mocks/          # Mock 데이터
  types/          # 도메인 타입
  utils/          # 상태 라벨/톤, 포맷, 검증
```

## API 연동 지점

현재는 `src/store/AppDataContext.tsx`가 `src/mocks/*`를 메모리에 들고 있습니다. API가 확정되면 이 Context의 액션(`approveEvent`, `rejectEvent`, `updateTenant`, `isEmailTaken`, `registerTenant`)과 조회 부분을 API 호출로 교체하면 됩니다. 그 외 `TODO:` 주석 위치:

- 로그인 `pages/auth/LoginPage.tsx`
- 비밀번호 설정 토큰 검증/저장 `pages/auth/PasswordSetupPage.tsx`
- 실시간 트래픽 `pages/dashboard/LiveTrafficCard.tsx`
- 발급 이력 CSV `pages/events/EventProgressDetailPage.tsx`
- 테넌트 등록(초기 비밀번호 생성·안내 메일 발송) `pages/tenants/TenantRegisterPage.tsx`

## 디자인 시스템

- 규칙: 스타일 가이드 (저장소 밖에서 관리)
- 값: `src/styles/tokens.css` — 색·글자 크기(`--type-scale`)·모서리·간격·컴포넌트 크기를 이 파일에서만 수정합니다.

## 폰트

- 본문: 프리젠테이션(Freesentation, SIL OFL) — noonnu CDN
- 제목: 페이퍼로지(Paperlogy, SIL OFL) — noonnu CDN, 300~900 굵기
- 굵기는 역할별 토큰으로 씁니다 (`tokens.css`의 `--fw-*`, `--fw-heading-*`) — 자세한 규칙은 스타일 가이드 3장

## 환경 변수

- `VITE_PORTAL_LOGIN_URL`: 비밀번호 설정 완료 후 이동할 Tetra Portal 로그인 주소 (기본값 `/login`)
