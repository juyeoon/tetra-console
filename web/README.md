# Tetra 운영 콘솔 (Frontend)

React 19 + TypeScript + Vite + MUI 7 (+ MUI X Charts)

이 폴더(`web/`)는 `console` 저장소의 화면 영역입니다. 저장소 루트에서 실행합니다.

```bash
cd web
npm install
npm run dev        # http://localhost:5173 (/api는 localhost:8080으로 프록시)
npm test           # 단위 테스트 (node --test, 테스트 패키지 없음)
npm run build      # 타입 체크 + 프로덕션 빌드
```

## API와 함께 실행 (로컬)

콘솔 화면은 **로그인해야 보입니다**(`GET /api/ops/me`로 확인). 화면만 따로 띄우는 우회 모드는 없으므로, 목 데이터 화면을 볼 때도 로컬 API를 같이 띄웁니다.

1. `api/.env` 준비 — `api/.env.example`을 복사해 채웁니다(자세한 값은 `api/dev-docs/로컬_실행.md`).
   - 테넌트 스키마 Flyway 생성(3-14, `feat/tenant-schema-flyway`)이 병합된 뒤에는 **`MIGRATOR_USERNAME` · `MIGRATOR_PASSWORD`도 필요**합니다(테넌트 등록 때 스키마를 만드는 DDL 계정). 비어 있으면 API가 뜨지 않습니다.
2. 의존 서비스와 API (`api/`에서)
   ```bash
   docker compose up -d   # MySQL + ElasticMQ
   ./gradlew bootRun      # :8080, .env를 읽어 local 프로필
   ```
   - MySQL 계정(앱 · 마이그레이션)과 seed는 **볼륨을 처음 만들 때만** 생깁니다. 3-14 이후 `MIGRATOR_*`를 처음 넣었거나 seed를 다시 넣으려면 `docker compose down -v`(로컬 데이터 삭제) 후 다시 `up -d`.
3. 화면 (`web/`에서) `npm run dev` → http://localhost:5173 → `/login`으로 이동하면 정상

### 로컬 운영자 계정

- `api/dev-docs/db/local/local_seed.sql`이 있으면(볼륨 첫 생성 때 실행) 운영자 1명이 들어 있습니다. 이메일 · 비밀번호는 그 파일 머리 주석을 봅니다.
- seed가 없거나 다른 계정 · 비밀번호가 필요하면 아래처럼 만듭니다(`api/`에서, `docker compose up -d` 상태). 같은 이메일이 이미 있으면 이름 · 비밀번호만 바꿉니다.
  - 비밀번호는 입력창(화면에 안 보임)으로 받아 **환경 변수로만** 넘기고, 끝나면 지웁니다. 명령줄 · 파일 · 셸 기록에 남지 않습니다.
  - 해시는 BCrypt(cost 10, `httpd` 이미지의 `htpasswd`). 컨테이너는 `--rm`이라 끝나면 지워집니다.

  **Git Bash**
  ```bash
  OPS_EMAIL='operator@tetra-operation.local'
  OPS_NAME='로컬 운영자'
  read -rsp '비밀번호: ' OPS_PW; echo
  export OPS_PW
  # 컨테이너 안에서도 stdin으로만 htpasswd에 넘긴다(명령 인자에 비밀번호 없음)
  OPS_HASH=$(docker run --rm -e OPS_PW httpd:2.4-alpine sh -c 'printf "%s\n" "$OPS_PW" | htpasswd -niBC 10 x' | cut -d: -f2 | tr -d '\r\n')
  unset OPS_PW
  printf "INSERT INTO operator (email, contact_name, password) VALUES ('%s', '%s', '%s') AS new
  ON DUPLICATE KEY UPDATE contact_name = new.contact_name, password = new.password;\n" "$OPS_EMAIL" "$OPS_NAME" "$OPS_HASH" |
    docker compose exec -T mysql sh -c 'MYSQL_PWD=$MYSQL_ROOT_PASSWORD mysql -uroot --default-character-set=utf8mb4 tetra_platform'
  unset OPS_HASH
  ```

  **PowerShell**
  ```powershell
  $OutputEncoding = [Text.UTF8Encoding]::new($false)   # 한글 이름을 MySQL에 그대로 넘기려고
  $email = 'operator@tetra-operation.local'; $name = '로컬 운영자'
  $env:OPS_PW = [Net.NetworkCredential]::new('', (Read-Host '비밀번호' -AsSecureString)).Password
  # PowerShell은 파이프(stdin)로 넘기면 BOM을 붙여 다른 값이 해시되므로 환경 변수 → 인자로 넘긴다.
  # 큰따옴표는 PowerShell 5.1이 깨뜨려서 set -f; IFS= 로 공백 · * 를 그대로 둔다
  $hash = ((docker run --rm -e OPS_PW httpd:2.4-alpine sh -c 'set -f; IFS=; htpasswd -nbBC 10 x $OPS_PW') -split ':')[1].Trim()
  Remove-Item Env:OPS_PW
  "INSERT INTO operator (email, contact_name, password) VALUES ('$email', '$name', '$hash') AS new ON DUPLICATE KEY UPDATE contact_name = new.contact_name, password = new.password;" |
    docker compose exec -T mysql sh -c 'MYSQL_PWD=$MYSQL_ROOT_PASSWORD mysql -uroot --default-character-set=utf8mb4 tetra_platform'
  ```

  확인: `echo "SELECT email, contact_name, LEFT(password, 7) FROM operator;" | docker compose exec -T mysql sh -c 'MYSQL_PWD=$MYSQL_ROOT_PASSWORD mysql -uroot --default-character-set=utf8mb4 tetra_platform'` → `$2y$10$`로 시작하면 정상. 운영자 등록 화면 · API는 없습니다(운영자는 DB로 등록).

