# Fake COM, registry and Outlook process boundaries. Never touch real mail.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'outlook-session.ps1')
$script:RealSilentProfile = ${function:Test-OutlookSilentProfile}
$script:CaseCount = 0
$script:AssertionCount = 0
function Assert-Equal($Actual, $Expected, [string]$Message) {
    $script:AssertionCount++
    if ($Actual -ne $Expected) { throw "FAIL: $Message; expected <$Expected>, got <$Actual>" }
}
function Assert-True([bool]$Value, [string]$Message) {
    $script:AssertionCount++
    if (-not $Value) { throw "FAIL: $Message" }
}
function Reset-SessionScenario {
    $script:Application = [pscustomobject]@{ Fixture = 'application' }
    $script:Namespace = [pscustomobject]@{ Fixture = 'namespace' }
    $script:Inbox = [pscustomobject]@{ Fixture = 'default-folder' }
    $script:ExistingApplication = $null
    $script:Processes = @()
    $script:DefaultProfile = 'fixture profile'
    $script:SilentProfile = $true
    $script:ComCalls = 0
    $script:NamespaceCalls = 0
    $script:FolderCalls = 0
    $script:StartCalls = 0
    $script:ForbiddenCalls = 0
    $script:ComFails = $false
    $script:NamespaceFails = $false
    $script:FolderFails = $false
    $script:NamespacesRequested = @()
    $script:FoldersRequested = @()
    $script:Events = @()
    $script:Registry = @{}
    $script:RegistryReads = @()
    foreach ($target in @($script:Application, $script:Namespace, $script:Inbox)) {
        foreach ($method in @('Display', 'Activate', 'Logon', 'Quit', 'Close', 'GetExplorer', 'CreateItem')) {
            $target | Add-Member ScriptMethod $method {
                $script:ForbiddenCalls++
                throw 'Outlook UI, interactive login and session changes are forbidden'
            }
        }
    }
    $script:Application | Add-Member ScriptMethod GetNamespace {
        param([string]$Name)
        $script:NamespaceCalls++
        $script:NamespacesRequested += $Name
        $script:Events += 'namespace'
        if ($script:NamespaceFails) { throw 'Fixture namespace failure' }
        return $script:Namespace
    }
    $script:Namespace | Add-Member ScriptMethod GetDefaultFolder {
        param([int]$FolderId)
        $script:FolderCalls++
        $script:FoldersRequested += $FolderId
        $script:Events += 'folder'
        if ($script:FolderFails) { throw 'Fixture MAPI initialization failure' }
        return $script:Inbox
    }
}
function Test-Case([string]$Name, [scriptblock]$Action) {
    Reset-SessionScenario
    & $Action
    Assert-Equal $script:StartCalls 0 'Never launch the Outlook executable'
    Assert-Equal $script:ForbiddenCalls 0 'Never show, activate, log on, quit or close Outlook UI'
    $script:CaseCount++
    Write-Output "PASS $Name"
}
function New-FakeOutlookProcess([string]$Title = '') {
    [pscustomobject]@{ Id = 24680; MainWindowTitle = $Title; SessionId = [Diagnostics.Process]::GetCurrentProcess().SessionId }
}
function Get-OutlookRunningApplication { return $script:ExistingApplication }
function Get-OutlookProcesses { return $script:Processes }
function Get-OutlookDefaultProfile { $script:Events += 'profile'; return $script:DefaultProfile }
function Test-OutlookSilentProfile { $script:Events += 'silent'; return $script:SilentProfile }
function Get-ItemProperty {
    param([string]$LiteralPath, [string]$Name, [string]$ErrorAction)
    Assert-Equal $Name 'PickLogonProfile' 'Only profile prompt metadata may be queried'
    $script:RegistryReads += $LiteralPath
    if ($script:Registry.ContainsKey($LiteralPath)) {
        return [pscustomobject]@{ PickLogonProfile = $script:Registry[$LiteralPath] }
    }
    return $null
}
function Start-Process { $script:StartCalls++; throw 'Starting the Outlook GUI is forbidden' }
function New-Object {
    param([string]$ComObject, [Parameter(Position=0)][string]$TypeName, [Parameter(Position=1)][object[]]$ArgumentList)
    if ($ComObject) {
        $script:ComCalls++
        $script:Events += 'com'
        Assert-Equal $ComObject 'Outlook.Application' 'Only Outlook COM activation is allowed'
        if ($script:ComFails) { throw 'Fixture COM failure' }
        return $script:Application
    }
    Microsoft.PowerShell.Utility\New-Object @PSBoundParameters
}
function Assert-Connected($Result) {
    Assert-Equal $Result.state 'connected' 'Successful connection state'
    Assert-True ([object]::ReferenceEquals($Result.application, $script:Application)) 'Return the same automation instance'
}
function Assert-Blocked($Result, [string]$State) {
    Assert-Equal $Result.state $State 'Accurate non-connected state'
    Assert-True ($null -eq $Result.application) 'No uninitialized application escapes'
    Assert-Equal $script:ComCalls 0 'Do not activate COM when silent startup is unsafe'
    Assert-Equal $script:NamespaceCalls 0 'Do not initialize MAPI when blocked'
    Assert-Equal $script:FolderCalls 0 'Do not access folders when blocked'
}
Test-Case 'ready session is reused without activation or initialization' {
    $script:Processes = @(New-FakeOutlookProcess 'Inbox - Fixture Outlook')
    $script:ExistingApplication = $script:Application
    Assert-Connected (Connect-OutlookSession)
    Assert-Equal $script:ComCalls 0 'No COM activation for a ready instance'
    Assert-Equal $script:NamespaceCalls 0 'No repeated MAPI initialization'
    Assert-Equal $script:FolderCalls 0 'No folder access for a ready instance'
}
Test-Case 'ready session remains usable if default profile configuration changed' {
    $script:ExistingApplication = $script:Application
    $script:DefaultProfile = ''
    $script:SilentProfile = $false
    Assert-Connected (Connect-OutlookSession)
    Assert-Equal $script:ComCalls 0 'No cold-start activation'
    Assert-Equal $script:Events.Count 0 'No profile or prompt-setting dependency for ready instance'
}
Test-Case 'existing profile chooser blocks automation' {
    $script:Processes = @(New-FakeOutlookProcess 'Choose Profile')
    Assert-Blocked (Connect-OutlookSession) 'profile_required'
}
Test-Case 'one chooser among windows blocks even a registered instance' {
    $script:Processes = @((New-FakeOutlookProcess 'Inbox - Fixture'), (New-FakeOutlookProcess 'Choose Profile'))
    $script:ExistingApplication = $script:Application
    Assert-Blocked (Connect-OutlookSession) 'profile_required'
}
Test-Case 'Chinese profile chooser is detected without activating COM' {
    $title = -join @([char]0x9009, [char]0x62E9, [char]0x914D, [char]0x7F6E, [char]0x6587, [char]0x4EF6)
    $script:Processes = @(New-FakeOutlookProcess $title)
    Assert-Blocked (Connect-OutlookSession) 'profile_required'
}
Test-Case 'traditional Chinese profile chooser is detected without activating COM' {
    $title = -join @([char]0x9078, [char]0x64C7, [char]0x8A2D, [char]0x5B9A, [char]0x6A94)
    $script:Processes = @(New-FakeOutlookProcess $title)
    Assert-Blocked (Connect-OutlookSession) 'profile_required'
}
Test-Case 'missing default profile prevents cold activation' {
    $script:DefaultProfile = ''
    Assert-Blocked (Connect-OutlookSession) 'no_profile'
}
Test-Case 'missing default profile prevents existing non-ROT activation' {
    $script:Processes = @(New-FakeOutlookProcess)
    $script:DefaultProfile = ''
    Assert-Blocked (Connect-OutlookSession) 'no_profile'
}
Test-Case 'enabled profile prompt prevents cold activation' {
    $script:SilentProfile = $false
    Assert-Blocked (Connect-OutlookSession) 'profile_required'
}
Test-Case 'enabled profile prompt prevents existing non-ROT activation' {
    $script:Processes = @(New-FakeOutlookProcess 'Inbox - Fixture Outlook')
    $script:SilentProfile = $false
    Assert-Blocked (Connect-OutlookSession) 'profile_required'
}
Test-Case 'cold automation initializes default MAPI folder without UI' {
    Assert-Connected (Connect-OutlookSession)
    Assert-Equal $script:ComCalls 1 'Exactly one COM activation'
    Assert-Equal $script:NamespaceCalls 1 'Exactly one MAPI session lookup'
    Assert-Equal $script:FolderCalls 1 'Exactly one default-folder initialization'
    Assert-Equal $script:NamespacesRequested[0] 'MAPI' 'Use MAPI namespace'
    Assert-Equal $script:FoldersRequested[0] 6 'Initialize default Inbox metadata only'
    Assert-Equal ($script:Events -join ',') 'profile,silent,com,namespace,folder' 'Validate noninteractive configuration before activation'
}
Test-Case 'hidden non-ROT instance uses automation without an explorer' {
    $script:Processes = @(New-FakeOutlookProcess)
    Assert-Connected (Connect-OutlookSession)
    Assert-Equal $script:ComCalls 1 'One COM fallback for unregistered instance'
    Assert-Equal $script:FolderCalls 1 'Initialize MAPI before connected state'
}
Test-Case 'visible non-ROT instance is not hidden or closed' {
    $script:Processes = @(New-FakeOutlookProcess 'Inbox - Fixture Outlook')
    Assert-Connected (Connect-OutlookSession)
    Assert-Equal $script:Processes[0].MainWindowTitle 'Inbox - Fixture Outlook' 'User-owned window unchanged'
    Assert-Equal $script:ComCalls 1 'One activation for visible instance'
}
Test-Case 'COM failure reports unavailable without GUI fallback or retry' {
    $script:ComFails = $true
    $result = Connect-OutlookSession
    Assert-Equal $result.state 'unavailable' 'COM failure surfaced'
    Assert-True ($null -eq $result.application) 'No uninitialized application escapes'
    Assert-Equal $script:ComCalls 1 'No repeated activation'
    Assert-Equal $script:NamespaceCalls 0 'No MAPI calls after COM failure'
}
Test-Case 'failed existing COM does not relaunch or terminate Outlook' {
    $script:Processes = @(New-FakeOutlookProcess 'Inbox - Fixture Outlook')
    $script:ComFails = $true
    Assert-Equal (Connect-OutlookSession).state 'unavailable' 'Existing COM failure surfaced'
    Assert-Equal $script:ComCalls 1 'Only one attempt'
    Assert-Equal $script:Processes.Count 1 'User process preserved'
}
Test-Case 'namespace failure does not attempt interactive Logon' {
    $script:NamespaceFails = $true
    $result = Connect-OutlookSession
    Assert-Equal $result.state 'unavailable' 'Namespace failure surfaced'
    Assert-True ($null -eq $result.application) 'No uninitialized application escapes'
    Assert-Equal $script:ComCalls 1 'No second activation'
    Assert-Equal $script:NamespaceCalls 1 'No namespace retry'
    Assert-Equal $script:FolderCalls 0 'No folder request after namespace failure'
}
Test-Case 'MAPI initialization failure has no interactive recovery' {
    $script:FolderFails = $true
    $result = Connect-OutlookSession
    Assert-Equal $result.state 'unavailable' 'Folder failure surfaced'
    Assert-True ($null -eq $result.application) 'No failed MAPI application escapes'
    Assert-Equal $script:ComCalls 1 'No second activation'
    Assert-Equal $script:FolderCalls 1 'No repeated initialization'
}
Test-Case 'later polling reuses a ready session without a GUI launch' {
    Assert-Connected (Connect-OutlookSession)
    $script:ExistingApplication = $script:Application
    $script:Processes = @(New-FakeOutlookProcess)
    1..3 | ForEach-Object { Assert-Connected (Connect-OutlookSession) }
    Assert-Equal $script:ComCalls 1 'Later polls reuse initial automation'
    Assert-Equal $script:FolderCalls 1 'Later polls do not initialize again'
}
Test-Case 'poll after Outlook exits recreates automation without its main window' {
    Assert-Connected (Connect-OutlookSession)
    $script:ExistingApplication = $null
    $script:Processes = @()
    Assert-Connected (Connect-OutlookSession)
    Assert-Equal $script:ComCalls 2 'Each cold poll creates automation only'
    Assert-Equal $script:FolderCalls 2 'Each cold instance initializes MAPI'
}
$script:PolicyKey = 'HKCU:\Software\Policies\Microsoft\Exchange\Client\Options'
$script:PreferenceKey = 'HKCU:\Software\Microsoft\Exchange\Client\Options'
Test-Case 'missing registry settings fail closed' {
    Assert-Equal (& $script:RealSilentProfile) $false 'Unknown prompt configuration is unsafe'
    Assert-Equal ($script:RegistryReads -join '|') ($script:PolicyKey + '|' + $script:PreferenceKey) 'Check policy before preference'
}
Test-Case 'ordinary registry numeric zero allows silent startup' {
    $script:Registry[$script:PreferenceKey] = 0
    Assert-Equal (& $script:RealSilentProfile) $true 'Numeric zero disables profile prompt'
}
Test-Case 'ordinary registry string zero allows silent startup' {
    $script:Registry[$script:PreferenceKey] = '0'
    Assert-Equal (& $script:RealSilentProfile) $true 'String zero disables profile prompt'
}
Test-Case 'ordinary registry one refuses silent startup' {
    $script:Registry[$script:PreferenceKey] = 1
    Assert-Equal (& $script:RealSilentProfile) $false 'Enabled prompt is rejected'
}
Test-Case 'policy enabled prompt overrides disabled user preference' {
    $script:Registry[$script:PolicyKey] = 1
    $script:Registry[$script:PreferenceKey] = 0
    Assert-Equal (& $script:RealSilentProfile) $false 'Policy wins'
    Assert-Equal $script:RegistryReads.Count 1 'Do not fall through after explicit policy'
}
Test-Case 'policy disabled prompt overrides enabled user preference' {
    $script:Registry[$script:PolicyKey] = '0'
    $script:Registry[$script:PreferenceKey] = 1
    Assert-Equal (& $script:RealSilentProfile) $true 'Explicit policy zero wins'
    Assert-Equal $script:RegistryReads.Count 1 'Read policy only when configured'
}
Test-Case 'invalid policy refuses startup even if user preference is zero' {
    $script:Registry[$script:PolicyKey] = 'unknown'
    $script:Registry[$script:PreferenceKey] = 0
    Assert-Equal (& $script:RealSilentProfile) $false 'Invalid policy fails closed'
}
# Also catch banned operations in any unexercised branch, ignoring comments.
$tokens = $null
$parseErrors = $null
$sessionAst = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'outlook-session.ps1'), [ref]$tokens, [ref]$parseErrors)
Assert-Equal @($parseErrors).Count 0 'Session policy parses'
$forbiddenCommands = @($sessionAst.FindAll({ param($node)
    $node -is [Management.Automation.Language.CommandAst] -and $node.GetCommandName() -match '(^|\\)(Start-Process|Stop-Process)$'
}, $true))
Assert-Equal $forbiddenCommands.Count 0 'No executable launch or termination in session policy'
$forbiddenMembers = @($sessionAst.FindAll({ param($node)
    $node -is [Management.Automation.Language.InvokeMemberExpressionAst] -and
    $node.Member.Value -in @('Display', 'Activate', 'Logon', 'Quit', 'Close', 'GetExplorer', 'CreateItem')
}, $true))
Assert-Equal $forbiddenMembers.Count 0 'No interactive or destructive Outlook member calls'
Write-Output "All $script:CaseCount Outlook session cases and $script:AssertionCount assertions passed (PowerShell $($PSVersionTable.PSVersion))."