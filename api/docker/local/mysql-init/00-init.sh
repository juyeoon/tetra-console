#!/bin/bash
# 로컬 MySQL 첫 기동 때 한 번 실행된다 (docker-entrypoint-initdb.d).
# 운영에서 tetra_platform은 db-schema Flyway가, 테넌트 스키마는 등록 API(TenantSchemaCreator)가 만든다.
# 이 스크립트는 로컬 테스트용이다(미리 넣어 두는 테넌트 2개 포함).
set -euo pipefail

export MYSQL_PWD="${MYSQL_ROOT_PASSWORD}"
run() { mysql --default-character-set=utf8mb4 -uroot "$@"; }

run -e "CREATE SCHEMA tetra_platform DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
run tetra_platform < /ddl/tetra_platform.sql

for tenant_id in ${LOCAL_TENANT_IDS}; do
  run -e "CREATE SCHEMA t_${tenant_id} DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
  run "t_${tenant_id}" < /ddl/tenant_schema.sql
done

# 앱 런타임 계정: tetra_platform.*와 t\_%.*에 DML만 (테넌트 데이터 격리 결정 문서)
run -e "CREATE USER '${APP_DB_USERNAME}'@'%' IDENTIFIED BY '${APP_DB_PASSWORD}'"
run -e "GRANT SELECT, INSERT, UPDATE, DELETE ON tetra_platform.* TO '${APP_DB_USERNAME}'@'%'"
run -e "GRANT SELECT, INSERT, UPDATE, DELETE ON \`t\\_%\`.* TO '${APP_DB_USERNAME}'@'%'"

# 마이그레이션 계정(tetra_migrator): 등록 때 t_{tenant_id} 생성 · 실패 시 삭제와 테이블 DDL, 공용 테이블 FK 참조
run -e "CREATE USER '${MIGRATOR_DB_USERNAME}'@'%' IDENTIFIED BY '${MIGRATOR_DB_PASSWORD}'"
run -e "GRANT CREATE, DROP, ALTER, INDEX, REFERENCES, SELECT, INSERT, UPDATE, DELETE ON \`t\\_%\`.* TO '${MIGRATOR_DB_USERNAME}'@'%'"
run -e "GRANT REFERENCES ON tetra_platform.* TO '${MIGRATOR_DB_USERNAME}'@'%'"

if [ -f /seed/local_seed.sql ]; then
  run tetra_platform < /seed/local_seed.sql
fi
