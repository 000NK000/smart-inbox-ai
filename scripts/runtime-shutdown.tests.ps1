# Runs the real shutdown orchestration with fake Docker boundaries. No container,
# Java process, user data, or network service is touched. Windows PowerShell 5.1.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'runtime-shutdown.ps1')
$script:TestCount = 0
$script:TestProjectRoot = 'C:\shutdown-test\owned'
$script:TestServices = @('redis', 'rmqnamesrv', 'rmqbroker', 'grafana')

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "FAIL: $Message" }
}
function Assert-Equal($Actual, $Expected, [string]$Message) {
    if ($Actual -ne $Expected) { throw "FAIL: $Message; expected <$Expected>, got <$Actual>" }
}
function Assert-Throws([scriptblock]$Action, [string]$Pattern) {
    $caught = $null
    try { & $Action | Out-Null } catch { $caught = $_ }
    Assert-True ($null -ne $caught) "Expected failure matching <$Pattern>"
    Assert-True ($caught.Exception.Message -match $Pattern) "Unexpected failure: $($caught.Exception.Message)"
}
function Test-Case([string]$Name, [scriptblock]$Action) {
    Reset-Scenario
    & $Action
    $script:TestCount++
    Write-Output "PASS $Name"
}
function New-FakeContainer([string]$Service, [string]$Root, [bool]$Running = $true, [string]$Id = '') {
    if (-not $Id) { $Id = $Service + '-id' }
    [pscustomobject]@{
        Id = $Id
        Config = [pscustomobject]@{ Labels = [pscustomobject]@{
            'com.docker.compose.project.working_dir' = $Root
            'com.docker.compose.service' = $Service
        } }
        State = [pscustomobject]@{ Running = $Running; Restarting = $false; Status = $(if ($Running) { 'running' } else { 'exited' }); OOMKilled = $false; ExitCode = 0 }
        HostConfig = [pscustomobject]@{ RestartPolicy = [pscustomobject]@{ Name = 'unless-stopped' } }
    }
}
function Reset-Scenario {
    # Deliberately not dependency order: production code must order the shutdown.
    $script:Containers = @(
        (New-FakeContainer 'redis' $script:TestProjectRoot),
        (New-FakeContainer 'rmqnamesrv' $script:TestProjectRoot),
        (New-FakeContainer 'grafana' $script:TestProjectRoot),
        (New-FakeContainer 'rmqbroker' $script:TestProjectRoot)
    )
    $script:Calls = New-Object System.Collections.ArrayList
    $script:Modes = @{}
    $script:ListFails = $false
    $script:InspectFails = $false
    $script:StateFailsFor = ''
    $script:UpdateFailsFor = ''
    $script:StopFails = $false
    $script:OtherExitCode = 0
    $script:OtherOom = $false
}
function Container([string]$Id) {
    $found = @($script:Containers | Where-Object Id -eq $Id)
    Assert-Equal $found.Count 1 'A fake Docker ID must identify exactly one container'
    return $found[0]
}
function Mutation-Calls {
    @($script:Calls | Where-Object { $_.Arguments[0] -in @('update', 'exec', 'stop') } | ForEach-Object { $_.Arguments -join ' ' })
}
function Invoke-Shutdown {
    Stop-ProjectContainersGracefully -ProjectRoot $script:TestProjectRoot -Services $script:TestServices -QueueTimeoutSeconds 0 | Out-Null
}
function Assert-DependenciesRunning {
    Assert-True (Container 'rmqnamesrv-id').State.Running 'Nameserver must survive a failed broker drain'
    Assert-True (Container 'redis-id').State.Running 'Redis must survive a failed broker drain'
    Assert-True (Container 'grafana-id').State.Running 'Other services must survive a failed broker drain'
    $dependencyMutations = @(Mutation-Calls | Where-Object { $_ -match 'rmqnamesrv-id|redis-id|grafana-id' })
    Assert-Equal $dependencyMutations.Count 0 'No dependency mutation may follow a failed broker drain'
}

