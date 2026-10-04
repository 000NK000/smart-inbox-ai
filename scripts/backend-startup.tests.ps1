# Real orchestration with fake process/network boundaries. Never starts Java,
# Docker, Outlook, a browser, or a live Smart Inbox service. Compatible with PS 5.1.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'backend-startup.ps1')
$global:SmartInboxStartupTest_Checks = 0
$global:SmartInboxStartupTest_Cases = 0
$global:SmartInboxStartupTest_TestRoot = Join-Path ([IO.Path]::GetTempPath()) ('smart-inbox-startup-test-' + [guid]::NewGuid().ToString('N'))
$originalLocation = (Get-Location).Path

function Assert-Startup([bool]$Condition, [string]$Message) {
    $global:SmartInboxStartupTest_Checks++
    if (-not $Condition) { throw "FAIL: $Message" }
}
function Assert-StartupThrows([scriptblock]$Work, [string]$Pattern) {
    $caught = $null
    try { & $Work } catch { $caught = $_ }
    Assert-Startup ($null -ne $caught) "Expected failure: $Pattern"
    Assert-Startup ($caught.Exception.Message -match $Pattern) "Failure explained: $Pattern (actual: $($caught.Exception.Message))"
}
function New-FakeProcess([int]$Identity, [string]$Name) {
    $process = [pscustomobject]@{ Id = $Identity; Name = $Name; HasExited = $false; ExitCode = 17 }
    $process | Add-Member ScriptMethod Refresh { }
    return $process
}
function Reset-StartupScenario([string]$Name) {
    # Short fixture paths also work in Windows PowerShell 5.1 without long-path support.
    $global:SmartInboxStartupTest_ScenarioRoot = Join-Path $global:SmartInboxStartupTest_TestRoot ([string]$global:SmartInboxStartupTest_Cases)
    $global:SmartInboxStartupTest_Events = New-Object System.Collections.ArrayList
    $global:SmartInboxStartupTest_Launches = New-Object System.Collections.ArrayList
    $global:SmartInboxStartupTest_Live = @{}
    $global:SmartInboxStartupTest_Owned = @()
    $global:SmartInboxStartupTest_Listeners = @{ 11434 = @([pscustomobject]@{ OwningProcess = 9000 }) }
    $global:SmartInboxStartupTest_FailUrl = ''
    $global:SmartInboxStartupTest_FailOnce = $false
    $global:SmartInboxStartupTest_ExitAtLaunch = ''
    $global:SmartInboxStartupTest_ExitDuringProbe = ''
    $global:SmartInboxStartupTest_QueueFails = $false
    foreach ($name in @('processor', 'collector', 'gateway')) {
        $target = Join-Path $global:SmartInboxStartupTest_ScenarioRoot "smart-$name\target"
        New-Item -ItemType Directory -Path $target -Force | Out-Null
        [IO.File]::WriteAllText((Join-Path $target "smart-$name-1.0.0-SNAPSHOT.jar"), 'fake jar; never executed')
    }
    New-Item -ItemType Directory -Path (Join-Path $global:SmartInboxStartupTest_ScenarioRoot '.run-logs') -Force | Out-Null
}
function Own-Service([string]$Name, [int]$Port, [int]$Identity, [switch]$Unbound) {
    $jar = Join-Path $global:SmartInboxStartupTest_ScenarioRoot "smart-$Name\target\smart-$Name-1.0.0-SNAPSHOT.jar"
    $global:SmartInboxStartupTest_Owned += [pscustomobject]@{ Name = 'java.exe'; ProcessId = $Identity; CommandLine = "java -jar `"$jar`"" }
    $global:SmartInboxStartupTest_Live[$Identity] = New-FakeProcess $Identity $Name
    if (-not $Unbound) { $global:SmartInboxStartupTest_Listeners[$Port] = @([pscustomobject]@{ OwningProcess = $Identity }) }
}
function Get-CimInstance { param($ClassName, $Filter) return $global:SmartInboxStartupTest_Owned }
function Get-NetTCPConnection { param($State, $LocalPort, $ErrorAction) if ($global:SmartInboxStartupTest_Listeners.ContainsKey([int]$LocalPort)) { return $global:SmartInboxStartupTest_Listeners[[int]$LocalPort] } }
function Get-Process { param($Id, $ErrorAction) if (-not $global:SmartInboxStartupTest_Live.ContainsKey([int]$Id)) { throw 'Unknown fake process' }; return $global:SmartInboxStartupTest_Live[[int]$Id] }
function Stop-Process { throw 'Tests must never stop any process' }
function Start-Sleep { param($Milliseconds, $Seconds) $null = $global:SmartInboxStartupTest_Events.Add('retry') }
function Start-Process {
    param($FilePath, $ArgumentList, $WorkingDirectory, $WindowStyle, $RedirectStandardOutput, $RedirectStandardError, [switch]$PassThru)
    Assert-Startup ($FilePath -eq 'java') 'Only a mocked Java process is launched'
    Assert-Startup ($PassThru -and $WindowStyle -eq 'Hidden') 'Every Java launch is hidden and observed'
    $name = @('processor', 'collector', 'gateway') | Where-Object { $RedirectStandardOutput -like "*\$_.out.log" }
    Assert-Startup (@($name).Count -eq 1) 'Launch identifies exactly one service'
    $identity = 1000 + $global:SmartInboxStartupTest_Launches.Count
    $process = New-FakeProcess $identity $name
    if ($global:SmartInboxStartupTest_ExitAtLaunch -eq $name) { $process.HasExited = $true }
    $global:SmartInboxStartupTest_Live[$identity] = $process
    $null = $global:SmartInboxStartupTest_Launches.Add([pscustomobject]@{ Name = $name; Arguments = $ArgumentList; Process = $process })
    $null = $global:SmartInboxStartupTest_Events.Add("launch:$name")
    return $process
}
function Invoke-WebRequest {
    param([switch]$UseBasicParsing, $Uri, $TimeoutSec)
    $null = $global:SmartInboxStartupTest_Events.Add("http:$Uri")
    if ($global:SmartInboxStartupTest_ExitDuringProbe) {
        @($global:SmartInboxStartupTest_Live.Values | Where-Object Name -eq $global:SmartInboxStartupTest_ExitDuringProbe)[0].HasExited = $true
    }
    if ($global:SmartInboxStartupTest_FailUrl -and $Uri -like $global:SmartInboxStartupTest_FailUrl) {
        if ($global:SmartInboxStartupTest_FailOnce) { $global:SmartInboxStartupTest_FailUrl = '' }
        throw 'Simulated unavailable HTTP endpoint'
    }
    return [pscustomobject]@{ StatusCode = 200 }
}
function Invoke-FakeBackends([int]$Timeout = 90) {
    Start-ProjectBackends -ProjectRoot $global:SmartInboxStartupTest_ScenarioRoot -NameServerAddress '127.0.0.1:29876' -LogDir (Join-Path $global:SmartInboxStartupTest_ScenarioRoot '.run-logs') -ServiceTimeoutSeconds $Timeout -GatewayTimeoutSeconds $Timeout
}
function Timing-Entries { @(Get-Content (Join-Path $global:SmartInboxStartupTest_ScenarioRoot '.smart-inbox\runtime\startup-timing.log') | ForEach-Object { $_ | ConvertFrom-Json }) }
function Test-StartupCase([string]$Name, [scriptblock]$Work) {
    Reset-StartupScenario $Name
    & $Work
    $global:SmartInboxStartupTest_Cases++
    Write-Output "PASS: $Name"
}

try {
    Test-StartupCase 'launch-all-before-readiness' {
        Invoke-FakeBackends
        Assert-Startup (($global:SmartInboxStartupTest_Events[0..2] -join ',') -eq 'launch:processor,launch:collector,launch:gateway') 'All three services launch before any HTTP wait'
        Assert-Startup (@($global:SmartInboxStartupTest_Events | Where-Object { $_ -like 'http:*' }).Count -eq 3) 'Each service has a real readiness endpoint'
        Assert-Startup ($global:SmartInboxStartupTest_Events[-1] -eq 'http:http://127.0.0.1:8080/api/mails/summaries?size=1') 'Final gate checks the routed mail database endpoint'
        foreach ($launch in $global:SmartInboxStartupTest_Launches) {
            Assert-Startup ($launch.Arguments -contains '--server.address=127.0.0.1') 'Java binds to localhost'
            Assert-Startup ($launch.Arguments -contains '--server.shutdown=graceful') 'Graceful shutdown remains enabled'
            Assert-Startup ($launch.Arguments -contains '--spring.lifecycle.timeout-per-shutdown-phase=60s') 'Shutdown budget is preserved'
            if ($launch.Name -ne 'gateway') { Assert-Startup ($launch.Arguments -contains '--rocketmq.name-server=127.0.0.1:29876' -and $launch.Arguments -contains '--rocketmq.nameServer=127.0.0.1:29876') 'Both queue-property overrides use the selected address' }
        }
        $timings = Timing-Entries
        Assert-Startup ($timings.Count -eq 5 -and @($timings | Where-Object { -not $_.success }).Count -eq 0) 'Every completed phase records successful timing'
        Assert-Startup (($timings[0].PSObject.Properties.Name | Sort-Object) -join ',' -eq 'at,seconds,stage,success') 'Timing data contains no command arguments, tokens, or content'
    }
    Test-StartupCase 'late-port-conflict-starts-nothing' {
        $global:SmartInboxStartupTest_Listeners[8080] = @([pscustomobject]@{ OwningProcess = 99999 })
        Assert-StartupThrows { Invoke-FakeBackends } 'Port 8080 belongs to another application'
        Assert-Startup ($global:SmartInboxStartupTest_Launches.Count -eq 0) 'All port ownership checks finish before any Java launch'
    }
    Test-StartupCase 'missing-final-build-starts-nothing' {
        Remove-Item -LiteralPath (Join-Path $global:SmartInboxStartupTest_ScenarioRoot 'smart-gateway\target\smart-gateway-1.0.0-SNAPSHOT.jar')
        Assert-StartupThrows { Invoke-FakeBackends } 'Build missing'
        Assert-Startup ($global:SmartInboxStartupTest_Launches.Count -eq 0) 'All build files are checked before launch'
    }
    Test-StartupCase 'reuse-running-and-not-yet-listening-owned-processes' {
        Own-Service 'processor' 8083 201 -Unbound
        Own-Service 'collector' 8082 202
        Own-Service 'gateway' 8080 203
        Invoke-FakeBackends
        Assert-Startup ($global:SmartInboxStartupTest_Launches.Count -eq 0) 'Already-starting owned JVM is reused without a duplicate'
        Assert-Startup (@($global:SmartInboxStartupTest_Events | Where-Object { $_ -like 'http:*' }).Count -eq 3) 'Reused processes still pass every readiness gate'
    }
    Test-StartupCase 'multiple-owned-processes-are-rejected' {
        Own-Service 'processor' 8083 201 -Unbound
        Own-Service 'processor' 8083 202 -Unbound
        Assert-StartupThrows { Invoke-FakeBackends } 'Multiple owned processor processes'
        Assert-Startup ($global:SmartInboxStartupTest_Launches.Count -eq 0) 'Duplicate existing JVMs never cause a third launch'
    }
    Test-StartupCase 'early-jvm-exit-fails-before-network-wait' {
        $global:SmartInboxStartupTest_ExitAtLaunch = 'collector'
        Assert-StartupThrows { Invoke-FakeBackends } 'collector exited during startup'
        Assert-Startup (@($global:SmartInboxStartupTest_Events | Where-Object { $_ -like 'http:*' }).Count -eq 0) 'Exit of any JVM aborts the first readiness wait immediately'
        Assert-Startup (@(Timing-Entries | Where-Object { $_.stage -eq 'ready-processor' -and -not $_.success }).Count -eq 1) 'Failure is recorded as a failed readiness phase'
    }
    Test-StartupCase 'exit-during-another-service-probe-fails-fast' {
        $global:SmartInboxStartupTest_ExitDuringProbe = 'gateway'
        Assert-StartupThrows { Invoke-FakeBackends } 'gateway exited during startup'
        Assert-Startup (@($global:SmartInboxStartupTest_Events | Where-Object { $_ -like 'http:*' }).Count -eq 1) 'Successful HTTP cannot hide the death of a different backend'
    }
    Test-StartupCase 'not-ready-retries-without-relaunching' {
        $global:SmartInboxStartupTest_FailUrl = '*8083*'; $global:SmartInboxStartupTest_FailOnce = $true
        Invoke-FakeBackends
        Assert-Startup ($global:SmartInboxStartupTest_Launches.Count -eq 3 -and $global:SmartInboxStartupTest_Events -contains 'retry') 'Transient startup unavailability retries HTTP only'
    }
    Test-StartupCase 'service-timeout-fails-with-no-force-stop' {
        $global:SmartInboxStartupTest_FailUrl = '*8083*'
        Assert-StartupThrows { Invoke-FakeBackends 0 } 'Service did not become ready'
        Assert-Startup (@($global:SmartInboxStartupTest_Events | Where-Object { $_ -like '*8080*' }).Count -eq 0) 'A failed processor never declares the gateway ready'
    }
    Test-StartupCase 'gateway-business-endpoint-failure-is-not-success' {
        $global:SmartInboxStartupTest_FailUrl = '*8080*'
        Assert-StartupThrows { Invoke-FakeBackends 0 } 'Service did not become ready'
        Assert-Startup (@(Timing-Entries | Where-Object { $_.stage -eq 'ready-gateway' -and -not $_.success }).Count -eq 1) 'Final routed endpoint failure is retained'
    }

    # Exercise the real entry script too. Only infrastructure boundary scripts
    # are replaced in the isolated fixture; backend orchestration stays real.
    foreach ($queueFailure in @($false, $true)) {
        Test-StartupCase ("entry-script-queue-failure-$queueFailure") {
            $global:SmartInboxStartupTest_QueueFails = $queueFailure
            $fixtureScripts = Join-Path $global:SmartInboxStartupTest_ScenarioRoot 'scripts'
            New-Item -ItemType Directory -Path $fixtureScripts -Force | Out-Null
            foreach ($file in @('start-smart-inbox.ps1', 'backend-startup.ps1')) { Copy-Item -LiteralPath (Join-Path $PSScriptRoot $file) -Destination (Join-Path $fixtureScripts $file) }
            # Dedicated global test-state names remain visible when callbacks
            # run inside the entry script's nested PowerShell script scope.
            function global:Test-StartupQueueBoundary {
                $null = $global:SmartInboxStartupTest_Events.Add('queue-route')
                if ($global:SmartInboxStartupTest_QueueFails) { throw 'Simulated queue route failure' }
                return [pscustomobject]@{ NameServerPort = 29876 }
            }
            function global:Test-StartupDockerBoundary { $null = $global:SmartInboxStartupTest_Events.Add('docker-ready') }
            [IO.File]::WriteAllText((Join-Path $fixtureScripts 'ensure-docker.ps1'), 'function Ensure-Docker { Test-StartupDockerBoundary }')
            [IO.File]::WriteAllText((Join-Path $fixtureScripts 'start-queue.ps1'), 'function Start-ProjectQueue { param($ProjectRoot) Test-StartupQueueBoundary }')
            $entry = Join-Path $fixtureScripts 'start-smart-inbox.ps1'
            if ($queueFailure) {
                Assert-StartupThrows { & $entry -SkipBuild -NoBrowser -BackendOnly } 'Simulated queue route failure'
                Assert-Startup ($global:SmartInboxStartupTest_Launches.Count -eq 0) 'Queue route failure prevents every JVM launch'
                Assert-Startup (@(Timing-Entries | Where-Object { $_.stage -eq 'backend-total' -and -not $_.success }).Count -eq 1) 'Entry-script timing preserves failure'
            } else {
                & $entry -SkipBuild -NoBrowser -BackendOnly
                Assert-Startup (($global:SmartInboxStartupTest_Events[0..4] -join ',') -eq 'docker-ready,queue-route,launch:processor,launch:collector,launch:gateway') 'Queue route passes before any Java launch'
                Assert-Startup (@(Timing-Entries | Where-Object { $_.stage -eq 'backend-total' -and $_.success }).Count -eq 1) 'Entry script records completed startup'
            }
        }
    }
    Write-Output "PASS: $global:SmartInboxStartupTest_Cases startup cases, $global:SmartInboxStartupTest_Checks assertions (PowerShell $($PSVersionTable.PSVersion))"
} finally {
    Set-Location -LiteralPath $originalLocation
    Remove-Item Function:\Test-StartupQueueBoundary -ErrorAction SilentlyContinue
    Remove-Item Function:\Test-StartupDockerBoundary -ErrorAction SilentlyContinue
    $temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    $resolved = [IO.Path]::GetFullPath($global:SmartInboxStartupTest_TestRoot)
    if (-not $resolved.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notlike 'smart-inbox-startup-test-*') { throw 'Test cleanup path is outside its temporary directory.' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
