# Tetra Operations Console API

운영 콘솔 API(Spring Boot). 저장소 안내는 [../README.md](../README.md), 작업 기록은 [docs/WORKLOG.md](docs/WORKLOG.md).

## 감사 로그(접속기록)

운영자의 Console 행위를 접속기록(개인정보 안전성 확보조치 기준 제8조)으로 2년 남긴다. 앱은 **stdout에 JSON 한 줄**만 내고,
옮기고 보관하는 일은 인프라가 한다: 로그 그룹 `/ecs/tetra-dev/console` → 구독 필터 `{ $.log_type = "AUDIT" }` → Firehose →
S3 `audit-logs/tenant_id=<tenant_id>/dt=<yyyy-MM-dd>/`(tenant_id가 null이면 `tenant_id=none`). Portal과 같은 형식이다(코드는 복사본, 공용 라이브러리 없음).

- **무엇을**: `/api/ops/**` 요청 **전부**(조회 GET 포함, 헬스 체크 · 화면 파일 제외). 화면이 15초마다 폴링해 조회 줄이 많다 — 정상.
- **언제**: 응답이 끝난 뒤 한 줄. 감사 줄을 만들지 못해도 응답은 그대로(WARN 한 줄, 값 없음).
- **코드**: `common/audit/` — `AuditFilter`(보안 체인 앞 바깥 필터) · `AuditPrincipalInterceptor`(JWT sub 복사) · `AuditActions`(행위 표) ·
  `AuditContext`(서비스가 넣는 보강 값, 요청 밖이면 아무것도 안 함) · `AuditEvent` · `AuditJson` · `AuditLogger` · `ClientIp` · `RequestIds` · `Masking`.
  로거 `AUDIT`는 `logback-spring.xml`에서 패턴 `%msg%n` · `additivity=false`(일반 로그는 Boot 기본 형식 그대로).

### 필드 (키 순서 고정)

| 키 | 값 |
| --- | --- |
| `log_type` | `AUDIT` |
| `audit_id` | 줄마다 새 UUID |
| `time` | 요청 시작 시각, KST ms (`2026-11-05T14:03:21.512+09:00`) |
| `app` | `console` |
| `actor` | `{type: OPERATOR, id: <JWT sub = operator_id>}`, 인증 전 · 보안 체인에서 끝난 요청은 `null` |
| `ip` | 원본 `X-Forwarded-For`의 **맨 오른쪽** 값(ALB가 붙인 값), 없으면 접속 주소. **Console은 SSM 포트 포워딩으로 들어오므로 중계 지점의 사설 IP다 — 실제 접속자는 CloudTrail `StartSession`으로 본다** |
| `user_agent` | 512자까지 |
| `action` | 아래 표. 표에 없으면 `API_REQUEST` |
| `path` | `API_REQUEST`일 때만. 쿼리를 뺀 경로, 256자까지 |
| `target` | `{type, id}` 또는 `null` |
| `tenant_id` | 아래 규칙, 없으면 `null` |
| `event_id` | 이벤트 대상일 때(경로 변수 · 리포트 수동 생성 본문) |
| `result` | 상태 < 400 → `SUCCESS`, 그 밖 → `FAILURE` |
| `reason` | 실패일 때 오류 응답의 `code`(`ErrorCode`), 필터까지 나온 예외는 `UNHANDLED` |
| `changes` | 성공일 때 바뀐 필드 `{필드: {before, after}}`, 값 200자까지. 줄이 8KB를 넘으면 비우고 `truncated: true` |
| `attempt` | 로그인 실패일 때 입력한 이메일(가림) |
| `request_id` | `X-Amzn-Trace-Id`의 `Self`, 없으면 `Root`, 형식이 틀리거나 없으면 UUID(일반 로그 MDC `request_id`에도 넣지만 패턴에는 찍히지 않음) |

`actor` · `target` · `tenant_id`는 값이 없어도 `null`로 남고, 나머지는 값이 있을 때만 나간다. 한글 등은 `\uXXXX`로 이스케이프해 줄은 늘 ASCII 한 줄이다.

### 행위 코드 (`/api/ops` 생략)

