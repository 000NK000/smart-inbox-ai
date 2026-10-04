# Exercises the real queue orchestration and port selector with simulated external
# boundaries. Does not invoke Docker, inspect Java, or touch the app. The isolated
# nested-scope regression briefly binds candidate ports using the real probe.
# Run with Windows PowerShell 5.1: powershell.exe -NoProfile -File <this file>
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\start-queue.ps1"

$script:Checks = 0
function Assert-QueueTest([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "FAIL: $Message" }
    $script:Checks++
}

function New-FakeQueueContainer([string]$Name, [string]$Root, [bool]$Running, [hashtable]$Mappings, [string]$RestartPolicy = 'unless-stopped') {
    $bindings = @{}
    foreach ($target in $Mappings.Keys) {
        $bindings[$target] = @([pscustomobject]@{ HostIp = '127.0.0.1'; HostPort = [string]$Mappings[$target] })
    }
    [pscustomobject]@{
        Id = $Name + '-id'
        Name = $Name
        Config = [pscustomobject]@{ Labels = [pscustomobject]@{ 'com.docker.compose.project.working_dir' = $Root } }
        State = [pscustomobject]@{ Running = $Running }
        HostConfig = [pscustomobject]@{ PortBindings = [pscustomobject]$bindings; RestartPolicy = [pscustomobject]@{ Name = $RestartPolicy } }
    }
}

function Reset-QueueScenario([string]$Name) {
    $script:ScenarioRoot = Join-Path $script:TestRoot $Name
    $runtime = Join-Path $script:ScenarioRoot '.smart-inbox\runtime'
    New-Item -ItemType Directory -Path $runtime -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $script:ScenarioRoot 'broker.conf'), "brokerName = broker-a`nbrokerIP1 = 127.0.0.1`nlistenPort = 30911`n")
    $script:FakeContainers = @{
        'smart-rmqnamesrv' = (New-FakeQueueContainer 'smart-rmqnamesrv' $script:ScenarioRoot $false @{ '9876/tcp' = 9876 })
        'smart-rmqbroker' = (New-FakeQueueContainer 'smart-rmqbroker' $script:ScenarioRoot $true @{ '30911/tcp' = 30911; '30909/tcp' = 30909 })
        'smart-redis' = (New-FakeQueueContainer 'smart-redis' $script:ScenarioRoot $true @{ '6379/tcp' = 6380 })
    }
    $script:DockerCalls = New-Object System.Collections.ArrayList
    $script:FakeJavaProcesses = @()
    $script:StopSignals = New-Object System.Collections.ArrayList
    $script:RouteChecks = 0
    $script:RouteReadyAfter = 1
    $script:FailComposeService = ''
    $script:FailRestartUpdate = $false
    $script:ForeignOwnerAfterCompose = ''
    $script:ReplaceQueueIdsOnCompose = $false
    $script:FailRoute = $false
    $script:BlockAllPorts = $false
    $script:SimulatedSeconds = 0
    $script:ReservedRanges = @([pscustomobject]@{ StartPort = 9811; EndPort = 9910 })
    $script:InitialPorts = '{"NameServerPort":9876,"BrokerPort":30911,"BrokerVipPort":30909}'
    $script:PortStateFile = Join-Path $runtime 'queue-ports.json'
    [IO.File]::WriteAllText($script:PortStateFile, $script:InitialPorts)
    Set-Location -LiteralPath $script:ScenarioRoot
}

