# Packages velo for Windows so end users do not need Java installed. Run from anywhere.
#
#   installer\windows\package.ps1                 app image (folder)     -> dist\velo\velo.exe
#   installer\windows\package.ps1 -Type exe       setup .exe installer   -> dist\velo-<version>.exe
#   installer\windows\package.ps1 -Type msi       .msi installer         -> dist\velo-<version>.msi
#   installer\windows\package.ps1 -Type launch4j  Launch4j velo.exe + runtime -> dist\launch4j\
#
# Options: -Version 1.2.0  -Vendor "Your Company"  -JdkHome "C:\Program Files\Java\jdk-21"  -NoVideoTools
# The portable zip is made by installer\portable\make-portable.ps1 (it calls this script).
param(
    [ValidateSet('app-image', 'exe', 'msi', 'launch4j')][string]$Type = 'app-image',
    [string]$Version = '0.1.0',
    [string]$Vendor = 'velo',
    [string]$JdkHome,
    # Leave out yt-dlp, ffmpeg and deno (about 260 MB); video sites then need them installed separately.
    [switch]$NoVideoTools
)

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$root = Split-Path (Split-Path $here -Parent) -Parent   # repository root
$source = Join-Path $root 'source'
$dist = Join-Path $root 'dist'
$work = Join-Path $root '.cache\windows'

# Never change this GUID once you ship: Windows uses it to recognise upgrades of the same product.
$UpgradeUuid = 'b009807e-74f1-4f99-beb6-d09d24d6f137'

function Resolve-Jdk {
    if ($JdkHome) { return $JdkHome }
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\jpackage.exe'))) { return $env:JAVA_HOME }
    $ErrorActionPreference = 'Continue'  # java prints its settings on stderr
    $line = & java -XshowSettings:properties -version 2>&1 | Where-Object { "$_" -match '^\s*java\.home\s*=' } | Select-Object -First 1
    return ("$line" -split '=', 2)[1].Trim()
}
$jdk = Resolve-Jdk
$bin = Join-Path $jdk 'bin'
if (-not (Test-Path (Join-Path $bin 'jpackage.exe'))) { throw "jpackage not found under $jdk; pass -JdkHome <JDK 21 folder>" }

# 1. The single self-contained jar.
& (Join-Path $source 'build.ps1') -Version $Version -Vendor $Vendor
if (-not $?) { throw 'build failed' }

# 2. jpackage copies everything in --input into the app, so stage the jar alone.
$stage = Join-Path $work 'input'
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force -Path $stage | Out-Null
Copy-Item (Join-Path $source 'velo.jar') $stage

# 3. The Java modules velo needs, for a small private runtime (jlink).
$modules = (& (Join-Path $bin 'jdeps.exe') --print-module-deps --ignore-missing-deps --multi-release 21 (Join-Path $stage 'velo.jar')).Trim()
# Not visible to jdeps but needed at run time: elliptic-curve TLS (most HTTPS sites),
# extra charsets, and the Windows accessibility bridge for screen readers.
$modules = "$modules,jdk.crypto.ec,jdk.charsets,jdk.accessibility"
Write-Host "Runtime modules: $modules"

# 4. Video tools (yt-dlp, ffmpeg, deno), downloaded and checksum-verified into .cache\windows\tools.
$tools = Join-Path $work 'tools'
if (-not $NoVideoTools) {
    & (Join-Path $here 'fetch-tools.ps1') -Stage $tools
    if (-not $?) { throw 'fetching video tools failed' }
}

$icon = Join-Path $here 'velo.ico'
New-Item -ItemType Directory -Force -Path $dist | Out-Null

if ($Type -eq 'launch4j') {
    # Launch4j only makes the .exe; the runtime is created here with jlink and shipped beside it.
    $out = Join-Path $dist 'launch4j'
    if (Test-Path $out) { Remove-Item -Recurse -Force $out }
    & (Join-Path $bin 'jlink.exe') --add-modules $modules --output (Join-Path $out 'runtime') `
        --strip-debug --no-man-pages --no-header-files
    if ($LASTEXITCODE -ne 0) { throw 'jlink failed' }
    Copy-Item (Join-Path $source 'velo.jar') $out
    Copy-Item -Recurse (Join-Path $source 'extension') $out
    if (-not $NoVideoTools) { Copy-Item -Recurse $tools $out }
    $l4j = Get-Command launch4jc -ErrorAction SilentlyContinue
    if (-not $l4j) {
        Write-Host "Runtime and jar are ready in $out."
        Write-Host "Install Launch4j, then run:  launch4jc `"$here\launch4j.xml`""
        return
    }
    & $l4j.Source (Join-Path $here 'launch4j.xml')
    if ($LASTEXITCODE -ne 0) { throw 'launch4j failed' }
    Write-Host "Built $out\velo.exe"
    return
}

# 5. jpackage.
$common = @(
    '--name', 'velo',
    '--app-version', $Version,
    '--vendor', $Vendor,
    '--description', 'Fast, resumable download manager',
    '--copyright', "Copyright (c) $((Get-Date).Year) $Vendor",
    '--icon', $icon,
    '--input', $stage,
    '--main-jar', 'velo.jar',
    '--main-class', 'dev.velo.Main',
    '--add-modules', $modules,
    '--jlink-options', '--strip-debug --no-man-pages --no-header-files',
    '--java-options', '-Xmx512m',
    '--app-content', (Join-Path $source 'extension'),
    '--add-launcher', "velo-cli=$(Join-Path $here 'velo-cli.properties')",
    '--dest', $dist
)
if (-not $NoVideoTools) { $common += @('--app-content', $tools) }

if ($Type -eq 'app-image') {
    $image = Join-Path $dist 'velo'
    if (Test-Path $image) { Remove-Item -Recurse -Force $image }
    & (Join-Path $bin 'jpackage.exe') --type app-image @common
    if ($LASTEXITCODE -ne 0) { throw 'jpackage failed' }
    Write-Host "Built app image: $image\velo.exe"
    return
}

# Installers: jpackage on JDK 17-23 drives WiX Toolset 3.x (candle.exe / light.exe on PATH).
# Use an installed WiX if there is one; otherwise fetch the verified binaries (no install needed).
if (-not (Get-Command candle.exe -ErrorAction SilentlyContinue)) {
    $wix = & (Join-Path $here 'fetch-wix.ps1') | Select-Object -Last 1
    $env:PATH = "$wix;$env:PATH"
}
$installer = @(
    '--type', $Type,
    '--win-dir-chooser',          # let the user pick the install folder
    '--win-menu',                 # Start menu entry...
    '--win-menu-group', 'velo',   # ...in this Start menu folder
    '--win-shortcut',             # desktop shortcut...
    '--win-shortcut-prompt',      # ...that the user can untick
    '--win-per-user-install',     # no admin rights needed; installs under %LOCALAPPDATA%
    '--win-upgrade-uuid', $UpgradeUuid
)
$license = Join-Path $root 'LICENSE.txt'
if (Test-Path $license) { $installer += @('--license-file', $license) }
& (Join-Path $bin 'jpackage.exe') @installer @common
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed' }
Write-Host "Built installer in $dist"
