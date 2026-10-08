# Install Android SDK command-line tools + platform + build-tools to D:\Android\sdk
# Idempotent: skips work already done.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$SdkRoot = 'D:\Android\sdk'
$DlDir   = 'D:\Android\dl'
$ZipUrl  = 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip'
$ZipPath = Join-Path $DlDir 'cmdline-tools.zip'
$JavaHome = 'D:\JAVA\JAVA17'

New-Item -ItemType Directory -Force -Path $SdkRoot, $DlDir | Out-Null

# 1. download
if (-not (Test-Path $ZipPath) -or (Get-Item $ZipPath).Length -lt 50MB) {
    Write-Host "[1/4] downloading cmdline-tools ..."
    Invoke-WebRequest -Uri $ZipUrl -OutFile $ZipPath -UseBasicParsing
} else {
    Write-Host "[1/4] zip already present, skip download"
}
Write-Host "      zip size = $([math]::Round((Get-Item $ZipPath).Length/1MB,1)) MB"

# 2. extract
$Latest = Join-Path $SdkRoot 'cmdline-tools\latest'
if (-not (Test-Path (Join-Path $Latest 'bin\sdkmanager.bat'))) {
    Write-Host "[2/4] extracting ..."
    $tmp = Join-Path $DlDir 'cmdline-extract'
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    Expand-Archive -Path $ZipPath -DestinationPath $tmp -Force
    New-Item -ItemType Directory -Force -Path (Split-Path $Latest) | Out-Null
    if (Test-Path $Latest) { Remove-Item $Latest -Recurse -Force }
    Move-Item (Join-Path $tmp 'cmdline-tools') $Latest
    Remove-Item $tmp -Recurse -Force
} else {
    Write-Host "[2/4] cmdline-tools already extracted, skip"
}

$SdkManager = Join-Path $Latest 'bin\sdkmanager.bat'
if (-not (Test-Path $SdkManager)) { throw "sdkmanager not found at $SdkManager" }

$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
$env:Path = "$JavaHome\bin;$SdkRoot\platform-tools;$SdkRoot\cmdline-tools\latest\bin;" + $env:Path

# 3. licenses
Write-Host "[3/4] accepting licenses ..."
$yes = ("y`r`n" * 60)
$yes | & $SdkManager --sdk_root=$SdkRoot --licenses 2>&1 | Select-Object -Last 8

# 4. install packages
Write-Host "[4/4] installing platforms;android-35 build-tools;35.0.0 platform-tools ..."
& $SdkManager --sdk_root=$SdkRoot 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools' 2>&1 | Select-Object -Last 25

Write-Host "=== RESULT ==="
& $SdkManager --sdk_root=$SdkRoot --list_installed 2>&1 | Select-Object -Last 20
Write-Host "SDK_INSTALL_DONE"