# Environmental boundaries only: real file generation, JSON persistence, ownership
# checks, reserved-port selection, and command ordering stay in production code.
function Get-WindowsReservedTcpRanges { $script:ReservedRanges }
function Test-QueueTcpPort([int]$Port) {
    if ($script:BlockAllPorts) { return $false }
    # Running owned bindings cannot be bound by a fresh probe, but must be reused.
    foreach ($container in $script:FakeContainers.Values) {
        if (-not $container.State.Running) { continue }
        foreach ($property in $container.HostConfig.PortBindings.PSObject.Properties) {
            if ($Port -in @($property.Value | ForEach-Object { [int]$_.HostPort })) { return $false }
        }
    }
    return $true
}
function Get-CimInstance { $script:FakeJavaProcesses | Where-Object { $_.Alive } }
function Get-Process([int]$Id) {
    $process = $script:FakeJavaProcesses | Where-Object { $_.ProcessId -eq $Id -and $_.Alive } | Select-Object -First 1
    if (-not $process) { return }
    $signal = Join-Path $script:ScenarioRoot ".smart-inbox\runtime\stop-$($process.Module)"
    if ((Test-Path -LiteralPath $signal) -and [IO.File]::ReadAllText($signal) -eq [string]$Id) {
        $null = $script:StopSignals.Add($process.Module)
        $process.Alive = $false
        Remove-Item -LiteralPath $signal
        return
    }
    $process
}
function Stop-Process { throw 'Queue startup must never forcibly kill a process.' }
function Get-Date { [datetime]'2026-09-30T12:00:00' + [timespan]::FromSeconds($script:SimulatedSeconds) }
function Start-Sleep { }
function Test-ProjectQueueRoute([int]$BrokerPort) {
    $script:RouteChecks++
    Assert-QueueTest ([IO.File]::ReadAllText($script:PortStateFile) -eq $script:BeforeStartPorts) 'Do not commit selected ports before route readiness'
    Assert-QueueTest ($BrokerPort -eq [int]$env:SMART_INBOX_BROKER_PORT) 'Route probe uses the selected broker port'
    if ($script:FailRoute) { $script:SimulatedSeconds = 100; return $false }
    return $script:RouteChecks -ge $script:RouteReadyAfter
}
function docker {
    $arguments = @($args | ForEach-Object { [string]$_ })
    $global:LASTEXITCODE = 0
    $null = $script:DockerCalls.Add([pscustomobject]@{
        Arguments = $arguments
        NameServerPort = $env:SMART_INBOX_NAMESRV_PORT
        BrokerPort = $env:SMART_INBOX_BROKER_PORT
        VipPort = $env:SMART_INBOX_BROKER_VIP_PORT
        BrokerConfig = $env:SMART_INBOX_BROKER_CONFIG
    })
    if ($arguments[0] -eq 'container' -and $arguments[1] -eq 'ls') {
        $script:FakeContainers.Keys
        return
    }
    if ($arguments[0] -eq 'inspect') {
        ConvertTo-Json -InputObject @($script:FakeContainers[$arguments[1]]) -Depth 8 -Compress
        return
    }
    if ($arguments[0] -eq 'update') {
        Assert-QueueTest ($arguments[1] -eq '--restart' -and $arguments[2] -eq 'unless-stopped') 'Only the expected restart policy is restored'
        if ($script:FailRestartUpdate) { $global:LASTEXITCODE = 1; return }
        foreach ($id in $arguments[3..($arguments.Count - 1)]) {
            $container = @($script:FakeContainers.Values | Where-Object { $_.Id -eq $id })
            Assert-QueueTest ($container.Count -eq 1 -and $container[0].Name -in @('smart-rmqnamesrv', 'smart-rmqbroker')) 'Restart updates target only inspected queue container IDs'
            $container[0].HostConfig.RestartPolicy.Name = 'unless-stopped'
        }
        return
    }
    if ($arguments[0] -ne 'compose' -or $arguments[1] -ne 'up') {
        throw "Unexpected Docker command: $($arguments -join ' ')"
    }
    if ($script:FailComposeService -and $script:FailComposeService -in $arguments) {
        $global:LASTEXITCODE = 1
        return
    }
    if ('rmqnamesrv' -in $arguments) {
        $restart = $script:FakeContainers['smart-rmqnamesrv'].HostConfig.RestartPolicy.Name
        $script:FakeContainers['smart-rmqnamesrv'] = New-FakeQueueContainer 'smart-rmqnamesrv' $script:ScenarioRoot $true @{ '9876/tcp' = [int]$env:SMART_INBOX_NAMESRV_PORT } $restart
        if ($script:ReplaceQueueIdsOnCompose) { $script:FakeContainers['smart-rmqnamesrv'].Id += '-replacement' }
    }
    if ('rmqbroker' -in $arguments) {
        $broker = [int]$env:SMART_INBOX_BROKER_PORT
        $vip = [int]$env:SMART_INBOX_BROKER_VIP_PORT
        $restart = $script:FakeContainers['smart-rmqbroker'].HostConfig.RestartPolicy.Name
        $script:FakeContainers['smart-rmqbroker'] = New-FakeQueueContainer 'smart-rmqbroker' $script:ScenarioRoot $true @{ "$broker/tcp" = $broker; "$vip/tcp" = $vip } $restart
        if ($script:ReplaceQueueIdsOnCompose) { $script:FakeContainers['smart-rmqbroker'].Id += '-replacement' }
        if ($script:ForeignOwnerAfterCompose) {
            $script:FakeContainers['smart-rmqbroker'].Config.Labels.'com.docker.compose.project.working_dir' = $script:ForeignOwnerAfterCompose
        }
    }
}

