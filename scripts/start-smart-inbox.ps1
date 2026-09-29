param([switch]$Restart, [switch]$SkipBuild, [switch]$NoBrowser, [switch]$BackendOnly)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
$localConfig = Join-Path $projectRoot 'config.local.ps1'
if (Test-Path -LiteralPath $localConfig) { . $localConfig }
$logDir = Join-Path $projectRoot '.run-logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

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
foreach ($module in @('collector', 'processor', 'gateway')) {
    $jarPath = Join-Path $projectRoot "smart-$module\target\smart-$module-1.0.0-SNAPSHOT.jar"
    $owned = Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like "*$jarPath*" }
    if ($Restart -or -not $SkipBuild) { $owned | ForEach-Object { Stop-Process -Id $_.ProcessId -Force } }
}

. "$PSScriptRoot\ensure-docker.ps1"
Ensure-Docker
& docker compose up -d --no-recreate rmqnamesrv rmqbroker redis
if ($LASTEXITCODE -ne 0) {
    throw 'Project containers failed to start. Check the Docker error above: a port bind/access error means a busy or Windows-reserved port, not a stopped Docker Desktop. See docker-compose.yml and broker.conf.'
}
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

foreach ($service in @(@{Name='processor';Port=8083}, @{Name='collector';Port=8082}, @{Name='gateway';Port=8080})) {
    $module = $service.Name
    $jarPath = Join-Path $projectRoot "smart-$module\target\smart-$module-1.0.0-SNAPSHOT.jar"
    $listener = Listener $service.Port
    if ($listener) {
        $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
        if ($owner.CommandLine -notlike "*$jarPath*") { throw "Port $($service.Port) belongs to another application." }
        continue
    }
    if (-not (Test-Path -LiteralPath $jarPath)) { throw "Build missing: $jarPath" }
    # Desktop Outlook COM must run as the signed-in Windows user, not an isolated service account.
    $javaArgs = @("`"-Duser.home=$env:USERPROFILE`"", '-Djava.net.useSystemProxies=true', '-jar', "`"$jarPath`"", '--spring.cloud.nacos.config.enabled=false', '--spring.cloud.nacos.discovery.enabled=false', '--server.shutdown=graceful', '--spring.lifecycle.timeout-per-shutdown-phase=60s')
    if ($module -eq 'processor') { $javaArgs += '--spring.autoconfigure.exclude=org.springframework.ai.autoconfigure.vectorstore.milvus.MilvusVectorStoreAutoConfiguration' }
    Start-Process java -ArgumentList $javaArgs -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput "$logDir\$module.out.log" -RedirectStandardError "$logDir\$module.err.log" | Out-Null
    if ($module -eq 'processor') { Wait-Http 'http://127.0.0.1:8083/actuator/health' }
    if ($module -eq 'collector') { Wait-Http 'http://127.0.0.1:8082/api/outlook/status' }
}
if (-not $BackendOnly) { Start-Web }
Wait-Http 'http://127.0.0.1:8080/api/mails/summaries?size=1' 120
# The collector automatically syncs Outlook after three seconds and IMAP after five.
# Wake-up is ready once the API works; do not block the switch on a slow mail provider.
Write-Host 'Smart Inbox: http://127.0.0.1:5173/'
if (-not $NoBrowser) { Start-Process 'http://127.0.0.1:5173/' }
