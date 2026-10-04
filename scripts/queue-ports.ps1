# Queue port selection helpers. Compatible with Windows PowerShell 5.1.
# No network configuration or persisted files are changed by these functions.

function ConvertTo-QueuePortInteger {
    param([AllowNull()][object]$Value)
    if ($null -eq $Value) { return $null }
    $numericTypes = @('Byte', 'SByte', 'Int16', 'UInt16', 'Int32', 'UInt32', 'Int64', 'UInt64', 'Single', 'Double', 'Decimal')
    if ([Type]::GetTypeCode($Value.GetType()).ToString() -notin $numericTypes) { return $null }
    $number = [double]$Value
    if ([double]::IsNaN($number) -or [double]::IsInfinity($number) -or $number -lt 1 -or $number -gt 65535 -or [Math]::Floor($number) -ne $number) { return $null }
    return [int]$number
}

function Get-QueuePortField {
    param([AllowNull()][object]$Value, [string]$Name)
    if ($null -eq $Value) { return $null }
    if ($Value -is [System.Collections.IDictionary]) {
        if ($Value.Contains($Name)) { return ,$Value[$Name] }
        return $null
    }
    $property = $Value.PSObject.Properties[$Name]
    if ($null -ne $property) { return ,$property.Value }
    return $null
}

function Test-QueuePortConfiguration {
    param([AllowNull()][object]$Ports)
    if ($null -eq $Ports -or $Ports -is [Array]) { return $false }
    $nameServer = ConvertTo-QueuePortInteger (Get-QueuePortField $Ports 'NameServerPort')
    $broker = ConvertTo-QueuePortInteger (Get-QueuePortField $Ports 'BrokerPort')
    $vip = ConvertTo-QueuePortInteger (Get-QueuePortField $Ports 'BrokerVipPort')
    if ($null -eq $nameServer -or $null -eq $broker -or $null -eq $vip) { return $false }
    return ($vip -eq ($broker - 2) -and $nameServer -ne $broker -and $nameServer -ne $vip)
}

function Read-QueuePortConfiguration {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        if (Test-Path -LiteralPath $Path) { throw 'Persisted queue port configuration must be a file.' }
        return $null
    }
    try {
        $json = Get-Content -LiteralPath $Path -Raw -ErrorAction Stop
        if ($json -notmatch '^\s*\{') { throw 'Expected one JSON object.' }
        $ports = $json | ConvertFrom-Json -ErrorAction Stop
    }
    catch { throw 'Persisted queue port configuration could not be read as JSON.' }
    if (-not (Test-QueuePortConfiguration $ports)) {
        throw 'Persisted queue port configuration is invalid: expected distinct integer ports in 1..65535 and BrokerVipPort = BrokerPort - 2.'
    }
    return [pscustomobject]@{
        NameServerPort = [int]$ports.NameServerPort
        BrokerPort = [int]$ports.BrokerPort
        BrokerVipPort = [int]$ports.BrokerVipPort
    }
}

function ConvertFrom-WindowsReservedTcpRanges {
    param([AllowEmptyCollection()][string[]]$Lines = @())
    foreach ($line in $Lines) {
        # Numeric rows are independent of the operating system's display language.
        # The optional '*' is netsh's marker for an administered exclusion.
        if ($line -match '^\s*(\d+)\s+(\d+)(?:\s+\*)?\s*$') {
            $start = 0
            $end = 0
            if (-not [int]::TryParse($Matches[1], [ref]$start) -or -not [int]::TryParse($Matches[2], [ref]$end) -or $start -lt 1 -or $end -gt 65535 -or $start -gt $end) {
                throw 'Windows reported an invalid reserved TCP port range.'
            }
            [pscustomobject]@{ StartPort = $start; EndPort = $end }
        }
        elseif ($line -match '^\s*[+-]?\d') { throw 'Windows reported a malformed reserved TCP port row.' }
    }
}

