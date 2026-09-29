param([switch]$SkipWebBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
$installDir = Join-Path $env:USERPROFILE '.smart-inbox\desktop-app'
$cache = Join-Path $projectRoot '.desktop-cache'
$buildDir = Join-Path $projectRoot 'desktop\build'
$version = '1.0.3912.50'
$sdk = Join-Path $cache "webview2-$version"
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $compiler)) { throw 'The Windows .NET Framework compiler is not installed.' }
$running = @(Get-CimInstance Win32_Process -Filter "Name='SmartInbox.exe'" | Where-Object { $_.ExecutablePath -eq (Join-Path $installDir 'SmartInbox.exe') })
if ($running.Count) { throw 'Exit Smart Inbox from its tray menu before updating the desktop app.' }
New-Item -ItemType Directory -Force -Path $cache,$buildDir,$installDir | Out-Null
if (-not (Test-Path -LiteralPath "$sdk\lib\net462\Microsoft.Web.WebView2.Core.dll")) {
    $zip = Join-Path $cache "webview2-$version.zip"
    Invoke-WebRequest -UseBasicParsing "https://api.nuget.org/v3-flatcontainer/microsoft.web.webview2/$version/microsoft.web.webview2.$version.nupkg" -OutFile $zip
    Expand-Archive -LiteralPath $zip -DestinationPath $sdk -Force
}
if (-not $SkipWebBuild) {
    Push-Location (Join-Path $projectRoot 'smart-web')
    try { & npm.cmd run build -- --configLoader runner; if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' } }
    finally { Pop-Location }
}
if (-not (Test-Path -LiteralPath "$projectRoot\smart-web\dist\index.html")) { throw 'Frontend build is missing.' }

# Draw an original app icon using Windows drawing primitives (no external artwork).
Add-Type -AssemblyName System.Drawing
$bitmap = New-Object System.Drawing.Bitmap 256,256
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.SmoothingMode = 'AntiAlias'
$path = New-Object System.Drawing.Drawing2D.GraphicsPath
$path.AddArc(8,8,88,88,180,90); $path.AddArc(160,8,88,88,270,90)
$path.AddArc(160,160,88,88,0,90); $path.AddArc(8,160,88,88,90,90); $path.CloseFigure()
$brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush ([System.Drawing.Point]::new(0,0)),([System.Drawing.Point]::new(256,256)),([System.Drawing.Color]::FromArgb(82,160,255)),([System.Drawing.Color]::FromArgb(22,78,210))
$graphics.FillPath($brush,$path)
$pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::White),11
$pen.LineJoin = 'Round'
$graphics.DrawRectangle($pen,55,79,146,105)
$graphics.DrawLines($pen,[System.Drawing.Point[]]@([System.Drawing.Point]::new(58,82),[System.Drawing.Point]::new(128,136),[System.Drawing.Point]::new(198,82)))
$graphics.FillEllipse([System.Drawing.Brushes]::White,178,40,38,38)
$png = New-Object System.IO.MemoryStream
$bitmap.Save($png,[System.Drawing.Imaging.ImageFormat]::Png)
$iconFile = Join-Path $buildDir 'smart-inbox.ico'
$writer = New-Object System.IO.BinaryWriter ([System.IO.File]::Create($iconFile))
try {
    $writer.Write([uint16]0); $writer.Write([uint16]1); $writer.Write([uint16]1)
    $writer.Write([byte]0); $writer.Write([byte]0); $writer.Write([byte]0); $writer.Write([byte]0)
    $writer.Write([uint16]1); $writer.Write([uint16]32); $writer.Write([uint32]$png.Length); $writer.Write([uint32]22)
    $writer.Write($png.ToArray())
} finally { $writer.Dispose(); $png.Dispose(); $pen.Dispose(); $brush.Dispose(); $path.Dispose(); $graphics.Dispose(); $bitmap.Dispose() }

$framework = Split-Path $compiler
$references = @('System.dll','System.Core.dll','System.Drawing.dll','System.Windows.Forms.dll','System.Net.Http.dll','System.Web.Extensions.dll') | ForEach-Object { '/reference:' + (Join-Path $framework $_) }
$references += @("/reference:$sdk\lib\net462\Microsoft.Web.WebView2.Core.dll", "/reference:$sdk\lib\net462\Microsoft.Web.WebView2.WinForms.dll")
& $compiler /nologo /target:winexe /platform:x64 /optimize+ /codepage:65001 "/out:$buildDir\SmartInbox.exe" "/win32icon:$iconFile" "/win32manifest:$projectRoot\desktop\app.manifest" @references "$projectRoot\desktop\SmartInbox.cs"
if ($LASTEXITCODE -ne 0) { throw 'Desktop compilation failed.' }
Copy-Item -LiteralPath "$buildDir\SmartInbox.exe",$iconFile -Destination $installDir -Force
Copy-Item -LiteralPath "$projectRoot\desktop\bridge.js" -Destination $installDir -Force
Copy-Item -LiteralPath "$sdk\lib\net462\Microsoft.Web.WebView2.Core.dll","$sdk\lib\net462\Microsoft.Web.WebView2.WinForms.dll","$sdk\runtimes\win-x64\native\WebView2Loader.dll" -Destination $installDir -Force
[IO.File]::WriteAllText((Join-Path $installDir 'project-path.txt'),$projectRoot,[Text.Encoding]::UTF8)
[IO.File]::WriteAllText((Join-Path $installDir 'SmartInbox.exe.config'), '<?xml version="1.0"?><configuration><startup><supportedRuntime version="v4.0" sku=".NETFramework,Version=v4.8"/></startup></configuration>')
$license = Get-ChildItem -LiteralPath $sdk -Filter '*LICENSE*' | Select-Object -First 1
if ($license) { Copy-Item -LiteralPath $license.FullName -Destination (Join-Path $installDir 'WebView2-LICENSE.txt') -Force }
$shell = New-Object -ComObject WScript.Shell
foreach ($folder in @([Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('Programs'))) {
    $shortcut = $shell.CreateShortcut((Join-Path $folder 'Smart Inbox.lnk'))
    $shortcut.TargetPath = Join-Path $installDir 'SmartInbox.exe'
    $shortcut.WorkingDirectory = $projectRoot
    $shortcut.IconLocation = (Join-Path $installDir 'smart-inbox.ico') + ',0'
    $shortcut.Description = 'Smart Inbox - mail, tasks, trends and watchlist'
    $shortcut.Save()
}
Write-Output "Installed: $installDir\SmartInbox.exe"
Write-Output 'Desktop and Start Menu shortcuts created. Existing project data and credentials were not moved.'
