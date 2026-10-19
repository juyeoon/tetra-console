<#
로컬 전용. Step Functions 실행 결과(EventBridge 이벤트)를 ElasticMQ 큐에 넣어 결과 리스너를 확인한다.

  .\docker\local\send-sfn-result.ps1 -Name approve-1043                 # 승인 등록 성공 → 호스팅 DEPLOYED
  .\docker\local\send-sfn-result.ps1 -Name approve-1043 -Status FAILED  # → DEPLOY_FAILED
  .\docker\local\send-sfn-result.ps1 -Name tenant-k7f2m9qx4b1t          # 환경 준비 성공 → READY

실행 이름이 approve-로 시작하면 승인 결과 큐, tenant-로 시작하면 테넌트 결과 큐로 보낸다.
#>
param(
    [Parameter(Mandatory = $true)] [string] $Name,
    [ValidateSet('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'ABORTED')] [string] $Status = 'SUCCEEDED',
    [string] $ErrorName = 'States.TaskFailed',
    [string] $Cause = 'local test',
    [string] $Endpoint = 'http://localhost:9324'
)

if ($Name -like 'approve-*') { $queue = 'approve-result' }
elseif ($Name -like 'tenant-*') { $queue = 'tenant-result' }
else { throw "실행 이름은 approve-{event_id} 또는 tenant-{tenant_id} 형식이어야 합니다: $Name" }

$detail = [ordered]@{
    executionArn = "arn:aws:states:ap-northeast-2:000000000000:execution:local:$Name"
    name         = $Name
    status       = $Status
    error        = $(if ($Status -eq 'SUCCEEDED') { $null } else { $ErrorName })
    cause        = $(if ($Status -eq 'SUCCEEDED') { $null } else { $Cause })
}
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
Write-Host "보냄: $queue <- $Name ($Status)"
