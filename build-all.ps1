# Builds ModSync for every supported version and collects the jars in dist\
#   powershell -ExecutionPolicy Bypass -File build-all.ps1
#   powershell -ExecutionPolicy Bypass -File build-all.ps1 -Only neoforge-1.21.4
#   powershell -ExecutionPolicy Bypass -File build-all.ps1 -Proxy 127.0.0.1:1080
param(
    [string[]]$Only,
    [string]$Proxy,
    [string]$Java17 = 'E:\java17',
    [string]$Java21 = 'E:\java21'
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force $dist | Out-Null

# ForgeGradle 6 runs on Java 17, ModDevGradle (NeoForge) on Java 21.
# Each build then compiles with the Java version its Minecraft needs (17 / 21 / 25), found via gradle.properties.
$targets = [ordered]@{
    'forge-1.20.1'     = $Java17
    'forge-1.21.1'     = $Java17
    'neoforge-1.21.4'  = $Java21
    'neoforge-1.21.8'  = $Java21
    'neoforge-1.21.11' = $Java21
    'neoforge-26.3'    = $Java21
}

$extra = @()
if ($Proxy) {
    $h, $p = $Proxy.Split(':')
    $extra = "-Dhttp.proxyHost=$h", "-Dhttp.proxyPort=$p", "-Dhttps.proxyHost=$h", "-Dhttps.proxyPort=$p"
}

$failed = @()
foreach ($name in $targets.Keys) {
    if ($Only -and $name -notin $Only) { continue }
    $dir = Join-Path $root "versions\$name"
    Write-Host "=== $name" -ForegroundColor Cyan
    $env:JAVA_HOME = $targets[$name]
    Push-Location $dir
    try {
        & .\gradlew.bat build --console=plain @extra
        if ($LASTEXITCODE -ne 0) { throw "gradle exited with $LASTEXITCODE" }
        Get-ChildItem build\libs\*.jar | Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } |
            Copy-Item -Destination $dist -Force
    } catch {
        Write-Host "FAILED: $name ($_)" -ForegroundColor Red
        $failed += $name
    } finally {
        Pop-Location
    }
}

Write-Host ''
Get-ChildItem $dist\*.jar | Select-Object Name, Length | Format-Table -AutoSize
if ($failed) { Write-Host "Failed: $($failed -join ', ')" -ForegroundColor Red; exit 1 }
