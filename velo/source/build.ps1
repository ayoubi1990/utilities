# Builds a single, self-contained velo.jar with the JDK alone (no Maven or Gradle needed).
#   .\build.ps1          compile and package velo.jar
#   .\build.ps1 -Test    also compile and run the test suite
param([switch]$Test, [string]$Version = '0.1.0', [string]$Vendor = 'velo')

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$out = Join-Path $root 'out'

function Compile($srcDir, $dest, $classpath) {
    $sources = Get-ChildItem -Recurse -Path $srcDir -Filter *.java | ForEach-Object { '"' + ($_.FullName -replace '\\', '/') + '"' }
    $argFile = Join-Path $out ("sources-" + (Split-Path $dest -Leaf) + ".txt")
    Set-Content -Path $argFile -Value $sources -Encoding ascii
    $cpArgs = if ($classpath) { @('-cp', $classpath) } else { @() }
    & javac -encoding UTF-8 -Xlint:all -Xlint:-serial -d $dest @cpArgs "@$argFile"
    if ($LASTEXITCODE -ne 0) { throw "javac failed for $srcDir" }
}

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path $out | Out-Null

function Find-Jar {
    $onPath = Get-Command jar -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    # The Oracle installer only puts java/javac shims on PATH; ask the JVM where the JDK lives.
    $ErrorActionPreference = 'Continue'  # java prints these settings on stderr
    $line = & java -XshowSettings:properties -version 2>&1 | Where-Object { "$_" -match '^\s*java\.home\s*=' } | Select-Object -First 1
    $javaHome = ("$line" -split '=', 2)[1].Trim()
    $jar = Join-Path $javaHome 'bin\jar.exe'
    if (-not (Test-Path $jar)) { throw "Cannot find jar.exe; set JAVA_HOME or add the JDK bin folder to PATH" }
    return $jar
}

$libs = Get-ChildItem -Path (Join-Path $root 'lib') -Filter *.jar | Sort-Object Name
$libCp = ($libs | ForEach-Object { $_.FullName }) -join ';'
$jarTool = Find-Jar

$mainClasses = Join-Path $out 'main'
Compile (Join-Path $root 'src/main/java') $mainClasses $libCp
# Non-code resources (the fallback icon font and its licence) go next to the classes.
Copy-Item -Recurse -Force (Join-Path $root 'src/main/resources/*') $mainClasses

# One self-contained velo.jar: our classes plus the unpacked third-party jars (FlatLaf).
$fat = Join-Path $out 'fat'
New-Item -ItemType Directory -Force -Path $fat | Out-Null
foreach ($lib in $libs) {
    Push-Location $fat
    try { & $jarTool --extract --file $lib.FullName } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { throw "could not unpack $($lib.Name)" }
}
# Drop what belongs to the individual library jars: their manifests, signatures and
# module descriptors (velo.jar runs on the class path, not as a module).
Remove-Item -Force -ErrorAction SilentlyContinue (Join-Path $fat 'META-INF\MANIFEST.MF'), (Join-Path $fat 'module-info.class')
Get-ChildItem -Path (Join-Path $fat 'META-INF') -Recurse -Include *.SF, *.RSA, *.DSA, *.EC, module-info.class -ErrorAction SilentlyContinue |
    Remove-Item -Force
Copy-Item -Recurse -Force (Join-Path $mainClasses '*') $fat
New-Item -ItemType Directory -Force -Path (Join-Path $fat 'META-INF') | Out-Null
Copy-Item (Join-Path $root 'THIRD-PARTY-NOTICES.txt') (Join-Path $fat 'META-INF\THIRD-PARTY-NOTICES.txt')

$manifest = Join-Path $out 'MANIFEST.MF'
Set-Content -Path $manifest -Encoding ascii -Value @(
    "Multi-Release: true",
    "Implementation-Title: velo",
    "Implementation-Version: $Version",
    "Implementation-Vendor: $Vendor"
)
& $jarTool --create --file (Join-Path $root 'velo.jar') --manifest $manifest --main-class dev.velo.Main -C $fat .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
Write-Host "Built velo.jar $Version (self-contained)"

if ($Test) {
    Compile (Join-Path $root 'src/test/java') (Join-Path $out 'test') "$mainClasses;$libCp"
    & java -ea -cp "$mainClasses;$(Join-Path $out 'test');$libCp" dev.velo.core.TestMain
    if ($LASTEXITCODE -ne 0) { throw 'tests failed' }
}
