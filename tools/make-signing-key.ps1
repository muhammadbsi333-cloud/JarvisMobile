# Creates the signing key for JARVIS Mobile releases and writes the four values you
# paste into GitHub (Settings > Secrets and variables > Actions > New repository secret).
#
# Run it once, in PowerShell:   powershell -ExecutionPolicy Bypass -File .\tools\make-signing-key.ps1
#
# KEEP jarvis-release.jks SAFE. Everyone who installs your app is tied to this key: if you lose it,
# they have to uninstall before they can update. Never put the .jks or the passwords in the project.

$ErrorActionPreference = "Stop"

# Find keytool: on the PATH, in Android Studio's bundled Java, or in JAVA_HOME.
$keytool = $null
$onPath = Get-Command keytool -ErrorAction SilentlyContinue
if ($onPath) { $keytool = $onPath.Source }
if (-not $keytool) {
    $candidates = @(
        "$env:ProgramFiles\Android\Android Studio\jbr\bin\keytool.exe",
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr\bin\keytool.exe"
    )
    if ($env:JAVA_HOME) { $candidates += "$env:JAVA_HOME\bin\keytool.exe" }
    foreach ($c in $candidates) { if (Test-Path $c) { $keytool = $c; break } }
}
if (-not $keytool) { throw "keytool was not found. Install Android Studio (it bundles Java) or a JDK, then run this again." }

$alias = "jarvis"
$chars = (48..57) + (65..90) + (97..122)
$password = -join ($chars | Get-Random -Count 24 | ForEach-Object { [char]$_ })
$store = Join-Path (Get-Location) "jarvis-release.jks"
if (Test-Path $store) { throw "jarvis-release.jks already exists here. Move it away first - this script will not overwrite a key." }

& $keytool -genkeypair -v -keystore $store -alias $alias -keyalg RSA -keysize 2048 -validity 10000 `
    -storepass $password -keypass $password -dname "CN=JARVIS Mobile"
if ($LASTEXITCODE -ne 0) { throw "keytool failed" }

$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($store))
$out = Join-Path (Get-Location) "github-secrets.txt"
@(
    "Create these four repository secrets on GitHub (names exactly as written):",
    "",
    "ANDROID_KEYSTORE_PASSWORD = $password",
    "ANDROID_KEY_ALIAS        = $alias",
    "ANDROID_KEY_PASSWORD     = $password",
    "ANDROID_KEYSTORE_BASE64  = (the very long text below)",
    "",
    $b64
) | Set-Content -Path $out -Encoding ascii

Write-Host ""
Write-Host "Done."
Write-Host "  Key file : $store   (back it up somewhere safe, never share it)"
Write-Host "  Secrets  : $out   (open it, copy each value into GitHub, then DELETE this file)"