function Get-WindowsReservedTcpRanges {
    param([scriptblock]$NetshRunner = {
        param([string]$AddressFamily)
        $lines = @(& netsh.exe interface $AddressFamily show excludedportrange protocol=tcp 2>&1)
        if ($LASTEXITCODE -ne 0) { throw "Could not read Windows $AddressFamily reserved TCP ports." }
        return $lines
    })
    $seen = @{}
    foreach ($family in @('ipv4', 'ipv6')) {
        $lines = @(& $NetshRunner $family)
        if ($lines.Count -eq 0) { throw "Windows $family reserved TCP port query returned no output." }
        $parsed = @(ConvertFrom-WindowsReservedTcpRanges -Lines $lines)
        $hasTableDivider = @($lines | Where-Object { $_ -match '^\s*-{2,}\s+-{2,}\s*$' }).Count -gt 0
        if ($parsed.Count -eq 0 -and -not $hasTableDivider) { throw "Windows $family reserved TCP port output was not a recognizable table." }
        foreach ($range in $parsed) {
            $key = '{0}:{1}' -f $range.StartPort, $range.EndPort
            if (-not $seen.ContainsKey($key)) {
                $seen[$key] = $true
                $range
            }
        }
    }
}

function Test-QueueTcpPort {
    param([Parameter(Mandatory = $true)][ValidateRange(1, 65535)][int]$Port)
    $listener = $null
    try {
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
        $listener.Server.ExclusiveAddressUse = $true
        $listener.Start()
        return $true
    }
    catch [System.Net.Sockets.SocketException] { return $false }
    finally {
        if ($null -ne $listener) { $listener.Stop() }
    }
}

function Select-QueuePorts {
    [CmdletBinding()]
    param(
        [ValidateRange(1, 65535)][int]$PreferredNameServerPort = 29876,
        [ValidateRange(3, 65535)][int]$PreferredBrokerPort = 30911,
        [AllowEmptyCollection()][object[]]$ExcludedRanges = @(),
        [scriptblock]$IsPortAvailable = { param([int]$Port) Test-QueueTcpPort -Port $Port }
    )

    $ranges = @()
    foreach ($range in $ExcludedRanges) {
        $start = ConvertTo-QueuePortInteger (Get-QueuePortField $range 'StartPort')
        $end = ConvertTo-QueuePortInteger (Get-QueuePortField $range 'EndPort')
        if ($null -eq $start -or $null -eq $end -or $start -gt $end) { throw 'Invalid excluded TCP range: expected StartPort <= EndPort within 1..65535.' }
        $ranges += [pscustomobject]@{ StartPort = $start; EndPort = $end }
    }

    $availability = @{}
    function Test-QueueCandidateAvailable {
        param([int]$Port)
        if ($availability.ContainsKey($Port)) { return [bool]$availability[$Port] }
        foreach ($range in $ranges) {
            if ($Port -ge $range.StartPort -and $Port -le $range.EndPort) {
                $availability[$Port] = $false
                return $false
            }
        }
        $result = @(& $IsPortAvailable $Port)
        if ($result.Count -ne 1 -or $result[0] -isnot [bool]) { throw 'Queue TCP availability probe must return exactly one Boolean value.' }
        $availability[$Port] = $result[0]
        return $result[0]
    }

    $nameCandidates = @($PreferredNameServerPort)
    foreach ($port in 29876..29925) {
        if ($port -ne $PreferredNameServerPort) { $nameCandidates += $port }
    }
    $brokerCandidates = @($PreferredBrokerPort)
    for ($port = 30911; $port -le 39911; $port += 100) {
        if ($port -ne $PreferredBrokerPort) { $brokerCandidates += $port }
    }

    $foundNameServer = $false
    foreach ($nameServer in $nameCandidates) {
        if (-not (Test-QueueCandidateAvailable $nameServer)) { continue }
        $foundNameServer = $true
        foreach ($broker in $brokerCandidates) {
            $vip = $broker - 2
            if ($nameServer -eq $broker -or $nameServer -eq $vip) { continue }
            if (-not (Test-QueueCandidateAvailable $broker)) { continue }
            if (-not (Test-QueueCandidateAvailable $vip)) { continue }
            return [pscustomobject]@{ NameServerPort = $nameServer; BrokerPort = $broker; BrokerVipPort = $vip }
        }
    }
    if (-not $foundNameServer) { throw 'No available queue nameserver TCP port: the preferred port and all fallback candidates 29876..29925 are reserved or occupied.' }
    throw 'No available queue broker/VIP TCP pair: the preferred pair and all fallback broker candidates 30911..39911 (step 100) conflict with reservations, occupied ports, or the nameserver.'
}

# Probes do not reserve ports for Docker. The caller must serialize startup,
# handle bind failures, and persist the complete trio only after successful startup.