### 확인 사항

- 로그인 쿠키는 `Secure`라 **Chrome · Firefox**로 확인합니다(`http://localhost`를 예외로 받아 줌). Safari는 쿠키를 받지 않아 로그인 후에도 다시 로그인 화면으로 돌아옵니다.
- API가 꺼져 있으면 "서버에 연결할 수 없습니다" 화면과 [다시 시도] 버튼이 뜹니다.
- 로그인 뒤 API가 401을 주면(쿠키 만료 8시간) "로그인이 만료되었습니다" 안내와 함께 로그인 화면으로 가고, 다시 로그인하면 원래 보던 화면으로 돌아옵니다. 로그인 전에 주소로 바로 들어온 경우도 로그인 후 그 화면으로 갑니다.
- 직접 로그아웃(사용자 메뉴 · 시스템 설정)은 안내 없이 로그인 화면으로 가고, 다시 로그인하면 대시보드(`/`)로 갑니다.

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

### 상태별 확인 (로컬 seed 기준)

화면 데이터는 모두 API에서 옵니다(대시보드의 클러스터 · 실시간 트래픽 · 테넌트 별 처리 현황만 예시 데이터, 카드에 "예시 데이터" 칩). 로컬 seed(`api/dev-docs/db/local/local_seed.sql`) 기준:

