param([switch]$Restart, [switch]$SkipBuild, [switch]$NoBrowser, [switch]$BackendOnly)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
$localConfig = Join-Path $projectRoot 'config.local.ps1'
if (Test-Path -LiteralPath $localConfig) { . $localConfig }
$logDir = Join-Path $projectRoot '.run-logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
. "$PSScriptRoot\backend-startup.ps1"

function Listener([int]$Port) {
    Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1
}
function Wait-Http([string]$Url, [int]$Seconds = 90) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        try { $null = Invoke-WebRequest -UseBasicParsing $Url -TimeoutSec 3; return } catch { Start-Sleep -Seconds 2 }
    } while ((Get-Date) -lt $deadline)
    throw "Service did not become ready: $Url. Check $logDir."
}
function Start-Web {
    if (-not (Listener 5173)) {
        $webRoot = Join-Path $projectRoot 'smart-web'
        $vite = Join-Path $webRoot 'node_modules\vite\bin\vite.js'
        Start-Process node -ArgumentList @("`"$vite`"", '--host', '127.0.0.1', '--port', '5173', '--strictPort', '--configLoader', 'runner') -WorkingDirectory $webRoot -WindowStyle Hidden -RedirectStandardOutput "$logDir\web.out.log" -RedirectStandardError "$logDir\web.err.log" | Out-Null
    }
    Wait-Http 'http://127.0.0.1:5173/'
}
$stateFile = Join-Path $projectRoot '.smart-inbox\runtime\state.json'
if (-not $BackendOnly -and (Test-Path -LiteralPath $stateFile)) {
    $state = Get-Content -Raw -LiteralPath $stateFile | ConvertFrom-Json
    if ($state.mode -ne 'active') {
        Start-Web
        Write-Host 'Smart Inbox is in standby/recovery mode. Use the switch on the page to resume.'
        if (-not $NoBrowser) { Start-Process 'http://127.0.0.1:5173/' }
        exit 0
    }
}

# Only this checkout's services may be stopped. Other Java/Node applications are untouched.
if ($Restart -or -not $SkipBuild) {
    $javaProcesses = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'")
    foreach ($module in @('collector', 'processor', 'gateway')) {
        $jarPath = Join-Path $projectRoot "smart-$module\target\smart-$module-1.0.0-SNAPSHOT.jar"
        $javaProcesses | Where-Object { $_.CommandLine -like "*$jarPath*" } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
    }
}

$startupTimer = [Diagnostics.Stopwatch]::StartNew()
$startupSucceeded = $false
try {
. "$PSScriptRoot\ensure-docker.ps1"
Invoke-StartupStage $projectRoot 'docker' { Ensure-Docker }
. "$PSScriptRoot\start-queue.ps1"
$queuePorts = Invoke-StartupStage $projectRoot 'queue' { Start-ProjectQueue -ProjectRoot $projectRoot }
$nameServerAddress = "127.0.0.1:$($queuePorts.NameServerPort)"
if (-not (Listener 11434)) {
    $ollama = Join-Path $env:LOCALAPPDATA 'Programs\Ollama\ollama.exe'
    if (Test-Path -LiteralPath $ollama) {
        Start-Process -FilePath $ollama -ArgumentList 'serve' -WindowStyle Hidden -RedirectStandardOutput "$logDir\ollama.out.log" -RedirectStandardError "$logDir\ollama.err.log" | Out-Null
    }
}
if (-not $SkipBuild) {
    & mvn "-Dmaven.repo.local=$projectRoot\.codex-m2" package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
}

Start-ProjectBackends -ProjectRoot $projectRoot -NameServerAddress $nameServerAddress -LogDir $logDir
if (-not $BackendOnly) { Start-Web }
# The collector automatically syncs Outlook after three seconds and IMAP after five.
# Wake-up is ready once the API works; do not block the switch on a slow mail provider.
Write-Host 'Smart Inbox: http://127.0.0.1:5173/'
if (-not $NoBrowser) { Start-Process 'http://127.0.0.1:5173/' }
$startupSucceeded = $true
} finally { Write-StartupTiming $projectRoot 'backend-total' $startupTimer.Elapsed.TotalSeconds $startupSucceeded }