# Fake only the external command boundary. Unexpected commands fail closed, so a
# future regression to kill/stop/remove/recreate a queue container is detected.
function docker {
    $arguments = @($args | ForEach-Object { [string]$_ })
    $null = $script:Calls.Add([pscustomobject]@{ Arguments = $arguments })
    $global:LASTEXITCODE = 0
    switch ($arguments[0]) {
        'ps' {
            Assert-Equal ($arguments -join ' ') 'ps -aq --filter label=com.docker.compose.project=smart-inbox-ai' 'Discovery must be scoped to the project label'
            if ($script:ListFails) { $global:LASTEXITCODE = 1; return }
            $script:Containers | ForEach-Object Id
            return
        }
        'inspect' {
            if ($arguments[1] -eq '--format') {
                Assert-Equal $arguments[2] '{{json .State}}' 'Polling reads only state'
                $id = $arguments[3]
                if ($script:StateFailsFor -eq $id) { $global:LASTEXITCODE = 1; return }
                (Container $id).State | ConvertTo-Json -Compress
            } else {
                if ($script:InspectFails) { $global:LASTEXITCODE = 1; return }
                $selected = @($arguments[1..($arguments.Count - 1)] | ForEach-Object { Container $_ })
                ConvertTo-Json -InputObject $selected -Depth 8 -Compress
            }
            return
        }
        'update' {
            Assert-Equal $arguments[1] '--restart' 'Only restart policy may be updated'
            foreach ($id in $arguments[3..($arguments.Count - 1)]) {
                if ($script:UpdateFailsFor -eq $id) { $global:LASTEXITCODE = 1; return }
                (Container $id).HostConfig.RestartPolicy.Name = $arguments[2]
            }
            return
        }
        'exec' {
            $id = $arguments[1]
            $item = Container $id
            $kind = if ($item.Config.Labels.'com.docker.compose.service' -eq 'rmqbroker') { 'broker' } else { 'namesrv' }
            Assert-Equal ($arguments[2..($arguments.Count - 1)] -join ' ') "sh mqshutdown $kind" 'Queue shutdown must use the official command'
            Assert-Equal $item.HostConfig.RestartPolicy.Name 'no' 'Disable automatic restart before requesting JVM shutdown'
            $mode = $script:Modes[$id]
            if ($mode -eq 'exec-fails') { $global:LASTEXITCODE = 1; return }
            if ($mode -eq 'stuck') { return }
            $item.State.Running = $false
            $item.State.Restarting = $false
            $item.State.Status = 'exited'
            $item.State.ExitCode = if ($mode -eq 'exit-0') { 0 } else { 143 }
            if ($mode -eq 'exit-137') { $item.State.ExitCode = 137 }
            if ($mode -eq 'oom') { $item.State.OOMKilled = $true }
            if ($mode -eq 'dead') { $item.State.Status = 'dead' }
            if ($mode -eq 'exec-race') { $global:LASTEXITCODE = 1 }
            return
        }
        'stop' {
            Assert-Equal ($arguments[1..2] -join ' ') '--time 60' 'Other services retain their graceful timeout'
            foreach ($id in $arguments[3..($arguments.Count - 1)]) {
                $item = Container $id
                Assert-True ($item.Config.Labels.'com.docker.compose.service' -notin @('rmqbroker', 'rmqnamesrv')) 'Never use docker stop on queue shell wrappers'
                if ($script:StopFails) { $global:LASTEXITCODE = 1; return }
                $item.State.Running = $false
                $item.State.Status = 'exited'
                $item.State.ExitCode = $script:OtherExitCode
                $item.State.OOMKilled = $script:OtherOom
            }
            return
        }
        default { throw "Unexpected Docker command: $($arguments -join ' ')" }
    }
}
function Start-Sleep { throw 'Tests must not sleep: immediate fake exits or a zero timeout are required.' }