- 승인 대기 중(승인/반려 버튼): `1043`(A사). 진행 중: `1044`(진행 결과 탭은 "진행 중"). 반려됨: `1045`
- 개인키 메일 발송 실패(OPS-03-M1), 집계 완료 · 종료 처리 실패 결과는 seed에 없다 — 필요하면 DB에 직접 상태를 넣어 확인
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
| 테넌트 등록 결과 화면 | 등록 완료 / 메일 발송 실패 (등록 때 메일 동기 발송) | 환경 준비 상태로 2가지 — 완료: "테넌트 등록이 완료되었습니다 / 환경 준비가 끝나면 {이메일}로 비밀번호 설정 메일이 발송됩니다." · 실패(FAILED): "테넌트 환경 준비에 실패했습니다 / {테넌트명} 계정은 등록되었지만 환경 준비에 실패해 비밀번호 설정 메일을 보내지 않았습니다. 운영자 알림에서 원인을 확인해 주세요." [테넌트 상세] 버튼 추가 | 비밀번호 설정 메일을 등록 직후가 아니라 환경 준비 성공(READY) 뒤 서버가 보낸다(3-20) — 등록 응답에 메일 결과(`mailSent`)가 없다. 메일 실패는 운영자 알림(MAIL/URGENT). 문구는 `pages/tenants/tenantView.ts` |
| 테넌트 상세 개요의 비밀번호 설정 여부 | 정의 없음 | "비밀번호" 행: "설정 완료"(초록) / "비밀번호 미설정"(회색) — 서버 `passwordSet`(password IS NOT NULL) | 메일 발송 기록 컬럼이 없어, 테넌트가 비밀번호를 설정했는지로 진행 상황을 본다(발송 실패는 대시보드 최근 알림) |
| OPS-06 목록에 가입일 열 추가 · 목록 · 상세의 환경 준비 칩 | 목록은 테넌트ID · 테넌트명 · 계정ID · 계정상태만, 환경 준비 표시 정의 없음 | 목록 마지막 열 "가입일"(날짜만 `YYYY.MM.DD`, 상세 개요는 날짜+시각) · 정렬은 가입일시 내림차순. 계정상태 옆에 "환경 준비 중"(회색) · "환경 준비 실패"(빨강), 완료면 표시 안 함 | 가입일은 추가하기로 결정. 칩은 스키마 생성 · 환경 준비 실패 테넌트를 목록에서 찾을 수 있게 |
| 승인 성공 시 피드백 | "별도 안내 없이 모달이 닫힘" | 모달은 닫히고 하단 토스트("승인했습니다") 표시 | 처리 결과 인지용 — 제거 여부 확인 필요 |
| 이벤트 상세의 상태 로그 | "신청 상태 로그"(신청 상태만) | 섹션 "상태 로그" — 기존 가로 타임라인(점 · 선, 날짜, 단계 이름 + 상태 배지, 설명) 그대로 신청 · 호스팅 · 이벤트 항목을 시각순으로. 단계 이름 앞에 작은 구분 라벨(신청/호스팅/이벤트), 시각 옆에 처리자(운영자 이름 / 테넌트 / 자동 처리). 원인(반려 사유)은 설명 아래 짧게(60자 넘으면 말줄임 + [펼치기]), 실패 계열은 빨강. 항목이 많으면 가로 스크롤(칸 최소 200px, 좁은 화면은 세로). 신청 · 진행 상세 공용. 단계 이름 · 설명 문구는 `pages/events/eventView.ts`의 `STATUS_LOG_TEXT` | 호스팅 배포 실패 원인(예: EventAppSyncTimedOut)을 운영자가 Console에서 봐야 함 |
| 승인 · 반려 실패 | 정의 없음 | 409(이미 처리됨 · 호스팅 기간 지남 · 환경 준비 전)는 모달 안에 서버 문구, 이미 처리됨이면 상세를 다시 받아 현재 상태 표시. 반려 사유 400은 입력란 오류 | 다른 운영자가 먼저 처리한 경우 등 |
| 대시보드 클러스터 · 실시간 트래픽 · 테넌트 별 처리 현황 | 실제 값 | 예시 데이터 + "예시 데이터" 칩 | API 없음(모니터링 연동 전) — 실제 숫자와 섞여 오해하지 않게 |
| 비밀번호 저장 완료 후 이동 | Tetra Portal 로그인(URL 미확정) | `VITE_PORTAL_LOGIN_URL`(기본 `/login`) | 도메인 확정 후 환경 변수로 지정 |
| 신청 내용(OPS-03-2·04-2), 리포트 | Tetra Portal 화면 재사용 | 안내 문구 placeholder | Portal 컴포넌트 미전달 |
| 알림센터(OPS-08) | Tetra Portal 알림센터(UI-TP-13) 재사용 | Portal 구조(탭 5종 · 건수 · 내용 검색 · 최신순 리스트 · 안읽음 점 · 클릭 시 읽음)를 Console 컴포넌트로 다시 만듦. 추가: 항목마다 테넌트명(없으면 -), [모두 읽음] 버튼(안읽음 0이면 비활성). Slack 영역은 "연동 예정" 칩만(채널 상자 없음). 읽음은 화면을 먼저 바꾸고 실패하면 되돌린 뒤 토스트. 탭 건수는 검색어와 무관하게 전체 기준. 우선순위 색은 대시보드 최근 알림 아이콘과 같은 `utils/status.ts`의 `priorityMeta` | 운영 콘솔은 모든 알림을 종류 무관하게 보이고 테넌트 구분이 필요. 읽음은 운영자 공통(한 명이 읽으면 모두에게 읽음) |
| 대시보드 KPI·실시간 트래픽 숫자 | 일반 숫자 | 네이비 띠 강조(A안 확정, `tokens.css`의 `--kpi-*`) | 디자인 결정 |
| 상단바 | 경로(브레드크럼) + 페이지 제목 | 연한 톤 상단바 하나에 상위 경로 + 제목(h2) 1회 표시 | 제목 중복 제거 (디자인 결정) |

## 남은 작업

1. **디자인** — Canva 팔레트 + Snow 타이포·간격, 글자 125%, CSS 토큰 모듈화, KPI 강조(A안 확정), 상단바 통일 반영 완료. 스타일 가이드 갱신 완료
2. **검증** — v0.5 반영 후 승인(메일 성공·실패)·반려·테넌트 정보 수정·등록(중복 확인, 메일 성공·실패)·비밀번호 8자 규칙을 실제 조작으로 확인 완료. 모바일 폭 전 화면 확인은 남음
3. **API 연동** — 로그인 · 테넌트 · 이벤트 신청/진행 · 승인/반려 · 진행 결과 · 발급 이력 CSV · 대시보드(테넌트 수 · 진행 중 이벤트 · 최근 알림) 연결 완료, 목 저장소(AppDataContext) 삭제. 알림센터(목록 · 읽음 · 모두 읽음) 연결 완료. 남은 것: 대시보드 클러스터 · 트래픽 · 테넌트 별 처리 현황(API 없음, 예시 데이터), 신청 내용 · 리포트(placeholder), 비밀번호 설정 화면(3-19), 비밀번호 설정 메일 재발송(OPS-07, API 없음)
4. **미확정 항목 확정 대기** — 트래픽 모니터링(OPS-05), 비밀번호 문자 조합 규칙, Portal 로그인 URL, Portal 재사용 화면 2종(신청 내용 · 리포트)

