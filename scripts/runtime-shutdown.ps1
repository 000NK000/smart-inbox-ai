# Dot-sourced by runtime-action.ps1. Only containers owned by this checkout may
# be stopped. RocketMQ's image starts sh -> sh -> java, so Docker's PID 1 TERM
# never reaches the JVM. Use its supported shutdown command instead of waiting
# for Docker to SIGKILL the broker (and its unflushed messages) after 60 seconds.
function Get-RuntimeContainerState([string]$Id) {
    $json = & docker inspect --format '{{json .State}}' $Id
    if ($LASTEXITCODE -ne 0) { throw 'Cannot verify project container state.' }
    return ($json | ConvertFrom-Json)
}

function Get-OwnedRuntimeContainers([string]$ProjectRoot, [string[]]$Services) {
    $ids = @(& docker ps -aq --filter 'label=com.docker.compose.project=smart-inbox-ai')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot contact Docker Desktop.' }
    if (-not $ids.Count) { return }
    $json = & docker inspect @ids
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect project containers.' }
    # Windows PowerShell 5.1 emits the JSON array as one pipeline object.
    $inspected = $json | ConvertFrom-Json
    foreach ($info in $inspected) {
        $labels = $info.Config.Labels
        if ($labels.'com.docker.compose.project.working_dir' -ine $ProjectRoot) { continue }
        $service = $labels.'com.docker.compose.service'
        if ($service -notin $Services) { continue }
        # A retry after an abnormal broker exit must not turn a previous failure
        # into "safe standby" just because the failed process is no longer alive.
        if ($service -in @('rmqbroker', 'rmqnamesrv') -and -not ($info.State.Running -or $info.State.Restarting)) {
            if ($info.State.OOMKilled -or $info.State.ExitCode -notin @(0, 143) -or $info.State.Status -notin @('exited', 'created')) {
                throw "RocketMQ $service previously exited abnormally; resume and verify the queue before retrying shutdown."
            }
        }
        if ($info.State.Running -or $info.State.Restarting) { $info }
    }
}

function Stop-RuntimeQueueContainer {
    param([object]$Container, [ValidateSet('broker', 'namesrv')][string]$Kind, [int]$TimeoutSeconds = 60)
    $id = [string]$Container.Id
    # A normal JVM exit otherwise triggers unless-stopped and starts a new JVM.
    # Keep restart disabled while the app is closed; Start-ProjectQueue restores
    # the Compose policy once the user explicitly opens/resumes the app.
    & docker update --restart no $id | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not disable automatic restart for RocketMQ $Kind." }
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $state = Get-RuntimeContainerState $id
    if ($state.Running -or $state.Restarting) {
        & docker exec $id sh mqshutdown $Kind | Out-Host
        $code = $LASTEXITCODE
        $state = Get-RuntimeContainerState $id
        if ($code -ne 0 -and ($state.Running -or $state.Restarting)) {
            throw "RocketMQ $Kind did not accept its graceful shutdown request. No process was forcibly killed."
        }
    }
    while ($state.Running -or $state.Restarting) {
        if ($timer.Elapsed.TotalSeconds -ge $TimeoutSeconds) {
            throw "RocketMQ $Kind is still saving after $TimeoutSeconds seconds. No process was forcibly killed; dependencies remain available."
        }
        Start-Sleep -Milliseconds 500
        $state = Get-RuntimeContainerState $id
    }
    if ($state.Status -ne 'exited' -or $state.OOMKilled -or $state.ExitCode -notin @(0, 143)) {
        throw "RocketMQ $Kind exited abnormally (code $($state.ExitCode)); shutdown was not confirmed safe."
    }
}

function Stop-ProjectContainersGracefully([string]$ProjectRoot, [string[]]$Services, [int]$QueueTimeoutSeconds = 60) {
    $containers = @(Get-OwnedRuntimeContainers $ProjectRoot $Services)
    if (-not $containers.Count) { return }
    # Broker flushes/unregisters before its nameserver stops. Redis remains alive
    # until all JVMs have closed. Never stop dependencies after a failed drain.
    foreach ($service in @('rmqbroker', 'rmqnamesrv')) {
        foreach ($container in @($containers | Where-Object { $_.Config.Labels.'com.docker.compose.service' -eq $service })) {
            $kind = if ($service -eq 'rmqbroker') { 'broker' } else { 'namesrv' }
            Write-Output "RocketMQ graceful shutdown: $kind"
            Stop-RuntimeQueueContainer -Container $container -Kind $kind -TimeoutSeconds $QueueTimeoutSeconds
        }
    }
    $otherIds = @($containers | Where-Object { $_.Config.Labels.'com.docker.compose.service' -notin @('rmqbroker', 'rmqnamesrv') } | ForEach-Object { $_.Id })
    if ($otherIds.Count) {
        & docker update --restart unless-stopped @otherIds | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not preserve project container stop policy.' }
        & docker stop --time 60 @otherIds | Out-Host
        if ($LASTEXITCODE -ne 0) { throw 'A project container could not stop.' }
        foreach ($id in $otherIds) {
            $state = Get-RuntimeContainerState $id
            if ($state.Running -or $state.Restarting -or $state.OOMKilled -or $state.ExitCode -notin @(0, 143)) {
                throw 'A project container did not confirm a clean stop.'
            }
        }
    }
}
