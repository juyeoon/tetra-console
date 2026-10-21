<#
로컬 전용. 테넌트 스키마(t_{tenant_id})를 손으로 만든다.
등록 API가 마이그레이션 계정(MIGRATOR_USERNAME)으로 스키마를 만들므로 평소에는 필요 없다.
마이그레이션 계정이 없는 예전 볼륨이거나, 등록 때 스키마 생성이 실패한 테넌트를 손으로 복구할 때만 쓴다(Flyway 이력은 남지 않음).

  .\docker\local\create-tenant-schema.ps1 -TenantId k7f2m9qx4b1t

스키마를 만든 뒤 환경 준비 결과를 보내면 READY가 되어 테넌트 스키마로 라우팅된다.
  .\docker\local\send-sfn-result.ps1 -Name tenant-k7f2m9qx4b1t
#>
param(
    [Parameter(Mandatory = $true)] [ValidatePattern('^[a-z0-9]{12}$')] [string] $TenantId
)

$schema = "t_$TenantId"
$script = "export MYSQL_PWD=`"`$MYSQL_ROOT_PASSWORD`"; " +
    "mysql --default-character-set=utf8mb4 -uroot -e 'CREATE SCHEMA $schema DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci' && " +
    "mysql --default-character-set=utf8mb4 -uroot $schema < /ddl/tenant_schema.sql"

Push-Location (Join-Path $PSScriptRoot '..\..')
try {
    docker compose exec -T mysql sh -c $script
    if ($LASTEXITCODE -ne 0) { throw "스키마 생성 실패: $schema" }
    Write-Host "만듦: $schema"
}
finally {
    Pop-Location
}
