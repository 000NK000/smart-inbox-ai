$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'queue-ports.ps1')
$script:testCount = 0

function Assert-Equal($Actual, $Expected, [string]$Message) {
    if ($Actual -ne $Expected) { throw "$Message; expected <$Expected>, got <$Actual>." }
}
function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Assert-Throws([scriptblock]$Action, [string]$Pattern) {
    $caught = $null
    try { & $Action | Out-Null } catch { $caught = $_ }
    if ($null -eq $caught) { throw "Expected an exception matching <$Pattern>." }
    if ($caught.Exception.Message -notmatch $Pattern) { throw "Unexpected exception: $($caught.Exception.Message)" }
}
function Test-Case([string]$Name, [scriptblock]$Action) {
    & $Action
    $script:testCount++
    Write-Output ('PASS ' + $Name)
}
function Assert-Ports($Actual, [int]$NameServer, [int]$Broker) {
    Assert-Equal $Actual.NameServerPort $NameServer 'Nameserver'
    Assert-Equal $Actual.BrokerPort $Broker 'Broker'
    Assert-Equal $Actual.BrokerVipPort ($Broker - 2) 'Broker VIP'
    Assert-True (Test-QueuePortConfiguration $Actual) 'Selection must form a valid configuration'
}

Test-Case 'valid preferred ports are reused even outside fallback ranges' {
    $ports = Select-QueuePorts -PreferredNameServerPort 28765 -PreferredBrokerPort 41011 -IsPortAvailable { $true }
    Assert-Ports $ports 28765 41011
}
Test-Case 'current 9876 excluded by 9811..9910 falls back to 29876' {
    $ports = Select-QueuePorts -PreferredNameServerPort 9876 -ExcludedRanges @(@{ StartPort = 9811; EndPort = 9910 }) -IsPortAvailable { $true }
    Assert-Ports $ports 29876 30911
}
Test-Case 'reserved default nameserver falls back and excluded ports are not probed' {
    $ports = Select-QueuePorts -ExcludedRanges @(@{ StartPort = 29876; EndPort = 29876 }) -IsPortAvailable {
        param($Port)
        if ($Port -eq 29876) { throw 'Excluded port reached probe' }
        $true
    }
    Assert-Ports $ports 29877 30911
}
Test-Case 'occupied default nameserver falls back' {
    $ports = Select-QueuePorts -IsPortAvailable { param($Port) $Port -ne 29876 }
    Assert-Ports $ports 29877 30911
}
Test-Case 'reserved broker alone rejects the whole pair' {
    $ports = Select-QueuePorts -ExcludedRanges @(@{ StartPort = 30911; EndPort = 30911 }) -IsPortAvailable { $true }
    Assert-Ports $ports 29876 31011
}
Test-Case 'reserved VIP alone rejects the whole pair' {
    $ports = Select-QueuePorts -ExcludedRanges @(@{ StartPort = 30909; EndPort = 30909 }) -IsPortAvailable { $true }
    Assert-Ports $ports 29876 31011
}
Test-Case 'occupied broker alone rejects the whole pair' {
    $ports = Select-QueuePorts -IsPortAvailable { param($Port) $Port -ne 30911 }
    Assert-Ports $ports 29876 31011
}
Test-Case 'occupied VIP alone rejects the whole pair' {
    $ports = Select-QueuePorts -IsPortAvailable { param($Port) $Port -ne 30909 }
    Assert-Ports $ports 29876 31011
}
Test-Case 'both excluded range endpoints are inclusive' {
    $ports = Select-QueuePorts -ExcludedRanges @(@{ StartPort = 29876; EndPort = 29877 }) -IsPortAvailable { $true }
    Assert-Ports $ports 29878 30911
}
Test-Case 'nameserver never collides with broker or VIP' {
    Assert-Ports (Select-QueuePorts -PreferredNameServerPort 30911 -IsPortAvailable { $true }) 30911 31011
    Assert-Ports (Select-QueuePorts -PreferredNameServerPort 30909 -IsPortAvailable { $true }) 30909 31011
}
Test-Case 'selection retries a different nameserver when needed for the sole broker pair' {
    $ports = Select-QueuePorts -PreferredNameServerPort 30911 -IsPortAvailable { param($Port) $Port -in @(30911, 30909, 29876) }
    Assert-Ports $ports 29876 30911
}
Test-Case 'last fallback nameserver and broker are included' {
    $ports = Select-QueuePorts -IsPortAvailable { param($Port) $Port -in @(29925, 39911, 39909) }
    Assert-Ports $ports 29925 39911
}
Test-Case 'all nameserver candidates unavailable fails clearly' {
    Assert-Throws { Select-QueuePorts -IsPortAvailable { $false } } 'No available queue nameserver'
}
Test-Case 'all broker pairs unavailable fails clearly' {
    Assert-Throws { Select-QueuePorts -IsPortAvailable { param($Port) $Port -ge 29876 -and $Port -le 29925 } } 'No available queue broker/VIP'
}
Test-Case 'probe output is strictly Boolean and probe errors propagate' {
    Assert-Throws { Select-QueuePorts -IsPortAvailable { 'False' } } 'exactly one Boolean'
    Assert-Throws { Select-QueuePorts -IsPortAvailable { 'debug'; $true } } 'exactly one Boolean'
    Assert-Throws { Select-QueuePorts -IsPortAvailable { throw 'Probe failed' } } 'Probe failed'
}
Test-Case 'invalid preferred ports and excluded ranges fail before selection' {
    Assert-Throws { Select-QueuePorts -PreferredNameServerPort 0 -IsPortAvailable { $true } } 'PreferredNameServerPort'
    Assert-Throws { Select-QueuePorts -PreferredBrokerPort 2 -IsPortAvailable { $true } } 'PreferredBrokerPort'
    Assert-Throws { Select-QueuePorts -ExcludedRanges @(@{ StartPort = 9; EndPort = 8 }) -IsPortAvailable { $true } } 'Invalid excluded TCP range'
    Assert-Throws { Select-QueuePorts -ExcludedRanges @(@{ StartPort = '9811'; EndPort = 9910 }) -IsPortAvailable { $true } } 'Invalid excluded TCP range'
}
Test-Case 'netsh parser ignores translated headings and understands administered marker' {
    $ranges = @(ConvertFrom-WindowsReservedTcpRanges @('Plages de ports TCP exclus', 'Debut Fin', '---------- --------', '     9811      9910', '    50000     50059     *', '* marqueur administratif'))
    Assert-Equal $ranges.Count 2 'Range count'
    Assert-Equal $ranges[0].StartPort 9811 'First range start'
    Assert-Equal $ranges[0].EndPort 9910 'First range end'
    Assert-Equal $ranges[1].EndPort 50059 'Administered range end'
}
Test-Case 'Windows ranges combine IPv4 and IPv6 and deduplicate' {
    $ranges = @(Get-WindowsReservedTcpRanges -NetshRunner {
        param($Family)
        if ($Family -eq 'ipv4') { @('IPv4 exclusions', '9811 9910', '50000 50059 *') }
        elseif ($Family -eq 'ipv6') { @('IPv6 exclusions', '9811 9910', '29876 29876') }
        else { throw 'Unexpected address family' }
    })
    Assert-Equal $ranges.Count 3 'Combined range count'
    Assert-Ports (Select-QueuePorts -ExcludedRanges $ranges -IsPortAvailable { $true }) 29877 30911
}
Test-Case 'netsh errors or invalid numeric rows fail explicitly' {
    Assert-Throws { Get-WindowsReservedTcpRanges -NetshRunner { throw 'netsh query failed' } } 'netsh query failed'
    Assert-Throws { Get-WindowsReservedTcpRanges -NetshRunner { @() } } 'returned no output'
    Assert-Throws { Get-WindowsReservedTcpRanges -NetshRunner { 'unrecognized output' } } 'not a recognizable table'
    Assert-Throws { ConvertFrom-WindowsReservedTcpRanges @('65530 65536') } 'invalid reserved TCP'
    Assert-Throws { ConvertFrom-WindowsReservedTcpRanges @('9910 9811') } 'invalid reserved TCP'
    Assert-Throws { ConvertFrom-WindowsReservedTcpRanges @('9811 100000') } 'invalid reserved TCP'
    Assert-Throws { ConvertFrom-WindowsReservedTcpRanges @('9811 99999999999999999999999999') } 'invalid reserved TCP'
    Assert-Throws { ConvertFrom-WindowsReservedTcpRanges @('9811 9910 unexpected') } 'malformed reserved TCP'
    Assert-Equal @(Get-WindowsReservedTcpRanges -NetshRunner { 'Start Port End Port'; '--------- --------' }).Count 0 'A successful table with no ranges is valid'
}
Test-Case 'persisted configuration rejects non-integers, missing fields, and collisions' {
    $valid = @{ NameServerPort = 29876; BrokerPort = 30911; BrokerVipPort = 30909 }
    Assert-True (Test-QueuePortConfiguration $valid) 'Valid hashtable'
    Assert-True (Test-QueuePortConfiguration ([pscustomobject]$valid)) 'Valid JSON-shaped object'
    foreach ($invalid in @($null, $true, '29876', 1.5, 0, 65536)) {
        $ports = @{ NameServerPort = $invalid; BrokerPort = 30911; BrokerVipPort = 30909 }
        Assert-True (-not (Test-QueuePortConfiguration $ports)) 'Invalid nameserver must be rejected without coercion'
    }
    Assert-True (-not (Test-QueuePortConfiguration @{ NameServerPort = 29876; BrokerPort = 30911 })) 'Missing VIP'
    Assert-True (-not (Test-QueuePortConfiguration @{ NameServerPort = 29876; BrokerPort = 30911; BrokerVipPort = 30910 })) 'Wrong VIP'
    Assert-True (-not (Test-QueuePortConfiguration @{ NameServerPort = 30911; BrokerPort = 30911; BrokerVipPort = 30909 })) 'Broker collision'
    Assert-True (-not (Test-QueuePortConfiguration @{ NameServerPort = 30909; BrokerPort = 30911; BrokerVipPort = 30909 })) 'VIP collision'
    Assert-True (-not (Test-QueuePortConfiguration @([pscustomobject]$valid))) 'Array is not one configuration'
    Assert-True (-not (Test-QueuePortConfiguration @{ NameServerPort = @(29876); BrokerPort = 30911; BrokerVipPort = 30909 })) 'An array field must not be unwrapped to a port'
    Assert-True (-not (Test-QueuePortConfiguration @{ NameServerPort = 29876; BrokerPort = @{ Value = 30911 }; BrokerVipPort = 30909 })) 'An object field is not a port'
}
Test-Case 'persisted JSON reader normalizes valid ports and never rewrites its input' {
    $directory = Join-Path ([IO.Path]::GetTempPath()) ('queue-port-tests-' + [Guid]::NewGuid().ToString('N'))
    $path = Join-Path $directory 'ports.json'
    [void][IO.Directory]::CreateDirectory($directory)
    try {
        Assert-True ($null -eq (Read-QueuePortConfiguration $path)) 'Missing file should return null'
        $json = '{"NameServerPort":29876,"BrokerPort":30911,"BrokerVipPort":30909}'
        [IO.File]::WriteAllText($path, $json)
        Assert-Ports (Read-QueuePortConfiguration $path) 29876 30911
        Assert-Equal ([IO.File]::ReadAllText($path)) $json 'Reader must not modify file'
        [IO.File]::WriteAllText($path, '{"NameServerPort":"29876","BrokerPort":30911,"BrokerVipPort":30909}')
        Assert-Throws { Read-QueuePortConfiguration $path } 'configuration is invalid'
        [IO.File]::WriteAllText($path, '{"NameServerPort":[29876],"BrokerPort":30911,"BrokerVipPort":30909}')
        Assert-Throws { Read-QueuePortConfiguration $path } 'configuration is invalid'
        [IO.File]::WriteAllText($path, '[' + $json + ']')
        Assert-Throws { Read-QueuePortConfiguration $path } 'could not be read as JSON'
        [IO.File]::WriteAllText($path, '{broken')
        Assert-Throws { Read-QueuePortConfiguration $path } 'could not be read as JSON'
    }
    finally {
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
        if (Test-Path -LiteralPath $directory) { Remove-Item -LiteralPath $directory }
    }
}
Test-Case 'real IPv4 bind probe detects occupied ports and releases successful probes' {
    $listener = $null
    $verification = $null
    try {
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
        $listener.Server.ExclusiveAddressUse = $true
        $listener.Start()
        $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
        Assert-True (-not (Test-QueueTcpPort $port)) 'Occupied loopback port must not pass'
        $listener.Stop()
        Assert-True (Test-QueueTcpPort $port) 'Released loopback port should pass'
        $verification = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $port)
        $verification.Server.ExclusiveAddressUse = $true
        $verification.Start()
    }
    finally {
        if ($null -ne $listener) { $listener.Stop() }
        if ($null -ne $verification) { $verification.Stop() }
    }
}

Write-Output ("All $script:testCount queue port tests passed (PowerShell $($PSVersionTable.PSVersion)).")
