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
| POST `/reports/generate` | `REPORT_GENERATE` | EVENT(본문 eventId) | `event_id` = 본문, `tenant_id` = 처리에서 찾은 테넌트 |
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
