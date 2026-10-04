# Outlook's COM activation may show a profile picker when no session is ready.
# Reuse existing sessions. Cold starts use the default profile through COM only.
# OUTLOOK.EXE /profile opens a main window despite WindowStyle Hidden.
function Get-OutlookRunningApplication {
    try { return [Runtime.InteropServices.Marshal]::GetActiveObject('Outlook.Application') }
    catch { return $null } # Hidden Office instances are not always registered in the ROT.
}

function Get-OutlookProcesses {
    $sessionId = [Diagnostics.Process]::GetCurrentProcess().SessionId
    @(Get-Process OUTLOOK -ErrorAction SilentlyContinue | Where-Object { $_.SessionId -eq $sessionId })
}

function Get-OutlookDefaultProfile {
    foreach ($root in @('HKCU:\Software\Microsoft\Office\16.0\Outlook',
                        'HKCU:\Software\Microsoft\Office\15.0\Outlook',
                        'HKCU:\Software\Microsoft\Office\14.0\Outlook',
                        'HKCU:\Software\Microsoft\Windows NT\CurrentVersion\Windows Messaging Subsystem')) {
        $profile = [string](Get-ItemProperty -LiteralPath $root -Name DefaultProfile -ErrorAction SilentlyContinue).DefaultProfile
        # Only accept a single existing profile key, never a registry subpath.
        if (-not [string]::IsNullOrWhiteSpace($profile) -and $profile -notmatch '["\r\n\\]' -and
            (Test-Path -LiteralPath (Join-Path $root "Profiles\$profile"))) { return $profile }
    }
    return ''
}

function Test-OutlookSilentProfile {
    # A default alone is insufficient: Outlook may still ask on activation.
    # Do not change registry settings from a reader. Unknown settings fail
    # closed instead of risking an interactive prompt.
    # Verify setup from the collector's Windows context: packaged developer
    # tools can have a private HKCU view that Outlook itself does not use.
    foreach ($root in @('HKCU:\Software\Policies\Microsoft\Exchange\Client\Options',
                        'HKCU:\Software\Microsoft\Exchange\Client\Options')) {
        $value = (Get-ItemProperty -LiteralPath $root -Name PickLogonProfile -ErrorAction SilentlyContinue).PickLogonProfile
        if ($null -ne $value) { return [string]$value -eq '0' }
    }
    return $false
}

function Test-OutlookProfileChooser([array]$Processes) {
    return @($Processes | Where-Object { $_.MainWindowTitle -match 'Choose Profile|选择配置文件|選擇設定檔' }).Count -gt 0
}

function Connect-OutlookSession {
    $mutex = [Threading.Mutex]::new($false, 'Local\SmartInbox.OutlookStartup.' + [Security.Principal.WindowsIdentity]::GetCurrent().User.Value)
    $held = $false
    try {
        try { $held = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $held = $true }
        if (-not $held) { return @{state='starting'; application=$null} }
        $processes = @(Get-OutlookProcesses)
        # An already-open picker needs the user's choice. Never activate COM or
        # launch another Outlook on every background sync while it is waiting.
        if (Test-OutlookProfileChooser $processes) { return @{state='profile_required'; application=$null} }
        $application = Get-OutlookRunningApplication
        if ($application) { return @{state='connected'; application=$application} }

        # Also protect the race where the user closes Outlook between the
        # process lookup and COM activation.
        if (-not (Get-OutlookDefaultProfile)) { return @{state='no_profile'; application=$null} }
        if (-not (Test-OutlookSilentProfile)) { return @{state='profile_required'; application=$null} }

        # Microsoft's InitializeMAPI pattern initializes the configured default
        # profile without Logon (which can show a picker) or an Explorer window.
        # https://learn.microsoft.com/en-us/office/vba/api/outlook.namespace.logon
        # The reader's bridge process already bounds stalled Office calls to 90s.
        # Never fall back to an executable launch, Display, or Activate.
        $application = New-Object -ComObject Outlook.Application
        $session = $application.GetNamespace('MAPI')
        $null = $session.GetDefaultFolder(6)
        return @{state='connected'; application=$application}
    } catch {
        return @{state='unavailable'; application=$null}
    } finally {
        if ($held) { $mutex.ReleaseMutex() }
        $mutex.Dispose()
    }
}
