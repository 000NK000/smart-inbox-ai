param([Parameter(Mandatory=$true)][ValidateSet('standby','resume','cleanup')][string]$Action)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
$runtimeDir = Join-Path $projectRoot '.smart-inbox\runtime'
New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
$operationLock = [IO.File]::Open((Join-Path $runtimeDir 'operation.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
$optional = @('mysql','milvus','etcd','minio','grafana','prometheus','rmqdashboard','nacos')
$required = @('redis','rmqnamesrv','rmqbroker')
. "$PSScriptRoot\runtime-shutdown.ps1"

function Invoke-ShutdownStage([string]$Name, [scriptblock]$Work) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $succeeded = $false
    try { & $Work; $succeeded = $true }
    finally {
        $entry = @{ at = [DateTime]::UtcNow.ToString('o'); stage = $Name; seconds = [Math]::Round($timer.Elapsed.TotalSeconds, 3); success = $succeeded } | ConvertTo-Json -Compress
        [IO.File]::AppendAllText((Join-Path $runtimeDir 'shutdown-timing.log'), $entry + [Environment]::NewLine)
    }
}

function Owned-Java([string]$Module) {
    $jar = Join-Path $projectRoot "smart-$Module\target\smart-$Module-1.0.0-SNAPSHOT.jar"
    @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like "*$jar*" })
}
function Stop-ServiceGracefully([string]$Module) {
    foreach ($process in (Owned-Java $Module)) {
        $signal = Join-Path $runtimeDir "stop-$Module"
        [IO.File]::WriteAllText($signal, [string]$process.ProcessId)
        $deadline = (Get-Date).AddSeconds(240)
        while (Get-Process -Id $process.ProcessId -ErrorAction SilentlyContinue) {
            if ((Get-Date) -gt $deadline) { throw "Timed out waiting for $Module to save and stop. No process was forcibly killed." }
            Start-Sleep -Milliseconds 500
        }
    }
}
try {
if ($Action -eq 'resume') {
    Write-Output 'PROGRESS:正在启动邮件队列和后台服务…'
    & "$PSScriptRoot\start-smart-inbox.ps1" -SkipBuild -NoBrowser -BackendOnly
    if ($LASTEXITCODE -ne 0) { throw 'Backend startup failed.' }
    Write-Output 'PROGRESS:后台服务已就绪，邮件将自动补同步'
    exit 0
}
if ($Action -eq 'cleanup') { Stop-ProjectContainersGracefully $projectRoot $optional; exit 0 }

Invoke-ShutdownStage 'total' {
Write-Output 'PROGRESS:正在停止收信，并等待本地数据保存…'
Invoke-ShutdownStage 'gateway' { Stop-ServiceGracefully 'gateway' }
Invoke-ShutdownStage 'collector' { Stop-ServiceGracefully 'collector' }
Invoke-ShutdownStage 'processor' { Stop-ServiceGracefully 'processor' }
Write-Output 'PROGRESS:正在卸载 AI 模型，释放内存和显存…'
Invoke-ShutdownStage 'model' {
# Read the model name from this project's config; never kill all Ollama processes/models.
$modelLine = Get-Content "$projectRoot\smart-processor\src\main\resources\application.yml" | Where-Object { $_ -match '^\s+model:\s*qwen' } | Select-Object -First 1
if (-not $modelLine) { throw 'Project AI model configuration was not found.' }
$model = ($modelLine -split 'model:',2)[1].Trim()
try { $loaded = Invoke-RestMethod 'http://127.0.0.1:11434/api/ps' -TimeoutSec 10 }
catch {
    if (Get-NetTCPConnection -State Listen -LocalPort 11434 -ErrorAction SilentlyContinue) { throw 'Ollama is running but its model state cannot be verified.' }
    $loaded = @{models=@()}
}
if (@($loaded.models | Where-Object name -eq $model).Count) {
    $body = @{model=$model;keep_alive=0} | ConvertTo-Json -Compress
    $null = Invoke-RestMethod -Method Post 'http://127.0.0.1:11434/api/generate' -ContentType 'application/json' -Body $body -TimeoutSec 90
    $deadline = (Get-Date).AddSeconds(60)
    do {
        $remaining = @((Invoke-RestMethod 'http://127.0.0.1:11434/api/ps' -TimeoutSec 10).models | Where-Object name -eq $model)
        if (-not $remaining.Count) { break }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    if ($remaining.Count) { throw 'AI model is still loaded; another application may be using it.' }
}
}
Write-Output 'PROGRESS:正在停止本项目容器…'
Invoke-ShutdownStage 'containers' { Stop-ProjectContainersGracefully $projectRoot ($optional + $required) }
Write-Output 'PROGRESS:待机完成，关闭开关即可恢复'
}
} finally { $operationLock.Dispose() }
