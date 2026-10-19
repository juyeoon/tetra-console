<#
로컬 전용. 로컬에서 새로 등록한 테넌트의 스키마(t_{tenant_id})를 만든다.
운영에서는 WF5 마이그레이션 Job이 하는 일이고, 앱은 DDL을 실행하지 않는다.

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
