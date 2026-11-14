param(
    [Parameter(Mandatory = $true)]
    [string]$Version
)

$ErrorActionPreference = "Stop"
$CleanVer = $Version.TrimStart('v')
$Tag = "v$CleanVer"

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

Write-Host "[1/4] Compilation locale du mod Forge 1.8.9 (purefps-$CleanVer.jar)..." -ForegroundColor Cyan
(Get-Content "gradle.properties" -Raw) -replace '(?m)^version=.*$', "version=$CleanVer" | Set-Content "gradle.properties" -NoNewline
.\gradlew.bat test remapJar --no-daemon

$JarPath = Join-Path $RepoRoot "build\libs\purefps-$CleanVer.jar"
if (-not (Test-Path $JarPath)) {
    throw "JAR introuvable : $JarPath"
}

Write-Host "[2/4] Compilation locale du Launcher Windows (MashClient.exe)..." -ForegroundColor Cyan
Push-Location (Join-Path $RepoRoot "launcher")
try {
    (Get-Content "Cargo.toml" -Raw) -replace '(?m)^version\s*=\s*"[^"]+"', "version = `"$CleanVer`"" | Set-Content "Cargo.toml" -NoNewline
    cargo build --release
} finally {
    Pop-Location
}

$ExePath = Join-Path $RepoRoot "launcher\target\release\MashClient.exe"
if (-not (Test-Path $ExePath)) {
    throw "MashClient.exe introuvable : $ExePath"
}

Write-Host "[3/4] Envoi direct des binaires locaux sur GitHub Releases ($Tag)..." -ForegroundColor Cyan
gh release view $Tag --repo MyMasha/ProjectFPS *> $null
if ($LASTEXITCODE -eq 0) {
    gh release upload $Tag $JarPath $ExePath --repo MyMasha/ProjectFPS --clobber
} else {
    gh release create $Tag $JarPath $ExePath --repo MyMasha/ProjectFPS --title "MashClient $Tag" --generate-notes
}

Write-Host "[4/4] Publication locale terminée : https://github.com/MyMasha/ProjectFPS/releases/tag/$Tag" -ForegroundColor Green
