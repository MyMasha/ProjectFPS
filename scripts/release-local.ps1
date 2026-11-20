param(
    [Parameter(Mandatory = $true)]
    [string]$Version
)

$ErrorActionPreference = "Stop"
if ($Version -notmatch '^v?\d+\.\d+\.\d+$') {
    throw "ERREUR : Format SemVer invalide '$Version' (attendu : X.Y.Z ou vX.Y.Z)."
}
$CleanVer = $Version.TrimStart('v')
$Tag = "v$CleanVer"
$ExpectedZinkSha256 = "d3f71e2ef11c032a6d7b507ee2f6bab6ca3fb20543944af70982fc24b92604cb"

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$LocalMsIdFile = Join-Path $RepoRoot "launcher\.ms_client_id"
$HasEnvMsId = -not [string]::IsNullOrWhiteSpace($env:MASHCLIENT_MS_CLIENT_ID)
$HasFileMsId = (Test-Path $LocalMsIdFile) -and (-not [string]::IsNullOrWhiteSpace((Get-Content $LocalMsIdFile -Raw)))
if (-not $HasEnvMsId -and -not $HasFileMsId) {
    throw "ERREUR CRITIQUE : MASHCLIENT_MS_CLIENT_ID (ou launcher/.ms_client_id) absent ! Publiez via un tag Git (git tag $Tag && git push origin $Tag) pour compiler sur GitHub Actions avec les secrets OAuth2 officiels."
}