Test-Case 'broker exits before nameserver, then Redis and optional services stop' {
    Invoke-Shutdown
    Assert-Equal ((Mutation-Calls) -join '|') 'update --restart no rmqbroker-id|exec rmqbroker-id sh mqshutdown broker|update --restart no rmqnamesrv-id|exec rmqnamesrv-id sh mqshutdown namesrv|update --restart unless-stopped redis-id grafana-id|stop --time 60 redis-id grafana-id' 'Shutdown order'
    foreach ($item in $script:Containers) { Assert-True (-not $item.State.Running) 'All owned requested services stopped' }
    Assert-Equal (Container 'rmqbroker-id').HostConfig.RestartPolicy.Name 'no' 'Broker stays closed after graceful exit'
    Assert-Equal (Container 'rmqnamesrv-id').HostConfig.RestartPolicy.Name 'no' 'Nameserver stays closed after graceful exit'
}
Test-Case 'successful zero exit is accepted as well as SIGTERM exit 143' {
    $script:Modes['rmqbroker-id'] = 'exit-0'
    Invoke-Shutdown
    Assert-Equal (Container 'rmqbroker-id').State.ExitCode 0 'Successful normal JVM exit accepted'
    Assert-Equal (Container 'rmqnamesrv-id').State.ExitCode 143 'Successful TERM exit accepted'
}
Test-Case 'same Compose project label from another checkout is never mutated' {
    $foreign = New-FakeContainer 'rmqbroker' 'C:\another-checkout' $true 'foreign-id'
    $script:Containers += $foreign
    Invoke-Shutdown
    Assert-True $foreign.State.Running 'Foreign container stays running'
    Assert-Equal $foreign.HostConfig.RestartPolicy.Name 'unless-stopped' 'Foreign restart policy preserved'
    Assert-Equal @(Mutation-Calls | Where-Object { $_ -match 'foreign-id' }).Count 0 'No foreign mutation'
}
Test-Case 'services outside the requested selection are preserved' {
    $extra = New-FakeContainer 'mysql' $script:TestProjectRoot
    $script:Containers += $extra
    Invoke-Shutdown
    Assert-True $extra.State.Running 'Unrequested service remains running'
    Assert-Equal @(Mutation-Calls | Where-Object { $_ -match 'mysql-id' }).Count 0 'No unrequested mutation'
}
Test-Case 'already stopped services require no update, exec, or stop' {
    $script:Containers = @((New-FakeContainer 'rmqbroker' $script:TestProjectRoot $false), (New-FakeContainer 'redis' $script:TestProjectRoot $false))
    Invoke-Shutdown
    Assert-Equal @(Mutation-Calls).Count 0 'Stopped services are untouched'
}
Test-Case 'retry after an abnormal queue exit still fails before stopping dependencies' {
    $script:Modes['rmqbroker-id'] = 'exit-137'
    Assert-Throws { Invoke-Shutdown } 'exited abnormally'
    $script:Calls.Clear()
    Assert-Throws { Invoke-Shutdown } 'previously exited abnormally'
    Assert-Equal @(Mutation-Calls).Count 0 'Retry never silently skips a failed broker'
    Assert-True (Container 'rmqnamesrv-id').State.Running 'Nameserver remains available'
    Assert-True (Container 'redis-id').State.Running 'Redis remains available'
}
Test-Case 'stopped OOM and dead queue states cannot be reported as safe standby' {
    $item = Container 'rmqbroker-id'
    $item.State.Running = $false
    $item.State.Status = 'exited'
    $item.State.ExitCode = 0
    $item.State.OOMKilled = $true
    Assert-Throws { Invoke-Shutdown } 'previously exited abnormally'
    $item.State.OOMKilled = $false
    $item.State.Status = 'dead'
    Assert-Throws { Invoke-Shutdown } 'previously exited abnormally'
    Assert-Equal @(Mutation-Calls).Count 0 'Neither case mutates dependencies'
}
Test-Case 'empty discovery returns without inspection or mutation' {
    $script:Containers = @()
    Invoke-Shutdown
    Assert-Equal $script:Calls.Count 1 'Only project discovery required'
}
Test-Case 'restarting queue container is handled rather than skipped' {
    $item = Container 'rmqbroker-id'
    $item.State.Running = $false
    $item.State.Restarting = $true
    $item.State.Status = 'restarting'
    Invoke-Shutdown
    Assert-True (-not $item.State.Restarting) 'Restart loop stopped'
    Assert-Equal $item.HostConfig.RestartPolicy.Name 'no' 'Restart policy remains disabled'
}
Test-Case 'queue exec failure leaves every dependency available' {
    $script:Modes['rmqbroker-id'] = 'exec-fails'
    Assert-Throws { Invoke-Shutdown } 'did not accept its graceful shutdown'
    Assert-DependenciesRunning
}
Test-Case 'a hung queue reaches its deadline without force killing or stopping dependencies' {
    $script:Modes['rmqbroker-id'] = 'stuck'
    Assert-Throws { Invoke-Shutdown } 'still saving after 0 seconds'
    Assert-True (Container 'rmqbroker-id').State.Running 'Hung broker is not killed'
    Assert-DependenciesRunning
}
Test-Case 'OOM exit is unsafe even with an otherwise accepted exit code' {
    $script:Modes['rmqbroker-id'] = 'oom'
    Assert-Throws { Invoke-Shutdown } 'exited abnormally'
    Assert-DependenciesRunning
}
Test-Case 'exit 137 is not accepted as a clean queue stop' {
    $script:Modes['rmqbroker-id'] = 'exit-137'
    Assert-Throws { Invoke-Shutdown } 'exited abnormally.*137'
    Assert-DependenciesRunning
}
Test-Case 'non-exited queue state is rejected even when not running' {
    $script:Modes['rmqbroker-id'] = 'dead'
    Assert-Throws { Invoke-Shutdown } 'exited abnormally'
    Assert-DependenciesRunning
}
Test-Case 'exec failure after a confirmed clean concurrent exit is harmless' {
    $script:Modes['rmqbroker-id'] = 'exec-race'
    Invoke-Shutdown
    Assert-True (-not (Container 'redis-id').State.Running) 'Clean exit race allows remaining shutdown'
}
Test-Case 'Docker discovery errors are surfaced before mutation' {
    $script:ListFails = $true
    Assert-Throws { Invoke-Shutdown } 'Cannot contact Docker'
    Assert-Equal @(Mutation-Calls).Count 0 'No changes on failed discovery'
}
Test-Case 'ownership inspection failure is surfaced before mutation' {
    $script:InspectFails = $true
    Assert-Throws { Invoke-Shutdown } 'Cannot inspect project containers'
    Assert-Equal @(Mutation-Calls).Count 0 'No changes without ownership proof'
}
Test-Case 'queue state inspection failure preserves dependencies' {
    $script:StateFailsFor = 'rmqbroker-id'
    Assert-Throws { Invoke-Shutdown } 'Cannot verify project container state'
    Assert-DependenciesRunning
}
Test-Case 'restart policy update failure never proceeds to queue shutdown' {
    $script:UpdateFailsFor = 'rmqbroker-id'
    Assert-Throws { Invoke-Shutdown } 'Could not disable automatic restart'
    Assert-Equal @(Mutation-Calls | Where-Object { $_ -like 'exec *' }).Count 0 'No JVM request without safe restart policy'
    Assert-DependenciesRunning
}
Test-Case 'nameserver failure prevents Redis or optional container shutdown' {
    $script:Modes['rmqnamesrv-id'] = 'exec-fails'
    Assert-Throws { Invoke-Shutdown } 'did not accept its graceful shutdown'
    Assert-True (-not (Container 'rmqbroker-id').State.Running) 'Broker completed first'
    Assert-True (Container 'redis-id').State.Running 'Redis preserved after nameserver failure'
    Assert-Equal @(Mutation-Calls | Where-Object { $_ -match 'redis-id|grafana-id' }).Count 0 'No later container mutation'
}
Test-Case 'other container policy errors are surfaced before Docker stop' {
    $script:UpdateFailsFor = 'redis-id'
    Assert-Throws { Invoke-Shutdown } 'Could not preserve project container stop policy'
    Assert-Equal @(Mutation-Calls | Where-Object { $_ -like 'stop *' }).Count 0 'Failed update prevents stop'
}
Test-Case 'other container Docker stop errors are surfaced' {
    $script:StopFails = $true
    Assert-Throws { Invoke-Shutdown } 'A project container could not stop'
}
Test-Case 'other container exit 137 is not silently accepted' {
    $script:OtherExitCode = 137
    Assert-Throws { Invoke-Shutdown } 'did not confirm a clean stop'
}
Test-Case 'other container OOM is not silently accepted' {
    $script:OtherOom = $true
    Assert-Throws { Invoke-Shutdown } 'did not confirm a clean stop'
}

Write-Output "All $script:TestCount runtime shutdown tests passed (PowerShell $($PSVersionTable.PSVersion))."
