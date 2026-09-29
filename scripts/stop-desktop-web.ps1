$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$script = Join-Path $projectRoot 'scripts\desktop-server.mjs'
# This has no data store. Stop only the dedicated static UI process, never Vite or unrelated Node apps.
$owned = @(Get-CimInstance Win32_Process -Filter "Name='node.exe'" | Where-Object { $_.CommandLine -and $_.CommandLine.Contains($script) })
foreach ($process in $owned) { Stop-Process -Id $process.ProcessId -ErrorAction Stop }
