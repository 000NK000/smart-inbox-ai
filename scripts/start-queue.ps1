# The desktop launcher owns these containers; never change Windows port reservations.
. "$PSScriptRoot\queue-ports.ps1"

function Get-ProjectQueueContainers([string]$ProjectRoot) {
    $names = @(& docker container ls -a --format '{{.Names}}')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect Docker containers.' }
    $containers = @{}
    foreach ($name in @('smart-rmqnamesrv', 'smart-rmqbroker', 'smart-redis')) {
        if ($name -notin $names) { continue }
        $details = & docker inspect $name
        if ($LASTEXITCODE -ne 0) { throw "Cannot inspect $name." }
        $info = @($details | ConvertFrom-Json)[0]
        if ($info.Config.Labels.'com.docker.compose.project.working_dir' -ine $ProjectRoot) {
            throw "Container $name belongs to another checkout. No containers were changed."
        }
        $containers[$name] = $info
    }
    return $containers
}

function Stop-OutdatedQueueClients([string]$ProjectRoot, [string]$NameServerAddress, [switch]$ReconfigureBroker) {
    foreach ($module in @('collector', 'processor')) {
        $jar = Join-Path $ProjectRoot "smart-$module\target\smart-$module-1.0.0-SNAPSHOT.jar"
        $processes = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like "*$jar*" })
        foreach ($process in $processes) {
            if (-not $ReconfigureBroker -and $process.CommandLine -match ('--rocketmq.name-server=' + [regex]::Escape($NameServerAddress) + '(?:\s|$)')) { continue }
            Write-Host "PROGRESS:Waiting for $module to save before updating its queue connection..."
            [IO.File]::WriteAllText((Join-Path $ProjectRoot ".smart-inbox\runtime\stop-$module"), [string]$process.ProcessId)
            $deadline = (Get-Date).AddSeconds(240)
            while (Get-Process -Id $process.ProcessId -ErrorAction SilentlyContinue) {
                if ((Get-Date) -gt $deadline) { throw "$module did not stop safely. No process was forcibly killed." }
                Start-Sleep -Seconds 1
            }
        }
    }
}

function Test-ProjectQueueRoute([int]$BrokerPort) {
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = (Get-Command docker.exe -ErrorAction Stop).Source
    $info.Arguments = 'exec smart-rmqbroker sh mqadmin clusterList -n rmqnamesrv:9876'
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = [Diagnostics.Process]::Start($info)
    try {
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(10000)) { $process.Kill(); return $false }
        $output = $stdout.GetAwaiter().GetResult()
        $null = $stderr.GetAwaiter().GetResult()
        return $process.ExitCode -eq 0 -and $output -match ('127\.0\.0\.1:' + $BrokerPort + '(?:\s|$)')
    } finally { $process.Dispose() }
}

function Restore-ProjectQueueRestartPolicy([string]$ProjectRoot) {
    # Safe shutdown temporarily uses restart=no so mqshutdown does not resurrect
    # the queue. Inspect after Compose: it may have replaced container IDs.
    $containers = Get-ProjectQueueContainers $ProjectRoot
    $restore = @()
    foreach ($name in @('smart-rmqnamesrv', 'smart-rmqbroker')) {
        $container = $containers[$name]
        if (-not $container) { throw "Queue container $name is missing after startup." }
        if ($container.HostConfig.RestartPolicy.Name -eq 'no') {
            $restore += $container.Id
        }
    }
    if ($restore.Count) {
        & docker update --restart unless-stopped @restore | Out-Host
        if ($LASTEXITCODE -ne 0) { throw 'Queue restart policy could not be restored after startup. No data volumes were removed.' }
    }
}

