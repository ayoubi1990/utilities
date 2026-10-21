# Downloads the video tools velo bundles, verifies their SHA-256 checksums against the
# publishers' own checksum files, and stages them for packaging (Windows x64 builds).
#
#   yt-dlp.exe              https://github.com/yt-dlp/yt-dlp           (Unlicense; Python is built in)
#   ffmpeg.exe, ffprobe.exe https://github.com/BtbN/FFmpeg-Builds      (LGPL 2.1 "shared" build)
#   deno.exe                https://github.com/denoland/deno           (MIT; JavaScript runtime YouTube needs)
#
# Downloads are cached in <repo>\.cache\downloads\. -Refresh fetches the latest releases again.
param(
    [string]$Stage,
    [switch]$Refresh
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # Invoke-WebRequest is much faster without the progress bar
$here = $PSScriptRoot
$root = Split-Path (Split-Path $here -Parent) -Parent
$cache = Join-Path $root '.cache\downloads'
$stage = if ($Stage) { $Stage } else { Join-Path $root '.cache\windows\tools' }
$headers = @{ 'User-Agent' = 'velo-build' }

if ($Refresh -and (Test-Path $cache)) { Remove-Item -Recurse -Force $cache }
New-Item -ItemType Directory -Force -Path $cache | Out-Null

function Get-Release($repo) {
    # In CI a token avoids GitHub's anonymous API rate limit. It is only sent to the API,
    # never with file downloads (those redirect to storage hosts).
    $api = $headers.Clone()
    if ($env:GITHUB_TOKEN) { $api['Authorization'] = "Bearer $env:GITHUB_TOKEN" }
    Invoke-RestMethod -Headers $api "https://api.github.com/repos/$repo/releases/latest"
}

function Get-Cached($url, $name) {
    $file = Join-Path $cache $name
    if (-not (Test-Path $file)) {
        Write-Host "Downloading $name"
        Invoke-WebRequest -UseBasicParsing -Headers $headers $url -OutFile "$file.download"
        Move-Item -Force "$file.download" $file
    }
    return $file
}

function Assert-Sha256($file, $expected) {
    # (Callers name cached files after the release they came from, so a stale cache cannot match.)
    $actual = (Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $expected.ToLowerInvariant()) {
        Remove-Item -Force $file
        throw "Checksum mismatch for $(Split-Path $file -Leaf): expected $expected, got $actual (file deleted)"
    }
    Write-Host "  verified $(Split-Path $file -Leaf)"
}

# Finds "<hash>  <name>" (or "<hash> *<name>") in a checksum file.
function Find-Hash($sumsFile, $name) {
    foreach ($line in Get-Content $sumsFile) {
        $parts = $line.Trim() -split '\s+\*?', 2
        if ($parts.Count -eq 2 -and $parts[1].Trim() -eq $name) { return $parts[0] }
    }
    throw "$name is not listed in $(Split-Path $sumsFile -Leaf)"
}

$versions = @()

# ---- yt-dlp ----
$rel = Get-Release 'yt-dlp/yt-dlp'
$base = "https://github.com/yt-dlp/yt-dlp/releases/download/$($rel.tag_name)"
$sums = Get-Cached "$base/SHA2-256SUMS" "yt-dlp-$($rel.tag_name).SHA2-256SUMS"
$ytdlp = Get-Cached "$base/yt-dlp.exe" "yt-dlp-$($rel.tag_name).exe"
Assert-Sha256 $ytdlp (Find-Hash $sums 'yt-dlp.exe')
$ytdlpLicense = Get-Cached "https://raw.githubusercontent.com/yt-dlp/yt-dlp/$($rel.tag_name)/LICENSE" "yt-dlp-LICENSE.txt"
$ytdlpThird = Get-Cached "https://raw.githubusercontent.com/yt-dlp/yt-dlp/$($rel.tag_name)/THIRD_PARTY_LICENSES.txt" "yt-dlp-THIRD_PARTY_LICENSES.txt"
$versions += "yt-dlp $($rel.tag_name)"

# ---- ffmpeg (LGPL, shared DLLs) ----
$rel = Get-Release 'BtbN/FFmpeg-Builds'
# Newest numbered release branch. BtbN has used both "ffmpeg-n9.0-latest-win64-lgpl-shared-9.0.zip"
# and "ffmpeg-n9.0.2-31-gc89d6ee1ca-win64-lgpl-shared-9.0.zip" style names, so accept either.
$pattern = '^ffmpeg-n(\d+)\.(\d+)\S*-win64-lgpl-shared-\d+\.\d+\.zip$'
$asset = $rel.assets | Where-Object { $_.name -match $pattern } |
    Sort-Object { $null = $_.name -match $pattern; [int]$Matches[1] * 1000 + [int]$Matches[2] } |
    Select-Object -Last 1
if (-not $asset) { throw 'No LGPL shared win64 ffmpeg build found in the latest BtbN release' }
$sums = Get-Cached ($rel.assets | Where-Object name -eq 'checksums.sha256').browser_download_url "ffmpeg-checksums-$($rel.id).sha256"
# BtbN republishes "latest" builds daily under the same file name, so cache them per release.
$ffzip = Get-Cached $asset.browser_download_url "ffmpeg-$($rel.id)-$($asset.name)"
Assert-Sha256 $ffzip (Find-Hash $sums $asset.name)
$versions += ($asset.name -replace '\.zip$', '')

# ---- deno ----
$rel = Get-Release 'denoland/deno'
$base = "https://github.com/denoland/deno/releases/download/$($rel.tag_name)"
$denozip = Get-Cached "$base/deno-x86_64-pc-windows-msvc.zip" "deno-$($rel.tag_name).zip"
$denoSum = Get-Cached "$base/deno-x86_64-pc-windows-msvc.zip.sha256sum" "deno-$($rel.tag_name).zip.sha256sum"
# Deno publishes the output of PowerShell's Get-FileHash; take the 64-hex-digit hash from it.
$denoHash = [regex]::Match((Get-Content $denoSum -Raw), '\b[0-9a-fA-F]{64}\b').Value
if (-not $denoHash) { throw "No SHA-256 found in $(Split-Path $denoSum -Leaf)" }
Assert-Sha256 $denozip $denoHash
$denoLicense = Get-Cached "https://raw.githubusercontent.com/denoland/deno/$($rel.tag_name)/LICENSE.md" "deno-LICENSE.md"
$versions += "deno $($rel.tag_name)"

# ---- stage: out\tools\ ----
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
$licenses = Join-Path $stage 'licenses'
New-Item -ItemType Directory -Force -Path $licenses | Out-Null

Copy-Item $ytdlp (Join-Path $stage 'yt-dlp.exe')
Copy-Item $ytdlpLicense (Join-Path $licenses 'yt-dlp-LICENSE.txt')
Copy-Item $ytdlpThird (Join-Path $licenses 'yt-dlp-THIRD_PARTY_LICENSES.txt')

$tmp = Join-Path $cache 'unzip'
if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
Expand-Archive $ffzip $tmp
$ffroot = Get-ChildItem $tmp -Directory | Select-Object -First 1
Copy-Item (Join-Path $ffroot.FullName 'bin\ffmpeg.exe'), (Join-Path $ffroot.FullName 'bin\ffprobe.exe') $stage
Copy-Item (Join-Path $ffroot.FullName 'bin\*.dll') $stage
Copy-Item (Join-Path $ffroot.FullName 'LICENSE.txt') (Join-Path $licenses 'ffmpeg-LICENSE.txt')
Remove-Item -Recurse -Force $tmp

Expand-Archive $denozip $tmp
Copy-Item (Join-Path $tmp 'deno.exe') $stage
Copy-Item $denoLicense (Join-Path $licenses 'deno-LICENSE.md')
Remove-Item -Recurse -Force $tmp

$sourceNote = @('Bundled third-party tools (run as separate programs by velo):', '') +
    ($versions | ForEach-Object { "  $_" }) + @(
    '',
    'FFmpeg is licensed under the LGPL 2.1. This is an unmodified build from',
    'https://github.com/BtbN/FFmpeg-Builds; its source code is available from',
    'https://ffmpeg.org/download.html and the build scripts from the BtbN repository.',
    'You may replace the FFmpeg DLLs in this folder with your own compatible build.'
)
Set-Content -Path (Join-Path $stage 'VERSIONS.txt') -Value $sourceNote -Encoding utf8
$size = (Get-ChildItem $stage -Recurse | Measure-Object Length -Sum).Sum / 1MB
Write-Host ("Staged video tools in {0} ({1:N0} MB): {2}" -f $stage, $size, ($versions -join ', '))