| 메서드 · 경로 | action | target | 보강 |
| --- | --- | --- | --- |
| POST `/auth/login` | `AUTH_LOGIN` | OPERATOR(성공 시) | 성공: actor · target = 운영자. 실패: `attempt` |
| POST `/auth/logout` | `AUTH_LOGOUT` | OPERATOR | |
| GET `/me` | `ACCOUNT_VIEW` | OPERATOR | |
| GET `/dashboard` | `DASHBOARD_VIEW` | — | |
| GET `/applications` | `APPLICATION_LIST_VIEW` | — | |
| GET `/live-events` | `LIVE_EVENT_LIST_VIEW` | — | |
| GET `/events/{eventId}` | `EVENT_VIEW` | EVENT | |
| POST `/events/{eventId}/approve` | `EVENT_APPROVE` | EVENT | `application_status` APPLIED → APPROVED |
| POST `/events/{eventId}/reject` | `EVENT_REJECT` | EVENT | `application_status` APPLIED → REJECTED, `reject_reason`(200자) |
| GET `/events/{eventId}/result` | `EVENT_RESULT_VIEW` | EVENT | |
| GET `/events/{eventId}/result/history-csv` | `ISSUANCE_CSV_DOWNLOAD` | EVENT | |
| GET `/events/{eventId}/application-summary` | `APPLICATION_SUMMARY_VIEW` | EVENT | |
| GET `/monitoring/overview` | `MONITORING_OVERVIEW_VIEW` | — | |
| GET `/monitoring/platform` | `TRAFFIC_PLATFORM_VIEW` | — | |
| GET `/monitoring/events` | `TRAFFIC_EVENT_LIST_VIEW` | — | |
| GET `/monitoring/events/{eventId}` | `TRAFFIC_EVENT_VIEW` | EVENT | |
| GET `/monitoring/events/{eventId}/executions` | `EXECUTION_VIEW` | EVENT | |
| GET `/notifications` | `NOTIFICATION_LIST_VIEW` | — | 검색어 `q` 안 남김 |
| GET `/notifications/unread-count` | `NOTIFICATION_COUNT_VIEW` | — | |
| PATCH `/notifications/{notificationId}/read` | `NOTIFICATION_READ` | NOTIFICATION | |
| PATCH `/notifications/read-all` | `NOTIFICATION_READ_ALL` | — | |
| GET `/reports` | `REPORT_LIST_VIEW` | — | |
| GET `/reports/{reportId}/download` | `REPORT_DOWNLOAD` | REPORT | |
| POST `/reports/{reportId}/email` | `REPORT_MAIL` | REPORT | |
| POST `/reports/generate` | `REPORT_GENERATE` | `EVENT_RESULT`: EVENT(본문 eventId) · `OPERATOR_AUDIT`: REPORT(만든 메타 id) | `EVENT_RESULT`: `event_id` = 본문, `tenant_id` = 처리에서 찾은 테넌트. `OPERATOR_AUDIT`: 테넌트 · 이벤트 없음 |
| GET `/tenants` | `TENANT_LIST_VIEW` | — | |
| GET `/tenants/email-availability` | `TENANT_EMAIL_CHECK` | — | 이메일 안 남김 |
| POST `/tenants` | `TENANT_REGISTER` | TENANT(새 id) | after: `name` · `email`(가림) · `root_domain` · `contact_name`(가림) |
| GET `/tenants/{tenantId}` | `TENANT_VIEW` | TENANT | |
| PATCH `/tenants/{tenantId}` | `TENANT_UPDATE` | TENANT | 바뀐 필드 before/after(이메일 · 담당자 이름 가림) |
| GET `/tenants/{tenantId}/events` | `TENANT_EVENTS_VIEW` | TENANT | |
| GET `/tenants/{tenantId}/resources` | `TENANT_RESOURCES_VIEW` | TENANT | |

- 행위는 MVC가 고른 경로 패턴 + 메서드로 정한다. 표에 없는 엔드포인트, 패턴이 없는 요청(보안 체인의 401 · 403, 405, 없는 경로 404)은
  `API_REQUEST` + `path`(쿼리 없음). 401 · 403은 actor `null`.
