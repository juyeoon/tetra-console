<#
로컬 전용. Step Functions 실행 결과(EventBridge 이벤트)를 ElasticMQ 큐에 넣어 결과 리스너를 확인한다.

  .\docker\local\send-sfn-result.ps1 -Name approve-1043                 # 승인 등록 성공 → 호스팅 DEPLOYED
  .\docker\local\send-sfn-result.ps1 -Name approve-1043 -Status FAILED  # → DEPLOY_FAILED
  .\docker\local\send-sfn-result.ps1 -Name tenant-k7f2m9qx4b1t          # 환경 준비 성공 → READY

실행 이름이 approve-로 시작하면 승인 결과 큐, tenant-로 시작하면 테넌트 결과 큐로 보낸다.

수명 주기 결과(WF2-1 · WF2-2 · WF4)는 -Flow로 보낸다. 실행 이름 대신 상태 머신 이름(tetra-{env}-{flow})과
input의 event_id로 구분하므로 -EventId · -TenantId가 필요하다. 큐는 event-lifecycle-result.

  .\docker\local\send-sfn-result.ps1 -Flow hosting-start -EventId 1044 -TenantId k7f2m9qx4b1t   # DEPLOYED → HOSTING
  .\docker\local\send-sfn-result.ps1 -Flow event-open -EventId 1044 -TenantId k7f2m9qx4b1t -Status FAILED  # → OPEN_FAILED
  .\docker\local\send-sfn-result.ps1 -Flow event-end -EventId 1044 -TenantId k7f2m9qx4b1t       # → ENDED · EXPIRED

-Env는 앱의 TETRA_ENV와 같아야 한다(로컬 .env 기본 local).
#>
param(
    [string] $Name,
    [ValidateSet('hosting-start', 'event-open', 'event-end')] [string] $Flow,
    [long] $EventId,
    [string] $TenantId,
    [string] $Env = 'local',
    [ValidateSet('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'ABORTED')] [string] $Status = 'SUCCEEDED',
    [string] $ErrorName = 'States.TaskFailed',
    [string] $Cause = 'local test',
    [string] $Endpoint = 'http://localhost:9324'
)

$detail = [ordered]@{}
if ($Flow) {
    if (-not $EventId -or -not $TenantId) { throw "-Flow에는 -EventId와 -TenantId가 필요합니다." }
    $queue = 'event-lifecycle-result'
    $stateMachine = "tetra-$Env-$Flow"
    # 실행 이름은 스케줄러가 정한다(리스너는 보지 않음)
    $Name = "$Flow-$EventId-local"
    $detail.stateMachineArn = "arn:aws:states:ap-northeast-2:000000000000:stateMachine:$stateMachine"
    $detail.input = (@{ tenant_id = $TenantId; event_id = $EventId; slug = 'local' } | ConvertTo-Json -Compress)
}
elseif ($Name -like 'approve-*') { $queue = 'approve-result' }
elseif ($Name -like 'tenant-*') { $queue = 'tenant-result' }
else { throw "실행 이름(approve-{event_id} · tenant-{tenant_id}) 또는 -Flow가 필요합니다: $Name" }

$detail.executionArn = "arn:aws:states:ap-northeast-2:000000000000:execution:local:$Name"
$detail.name = $Name
$detail.status = $Status
$detail.error = $(if ($Status -eq 'SUCCEEDED') { $null } else { $ErrorName })
$detail.cause = $(if ($Status -eq 'SUCCEEDED') { $null } else { $Cause })
$event = [ordered]@{
    version       = '0'
    id            = [guid]::NewGuid().ToString()
    'detail-type' = 'Step Functions Execution Status Change'
    source        = 'aws.states'
    detail        = $detail
}
$body = $event | ConvertTo-Json -Depth 5 -Compress

$queueUrl = ([xml](Invoke-WebRequest -UseBasicParsing -Method Post -Uri $Endpoint `
        -Body @{ Action = 'GetQueueUrl'; QueueName = $queue }).Content).GetQueueUrlResponse.GetQueueUrlResult.QueueUrl
Invoke-WebRequest -UseBasicParsing -Method Post -Uri $Endpoint `
    -Body @{ Action = 'SendMessage'; QueueUrl = $queueUrl; MessageBody = $body } | Out-Null
if ($Flow) { Write-Host "보냄: $queue <- $stateMachine event_id=$EventId ($Status)" }
else { Write-Host "보냄: $queue <- $Name ($Status)" }
