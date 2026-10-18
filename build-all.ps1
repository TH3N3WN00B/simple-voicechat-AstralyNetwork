# Builds every Minecraft version under versions\ (or only the ones given) and collects the jars into dist\<version>\.
param([string[]]$Versions)
$ErrorActionPreference = 'Continue'
Set-Location $PSScriptRoot
if (-not $Versions) { $Versions = Get-Content versions.txt | Where-Object { $_.Trim() } }
$failed = @()
foreach ($v in $Versions) {
    Write-Host "=== $v"
    $dir = Join-Path $PSScriptRoot "versions\$v"
    if (-not (Test-Path $dir)) { Write-Host "missing $dir"; $failed += $v; continue }
    Push-Location $dir
    & .\gradlew.bat clean build --console=plain
    $code = $LASTEXITCODE
    Pop-Location
    if ($code -ne 0) { $failed += $v; continue }
    $out = Join-Path $PSScriptRoot "dist\$v"
    if (Test-Path $out) { Remove-Item -Recurse -Force $out }
    New-Item -ItemType Directory -Force $out | Out-Null
    foreach ($m in 'fabric', 'forge', 'neoforge', 'paper', 'bukkit', 'bungeecord', 'velocity') {
        $libs = Join-Path $dir "$m\build\libs"
        if (Test-Path $libs) {
            Get-ChildItem $libs -Filter *.jar | Where-Object { $_.Name -notmatch 'sources|javadoc|dev|slim|plain|api-stub' } | Copy-Item -Destination $out
        }
    }
}
if ($failed) { Write-Host "FAILED: $($failed -join ', ')"; exit 1 }
Write-Host 'All versions built.'