- **엔드포인트를 더하면 `AuditActions.ENTRIES`에 코드(`{대상}_{동사}`)를 붙인다.** `AuditLogApiTest.everyApiMappingHasAnActionCode`가 컨트롤러 매핑과 표가 다르면 실패한다.
- **짧은 한글 이름은 `AuditActions.NAMES`**(운영자 접속기록 CSV '행위' 열, 예 `AUTH_LOGIN` 로그인 · `ISSUANCE_CSV_DOWNLOAD` 발급 이력 CSV 다운로드 · `API_REQUEST` 기타 요청). 코드를 더하면 이름도 더한다 — `AuditActionNamesTest`가 빠진 코드 · 남는 이름을 잡는다.

### tenant_id

1. 보강 값: 테넌트 등록(새 id).
2. 경로 변수 `{tenantId}`(형식 `^[a-z0-9]{12}$`일 때만).
3. 이벤트(`event_id`)가 있으면 `event_registry.tenant_id` — 서비스가 이미 찾았으면(`TenantRouter.findEvent`) 그 값을 쓰고, 아니면 필터가 끝날 때 PK로 한 번 조회. 조회가 실패해도 줄은 남는다.
4. 그 밖(여러 테넌트 목록 · 알림 · 운영자 리포트 · 못 찾음)은 `null` → S3 `tenant_id=none/`.

### 넣지 않는 것 · 가림

- 비밀번호 · JWT · 쿠키 · 비밀번호 설정 링크와 서명 · presigned URL · 요청 본문 · 쿼리(검색어 · 이메일 확인 값) · 스택.
- 이메일 `ki***@ex***.com`, 담당자 이름 `박*호`(`Masking`). 원문은 감사 줄 · 일반 로그 어디에도 없다.

### 설정

- `server.forward-headers-strategy: none`(명시) — ip를 앱이 XFF 맨 오른쪽 값으로 고르는 전제. 앱은 요청으로 URL · scheme을 만들지 않는다.
- Console은 HSTS를 보내지 않는다 — SSM 터널 localhost로 접속해 HSTS가 localhost 전체에 걸리는 것을 피함. Portal은 보냄.
- API 문서(`/v3/api-docs`)의 서버 주소는 상대 경로 `/`.

## 리포트

OPS-09 리포트 화면(`/logs/reports`)은 `operator_report_meta`를 보여 주고, 위쪽 **[리포트 만들기]** 모달이 `POST /api/ops/reports/generate`를 부른다.
저장은 `ReportStore`(S3 먼저 → 같은 `file_path`가 없을 때만 메타 + REPORT 알림 — 같은 리포트를 다시 만들면 파일만 덮어씀, `created: false`).

| 종류(`category`) | 본문 | 만드는 것 |
| --- | --- | --- |
| `OPERATOR_AUDIT` 운영자 접속기록 | `{ "category": "OPERATOR_AUDIT", "from": "2026-11-01", "to": "2026-11-05" }` | 기간(KST 날짜, 양끝 포함)의 운영자 감사 줄 CSV 하나 → 운영자 리포트 버킷 · OPS-09 목록 |
| `EVENT_RESULT` 이벤트 결과 | `{ "category": "EVENT_RESULT", "eventId": 1043 }` | 이벤트 하나의 결과 zip → 테넌트 로그 버킷 · 테넌트(Portal) 리포트 화면 |
| `MONTHLY` | — | 아직 없음 — 501 `NOT_IMPLEMENTED` |

- 응답 200 `{category, eventId, tenantId, filePath, created, from, to, rows}` — 운영자 접속기록은 `eventId` · `tenantId` null, `rows` = CSV 데이터 줄 수. 이벤트 결과는 `from` · `to` · `rows` null. `filePath`는 버킷 안의 키(버킷 이름 · 링크 없음).
- 오류: 400 `INVALID_INPUT`(`fields`: 빈 값 · from > to · to가 오늘(KST) 뒤 · 기간 31일 넘음 · 분류에 쓰지 않는 값 — 예 EVENT_RESULT에 from), 404 `EVENT_NOT_FOUND`, 409 `REPORT_BUSY`(같은 분류 리포트를 다른 요청 · 작업이 만드는 중) · `RESULT_NOT_READY` · `TENANT_NOT_READY` · `AUDIT_BUCKET_NOT_CONFIGURED`, 501 `NOT_IMPLEMENTED`(MONTHLY), 401.
- 감사 줄은 `REPORT_GENERATE`(대상은 위 행위 코드 표).

