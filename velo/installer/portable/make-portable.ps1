# Builds the Windows portable package: a zip users unpack anywhere and run velo.exe from.
#   installer\portable\make-portable.ps1 -Version 1.2.0 [-Vendor "Your Company"] [-NoVideoTools]
#   -> dist\velo-<version>-windows-x64-portable.zip
param(
    [string]$Version = '0.1.0',
    [string]$Vendor = 'velo',
    [switch]$NoVideoTools
)
$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$dist = Join-Path $root 'dist'

& (Join-Path $root 'installer\windows\package.ps1') -Type app-image -Version $Version -Vendor $Vendor -NoVideoTools:$NoVideoTools
if (-not $?) { throw 'packaging failed' }

$zip = Join-Path $dist "velo-$Version-windows-x64-portable.zip"
if (Test-Path $zip) { Remove-Item -Force $zip }
Compress-Archive -Path (Join-Path $dist 'velo') -DestinationPath $zip
Write-Host ("Built {0} ({1:N0} MB)" -f $zip, ((Get-Item $zip).Length / 1MB))
