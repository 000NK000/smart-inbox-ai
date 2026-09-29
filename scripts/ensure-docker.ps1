function Ensure-Docker {
    $dockerCommand = Get-Command docker.exe -ErrorAction SilentlyContinue
    if (-not $dockerCommand) { throw 'Docker Desktop is not installed or docker.exe is missing from PATH.' }
    function Test-DockerReady {
        $info = New-Object System.Diagnostics.ProcessStartInfo
        $info.FileName = $dockerCommand.Source
        $info.Arguments = 'info --format {{.ServerVersion}}'
        $info.UseShellExecute = $false
        $info.CreateNoWindow = $true
        $info.RedirectStandardOutput = $true
        $info.RedirectStandardError = $true
        $process = [System.Diagnostics.Process]::Start($info)
        try {
            if (-not $process.WaitForExit(5000)) { $process.Kill(); return $false }
            return $process.ExitCode -eq 0
        } finally { $process.Dispose() }
    }
    if (Test-DockerReady) { return }
    Write-Output 'PROGRESS:正在启动 Docker Desktop，首次启动可能需要一两分钟…'
    $desktop = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
    if (-not (Test-Path -LiteralPath $desktop)) { throw 'Docker Desktop executable was not found.' }
    if (-not (Get-Process -Name 'Docker Desktop' -ErrorAction SilentlyContinue)) {
        Start-Process -FilePath $desktop -WindowStyle Hidden | Out-Null
    }
    $deadline = (Get-Date).AddSeconds(180)
    do {
        if (Test-DockerReady) { return }
        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)
    throw 'Docker Desktop did not become ready. Open Docker Desktop to check its startup status, then retry.'
}
