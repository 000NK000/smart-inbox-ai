param(
    [Parameter(Mandatory = $true)][string]$Email,
    [Parameter(Mandatory = $true)][string]$SinceIso,
    [int]$Limit = 0, # Zero means every message within the requested time window.
    [string]$StatusOnly = 'False',
    [string]$MetadataOnly = 'True',
    [string]$RequestedIdsFile = ''
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

function Write-Result([string]$State, [array]$Messages = @()) {
    [pscustomobject]@{ state = $State; messages = @($Messages) } | ConvertTo-Json -Depth 5 -Compress
}

try {
    # Do not rely on a single Office-version registry path here. A working
    # Outlook MAPI profile can be registered elsewhere, and the COM session is
    # the authoritative availability check.
    $application = New-Object -ComObject Outlook.Application
    $session = $application.GetNamespace('MAPI')
    $account = @($session.Accounts | Where-Object { $_.SmtpAddress -ieq $Email } | Select-Object -First 1)
    if (-not $account) {
        Write-Result 'account_not_found'
        exit 0
    }
    if ($session.Offline) { Write-Result 'offline'; exit 0 }
    if ($StatusOnly -eq 'True') {
        Write-Result 'connected'
        exit 0
    }

    $cutoff = [DateTime]::Parse($SinceIso).ToLocalTime()
    $requested = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    if ($MetadataOnly -ne 'True') {
        if (-not $RequestedIdsFile -or -not (Test-Path -LiteralPath $RequestedIdsFile -PathType Leaf)) {
            Write-Result 'request_missing'; exit 0
        }
        $requestedIds = Get-Content -Raw -LiteralPath $RequestedIdsFile -Encoding UTF8 | ConvertFrom-Json
        foreach ($id in $requestedIds) {
            if ($id -isnot [string] -or -not $id -or $id.Length -gt 4096) { throw 'Invalid requested identity' }
            $null = $requested.Add($id)
        }
        if ($requested.Count -eq 0) { Write-Result 'connected'; exit 0 }
    }
    $inbox = $account[0].DeliveryStore.GetDefaultFolder(6)
    $items = $inbox.Items
    $items.Sort('[ReceivedTime]', $true)
    $messages = New-Object System.Collections.Generic.List[object]
    foreach ($item in $items) {
        if ($Limit -gt 0 -and $messages.Count -ge $Limit) { break }
        if ($null -eq $item -or $item.MessageClass -notlike 'IPM.Note*') { continue }
        if ($item.ReceivedTime -lt $cutoff) { break }

        $entryId = [string]$item.EntryID
        if ($MetadataOnly -ne 'True' -and -not $requested.Contains($entryId)) { continue }
        $internetMessageId = ''
        try { $internetMessageId = [string]$item.PropertyAccessor.GetProperty('http://schemas.microsoft.com/mapi/proptag/0x1035001E') } catch { }
        if ($MetadataOnly -eq 'True') {
            # No Body/HTMLBody/preview property is accessed while discovering identities.
            $messages.Add([pscustomobject]@{
                entryId = $entryId
                internetMessageId = $internetMessageId
                receivedTime = $item.ReceivedTime.ToUniversalTime().ToString('o')
            })
            continue
        }

        $senderAddress = [string]$item.SenderEmailAddress
        if ($item.SenderEmailType -eq 'EX') {
            try {
                $exchangeUser = $item.Sender.GetExchangeUser()
                if ($exchangeUser -and $exchangeUser.PrimarySmtpAddress) { $senderAddress = $exchangeUser.PrimarySmtpAddress }
            } catch { }
        }
        $body = [string]$item.Body
        $htmlBody = [string]$item.HTMLBody
        $messages.Add([pscustomobject]@{
            entryId = $entryId
            internetMessageId = $internetMessageId
            subject = [string]$item.Subject
            sender = if ($item.SenderName) { "$($item.SenderName) <$senderAddress>" } else { $senderAddress }
            body = $body
            htmlBody = $htmlBody
            unread = [bool]$item.UnRead
            receivedTime = $item.ReceivedTime.ToUniversalTime().ToString('o')
        })
    }
    Write-Result 'connected' $messages.ToArray()
} catch {
    Write-Result 'unavailable'
}
