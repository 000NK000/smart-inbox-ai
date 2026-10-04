# Launch independent JVMs together, then verify every service before declaring
# startup complete. This helper never stops processes or changes queue state.
function Write-StartupTiming([string]$ProjectRoot, [string]$Stage, [double]$Seconds, [bool]$Success) {
    try {
        $directory = Join-Path $ProjectRoot '.smart-inbox\runtime'
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
        $entry = @{ at = [DateTime]::UtcNow.ToString('o'); stage = $Stage; seconds = [Math]::Round($Seconds, 3); success = $Success } | ConvertTo-Json -Compress
        [IO.File]::AppendAllText((Join-Path $directory 'startup-timing.log'), $entry + [Environment]::NewLine)
    } catch { Write-Warning 'Startup timing could not be saved; service readiness checks still apply.' }
}

function Invoke-StartupStage([string]$ProjectRoot, [string]$Stage, [scriptblock]$Work) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $succeeded = $false
    try { & $Work; $succeeded = $true }
    finally { Write-StartupTiming $ProjectRoot $Stage $timer.Elapsed.TotalSeconds $succeeded }
}

function Get-BackendStartupPlan([string]$ProjectRoot) {
    $ownedJava = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'")
    $plan = @()
    foreach ($definition in @(
        @{ Name = 'processor'; Port = 8083; Url = 'http://127.0.0.1:8083/actuator/health' },
        @{ Name = 'collector'; Port = 8082; Url = 'http://127.0.0.1:8082/api/outlook/status' },
        @{ Name = 'gateway'; Port = 8080; Url = 'http://127.0.0.1:8080/api/mails/summaries?size=1' }
    )) {
        $jar = Join-Path $ProjectRoot "smart-$($definition.Name)\target\smart-$($definition.Name)-1.0.0-SNAPSHOT.jar"
        if (-not (Test-Path -LiteralPath $jar)) { throw "Build missing: $jar" }
        $owned = @($ownedJava | Where-Object { $_.CommandLine -and $_.CommandLine.IndexOf($jar, [StringComparison]::OrdinalIgnoreCase) -ge 0 })
        if ($owned.Count -gt 1) { throw "Multiple owned $($definition.Name) processes are running. No additional JVMs were started." }
        $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $definition.Port -ErrorAction SilentlyContinue)
        foreach ($listener in $listeners) {
            if (-not $owned.Count -or $listener.OwningProcess -ne $owned[0].ProcessId) {
                throw "Port $($definition.Port) belongs to another application. No additional JVMs were started."
            }
        }
        $process = $null
        # Reuse an already-starting owned JVM even if its port is not bound yet.
        if ($owned.Count) { $process = Get-Process -Id $owned[0].ProcessId -ErrorAction Stop }
        $plan += [pscustomobject]@{ Name = $definition.Name; Jar = $jar; Url = $definition.Url; Process = $process }
    }
    return $plan
}

function Assert-BackendProcessesRunning([object[]]$Services) {
    foreach ($service in $Services) {
        $service.Process.Refresh()
        if ($service.Process.HasExited) {
            throw "Service $($service.Name) exited during startup (code $($service.Process.ExitCode)). Check .run-logs/$($service.Name).err.log and $($service.Name).out.log."
        }
    }
}

function Wait-BackendHttp([string]$Url, [object[]]$Services, [int]$Seconds = 90) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        Assert-BackendProcessesRunning $Services
        $ready = $false
        try { $null = Invoke-WebRequest -UseBasicParsing $Url -TimeoutSec 3; $ready = $true }
        catch { # A listener is not sufficient; retry the actual HTTP endpoint.
        }
        Assert-BackendProcessesRunning $Services
        if ($ready) { return }
        if ($timer.Elapsed.TotalSeconds -ge $Seconds) { break }
        Start-Sleep -Milliseconds 500
    } while ($timer.Elapsed.TotalSeconds -lt $Seconds)
    throw "Service did not become ready: $Url. Check .run-logs. No process was forcibly stopped."
}

function Start-ProjectBackends([string]$ProjectRoot, [string]$NameServerAddress, [string]$LogDir, [int]$ServiceTimeoutSeconds = 90, [int]$GatewayTimeoutSeconds = 120) {
    $plan = @(Invoke-StartupStage $ProjectRoot 'backend-preflight' { Get-BackendStartupPlan $ProjectRoot })
    Invoke-StartupStage $ProjectRoot 'backend-launch' {
        foreach ($service in $plan) {
            if ($null -ne $service.Process) { continue }
            # Outlook COM runs as the signed-in user. Raw Java ports remain local.
            $javaArgs = @("`"-Duser.home=$env:USERPROFILE`"", '-Djava.net.useSystemProxies=true', '-jar', "`"$($service.Jar)`"", '--server.address=127.0.0.1', '--spring.cloud.nacos.config.enabled=false', '--spring.cloud.nacos.discovery.enabled=false', '--server.shutdown=graceful', '--spring.lifecycle.timeout-per-shutdown-phase=60s')
            if ($service.Name -in @('collector', 'processor')) {
                $javaArgs += @("--rocketmq.name-server=$NameServerAddress", "--rocketmq.nameServer=$NameServerAddress")
            }
            if ($service.Name -eq 'processor') { $javaArgs += '--spring.autoconfigure.exclude=org.springframework.ai.autoconfigure.vectorstore.milvus.MilvusVectorStoreAutoConfiguration' }
            $service.Process = Start-Process java -ArgumentList $javaArgs -WorkingDirectory $ProjectRoot -WindowStyle Hidden -RedirectStandardOutput "$LogDir\$($service.Name).out.log" -RedirectStandardError "$LogDir\$($service.Name).err.log" -PassThru
        }
    }
    # No health wait is inside the launch loop. The queue route has already been
    # checked by the caller; these separate processes can initialize concurrently.
    foreach ($service in $plan) {
        $timeout = if ($service.Name -eq 'gateway') { $GatewayTimeoutSeconds } else { $ServiceTimeoutSeconds }
        Invoke-StartupStage $ProjectRoot ("ready-" + $service.Name) { Wait-BackendHttp $service.Url $plan $timeout }
    }
}