## 구조

```
src/
  styles/         # tokens.css(디자인 토큰 — 수정은 여기서), fonts.css
  theme/          # tokens.ts(CSS 변수 → TS 이름 연결), theme.ts(MUI 테마)
  components/
    layout/       # 사이드바, 페이지 헤더, 사용자 메뉴
    common/       # 상태칩, 정보목록, 통계카드, 테이블 카드 등
  pages/          # 화면별 페이지
  api/            # API 호출(client · auth · tenants · events · dashboard · notifications)과 응답 타입(types.ts)
  hooks/          # useApiQuery(불러오기 · 로딩 · 오류 · 다시 시도)
  store/          # AuthContext(로그인 상태), Toast
  mocks/          # 대시보드 예시 데이터(API 없는 카드만)
  types/          # 화면 도메인 타입
  utils/          # 상태 라벨/톤, 일시(datetime · KST), 포맷, 검증, API 오류 → 폼(formErrors)
```

## API 연동 지점

- 호출 모듈 `src/api/client.ts`: `apiRequest('/me')` → `/api/ops/me`. 같은 출처 쿠키, 오류 `{code, message, fields}` → `ApiError`(화면에는 `message`), 401이면 로그인 화면으로. 연결 실패 · JSON 아닌 오류는 공통 문구
- 로그인 상태 `src/store/AuthContext.tsx`: `RequireAuth`가 `/me` 확인 전에는 콘솔 화면을 그리지 않는다. 화면에서는 `useOperator()` · `useAuth().logout()`
- 테스트: `src/**/*.test.ts`를 `node --test`가 TypeScript 그대로 실행한다. 그래서 **테스트 대상 파일과 그 import 사슬은 상대 경로 + `.ts` 확장자로 import**한다(`@/` 별칭 · 확장자 없는 import는 Node가 못 푼다). 테스트 파일은 `tsc` 대상에서 뺀다(`@types/node` 없음). 화면(DOM) 테스트 도구는 아직 없다

- 응답 타입 `src/api/types.ts`: 서버 DTO 이름 그대로 손으로 옮긴다(새 패키지 없음). `/v3/api-docs`로 교차 확인. 일시는 ISO-8601 문자열이고 화면 표시는 `utils/datetime.ts`(KST 고정)
- 불러오기 `src/hooks/useApiQuery.ts`: 화면에 들어올 때 부르고 `loading` · `error` · `reload` · `setData`(수정 응답 반영). 표시는 `components/common/QueryStatus.tsx`(스피너 / 서버 문구 + 다시 시도)
- 폼 오류 `utils/formErrors.ts`: 400 `fields` → 입력란, 409 `EMAIL_DUPLICATED` → email 입력란, 그 밖 → 폼 위 Alert

- 응답 → 화면 모델(순수 함수, node 테스트): 이벤트 `pages/events/eventView.ts`(상태 로그 `statusLogs` → 가로 타임라인 `StatusTimeline`, 반려 사유 · 처리 일시는 그중 신청(APPLICATION) 로그에서, 결과 state `IN_PROGRESS`는 지금 시각과 오픈 시각으로 오픈 전/진행 중), 승인 · 반려 실패 `pages/events/decision.ts`, 테넌트 `pages/tenants/tenantView.ts`, 대시보드 알림 `pages/dashboard/dashboardView.ts`, 알림센터 필터 · 건수 · 검색 · 읽음 `pages/logs/notificationView.ts`

`TODO:` 주석 위치:

- 비밀번호 설정 토큰 검증/저장 `pages/auth/PasswordSetupPage.tsx`
- 실시간 트래픽 `pages/dashboard/LiveTrafficCard.tsx` (예시 데이터)

## 디자인 시스템

- 규칙: 스타일 가이드 (저장소 밖에서 관리)
- 값: `src/styles/tokens.css` — 색·글자 크기(`--type-scale`)·모서리·간격·컴포넌트 크기를 이 파일에서만 수정합니다.

## 폰트

- 본문: 프리젠테이션(Freesentation, SIL OFL) — noonnu CDN
- 제목: 페이퍼로지(Paperlogy, SIL OFL) — noonnu CDN, 300~900 굵기
- 굵기는 역할별 토큰으로 씁니다 (`tokens.css`의 `--fw-*`, `--fw-heading-*`) — 자세한 규칙은 스타일 가이드 3장

## 환경 변수

- `VITE_PORTAL_LOGIN_URL`: 비밀번호 설정 완료 후 이동할 Tetra Portal 로그인 주소 (기본값 `/login`)
