# Creates the release signing key for the Android app, ONCE, on your own PC.
#   Right-click > Run with PowerShell   (or:  powershell -ExecutionPolicy Bypass -File tools\make-release-key.ps1)
#
# Output (outside the repo, never commit it):  D:\project\xreal-space\keys\
#   xreal-space-release.jks   the key        -> back it up (USB / Google Drive). Lose it = users must uninstall to update.
#   secrets.txt               the 3 values   -> paste into GitHub, then you may delete this file
#
# Needs keytool, which comes with Android Studio (or any Java JDK).

$ErrorActionPreference = "Stop"
$outDir = "D:\project\xreal-space\keys"
$ks = Join-Path $outDir "xreal-space-release.jks"
$alias = "xreal-space"

if (Test-Path $ks) { Write-Host "Key already exists: $ks  (not overwriting)" -ForegroundColor Yellow; exit 1 }

# find keytool
$candidates = @(
  (Get-Command keytool -ErrorAction SilentlyContinue).Source,
  "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe",
  "C:\Program Files\Android\Android Studio\jre\bin\keytool.exe"
) + (Get-ChildItem "C:\Program Files\Java\*\bin\keytool.exe", "C:\Program Files\Eclipse Adoptium\*\bin\keytool.exe" -ErrorAction SilentlyContinue | ForEach-Object FullName)
$keytool = $candidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $keytool) { Write-Host "keytool not found. Install Android Studio or a Java JDK (Temurin 17), then run again." -ForegroundColor Red; exit 1 }

New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$chars = [char[]]"ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
# crypto-random password (works on Windows PowerShell 5.1 and PowerShell 7)
$bytes = New-Object byte[] 24
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$pw = -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })

& $keytool -genkeypair -keystore $ks -alias $alias -keyalg RSA -keysize 4096 -validity 10000 `
  -storepass $pw -keypass $pw -dname "CN=Nguyen Manh Hung, O=JinerGenkai, C=VN" | Out-Null

$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($ks))
$secrets = Join-Path $outDir "secrets.txt"
@"
Paste these 3 into GitHub: repo jinergenkai/xreal-space > Settings > Secrets and variables > Actions > New repository secret

Name:  ANDROID_KEYSTORE_B64
Value: $b64

Name:  ANDROID_KEYSTORE_PASSWORD
Value: $pw

Name:  ANDROID_KEY_ALIAS
Value: $alias
"@ | Set-Content -Encoding UTF8 $secrets

Write-Host ""
Write-Host "Done." -ForegroundColor Green
Write-Host "  Key:     $ks   <- BACK THIS UP, keep it private"
Write-Host "  Secrets: $secrets   <- paste the 3 values into GitHub, then delete this file"
Start-Process notepad.exe $secrets
