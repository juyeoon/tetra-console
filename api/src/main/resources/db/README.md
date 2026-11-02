# db/ — 스키마 사본 (읽기 전용)

아래 파일은 **`tetra/app/db-schema` 저장소의 Flyway 파일을 그대로 복사한 것**이다. 사본은 이 표의 위치에만 둔다.

| 파일 | 쓰는 곳 | 원본 | 확인한 커밋 | SHA-256 (CR 제거, LF 기준) |
|---|---|---|---|---|
| `src/main/resources/db/migration/tenant/V1__init_tenant_schema.sql` | 테넌트 등록 때 `TenantSchemaCreator`가 Flyway로 실행(`classpath:db/migration/tenant`, 이미지에 들어감) · 통합 테스트 | db-schema `db/migration/tenant/` | main `b2c008e` (2026-10-19) | `86435fdd49d1adb919c3d8e2eb465e0c52ddff48c48075f038a7b7cd529e67d8` |
| `src/test/resources/db/migration/platform/V1__init_platform_schema.sql` | 통합 테스트만(`MySqlTestDatabase`) | db-schema `db/migration/platform/` | main `b2c008e` (2026-10-19) | `7c28cd3e2d8a8057e81989a143f6fc4a558dedf80e1ba4f918868da4464c8ba4` |
| `src/test/resources/db/migration/platform/V4__add_operator_notification_dedupe_key.sql` | 통합 테스트만(`MySqlTestDatabase`, V1 다음) | db-schema `db/migration/platform/` | main `1f49297509ffd94e7260214115c011e786c820e6` (2026-10-29) | `159b71db705f9fe6b64e77025d9737e11362742dee4f738066961a307766d154` |
| `src/test/resources/db/migration/platform/V5__add_operator_notification_slack_status.sql` | 통합 테스트만(`MySqlTestDatabase`, V4 다음) | db-schema `db/migration/platform/` | `feat/platform-v5-slack-notify` `1e6d64b7eb4932e7b893b49f0c548f0324850057` (2026-11-02, dev 적용) | `8fe057833b60ee0af97db8700c3ae716b392bf7d511ed48046e0aa2fe0db4713` |

- **여기서 고치지 않는다.** 스키마 변경은 db-schema 저장소 MR(Flyway)로만 한다. `TenantMigrationScriptTest`가 이 표의 SHA-256과 파일을 비교한다.
- db-schema에 새 V 파일이 병합되면 같은 이름으로 같은 폴더에 복사하고, 이 표에 행(커밋 · SHA-256)을 추가한다. 이미 만든 테넌트가 있으므로 V1은 바꾸지 않는다.
  - 해시 구하기: `tr -d '\r' < 파일 | sha256sum` (Windows 체크아웃은 CRLF라 CR을 빼고 계산한다. Flyway 체크섬도 줄바꿈과 무관)
- **시드 V2(`platform/V2__seed_plans_templates.sql`)는 넣지 않는다** — 테스트는 `src/test/resources/seed/platform_base.sql`로 요금제 · 템플릿을 직접 넣으므로 겹치면 중복 키 오류. V3(`V3__hide_xl_plan.sql`, 시드 요금제 표시 여부 UPDATE)도 같은 이유로 넣지 않는다.
- 원래 `api/db/migration/`에 있던 사본을 2026-10-21 여기로 옮겼다(main `5211b81` 사본과 내용 같음).
- 로컬 docker-compose(`docker-compose.yml`)는 아직 `dev-docs/db/init`을 본다.
