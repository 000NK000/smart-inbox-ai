$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
$localConfig = Join-Path $projectRoot 'config.local.ps1'
if (Test-Path -LiteralPath $localConfig) { . $localConfig }
$logDir = Join-Path $projectRoot '.run-logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
Write-Output 'PROGRESS:正在检查本机运行环境…'
$node = Get-Command node.exe -ErrorAction Stop
if (-not (Test-Path -LiteralPath "$projectRoot\smart-web\dist\index.html")) { throw 'Frontend build missing. Run scripts/install-desktop.ps1.' }
$listener = Get-NetTCPConnection -State Listen -LocalPort 5173 -ErrorAction SilentlyContinue | Select-Object -First 1
if ($listener) {
    $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
    $allowed = @("$projectRoot\scripts\desktop-server.mjs", "$projectRoot\smart-web\node_modules\vite\bin\vite.js")
    if ($owner.Name -ne 'node.exe' -or -not ($allowed | Where-Object { $owner.CommandLine.Contains($_) })) { throw 'Port 5173 belongs to another application. No process was stopped.' }
} else {
    Write-Output 'PROGRESS:正在启动桌面界面…'
    Start-Process -FilePath $node.Source -ArgumentList "`"$projectRoot\scripts\desktop-server.mjs`"" -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput "$logDir\desktop-web.out.log" -RedirectStandardError "$logDir\desktop-web.err.log" | Out-Null
}
$deadline = (Get-Date).AddSeconds(30)
do {
    try { $state = Invoke-RestMethod 'http://127.0.0.1:5173/api/runtime/status' -TimeoutSec 3; break } catch { Start-Sleep -Seconds 1 }
} while ((Get-Date) -lt $deadline)
if (-not $state -or -not $state.mode -or -not $state.token) { throw 'The desktop controller did not become ready.' }
if ($state.mode -eq 'active') {
    Write-Output 'PROGRESS:正在启动邮件、任务和 AI 后台…'
    & "$PSScriptRoot\start-smart-inbox.ps1" -SkipBuild -NoBrowser -BackendOnly
    if ($LASTEXITCODE -ne 0) { throw 'Backend startup failed. See .run-logs.' }
} else {
    Write-Output 'PROGRESS:已保留上次待机状态，可在窗口中恢复运行。'
}
Write-Output 'PROGRESS:准备就绪'