function Invoke-ScenarioStart {
    $script:BeforeStartPorts = [IO.File]::ReadAllText($script:PortStateFile)
    Start-ProjectQueue -ProjectRoot $script:ScenarioRoot
}
function Assert-StartFails([string]$MessagePattern) {
    $caught = $null
    try { $null = Invoke-ScenarioStart } catch { $caught = $_ }
    Assert-QueueTest ($null -ne $caught) 'Startup must fail for the scenario'
    Assert-QueueTest ([string]$caught -match $MessagePattern) "Failure should explain $MessagePattern"
    Assert-QueueTest ([IO.File]::ReadAllText($script:PortStateFile) -eq $script:BeforeStartPorts) 'Failed startup preserves the last successful port state'
}

function Test-NestedQueueScriptScope {
    # A fresh process is essential: the fake Test-QueueTcpPort above would mask a
    # GetNewClosure resolution failure if inherited by this regression scenario.
    $scopeRoot = Join-Path $script:TestRoot 'nested-script-scope'
    New-Item -ItemType Directory -Path $scopeRoot -Force | Out-Null
    $outerScript = @'
param([string]$StartQueuePath, [string]$ProjectRoot)
$ErrorActionPreference = 'Stop'
try {
    & "$PSScriptRoot\nested-startup.ps1" -StartQueuePath $StartQueuePath -ProjectRoot $ProjectRoot
    exit 0
} catch {
    Write-Output ('REGRESSION_FAILURE:' + $_.FullyQualifiedErrorId + ':' + $_.Exception.Message)
    exit 1
}
'@
    $nestedScript = @'
param([string]$StartQueuePath, [string]$ProjectRoot)
$ErrorActionPreference = 'Stop'
# Match runtime-action -> start-smart-inbox -> dot-sourced start-queue/helpers.
# Keep all helper definitions in this child script scope, never in global scope.
. $StartQueuePath
function Get-WindowsReservedTcpRanges { @() }
function Get-CimInstance { @() }
$script:ComposeCalls = 0
$script:RouteCalls = 0
function docker {
    $global:LASTEXITCODE = 0
    if ($args[0] -eq 'container' -and $args[1] -eq 'ls') {
        if ($script:ComposeCalls -ge 2) { @('smart-rmqnamesrv', 'smart-rmqbroker') }
        return
    }
    if ($args[0] -eq 'inspect') {
        ConvertTo-Json -InputObject @([pscustomobject]@{
            Id = [string]$args[1]
            Config = [pscustomobject]@{ Labels = [pscustomobject]@{ 'com.docker.compose.project.working_dir' = $ProjectRoot } }
            HostConfig = [pscustomobject]@{ RestartPolicy = [pscustomobject]@{ Name = 'unless-stopped' } }
        }) -Depth 8 -Compress
        return
    }
    if ($args[0] -ne 'compose' -or $args[1] -ne 'up') { throw 'Unexpected Docker command in nested-scope regression.' }
    $script:ComposeCalls++
}
function Test-ProjectQueueRoute([int]$BrokerPort) {
    $script:RouteCalls++
    if ($BrokerPort -ne [int]$env:SMART_INBOX_BROKER_PORT) { throw 'Nested route probe received the wrong broker port.' }
    return $true
}
New-Item -ItemType Directory -Path $ProjectRoot -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $ProjectRoot 'broker.conf'), "brokerName = broker-a`nbrokerIP1 = 127.0.0.1`nlistenPort = 30911`n")
Set-Location -LiteralPath $ProjectRoot
# Do not fake Test-QueueTcpPort. Occupied/reserved candidates naturally cause
# fallback; this scenario makes no assumption that any particular port is free.
$ports = Start-ProjectQueue -ProjectRoot $ProjectRoot
if (-not (Test-QueuePortConfiguration $ports)) { throw 'Nested startup returned an invalid selected port trio.' }
$saved = Read-QueuePortConfiguration (Join-Path $ProjectRoot '.smart-inbox\runtime\queue-ports.json')
if ($saved.NameServerPort -ne $ports.NameServerPort -or $saved.BrokerPort -ne $ports.BrokerPort -or $saved.BrokerVipPort -ne $ports.BrokerVipPort) { throw 'Nested startup persisted a different selected port trio.' }
if ($script:ComposeCalls -ne 2 -or $script:RouteCalls -ne 1) { throw 'Nested startup did not complete the orchestration.' }
Write-Output 'NESTED_REAL_PROBE_OK'
'@
    $outerPath = Join-Path $scopeRoot 'outer-runtime.ps1'
    [IO.File]::WriteAllText($outerPath, $outerScript)
    [IO.File]::WriteAllText((Join-Path $scopeRoot 'nested-startup.ps1'), $nestedScript)

    # Recreate the former defect in a disposable fixture only. The production
    # startup script and helper are never modified by this regression test.
    $sourcePath = Join-Path $PSScriptRoot 'start-queue.ps1'
    $currentSource = [IO.File]::ReadAllText($sourcePath)
    $closurePattern = '(?m)^(\s*\$isAvailable\s*=\s*\{[^\r\n]*\})(\r?)$'
    $oldSource = [regex]::Replace($currentSource, $closurePattern, '$1.GetNewClosure()$2')
    Assert-QueueTest ($oldSource -ne $currentSource) 'Regression fixture restores the former closure behavior'
    $oldDirectory = Join-Path $scopeRoot 'old-closure-fixture'
    New-Item -ItemType Directory -Path $oldDirectory | Out-Null
    $oldPath = Join-Path $oldDirectory 'start-queue.ps1'
    [IO.File]::WriteAllText($oldPath, $oldSource)
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'queue-ports.ps1') -Destination (Join-Path $oldDirectory 'queue-ports.ps1')
    $windowsPowerShell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $oldOutput = @(& $windowsPowerShell -NoProfile -ExecutionPolicy Bypass -File $outerPath -StartQueuePath $oldPath -ProjectRoot (Join-Path $scopeRoot 'old-project'))
    $oldExitCode = $LASTEXITCODE
    Assert-QueueTest ($oldExitCode -ne 0) 'Former closure version fails when launched from a nested script'
    Assert-QueueTest (($oldOutput -join "`n") -match 'REGRESSION_FAILURE:.*CommandNotFoundException.*Test-QueueTcpPort') ('Former version fails specifically because the real TCP helper is out of scope: ' + ($oldOutput -join "`n"))

    $currentOutput = @(& $windowsPowerShell -NoProfile -ExecutionPolicy Bypass -File $outerPath -StartQueuePath $sourcePath -ProjectRoot (Join-Path $scopeRoot 'current-project'))
    $currentExitCode = $LASTEXITCODE
    Assert-QueueTest ($currentExitCode -eq 0) ('Current nested startup succeeds with the real TCP probe: ' + ($currentOutput -join "`n"))
    Assert-QueueTest ($currentOutput -contains 'NESTED_REAL_PROBE_OK') 'Current nested startup reaches successful persistence and route readiness'
    Write-Host 'PASS: nested-script scope with real TCP probe (old closure fails; fixed version succeeds)'
}