$ZinkPath = Join-Path $RepoRoot "dist\mashclient-vulkan-zink-win64.zip"
if (-not (Test-Path $ZinkPath)) {
    throw "ERREUR CRITIQUE : Archive Zink introuvable : $ZinkPath"
}
$ZinkHash = (Get-FileHash -Path $ZinkPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($ZinkHash -ne $ExpectedZinkSha256) {
    throw "ERREUR CRITIQUE : Empreinte SHA-256 invalide pour $ZinkPath (reçu $ZinkHash, attendu $ExpectedZinkSha256)."
}

$LicensePath = Join-Path $RepoRoot "LICENSE"
if (-not (Test-Path $LicensePath)) {
    throw "ERREUR CRITIQUE : LICENSE (GPL-3.0) introuvable à la racine du dépôt."
}

$NoticesPath = Join-Path $RepoRoot "THIRD_PARTY_LICENSES.md"
if (-not (Test-Path $NoticesPath)) {
    throw "ERREUR CRITIQUE : THIRD_PARTY_LICENSES.md introuvable à la racine du dépôt."
}

Write-Host "[1/5] Compilation locale du mod Forge 1.8.9 (purefps-$CleanVer.jar)..." -ForegroundColor Cyan
(Get-Content "gradle.properties" -Raw) -replace '(?m)^version=.*$', "version=$CleanVer" | Set-Content "gradle.properties" -NoNewline
.\gradlew.bat test remapJar --no-daemon

$JarPath = Join-Path $RepoRoot "build\libs\purefps-$CleanVer.jar"
if (-not (Test-Path $JarPath)) {
    throw "JAR introuvable : $JarPath"
}

Write-Host "[2/5] Tests & compilation locale du Launcher Windows (MashClient.exe)..." -ForegroundColor Cyan
$CargoHome = if (-not [string]::IsNullOrWhiteSpace($env:CARGO_HOME)) { $env:CARGO_HOME } else { Join-Path $env:USERPROFILE ".cargo" }
$PrevRustFlags = $env:RUSTFLAGS
$env:RUSTFLAGS = "--remap-path-prefix=$RepoRoot=/src --remap-path-prefix=$CargoHome=/cargo -C link-arg=/PDBALTPATH:%_PDB%"
Push-Location (Join-Path $RepoRoot "launcher")
try {
    (Get-Content "Cargo.toml" -Raw) -replace '(?m)^version\s*=\s*"[^"]+"', "version = `"$CleanVer`"" | Set-Content "Cargo.toml" -NoNewline
    cargo test -- --skip test_live_microsoft_device_code_endpoint
    cargo build --release
} finally {
    Pop-Location
    $env:RUSTFLAGS = $PrevRustFlags
}

$ExePath = Join-Path $RepoRoot "launcher\target\release\MashClient.exe"
if (-not (Test-Path $ExePath)) {
    throw "MashClient.exe introuvable : $ExePath"
}

Write-Host "[3/5] Préparation de MashClient-Windows-Portable.exe et compilation de MashClient-Windows-Setup.exe..." -ForegroundColor Cyan
$StageDir = Join-Path $RepoRoot "build\release-stage"
New-Item -ItemType Directory -Force -Path $StageDir | Out-Null
$PortablePath = Join-Path $StageDir "MashClient-Windows-Portable.exe"
Copy-Item $ExePath -Destination $PortablePath -Force

$Iscc = @(
    (Get-Command iscc -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue),
    "C:\Program Files (x86)\Inno Setup 6\ISCC.exe",
    "C:\Program Files\Inno Setup 6\ISCC.exe"
) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $Iscc) {
    throw "ERREUR CRITIQUE : Inno Setup 6 (ISCC.exe) introuvable pour compiler MashClient-Windows-Setup.exe."
}
$IssPath = Join-Path $RepoRoot "launcher\installer\mashclient.iss"
& $Iscc "/DMyAppVersion=$CleanVer" "/O$StageDir" $IssPath
if ($LASTEXITCODE -ne 0) {
    throw "ERREUR CRITIQUE : Échec de compilation Inno Setup (exit code $LASTEXITCODE)."
}
$SetupPath = Join-Path $StageDir "MashClient-Windows-Setup.exe"
if (-not (Test-Path $SetupPath)) {
    throw "MashClient-Windows-Setup.exe introuvable : $SetupPath"
}

Write-Host "[4/5] Génération de SHA256SUMS.txt & publication atomique ($Tag)..." -ForegroundColor Cyan
$SumsPath = Join-Path $StageDir "SHA256SUMS.txt"
$JarHash = (Get-FileHash -Path $JarPath -Algorithm SHA256).Hash.ToLowerInvariant()
$SetupHash = (Get-FileHash -Path $SetupPath -Algorithm SHA256).Hash.ToLowerInvariant()
$PortableHash = (Get-FileHash -Path $PortablePath -Algorithm SHA256).Hash.ToLowerInvariant()
$LicenseHash = (Get-FileHash -Path $LicensePath -Algorithm SHA256).Hash.ToLowerInvariant()
$NoticesHash = (Get-FileHash -Path $NoticesPath -Algorithm SHA256).Hash.ToLowerInvariant()
@(
    "$JarHash  purefps-$CleanVer.jar"
    "$SetupHash  MashClient-Windows-Setup.exe"
    "$PortableHash  MashClient-Windows-Portable.exe"
    "$ZinkHash  mashclient-vulkan-zink-win64.zip"
    "$LicenseHash  LICENSE"
    "$NoticesHash  THIRD_PARTY_LICENSES.md"
) -join "`n" | Set-Content -Path $SumsPath -Encoding ASCII -NoNewline
Add-Content -Path $SumsPath -Value "`n" -Encoding ASCII -NoNewline

gh release view $Tag --repo MyMasha/ProjectFPS *> $null
if ($LASTEXITCODE -eq 0) {
    throw "ERREUR : La release $Tag existe déjà sur MyMasha/ProjectFPS. Incrémentez le numéro de version plutôt que d'écraser une release existante."
}
gh release create $Tag $SetupPath $PortablePath $JarPath $ZinkPath $LicensePath $NoticesPath $SumsPath --repo MyMasha/ProjectFPS --title "MashClient $Tag" --generate-notes

Write-Host "[5/5] Publication locale terminée : https://github.com/MyMasha/ProjectFPS/releases/tag/$Tag" -ForegroundColor Green
