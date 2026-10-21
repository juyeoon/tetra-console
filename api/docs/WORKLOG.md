# WORKLOG

## 2026-10-14 KST — 지침 수정 (DDL 경로, Spring Boot 버전)
- 한 일: CLAUDE.md의 DDL 경로를 실제 위치 `docs/db/init/`로 고침(§2, §3, §12). 기술 스택을 Spring Boot 4.1.x + Spring Cloud AWS 4.2.x로 바꿈(3.x 무료 지원 종료, 사용자 요청)
- 파일: CLAUDE.md, WORKLOG.md(신규)
- 남은 것: Spring Cloud AWS 공식 호환표가 Boot 4.0.x까지라 Boot 4.1에서의 SQS 동작을 통합 테스트로 확인

## 2026-10-14 KST — 지침 수정 (init.sql 역할)
- 한 일: CLAUDE.md §2·§3에서 `docs/db/init/*.sql`을 "로컬 테스트용 DDL"로 명시. 엔티티 기준은 물리 ERD, 운영 스키마는 WF5 마이그레이션 Job
- 파일: CLAUDE.md
- 남은 것: 로컬 실행용 docker-compose(MySQL 8.4 + init.sql) 추가 예정

## 2026-10-14 KST — 프로젝트 뼈대, 엔티티, 테넌트 라우팅
- 한 일: Spring Initializr로 Boot 4.1.1 / Gradle 9.7.1 프로젝트 생성. Spring Cloud AWS 4.2.0 BOM, AWS SDK(sfn·sesv2·s3) 추가. application.yml(환경 변수 참조만), TetraProperties(@Validated, 비밀 값 기본값 없음). 엔티티 18개(공용 catalog 지정), 상태 enum, 스키마 멀티테넌시(SchemaTenantIdentifierResolver, SchemaMultiTenantConnectionProvider: 반납 시 tetra_platform 복귀, 실패 시 풀에서 제거), 이중 확인(TenantGuardListener). 테스트 기반(MySQL 8.4 Testcontainers, 공용 1 + 테넌트 2 스키마, DML 전용 앱 계정), ddl-auto=validate 테스트 통과
- 파일: build.gradle, settings.gradle, gradlew*, gradle/, src/main/resources/application.yml, src/main/java/com/tetra/ops/{OpsApiApplication, config/*, tenancy/*, domain/**, common/time/Kst}.java, src/test/java/com/tetra/ops/{SchemaValidationTest, support/MySqlTestDatabase}.java, src/test/resources/{application-test.yml, seed/platform_base.sql}
- 남은 것: INT UNSIGNED 컬럼은 Long + columnDefinition "int unsigned"로 매핑(TODO(미정) event_id 타입). 오프라인 JDK 21은 ~/.jdks에 설치(JAVA_HOME 지정 필요)

## 2026-10-14 KST — API·상태 변경·리스너 구현
- 한 일: §6 경로 전체 구현. 오류 응답(ErrorCode enum, GlobalExceptionHandler), JWT 쿠키 인증(Spring Security 리소스 서버 + Nimbus HS256), TenantRouter(eventId/tenantId → READY 확인 → 컨텍스트), EventStatusService(승인·반려·배포 결과, 조건부 UPDATE, registry → event 순서), TenantActivityService.deactivateIfIdle, 승인(slug 재시도 5회, RSA 2048, 커밋 후 SFN·SES), 반려, 이벤트 상세·진행 결과(state 4종)·CSV presigned URL, OPS-03·04 목록, 대시보드, 테넌트 목록·개요(배지)·이벤트·리소스·수정·이메일 확인·등록(WF5, HMAC 링크, OPS-07-1 메일), SQS 리스너 2개, 501 껍데기 3개
- 파일: src/main/java/com/tetra/ops/{auth, common, config, dashboard, event, external, listener, stub, tenant, tenancy/TenantRouter}/*, domain/**/*Repository.java
- 남은 것: TODO(미정) — 개인키 메일 문구, SFN input 키 이름·일시 형식, history_file_path 형식, 이메일 형식 규칙, 06-3 정렬, CSRF, READY 아닌 테넌트 응답 코드

## 2026-10-14 KST — §12 테스트, 배포·로컬 실행 파일
- 한 일: 통합 테스트 67개(MySQL 8.4 Testcontainers, 풀 크기 1로 반납 시 스키마 초기화까지 검증). 승인·반려 트랜잭션 내용, CHECK 제약, 중복 요청·중복 메시지, 교차 접근·이중 확인, 테넌트 비활성, SFN·SES 실패, 비밀 값 로그 미노출, 설정 누락 시 기동 실패, ElasticMQ로 실제 @SqsListener ack/재전달 확인(Boot 4.1 + Spring Cloud AWS 4.2 동작 확인). 이벤트 bulk UPDATE에 tenant_id 조건 추가. 로컬 docker-compose로 실제 jar 스모크 테스트(로그인→승인→409, 로그에 개인키·JWT·비밀번호 없음) 후 정리
- 파일: src/test/**, .gitignore(.env·application-local.yml·docs/), .gitattributes, .env.example, deploy/example/{deployment,configmap,secret,serviceaccount}.yaml, Dockerfile, docker-compose.yml, docker/local/mysql-init/00-init.sh, docs/db/local/local_seed.sql, build.gradle(plain jar 끔, app.jar), TetraProperties(csv 기본값 버그 수정)
- 남은 것: docs/를 gitignore에 넣어 테스트가 docs/db/init에 의존(새 clone·CI에서 DDL 필요). 승인 시 hosting_end_at이 이미 지났으면 chk_auth_key_expires로 500

## 2026-10-14 KST — 반려 시 테넌트 알림 기록, 이미지 빌드에서 docs 제외
- 한 일: 반려 트랜잭션에 tenant_notification INSERT 추가(이벤트명·반려 사유 포함, is_read 0). CLAUDE.md §7.4에서 "테넌트 알림은 기록하지 않는다" 삭제하고 INSERT 항목 추가. .dockerignore로 빌드 컨텍스트를 app.jar만 남김(docs/·init.sql 제외), docker build로 확인. 전체 테스트 68개 통과
- 파일: CLAUDE.md, src/main/java/com/tetra/ops/event/EventStatusService.java, .../domain/tenant/TenantNotification.java, .../domain/tenant/TenantNotificationRepository.java(신규), src/test/java/com/tetra/ops/RejectApiTest.java, .dockerignore(신규)
- 남은 것: TODO(미정) 반려 알림 category·priority·문구(임시 APPLICATION / WARNING)

## 2026-10-15 KST — 결정 반영 (승인 409, 비활성 기준, 승인 알림, 총 재고, 배포 결과 보정)
- 한 일: 호스팅 종료 시각이 지난 신청 승인 시 409 HOSTING_PERIOD_ENDED(트랜잭션 롤백, 외부 호출 없음). 테넌트 비활성 판정을 화면설계서 OPS-06 기준으로 변경(APPLIED 또는 APPROVED·SCHEDULED/OPEN, OPEN_FAILED 제외). 승인 트랜잭션에 tenant_notification INSERT 추가. OPS-04-3 총 재고를 event.stock_count로 변경. SQS 승인 결과 SUCCEEDED면 DEPLOY_FAILED → DEPLOYED도 허용. CLAUDE.md §7.3·§7.4·§7.8·§8 반영. 테스트 72개 통과
- 파일: CLAUDE.md, src/main/java/com/tetra/ops/{common/error/ErrorCode, event/EventStatusService, event/EventReader, domain/platform/EventRegistryRepository, tenant/TenantActivityService}.java, src/test/java/com/tetra/ops/{ApproveApiTest, RejectApiTest, StepFunctionsResultHandlerTest, EventQueryApiTest}.java
- 남은 것: TODO(미정) 승인 알림 priority·문구(임시 INFO). OPS-04-3 탭 막기 조건은 프런트·화면설계서 담당과 확인

## 2026-10-15 KST — 상태 변화 알림 (운영자·테넌트)
- 한 일: NotificationService 추가(알림은 이 서비스에서만). 승인·반려(운영자+테넌트), 배포 결과(PENDING→DEPLOYED/DEPLOY_FAILED, DEPLOY_FAILED→DEPLOYED), 테넌트 등록·자동 비활성, 환경 준비 READY/FAILED, 개인키·비밀번호 설정 메일 실패를 운영자 알림으로 남김. 우선순위는 추천표대로(실패 URGENT, 반려 WARNING, 그 외 INFO). 중복 메시지는 상태가 안 바뀌어 알림도 1건. CLAUDE.md §5·§7.3·§7.4·§7.12·§8 수정, §7.13 알림 표 추가. 테스트 78개 통과
- 파일: CLAUDE.md, src/main/java/com/tetra/ops/notification/NotificationService.java(신규), .../domain/platform/OperatorNotification.java, .../event/{EventStatusService, EventCommandService}.java, .../tenant/{TenantActivityService, TenantWriter, TenantCommandService}.java, .../listener/StepFunctionsResultHandler.java, src/test/java/com/tetra/ops/NotificationTest.java(신규)
- 남은 것: TODO(미정) 알림 문구

## 2026-10-15 KST — 저장소 구조 변경 (api/ 하위로 이동)
- 한 일: 프런트와 저장소를 같이 쓰기 위해 이 앱의 파일 전체를 api/ 아래로 옮김(docs/ 포함, build·.gradle은 지우고 다시 빌드). 루트에 공통 .gitignore(OS·IDE·.env)와 .gitattributes(gradlew·*.sh LF, *.bat·*.cmd CRLF, *.jar binary) 새로 작성, 기존 .gitignore는 api/.gitignore(앱 전용)로 둠. CLAUDE.md에 저장소 구조 설명 추가, WORKLOG 위치를 api/로 정정. api/에서 전체 빌드·테스트 78개 통과
- 파일: ../.gitignore(신규), ../.gitattributes(이동·수정), .gitignore, CLAUDE.md, (그 외 전 파일 경로 이동)
- 남은 것: 없음

## 2026-10-15 KST — 문서 폴더 정리, deploy·지침 ignore, K8s 이식 문서
- 한 일: 참고 문서 폴더 docs/ → dev-docs/(커밋하지 않음)로 이름 변경, 새 docs/(커밋함)를 만들고 WORKLOG.md를 docs/로 이동. api/.gitignore에 dev-docs/·CLAUDE.md·deploy/ 추가(deploy는 K8s 설계 담당이 관리). 테스트(MySqlTestDatabase)·docker-compose·.dockerignore의 경로를 dev-docs로 변경. CLAUDE.md에 문서 폴더 규칙 추가, §2·§3·§10·§12·§13·작업 로그 경로 수정. K8s 설계 담당용 dev-docs/K8s_이식_반영사항.md 작성(환경 변수, IRSA 권한, 사전 인프라, DB, 프로브·종료, 확장, 네트워크·HTTPS, 예시 매니페스트). 전체 테스트 78개 통과
- 파일: docs/WORKLOG.md(이동), dev-docs/(이름 변경), dev-docs/K8s_이식_반영사항.md(신규), .gitignore, .dockerignore, docker-compose.yml, CLAUDE.md, src/test/java/com/tetra/ops/{SchemaValidationTest, support/MySqlTestDatabase}.java
- 남은 것: K8s 문서의 리소스 값·Secret 채우는 방식은 담당 결정

## 2026-10-15 KST — 팀 브랜치·커밋 규칙을 지침에 반영
- 한 일: CLAUDE.md Git 항목에 팀 규칙 추가(브랜치 <type>/<이슈번호>-<설명>, type 9종, 커밋 <type>(<scope>): <요약> + 본문 + Refs #이슈, scope는 web·api, 봇 형식 제외)
- 파일: CLAUDE.md
- 남은 것: 규칙 강제(commit-msg 훅·CI)와 루트 문서 위치는 확인 후 진행

## 2026-10-15 KST — 화면(web)과 한 이미지로 서빙: 보안 설정·SPA 경로·dist 포함
- 한 일: SecurityConfig를 /api/**·/actuator/**만 인증, 나머지(화면 파일) 공개로 변경. CookieBearerTokenResolver가 인증 경로에서만 쿠키를 읽게 함(화면 요청에 만료 쿠키가 붙어도 401 안 남). SpaWebConfig 추가(있는 파일은 그대로, /api/**·확장자 있는 경로는 넘기지 않음, 그 밖은 index.html). build.gradle processResources에서 ../web/dist를 classpath:static/에 포함(JAR, WAR 아님). 폴더 이름은 컨벤션대로 web/. CLAUDE.md 저장소 구조·§3 반영, Dockerfile·.gitattributes 주석 수정. SpaRoutingTest 5개 추가(테스트용 static은 src/test/resources/static), 임시 복사본에서 web/dist가 jar의 static/에 들어가는 것 확인. 전체 테스트 83개 통과
- 파일: src/main/java/com/tetra/ops/config/{SecurityConfig, SpaWebConfig(신규)}.java, .../auth/CookieBearerTokenResolver.java, build.gradle, Dockerfile, CLAUDE.md, ../.gitattributes, src/test/java/com/tetra/ops/SpaRoutingTest.java(신규), src/test/resources/static/{index.html, assets/app.js}(신규)
- 남은 것: 정적 파일 캐시 헤더(index.html no-cache, 해시 파일 장기 캐시)는 web 빌드 결과를 보고 결정

## 2026-10-15 KST — 프런트용 API 문서 자동 생성 (springdoc)
- 한 일: springdoc-openapi-starter-webmvc-ui 3.1.1 추가(Spring Boot 4.1 기준 버전). /v3/api-docs(JSON)·/swagger-ui.html, 문서화 대상 /api/**, API_DOCS_ENABLED로만 켬(기본 false). OpenApiConfig: 설명(쿠키 인증·일시 +09:00·상태 값·오류 형식), 쿠키 인증 스킴, ErrorResponse 스키마, @ApiErrorCodes로 엔드포인트별 오류 code를 상태 코드별로 문서화(401·500 자동). 컨트롤러에 화면 ID 태그·설명, 요청·응답 주요 필드 @Schema. ApiDocsTest 3개(경로 19개·쿠키 인증·오류 code·Swagger UI), 테스트가 build/openapi.json을 남김. CLAUDE.md §10·.env.example·K8s 문서에 API_DOCS_ENABLED 추가. 전체 테스트 86개 통과
- 파일: build.gradle, src/main/resources/application.yml, .env.example, src/main/java/com/tetra/ops/config/OpenApiConfig.java(신규), .../common/error/{ApiErrorCodes(신규), ErrorResponse}.java, .../{auth/AuthController, dashboard/DashboardController, event/EventController, event/EventCommandService, tenant/TenantController, tenant/TenantCommandService, stub/NotImplementedController}.java, src/test/java/com/tetra/ops/ApiDocsTest.java(신규), src/test/resources/application-test.yml, CLAUDE.md, dev-docs/K8s_이식_반영사항.md
- 남은 것: TODO(미정) 운영 환경 문서 노출 여부(지금은 기본 꺼짐)

## 2026-10-15 KST — 로컬 테스트 환경
- 한 일: local 프로필 추가 — LocalStepFunctionsGateway(호출 생략, 시작됨 처리), LocalMailGateway(build/local-mail/에 파일 저장, 본문은 로그에 안 남김), 실제 AWS 구현은 @Profile("!local"). docker-compose에 ElasticMQ(결과 큐 2개) 추가, MySQL healthcheck. bootRun이 api/.env를 환경 변수로 읽게 함. 스크립트: docker/local/send-sfn-result.ps1(결과 메시지 전송), create-tenant-schema.ps1(새 테넌트 스키마). docs/로컬_실행.md 작성. .env.example에 로컬 전용 키, 루트 .gitattributes에 *.ps1 CRLF. 로컬 .env(커밋하지 않음) 생성. 실제로 띄워 로그인→승인(개인키 메일 파일)→SQS 결과(DEPLOYED)→테넌트 등록(링크 메일 파일)→스키마 생성→READY→대시보드 알림까지 확인 후 데이터 초기화. 전체 테스트 86개 통과
- 파일: src/main/java/com/tetra/ops/external/{LocalStepFunctionsGateway, LocalMailGateway}.java(신규), .../external/{AwsStepFunctionsGateway, SesMailGateway}.java, docker-compose.yml, docker/local/{elasticmq.conf, send-sfn-result.ps1, create-tenant-schema.ps1}(신규), build.gradle, .env.example, docs/로컬_실행.md(신규), CLAUDE.md, ../.gitattributes
- 남은 것: 없음

## 2026-10-15 KST — 로컬 실행 가이드를 dev-docs로 이동
- 한 일: docs/로컬_실행.md를 dev-docs/로컬_실행.md로 옮김(커밋하지 않음). 로컬 실행 코드·compose·스크립트는 커밋 대상으로 둠. .env·.env.example·docker-compose.yml·CLAUDE.md의 가이드 경로 수정
- 파일: dev-docs/로컬_실행.md(이동), .env.example, docker-compose.yml, CLAUDE.md
- 남은 것: 없음

## 2026-10-15 KST — 진행 결과 탭 판정값, CSV 다운로드 헤더
- 한 일: 이벤트 상세 응답 status에 resultAvailable 추가(결과 API와 같은 state 판정 resultState를 공유, IN_PROGRESS면 false → 프런트가 OPS-04 진행 결과 탭을 막음). CSV presigned URL에 Content-Disposition: attachment(filename=issuance-history-{event_id}.csv)를 넣어 파일로 저장되게 함. CLAUDE.md §7.5·§7.8 반영. 테스트 추가(상태별 resultAvailable, S3 서명 URL 헤더). 전체 테스트 88개 통과
- 파일: src/main/java/com/tetra/ops/event/{EventReader, EventDetailResponse, EventQueryService}.java, .../external/{DownloadUrlSigner, S3DownloadUrlSigner}.java, src/test/java/com/tetra/ops/{EventQueryApiTest, external/S3DownloadUrlSignerTest(신규)}.java, CLAUDE.md
- 남은 것: OPS-08 알림센터는 Portal 화면 확정 후

## 2026-10-15 KST — 물리 ERD 갱신 반영 (template 정리, event.layout_config)
- 한 일: template에서 위치·테마 5개 컬럼 삭제(타입당 1행, uk_template_event_type), event에 layout_config(JSON, 읽기 전용) 추가. 승인 Step Functions input에 layout_config를 JSON 객체로 추가(물리·논리 ERD "event.json layout으로 그대로 전달"). 테스트 seed·TestData·로컬 seed 갱신(템플릿 file_path apps/coupon/, 이벤트 layout_config). CLAUDE.md §7.3 input 목록 수정. 새 DDL로 엔티티 검증 포함 전체 테스트 88개 통과, 로컬 DB를 새 DDL로 다시 만들어 seed 적재 확인
- 파일: src/main/java/com/tetra/ops/domain/platform/Template.java, .../domain/tenant/Event.java, .../event/EventCommandService.java, src/test/resources/seed/platform_base.sql, src/test/java/com/tetra/ops/{support/TestData, ApproveApiTest}.java, dev-docs/db/local/local_seed.sql, CLAUDE.md
- 남은 것: TODO(미정) Step Functions input 키 이름은 담당과 맞춤(layout_config 포함)

## 2026-10-21 KST — 워크플로우(ops-workflows) 계약 맞춤: 입력 형식·재실행 반영
- 한 일: WF1-2 승인 input 일시 4개를 KST 벽시계 yyyy-MM-dd'T'HH:mm:ss로 직렬화(Kst.wallClock, 오프셋 없음·초 항상 포함 — 이전 OffsetDateTime.toString()은 +09:00이 붙고 초가 0이면 초를 뺌). input에 name·endpoint_url 추가. 결과 리스너 실행 이름 정규식에 수동 재실행 접미어 (-r\d+)? 허용. 테넌트 환경 준비 결과는 SUCCEEDED면 FAILED → READY도 허용(조건부 UPDATE, 실패가 다시 오면 FAILED 유지). 테스트: KstTest(초 0·소수 초·null), ApproveApiTest input 키 스냅샷·일시 형식(기존 "+09:00" 확인 교체), StepFunctionsResultHandlerTest(approve-12-r1, tenant -r1 FAILED→READY, 재실행 실패 시 FAILED 유지, READY 유지, 잘못된 접미어 무시). 전체 테스트 98개 통과(main 기준선 88개 통과 확인 후 작업)
- 파일: src/main/java/com/tetra/ops/common/time/Kst.java, .../event/EventCommandService.java, .../listener/StepFunctionsResultHandler.java, .../tenant/TenantWriter.java, src/test/java/com/tetra/ops/{ApproveApiTest, StepFunctionsResultHandlerTest, common/time/KstTest(신규)}.java, CLAUDE.md(§7.3·§8, 커밋 안 됨)
- 남은 것: 수명 주기 결과 리스너(SQS_EVENT_LIFECYCLE_RESULT_QUEUE), 승인 전 검사(hosting_start_at·start_at−90분 미래, 동시 이벤트 수 제한), 테넌트 스키마 Flyway 생성(wip/tenant-flyway)은 이후 작업

## 2026-10-21 KST — 테넌트 등록 시 스키마를 Flyway로 생성
- 한 일: 등록(WF5) 순서를 tenant INSERT 커밋 → t_<tenant_id> 생성(TenantSchemaCreator, 마이그레이션 계정 tetra_migrator, Flyway 12.4.0 라이브러리, 트랜잭션 밖) → StartExecution → 비밀번호 설정 메일로. 스키마 생성 실패 시 방금 만든 스키마 DROP, FAILED, StartExecution·메일 없음(응답 mailSent false). 이미 있는 스키마는 지우지 않음. 운영자 알림 문구로 스키마 생성 실패 / StartExecution 실패(스키마 있음, 수동 재실행) 구분. 설정 tetra.db-migration(MIGRATOR_USERNAME·MIGRATOR_PASSWORD — 태스크 정의 이름, 비밀번호 toString 가림), spring.flyway.enabled false(자동 설정 모듈은 넣지 않음). 마이그레이션 사본을 한 곳으로: tenant V1은 src/main/resources/db/migration/tenant(이미지에 포함), platform V1은 src/test/resources/db/migration/platform, api/db/ 삭제. src/main/resources/db/README.md에 db-schema 커밋 b2c008e·SHA-256 기록, TenantMigrationScriptTest가 해시 비교(CR 제거). 테스트: 등록 시 실제 스키마 10테이블 + flyway 이력 V1, StartExecution 시점에 스키마 완성, StartExecution → 메일 순서, 앱 계정 DML 가능, 테이블 실패 시 DROP·FAILED·SFN/메일 없음, 기존 스키마 유지, 설정 누락 시 기동 실패. TestData.reset이 등록으로 생긴 t_ 스키마 삭제. 로컬: .env.example·docker-compose·00-init.sh에 마이그레이션 계정. 전체 테스트 104개 통과
- 파일: build.gradle, src/main/java/com/tetra/ops/{config/TetraProperties, tenant/TenantCommandService, tenant/TenantSchemaCreator(신규)}.java, src/main/resources/{application.yml, db/README.md, db/migration/tenant/V1__init_tenant_schema.sql}, src/test/java/com/tetra/ops/{TenantApiTest, TenantMigrationScriptTest(신규), TenantSchemaCreationFailureTest(신규), config/TetraPropertiesTest, support/{IntegrationTest, MySqlTestDatabase, TestData}}.java, src/test/resources/{application-test.yml, db/migration/platform/V1__init_platform_schema.sql, db/migration/tenant-broken/V1__broken.sql}, .env.example, docker-compose.yml, docker/local/{mysql-init/00-init.sh, create-tenant-schema.ps1}, db/(삭제), dev-docs/{로컬_실행.md, db/tetra_migrator_권한.md}(커밋 안 됨)
- 남은 것: 운영 RDS tetra_migrator 권한 확인(dev-docs/db/tetra_migrator_권한.md로 인프라에 요청). 스키마 생성 실패 테넌트의 재시도 경로 없음(수동). dev Swagger는 꺼져 있음(API_DOCS_ENABLED 미설정) — 켜는 건 별도 MR

## 2026-10-21 KST — 화면(web) 로그인 상태와 API 호출 모듈 연결 (3-15 · 3-16)
- 한 일: web에 API 호출 모듈(src/api/client.ts — /api/ops 상대 경로, credentials same-origin, 오류 {code, message, fields} → ApiError, 401이면 로그인 화면, 연결 실패 · JSON 아닌 오류는 공통 문구)과 인증 API(login · logout · me). AuthContext: 앱 시작 시 GET /api/ops/me로 판단, RequireAuth가 확인 전에는 콘솔 화면(목 대시보드 포함)을 그리지 않음, 401(만료)이면 안내와 함께 /login, 로그인 전 직접 URL 접근도 /login — 둘 다 다시 로그인하면 원래 경로로, 서버 연결 실패는 다시 시도 화면. 로그인 화면 "이메일" · type=email · 서버 문구 표시. 직접 로그아웃(사용자 메뉴 · 시스템 설정)은 POST logout 후 결과와 상관없이 /login(만료 안내 없음), 다시 로그인하면 /로 — 로그인 전이 된 이유(none · expired · logout)를 상태에 두고 authState.loginRedirectState가 from을 정함(로그아웃 화면으로 돌아가던 문제 수정). 운영자 이름 · 이메일은 /me 값(mockOperator 삭제). Vite 개발 프록시 /api → localhost:8080. 우회(목 로그인) 모드는 넣지 않음. 대시보드 · 승인 · 테넌트는 계속 목. 테스트: node --test(Node 24 타입 제거, 새 패키지 없음) 22개(로그아웃 → from 없음, 401 → from 유지 포함) — 테스트 대상과 import 사슬은 상대 경로 + .ts(@/ · 확장자 없는 import는 Node가 못 풂, tsconfig allowImportingTsExtensions · erasableSyntaxOnly, 테스트 파일은 tsc 제외). CI web job에 npm test(이미지 node:24.19). 로컬에서 API + Vite 프록시로 로그인 200(쿠키) → me 200 → 로그아웃 204 확인. README에 API와 함께 실행 · 로컬 운영자 계정 만들기(비밀번호는 입력창 → 환경 변수, BCrypt는 httpd 이미지 htpasswd) · MIGRATOR_* · docker compose down -v 안내
- 파일: ../.gitlab-ci.yml, ../web/{package.json, tsconfig.json, vite.config.ts, README.md}, ../web/src/{main.tsx, App.tsx, api/{client, auth, client.test}.ts(신규), store/{AuthContext.tsx, authState.ts, authState.test.ts}(신규), pages/auth/LoginPage.tsx, pages/settings/SystemSettingsPage.tsx, components/layout/UserMenu.tsx, mocks/dashboard.ts}
- 남은 것: 브라우저 화면 확인은 수동(Chrome · Firefox, Safari는 Secure 쿠키를 localhost에서 안 받음). 화면(DOM) 테스트 도구 없음
- 다음 할 일 (API, 이번에 고치지 않음): ① POST /api/ops/auth/logout을 permitAll로 — 지금은 로그인 필요 경로라 쿠키가 만료된 뒤 로그아웃하면 401(화면은 무시하고 로그아웃 처리). ② CSRF 정책 확정 — 지금은 꺼 두고 SameSite=Strict 쿠키로 대신함(SecurityConfig TODO). 켜면 토큰 전달(쿠키 → X-XSRF-TOKEN 헤더)을 web client.ts 한 곳에 추가

## 2026-10-21 KST — 테넌트 응답에 provisioningStatus · createdAt 추가, 문서 스키마 이름 충돌 정리
- 한 일: 화면 MR(feat/web-api-data) 준비. 기존 필드 이름 · 의미는 그대로 두고 추가만. POST /tenants 응답에 provisioningStatus(응답 시점 값을 DB에서 다시 읽음 — 보통 PROVISIONING, 스키마 생성 · StartExecution 실패면 FAILED) — 화면이 mailSent=false를 "환경 준비 실패(메일 안 보냄)"와 "메일 발송 실패"로 나눌 수 있게. GET /tenants/{id} basic과 PATCH 응답(같은 Basic)에 provisioningStatus, GET /tenants items에 provisioningStatus · createdAt(목록 정렬 기준). springdoc에서 이벤트 상세의 안쪽 record Basic이 테넌트 개요 Basic과 같은 이름으로 합쳐져 문서가 틀리던 것을 @Schema(name)으로 분리(EventDetailBasic · EventDetailStatus · EventDetailResources · EventDetailApplicationLog, TenantBasic) — JSON은 그대로. 테스트: 등록 PROVISIONING / StartExecution 실패 FAILED / 메일만 실패 PROVISIONING + mailSent false / 스키마 생성 실패 FAILED, 목록 provisioningStatus · createdAt, 개요 · 수정 provisioningStatus, ApiDocsTest 스키마 이름. 전체 테스트 105개 통과
- 파일: src/main/java/com/tetra/ops/{tenant/TenantCommandService, tenant/TenantQueryService, tenant/TenantController, event/EventDetailResponse}.java, src/test/java/com/tetra/ops/{TenantApiTest, TenantSchemaCreationFailureTest, ApiDocsTest}.java
- 남은 것: 비밀번호 설정 메일 재발송 API(화면설계서 OPS-07의 "발송 실패 · 재발송" 상태에 필요. 지금은 재발송 경로 없음, 화면은 "테넌트 상세에서 확인" 안내만). 이벤트 상세 rejectReason · processedAt, live-events counts 확장은 하지 않음(화면은 로그에서 꺼냄). 화면 MR A는 이 MR 머지 후 feat/web-api-data를 main에 rebase해서 시작

## 2026-10-21 KST — 화면(web) 테넌트를 API 데이터로 (3-17 · 3-18, MR A: 공통 + 테넌트)
- 한 일: 공통 — 응답 타입 src/api/types.ts(서버 DTO 이름 그대로, 손으로), src/api/tenants.ts(목록 · 개요 · 이벤트 · 리소스 · 이메일 확인 · 등록 · 수정), hooks/useApiQuery(불러오기 · 로딩 · 오류 · reload · setData, 요청 취소), components/common/QueryStatus(스피너 / 서버 문구 + 다시 시도), utils/datetime(ISO → 'yyyy.MM.dd HH:mm', KST 고정), utils/formErrors(400 fields → 입력란, 409 EMAIL_DUPLICATED → email 입력란, 그 밖 → Alert). 테넌트 — 목록(서버 순서 = createdAt 내림차순, 계정상태 칩 옆 환경 준비 칩: 준비 중 회색 · 실패 빨강 · 완료 미표시), 상세(404 → 찾을 수 없음 화면, 헤더 칩, 탭별로 events · resources 요청), 개요(버킷 auditLog, 상태 로그 badge · relatedEvent 링크, 수정 PATCH 응답으로 갱신 — 이메일 중복은 저장 시 409를 입력란 오류로), 등록(중복 확인 · 등록 API, 결과 화면 4가지 tenantView.registerOutcome: 완료 / 메일 발송 실패(환경 준비 진행 중, "메일을 받지 못한 경우 테넌트 상세에서 확인해 주세요") / 환경 준비 시작 실패(메일은 발송) / 환경 준비 실패(메일 안 보냄), [테넌트 상세] 버튼). 이벤트 타입 TIME_SALE → TIMESALE(API 값), Plan에 XL. AppDataContext에서 테넌트 액션 · mocks의 s3Buckets 삭제(이벤트 화면용 tenants만 남김, MR B에서 삭제). 테스트 node --test 38개(일시 · 폼 오류 · 등록 결과 · 칩 · 이벤트 경로 추가). 로컬: 새 코드 API + Vite 프록시로 목록 · 이메일 확인(정상 · 400) · 등록(201 PROVISIONING) · 중복 409 · 400 fields · 개요 · 수정(이메일 중복 409 · 이름 200) · 이벤트 · 리소스 · 404 확인 후 확인용 테넌트(스키마 · 행 · 메일 파일) 삭제
- 파일: ../web/src/{api/{types, tenants}.ts, hooks/useApiQuery.ts, components/common/{QueryStatus, PlanBadge}.tsx, utils/{datetime, formErrors, event, status}.ts, utils/{datetime, formErrors}.test.ts, pages/tenants/{TenantListPage, TenantDetailPage, TenantRegisterPage}.tsx, pages/tenants/{tenantView, tenantView.test}.ts, pages/tenants/components/{TenantOverview, TenantEventTables, EditFieldDialog}.tsx, store/AppDataContext.tsx, mocks/tenants.ts, types/index.ts}, ../web/README.md
- 남은 것: MR B(이벤트 · 승인 · 대시보드 · 목 삭제). 비밀번호 설정 메일 재발송 API · 화면(OPS-07 "발송 실패 · 재발송"). 브라우저 화면 확인은 수동

## 2026-10-21 KST — 회사 root 도메인 규칙 통일, 이벤트 fullDomain을 플랫폼 도메인으로
- 한 일: TenantInputRules.domain — 앞뒤 공백 제거 → 소문자(Locale.ROOT) → 소문자 기준 정규식 ^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$, 소문자 값을 저장(Step Functions input root_domain도 소문자). http(s)://·경로·포트·공백·밑줄·끝의 점·한글 도메인·퓨니코드 TLD 거부(MVP 결정), 문구 "도메인 형식이 올바르지 않습니다. (예: e-corp.com — http://, 경로, 포트 없이)"(web과 같음). 이미 저장된 대문자 값은 UPDATE하지 않음(결정). 이벤트 상세 resources.fullDomain을 {slug}.{tenant_id}.{root_domain} → {slug}.{tenant_id}.{tetra.platform-domain}으로(ops-workflows 10-17 결정, WF5 시험 abcd1234.wf5test00001.tetraops.cloud). 설정 tetra.platform-domain = ${PLATFORM_DOMAIN:tetraops.cloud}(application.yml 기본값, 태스크 정의는 건드리지 않음). 응답 필드 · 형식은 그대로. 테스트: TenantInputRulesTest(web validation.test.ts와 같은 예시 표 — 통과 5 · 경계 63/253 · 거부 13 · 64자 라벨 · 254자 · 빈 값), 등록 시 소문자 저장 · SFN input · URL 거부 400, fullDomain tetraops.cloud, platform-domain 덮어쓰기 · 빈 값 기동 실패. 전체 테스트 127개 통과
- 파일: src/main/java/com/tetra/ops/{tenant/TenantInputRules, event/EventReader, event/EventDetailResponse, config/TetraProperties}.java, src/main/resources/application.yml, .env.example, src/test/java/com/tetra/ops/{tenant/TenantInputRulesTest(신규), TenantApiTest, EventQueryApiTest, config/TetraPropertiesTest}.java
- 남은 것: 화면(MR A)의 isDomain과 예시 표를 같이 유지. 환경별 플랫폼 도메인이 다르면 태스크 정의에 PLATFORM_DOMAIN 추가(인프라)
