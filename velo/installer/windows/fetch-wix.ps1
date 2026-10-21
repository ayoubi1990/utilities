# Gets the WiX Toolset 3.14.1 binaries that jpackage uses to build .msi/.exe installers,
# without installing anything: the official zip is unpacked into <repo>\.cache\downloads\wix314.
# Prints the folder to add to PATH.
#
# The zip's programs are not Authenticode-signed and the release publishes no checksum, so its
# SHA-256 is pinned below. It was verified once by checking that all 68 programs and libraries
# in it are byte-identical to those in the official WiX 3.14.1 package on nuget.org.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$cache = Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) '.cache\downloads'
$dir = Join-Path $cache 'wix314'
$url = 'https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip'
$sha256 = '6ac824e1642d6f7277d0ed7ea09411a508f6116ba6fae0aa5f2c7daa2ff43d31'

if (-not (Test-Path (Join-Path $dir 'candle.exe'))) {
    New-Item -ItemType Directory -Force -Path $cache | Out-Null
    $zip = Join-Path $cache 'wix314-binaries.zip'
    if (-not (Test-Path $zip)) {
        Write-Host 'Downloading WiX Toolset 3.14.1 binaries'
        Invoke-WebRequest -UseBasicParsing -Headers @{ 'User-Agent' = 'velo-build' } $url -OutFile "$zip.download"
        Move-Item -Force "$zip.download" $zip
    }
    $actual = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $sha256) {
        Remove-Item -Force $zip
        throw "WiX zip checksum mismatch: expected $sha256, got $actual (file deleted)"
    }
    Write-Host '  verified wix314-binaries.zip'
    if (Test-Path $dir) { Remove-Item -Recurse -Force $dir }
    Expand-Archive $zip $dir
}
$dir