function Start-ProjectQueue([string]$ProjectRoot) {
    $runtimeDir = Join-Path $ProjectRoot '.smart-inbox\runtime'
    $portsFile = Join-Path $runtimeDir 'queue-ports.json'
    New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
    $containers = Get-ProjectQueueContainers $ProjectRoot
    $preferredNameServer = 29876
    $preferredBroker = 30911
    if (Test-Path -LiteralPath $portsFile) {
        try {
            $saved = Read-QueuePortConfiguration -Path $portsFile
            $preferredNameServer = $saved.NameServerPort
            $preferredBroker = $saved.BrokerPort
        } catch { Write-Warning 'Saved queue ports are invalid; checking default candidates.' }
    }

    # A listener counts as reusable only if it belongs to this checkout's running queue.
    $ownedPorts = @{}
    foreach ($name in @('smart-rmqnamesrv', 'smart-rmqbroker')) {
        $container = $containers[$name]
        if (-not $container -or -not $container.State.Running) { continue }
        $publishedPorts = @()
        foreach ($property in $container.HostConfig.PortBindings.PSObject.Properties) {
            foreach ($binding in @($property.Value)) {
                $ownedPorts[[int]$binding.HostPort] = $true
                $publishedPorts += [int]$binding.HostPort
                if ($name -eq 'smart-rmqnamesrv' -and $property.Name -eq '9876/tcp') {
                    $preferredNameServer = [int]$binding.HostPort
                }
            }
        }
        if ($name -eq 'smart-rmqbroker' -and $publishedPorts.Count) { $preferredBroker = ($publishedPorts | Measure-Object -Maximum).Maximum }
    }
    # Keep normal script scope: GetNewClosure hides dot-sourced helpers in PS 5.1.
    $isAvailable = { param($Port) if ($ownedPorts.ContainsKey([int]$Port)) { return $true }; Test-QueueTcpPort -Port $Port }
    $ports = Select-QueuePorts -PreferredNameServerPort $preferredNameServer -PreferredBrokerPort $preferredBroker -ExcludedRanges @(Get-WindowsReservedTcpRanges) -IsPortAvailable $isAvailable
    $address = "127.0.0.1:$($ports.NameServerPort)"
    $broker = $containers['smart-rmqbroker']
    $brokerPortChanged = $broker -and -not $broker.HostConfig.PortBindings.PSObject.Properties["$($ports.BrokerPort)/tcp"]
    Stop-OutdatedQueueClients -ProjectRoot $ProjectRoot -NameServerAddress $address -ReconfigureBroker:$brokerPortChanged

    $env:SMART_INBOX_NAMESRV_PORT = [string]$ports.NameServerPort
    $env:SMART_INBOX_BROKER_PORT = [string]$ports.BrokerPort
    $env:SMART_INBOX_BROKER_VIP_PORT = [string]$ports.BrokerVipPort
    $env:SMART_INBOX_BROKER_CONFIG = './.smart-inbox/runtime/broker.conf'
    $brokerConfig = Get-Content -Raw -LiteralPath (Join-Path $ProjectRoot 'broker.conf')
    $listenSetting = "listenPort = $($ports.BrokerPort)"
    if ($brokerConfig -match '(?m)^\s*listenPort\s*=') {
        $brokerConfig = [regex]::Replace($brokerConfig, '(?m)^\s*listenPort\s*=.*$', $listenSetting)
    } else { $brokerConfig += "`n$listenSetting`n" }
    $generatedBroker = Join-Path $runtimeDir 'broker.conf'
    $brokerChanged = -not (Test-Path -LiteralPath $generatedBroker)
    if (-not $brokerChanged) { $brokerChanged = [IO.File]::ReadAllText($generatedBroker) -ne $brokerConfig }
    if ($brokerChanged) { [IO.File]::WriteAllText($generatedBroker, $brokerConfig, [Text.UTF8Encoding]::new($false)) }

    Write-Host "PROGRESS:Queue ports checked: nameserver $($ports.NameServerPort), broker $($ports.BrokerPort), VIP $($ports.BrokerVipPort)."
    # Reconcile changed mappings instead of preserving stale --no-recreate bindings.
    # Compose retains the existing queue store and Redis volumes.
    & docker compose up -d rmqnamesrv redis | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Queue dependencies failed to start after port preflight. Check the Docker error above.' }
    $brokerArgs = @('compose', 'up', '-d', '--no-deps')
    if ($brokerChanged) { $brokerArgs += '--force-recreate' }
    & docker @brokerArgs rmqbroker | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Queue broker failed to start after port preflight. Check the Docker error above.' }
    Restore-ProjectQueueRestartPolicy -ProjectRoot $ProjectRoot

    # TCP LISTEN alone does not establish that clients can discover the broker.
    $deadline = (Get-Date).AddSeconds(90)
    do {
        if (Test-ProjectQueueRoute -BrokerPort $ports.BrokerPort) {
            [IO.File]::WriteAllText($portsFile, ($ports | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
            return $ports
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw 'Queue started but its broker route is not ready. Check the rmqbroker logs; no data volumes were removed.'
}