### 운영자 접속기록 (`report/audit/`)

- **읽는 곳.** 감사 버킷 `audit-logs/tenant_id=<12자 또는 none>/dt=YYYY-MM-DD/*.jsonl.gz`. 목록은 늘 `audit-logs/`로 시작하는 접두어(태스크 역할 `s3:ListBucket` 조건 `s3:prefix = audit-logs/*`): `audit-logs/` 아래 `tenant_id=` 폴더 → 폴더마다 `dt=from ~ to+1일`. dt는 Firehose가 받은 시각(KST)이라 자정 근처 줄은 다음 날 폴더에 있다 — 줄의 `time`(KST)으로 기간을 다시 거른다. 목록 · 읽기는 동시에 8개까지(`OperatorAuditReader`).
- **파일.** gzip(첫 두 바이트로 판단) 또는 평문, JSON 한 줄씩(레코드 사이에만 줄바꿈 — 마지막 줄 끝 줄바꿈 없음). 빈 줄은 무시, `"app":"console"`(log_type AUDIT)만 — Portal 줄은 넣지 않는다. 읽지 못한 줄(JSON · time 형식 오류)은 빼고 WARN 한 줄에 개수.
- **감사 버킷 이름.** `tetra.report.audit-bucket`(`REPORT_AUDIT_BUCKET`). 비우면 운영자 리포트 버킷 이름의 `-operator-logs-`를 `-audit-`로 바꾼다(dev `tetra-dev-operator-logs-<계정>` → `tetra-dev-audit-<계정>`). 바꿀 수 없는 이름이면 기동은 하고 WARN 한 줄, 생성 요청은 409 `AUDIT_BUCKET_NOT_CONFIGURED`.
- **잠금.** `DbLock("tetra:report-operator-audit")`을 기다리지 않고 잡음(못 잡으면 409 `REPORT_BUSY`).
- **저장.** 키 `reports/{yyyy}/{mm}/operator-audit-{yyyyMMdd}-{yyyyMMdd}.csv`(yyyy/mm = from의 달), 이름 `운영자 접속기록 2026-11-01 ~ 2026-11-05`, 메타 기간 from 00:00 ~ to 23:59:59 KST. 운영자 알림 REPORT/INFO `[2026-11-01 ~ 2026-11-05] 운영자 접속기록 리포트가 생성되었습니다. …`, 중복 방지 키 `REPORT:OPERATOR_AUDIT:2026-11-01~2026-11-05`(기간 기준).
- **CSV**(UTF-8 BOM · CRLF · 수식 가림 켬, 일시 오름차순, 줄이 없으면 머리글만). 열: `일시(KST)` · `운영자 ID` · `운영자 이름`(운영자 표 `contact_name`, 이메일 없음) · `행위`(`AuditActions.NAMES`) · `행위 코드` · `대상 종류` · `대상 ID` · `테넌트` · `이벤트` · `결과`(성공/실패) · `사유 코드` · `접속 IP` · `경로`(API_REQUEST만) · `변경 내용`(changes JSON 원문 — 이미 가린 값) · `시도 계정(가림)`(로그인 실패만) · `요청 ID` · `다운로드 사유(점검자 기입)`(`ISSUANCE_CSV_DOWNLOAD` · `REPORT_DOWNLOAD` 줄만 빈 칸, 나머지 `-` — `CsvWriter.Literal`로 가림 없이). user_agent는 넣지 않는다.
- **한계.** 최근 15~20분(Firehose 버퍼) 기록은 아직 S3에 없을 수 있다. 줄은 메모리에 모아 CSV를 한 번에 만든다(기간 상한 31일). 하루 넘게 늦게 옮겨진 줄은 없다고 본다.
- **로컬.** local 프로필은 S3 대신 `build/local-s3/{감사 버킷}/audit-logs/…` 파일을 읽고(`LocalObjectReader`), 만든 CSV는 `build/local-s3/{운영자 리포트 버킷}/reports/…`에 남는다(`LocalObjectStorage`).
