# db/ — 스키마 사본 (읽기 전용)

`migration/` 아래 파일은 **`tetra/app/db-schema` 저장소의 Flyway V1 파일을 그대로 복사한 것**이다. 통합 테스트(Testcontainers, `MySqlTestDatabase`)가 이 파일로 공용 1 + 테넌트 2 스키마를 만든다 — 커밋되어 있으므로 새 clone · CI에서도 돈다(2026-10-19, CI 구축 때 `dev-docs/db/init`에서 옮김).

| 파일 | 원본 | 복사한 커밋 |
|---|---|---|
| `migration/platform/V1__init_platform_schema.sql` | db-schema `db/migration/platform/` | main `5211b81` (2026-10-16) |
| `migration/tenant/V1__init_tenant_schema.sql` | db-schema `db/migration/tenant/` | main `5211b81` (2026-10-16) |

- **여기서 고치지 않는다.** 스키마 변경은 db-schema 저장소 MR(Flyway)로만 한다.
- db-schema에 새 V 파일이 병합되면(테이블 · 열 변경) 같은 이름으로 여기에 복사하고, `MySqlTestDatabase`가 그 파일도 적용하게 한다.
- **시드 V2(`platform/V2__seed_plans_templates.sql`)는 넣지 않는다** — 테스트는 `src/test/resources/seed/platform_base.sql`로 요금제 · 템플릿을 직접 넣으므로 겹치면 중복 키 오류.
- 로컬 docker-compose(`docker-compose.yml`)는 아직 `dev-docs/db/init`을 본다 — 필요하면 같은 경로로 바꾼다.