$script:TestRoot = Join-Path $PSScriptRoot ('.queue-integration-' + [guid]::NewGuid().ToString('N'))
$originalLocation = (Get-Location).Path
$originalEnvironment = @{}
$queueVariables = @('SMART_INBOX_NAMESRV_PORT', 'SMART_INBOX_BROKER_PORT', 'SMART_INBOX_BROKER_VIP_PORT', 'SMART_INBOX_BROKER_CONFIG')
foreach ($name in $queueVariables) { $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    Reset-QueueScenario 'reserved-nameserver'
    $script:RouteReadyAfter = 2
    $ports = Invoke-ScenarioStart
    Assert-QueueTest ($ports.NameServerPort -eq 29876) 'Reserved old nameserver port 9876 moves to 29876'
    Assert-QueueTest ($ports.BrokerPort -eq 30911 -and $ports.BrokerVipPort -eq 30909) 'Reuse the owned broker and VIP pair'
    Assert-QueueTest ($script:RouteChecks -eq 2) 'Startup retries until the broker route is ready'
    $saved = Read-QueuePortConfiguration $script:PortStateFile
    Assert-QueueTest ($saved.NameServerPort -eq $ports.NameServerPort -and $saved.BrokerPort -eq $ports.BrokerPort) 'Persist exactly the selected ports after readiness'
    $brokerConfigPath = Join-Path $script:ScenarioRoot '.smart-inbox\runtime\broker.conf'
    Assert-QueueTest ([IO.File]::ReadAllText($brokerConfigPath) -match '(?m)^listenPort = 30911$') 'Generated broker configuration matches the published broker port'
    $compose = @($script:DockerCalls | Where-Object { $_.Arguments[0] -eq 'compose' })
    Assert-QueueTest ($compose.Count -eq 2) 'Start dependencies and broker in separate reconciliation steps'
    Assert-QueueTest ($compose[0].Arguments -contains 'rmqnamesrv') 'Dependencies reconcile before broker startup'
    Assert-QueueTest ($compose[1].Arguments -contains '--force-recreate') 'First generated broker configuration recreates the broker'
    foreach ($call in $compose) {
        Assert-QueueTest ($call.NameServerPort -eq '29876' -and $call.BrokerPort -eq '30911' -and $call.VipPort -eq '30909') 'Every Compose command receives one coherent selected port trio'
        Assert-QueueTest ($call.BrokerConfig -eq './.smart-inbox/runtime/broker.conf') 'Compose uses the generated configuration'
        Assert-QueueTest ($call.Arguments -notcontains '--no-recreate' -and $call.Arguments -notcontains '-v') 'Reconciliation does not retain stale mappings or delete volumes'
    }

    $script:DockerCalls.Clear()
    $script:RouteChecks = 0
    $script:RouteReadyAfter = 1
    $retryPorts = Invoke-ScenarioStart
    $retryCompose = @($script:DockerCalls | Where-Object { $_.Arguments[0] -eq 'compose' })
    Assert-QueueTest ($retryPorts.NameServerPort -eq 29876 -and $retryPorts.BrokerPort -eq 30911) 'Repeated startup reuses live owned ports'
    Assert-QueueTest (@($retryCompose | Where-Object { $_.Arguments -contains '--force-recreate' }).Count -eq 0) 'Repeated startup does not unnecessarily recreate the broker'
    Assert-QueueTest (@($script:DockerCalls | Where-Object { $_.Arguments[0] -eq 'update' }).Count -eq 0) 'Healthy restart policies are not rewritten'
    Write-Host 'PASS: reserved-port recovery, readiness retry, coherent persistence, repeat startup'

    Reset-QueueScenario 'restore-restart-policy'
    $script:ReplaceQueueIdsOnCompose = $true
    foreach ($name in @('smart-rmqnamesrv', 'smart-rmqbroker', 'smart-redis')) {
        $script:FakeContainers[$name].State.Running = $false
        $script:FakeContainers[$name].HostConfig.RestartPolicy.Name = 'no'
    }
    $null = Invoke-ScenarioStart
    $updates = @($script:DockerCalls | Where-Object { $_.Arguments[0] -eq 'update' })
    Assert-QueueTest ($updates.Count -eq 1) 'Startup restores the stopped queue restart policies in one update'
    Assert-QueueTest ($updates[0].Arguments.Count -eq 5 -and 'smart-rmqnamesrv-id-replacement' -in $updates[0].Arguments -and 'smart-rmqbroker-id-replacement' -in $updates[0].Arguments) 'Restoration uses exactly the two replacement MQ IDs instead of pre-Compose IDs'
    $updateIndex = $script:DockerCalls.IndexOf($updates[0])
    $callsBeforeUpdate = @($script:DockerCalls | Select-Object -First $updateIndex)
    Assert-QueueTest (@($callsBeforeUpdate | Where-Object { $_.Arguments[0] -eq 'compose' }).Count -eq 2) 'Policy restoration runs after both Compose startups'
    Assert-QueueTest (@($callsBeforeUpdate | Where-Object { $_.Arguments[0] -eq 'inspect' }).Count -eq 6) 'Policies are based on freshly inspected post-Compose containers'
    Assert-QueueTest ($script:FakeContainers['smart-redis'].HostConfig.RestartPolicy.Name -eq 'no') 'Queue policy restoration does not change Redis'
    Assert-QueueTest ($script:FakeContainers['smart-rmqbroker'].HostConfig.RestartPolicy.Name -eq 'unless-stopped' -and $script:FakeContainers['smart-rmqnamesrv'].HostConfig.RestartPolicy.Name -eq 'unless-stopped') 'Both MQ containers regain their expected policy'
    Write-Host 'PASS: safe-stop restart policies restored only for owned MQ containers after Compose'

    Reset-QueueScenario 'restart-policy-failure'
    $script:FakeContainers['smart-rmqbroker'].HostConfig.RestartPolicy.Name = 'no'
    $script:FailRestartUpdate = $true
    Assert-StartFails 'restart policy could not be restored'
    Assert-QueueTest ($script:RouteChecks -eq 0) 'Restart-policy failure is surfaced before readiness and persistence'
    Write-Host 'PASS: restart-policy failure preserves saved ports and fails visibly'

    Reset-QueueScenario 'foreign-post-compose'
    $script:FakeContainers['smart-rmqbroker'].HostConfig.RestartPolicy.Name = 'no'
    $script:ForeignOwnerAfterCompose = 'C:\different-checkout'
    Assert-StartFails 'another checkout'
    Assert-QueueTest (@($script:DockerCalls | Where-Object { $_.Arguments[0] -eq 'update' }).Count -eq 0) 'Changed ownership is refused before any restart-policy update'
    Assert-QueueTest ($script:RouteChecks -eq 0) 'Post-Compose ownership mismatch never proceeds to readiness'
    Write-Host 'PASS: restart policy restoration rechecks checkout ownership'

    Reset-QueueScenario 'broker-port-reservation'
    $script:FakeContainers['smart-rmqnamesrv'] = New-FakeQueueContainer 'smart-rmqnamesrv' $script:ScenarioRoot $true @{ '9876/tcp' = 29876 }
    $script:ReservedRanges += [pscustomobject]@{ StartPort = 30909; EndPort = 30911 }
    [IO.File]::WriteAllText($script:PortStateFile, '{"NameServerPort":29876,"BrokerPort":30911,"BrokerVipPort":30909}')
    $processId = 41000
    foreach ($module in @('collector', 'processor')) {
        $processId++
        $jar = Join-Path $script:ScenarioRoot "smart-$module\target\smart-$module-1.0.0-SNAPSHOT.jar"
        $script:FakeJavaProcesses += [pscustomobject]@{ ProcessId = $processId; Module = $module; Alive = $true; CommandLine = "java -jar `"$jar`" --rocketmq.name-server=127.0.0.1:29876" }
    }
    $script:FakeJavaProcesses += [pscustomobject]@{ ProcessId = 42000; Module = 'unrelated'; Alive = $true; CommandLine = 'java -jar C:\another-app\server.jar' }
    $movedPorts = Invoke-ScenarioStart
    Assert-QueueTest ($movedPorts.NameServerPort -eq 29876) 'Broker reservation does not unnecessarily change the nameserver'
    Assert-QueueTest ($movedPorts.BrokerPort -ne 30911 -and $movedPorts.BrokerVipPort -eq $movedPorts.BrokerPort - 2) 'Reserved broker ports move as one coherent pair'
    Assert-QueueTest ($script:StopSignals.Count -eq 2 -and 'collector' -in $script:StopSignals -and 'processor' -in $script:StopSignals) 'Changed broker ports gracefully stop both clients even with unchanged nameserver'
    Assert-QueueTest ($script:FakeJavaProcesses[-1].Alive) 'Unrelated Java processes remain untouched'
    $movedConfig = [IO.File]::ReadAllText((Join-Path $script:ScenarioRoot '.smart-inbox\runtime\broker.conf'))
    Assert-QueueTest ($movedConfig -match ("(?m)^listenPort = " + $movedPorts.BrokerPort + '$')) 'Moved broker port is reflected in generated configuration'
    Write-Host 'PASS: broker port change safely stops only the affected project clients'

    Reset-QueueScenario 'foreign-checkout'
    $script:FakeContainers['smart-redis'].Config.Labels.'com.docker.compose.project.working_dir' = 'C:\different-checkout'
    Assert-StartFails 'another checkout'
    Assert-QueueTest (@($script:DockerCalls | Where-Object { $_.Arguments[0] -eq 'compose' }).Count -eq 0) 'Foreign checkout fails before any Docker mutation'
    Assert-QueueTest (-not (Test-Path (Join-Path $script:ScenarioRoot '.smart-inbox\runtime\broker.conf'))) 'Foreign checkout does not create runtime broker configuration'
    Write-Host 'PASS: foreign-checkout ownership guard'

    Reset-QueueScenario 'dependency-failure'
    $script:FailComposeService = 'rmqnamesrv'
    Assert-StartFails 'dependencies failed'
    Assert-QueueTest ($script:RouteChecks -eq 0) 'Dependency failure never probes readiness'
    Assert-QueueTest (@($script:DockerCalls | Where-Object { $_.Arguments -contains 'rmqbroker' }).Count -eq 0) 'Dependency failure does not continue into broker reconciliation'
    Write-Host 'PASS: dependency failure keeps saved ports'

    Reset-QueueScenario 'broker-failure'
    $script:FailComposeService = 'rmqbroker'
    Assert-StartFails 'broker failed'
    Assert-QueueTest ($script:RouteChecks -eq 0) 'Broker failure never probes readiness'
    Write-Host 'PASS: broker failure keeps saved ports'

    Reset-QueueScenario 'readiness-failure'
    $script:FailRoute = $true
    Assert-StartFails 'broker route is not ready'
    Assert-QueueTest ($script:RouteChecks -eq 1) 'Readiness failure honors the deadline'
    Write-Host 'PASS: missing broker route keeps saved ports'

    Reset-QueueScenario 'no-free-ports'
    $script:BlockAllPorts = $true
    Assert-StartFails 'No available queue nameserver'
    Assert-QueueTest (@($script:DockerCalls | Where-Object { $_.Arguments[0] -eq 'compose' }).Count -eq 0) 'No free port fails before Compose mutations'
    Write-Host 'PASS: exhausted port candidates prevent Compose startup'
    Test-NestedQueueScriptScope
    Write-Host "PASS: $script:Checks integration assertions (PowerShell $($PSVersionTable.PSVersion))"
}
finally {
    Set-Location -LiteralPath $originalLocation
    foreach ($name in $queueVariables) { [Environment]::SetEnvironmentVariable($name, $originalEnvironment[$name], 'Process') }
    # Remove only this test's unique temporary directory after an absolute-path guard.
    $resolvedRoot = [IO.Path]::GetFullPath($PSScriptRoot).TrimEnd('\') + '\'
    $resolvedTest = [IO.Path]::GetFullPath($script:TestRoot)
    if (-not $resolvedTest.StartsWith($resolvedRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Test cleanup path escaped its output directory.' }
    if (Test-Path -LiteralPath $resolvedTest) { Remove-Item -LiteralPath $resolvedTest -Recurse -Force }
}
