# Tetra Operations Console

Tetra 운영자용 콘솔. 화면(`web/`, React · Vite)과 API(`api/`, Spring Boot)를 **한 이미지**로 배포한다.
API가 `web/dist`를 `classpath:/static/`에 넣어 화면 파일도 함께 내보낸다.

| 폴더 | 내용 | 안내 |
| --- | --- | --- |
| `web/` | 운영 콘솔 화면 | [web/README.md](web/README.md) — API와 함께 로컬 실행 · 화면 / 라우트 · 화면설계서 대비 차이 |
| `api/` | 운영 콘솔 API, SQS 결과 리스너 | `api/docs/WORKLOG.md` — 작업 기록 |
| `.gitlab-ci.yml` | CI/CD | 아래 "배포(dev)" |

## 로컬 실행

[web/README.md](web/README.md)의 "API와 함께 실행 (로컬)"을 따른다(API: `api/`에서 `docker compose up -d` → `./gradlew bootRun`, 화면: `web/`에서 `npm run dev`).

## 배포(dev)

### 흐름

- **MR**: web(단위 테스트 · 타입 검사 · 빌드), api(단위 · 통합 테스트). 배포하지 않는다.
- **main 머지**: 위 테스트 → `jar`(web/dist 포함) → `build-image`(ECR `tetra/console:<커밋 SHA 7자리>`) → **`deploy-dev` 자동**.
  - `deploy-dev`는 태스크 정의 family의 최신 리비전을 복사해 이미지만 바꾸고 ECS 서비스(`tetra-dev` / `console`)를 갱신한 뒤,
    서비스가 안정될 때까지 기다리고(`aws ecs wait services-stable`, 최대 약 10분) **새 리비전으로 떴는지** 확인한다.
  - 새 태스크가 헬스체크(ALB `/actuator/health/liveness`)를 통과하지 못하면 ECS circuit breaker가 이전 리비전으로 되돌리고, 잡은 실패로 끝난다.
- MR 파이프라인 · 다른 브랜치에서는 `deploy-dev`가 돌지 않는다.

### 밤에 dev를 꺼 둔 경우

밤에는 GitLab EC2 · Runner 2대(general · protected) · SSM 중계 · RDS를 꺼 둔다(ECS · NAT · EKS · ALB는 켜 둠). 아침에 켤 때 RDS는 선택이다.

**GitLab · Runner는 켰는데 RDS는 켜지 않은 채 main에 머지하면** `deploy-dev`가 실패한다.

- 앱은 기동 중 DB에 연결하지 못하면 뜨지 않는다. Hibernate가 시작할 때 DB 메타데이터로 Dialect를 정하는데(`hibernate.dialect` 설정 없음)
  연결에 실패하면 `Unable to determine Dialect without JDBC metadata`로 컨텍스트 생성이 실패하고 프로세스가 끝난다(로컬에서 DB를 끄고 확인).
  RDS가 멈춰 있으면 연결 시도가 응답 없이 끝날 때까지(Hikari 연결 대기 기본 30초) 기다린 뒤 같은 오류로 실패한다(시간은 추정).
- 새 태스크는 헬스체크를 한 번도 통과하지 못하고 계속 다시 시작된다 → circuit breaker가 실패를 세어 이전 리비전으로 되돌린다.
  이때 이전 리비전 태스크는 그대로 떠 있다(최소 정상 100%).
- 잡 로그는 둘 중 하나로 끝난다(빨강).
  - 10분 안에 되돌리기까지 끝나면: `배포 실패 — 헬스체크를 통과하지 못해 이전 리비전(...)으로 되돌아갔다`
  - 아니면 `wait services-stable`이 `Max attempts exceeded`로 끝난다. 이 경우에도 ECS는 혼자 이전 리비전으로 되돌린다.
- 확인: ECS 서비스 이벤트(태스크 시작 · 중지 이유), CloudWatch 로그 `/ecs/tetra-dev/console`의 `Unable to determine Dialect` / `Communications link failure`.

### 다시 돌리는 방법

1. RDS를 켜고 `available`이 될 때까지 기다린다.
2. **가장 최근** main 파이프라인의 `deploy-dev` 잡에서 **Retry**, 또는 main에서 **Run pipeline**.
   - 이미지는 이미 ECR에 있어 `build-image`는 건너뛴다(같은 태그면 빌드하지 않음).
   - 더 오래된 파이프라인의 `deploy-dev`를 Retry하면 그 커밋의 이미지로 되돌아가니 하지 않는다.
   - `resource_group`이 배포를 한 번에 하나만 돌게 한다.
3. 잡 로그 끝이 `배포 완료 — <이미지>`인지 확인한다.

## 브라우저 캐시

- `index.html`(SPA 라우트로 내려가는 것 포함) · favicon 등 해시 없는 파일: `Cache-Control: no-cache` — 배포 뒤 새로고침만으로 새 화면.
- `assets/`(Vite 해시 이름 JS · CSS · 이미지): `max-age=31536000, public, immutable`.
- 설정: `api/src/main/java/com/tetra/ops/config/SpaWebConfig.java`
