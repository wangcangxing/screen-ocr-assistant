#requires -version 5.1
<#
独立验证脚本 · APK 构建与产物核对（对抗性）
==============================================
口径来源：docs\接口约定-SoM与解析服务.md（冻结契约 v1）§5：
  applicationId = com.dsh.screenocr.omni、versionName = 1.4-omni、namespace 仍为 com.dsh.screenocr。

做的事（全程只读源码 + 构建，不改任何业务文件）：
  1) 记录 JAVA_HOME / gradle / aapt2 环境事实；
  2) 跑 gradle assembleDebug 并记录退出码（可能很久，日志落 verify\out\）；
  3) 定位本次构建出的 APK，报绝对路径 / 字节数 / SHA256 / 修改时间；
  4) 用 aapt2 dump badging 证明 package 与 versionName（产物侧权威证据）；
  5) 静态核对 app\build.gradle.kts 的 applicationId / versionName / namespace（源码侧证据，仅供参考）。

用法：
  powershell -NoProfile -ExecutionPolicy Bypass -File verify\check_android_build.ps1
  ... -SkipBuild        # 不重新构建，只核对已有 APK（快速复跑）
  ... -SelfTest         # 离线自检：只验证脚本自身解析逻辑，不构建、不读 APK

退出码：0 = 无非通过项；1 = 有不通过项。
#>
[CmdletBinding()]
param(
    [string]$ProjectDir       = 'D:\program\screen-ocr-assistant-omniparser',
    [string]$JavaHome         = 'D:\JAVA\JAVA17',
    [string]$GradleBat        = 'D:\Android\gradle\gradle-8.11.1\bin\gradle.bat',
    [string]$BuildToolsDir    = 'D:\Android\sdk\build-tools',
    [string]$ExpectedPackage  = 'com.dsh.screenocr.omni',
    [string]$ExpectedVersion  = '1.5-omni',
    [string]$ExpectedNamespace = 'com.dsh.screenocr',
    [string]$ExpectedSha256    = '',
    [string]$ExpectedVersionCode = '6',
    [int]$ExpectedMinSdk       = 30,
    [int]$ExpectedTargetSdk    = 35,
    [switch]$Clean,
    [switch]$SkipBuild,
    [switch]$SkipRegexScan,
    [switch]$SelfTest
)

$ErrorActionPreference = 'Continue'
$script:Results = New-Object System.Collections.Generic.List[object]
$OutDir = Join-Path $ProjectDir 'verify\out'

function Add-Check([string]$Kind, [string]$Item, [string]$Detail = '') {
    $script:Results.Add([pscustomobject]@{ kind = $Kind; item = $Item; detail = $Detail })
    $line = "[$Kind] $Item"
    if ($Detail) { $line += "  —— $Detail" }
    Write-Host $line
}
function Pass([string]$Item, [string]$Detail = '') { Add-Check '通过'   $Item $Detail }
function Fail([string]$Item, [string]$Detail = '') { Add-Check '不通过' $Item $Detail }
function Warn([string]$Item, [string]$Detail = '') { Add-Check '注意'   $Item $Detail }

function Get-Badging([string]$Text) {
    # aapt2 dump badging 的首行形如：
    # package: name='com.dsh.screenocr.omni' versionCode='4' versionName='1.4-omni' platformBuildVersionName='...'
    $r = [ordered]@{ package = $null; versionCode = $null; versionName = $null
                     sdkVersion = $null; targetSdkVersion = $null; label = $null }
    $m = [regex]::Match($Text, "package:\s+name='([^']*)'\s+versionCode='([^']*)'\s+versionName='([^']*)'")
    if ($m.Success) {
        $r.package     = $m.Groups[1].Value
        $r.versionCode = $m.Groups[2].Value
        $r.versionName = $m.Groups[3].Value
    }
    # 注意大小写：aapt2 35 打的是 `minSdkVersion`（min + SdkVersion，大写 S），老版本是 `sdkVersion`；
    # 必须用行首锚定，否则 `targetSdkVersion` 也会被 `sdkVersion` 分支命中（实测踩过）。
    $m2 = [regex]::Match($Text, "(?im)^(?:min)?sdkVersion:'([^']*)'"); if ($m2.Success) { $r.sdkVersion = $m2.Groups[1].Value }
    $m3 = [regex]::Match($Text, "(?im)^targetSdkVersion:'([^']*)'");  if ($m3.Success) { $r.targetSdkVersion = $m3.Groups[1].Value }
    $m4 = [regex]::Match($Text, "application-label:'([^']*)'"); if ($m4.Success) { $r.label = $m4.Groups[1].Value }
    return $r
}

function Get-BuildGradleValue([string]$Text, [string]$Key) {
    $m = [regex]::Match($Text, "(?m)^\s*$Key\s*=\s*`"([^`"]*)`"")
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}

function Read-Utf8Text([string]$Path) {
    # 必须显式按 UTF-8 读！PowerShell 5.1 的 `Get-Content -Raw` 对**无 BOM** 的 UTF-8 源码按 GBK 解码，
    # 中文注释处错位的双字节会吞掉行尾换行，导致下一行的 `^` 锚定正则整体失配（本脚本实测踩过：
    # applicationId 被解析成空串、被误报为「源码未改包名」）。
    return [System.IO.File]::ReadAllText($Path, [System.Text.UTF8Encoding]::new($false))
}

function Dump-Summary {
    if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
    $nPass = @($script:Results | Where-Object kind -eq '通过').Count
    $nFail = @($script:Results | Where-Object kind -eq '不通过').Count
    $nWarn = @($script:Results | Where-Object kind -eq '注意').Count
    Write-Host ''
    Write-Host '================ 汇总 ================'
    Write-Host ("通过 {0} 项 / 不通过 {1} 项 / 注意 {2} 项" -f $nPass, $nFail, $nWarn)
    if ($nFail -gt 0) {
        Write-Host '不通过清单：'
        foreach ($r in $script:Results) { if ($r.kind -eq '不通过') { Write-Host ("  - {0}  ({1})" -f $r.item, $r.detail) } }
    }
    $jsonPath = Join-Path $OutDir 'android-build-result.json'
    try {
        [pscustomobject]@{ time = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss'); results = $script:Results } |
            ConvertTo-Json -Depth 5 | Set-Content -Path $jsonPath -Encoding UTF8
        Write-Host "结构化结果：$jsonPath"
    } catch { Write-Warning "写 JSON 失败: $_" }
    return $(if ($nFail -gt 0) { 1 } else { 0 })
}

# ------------------------------------------------------------ 自检模式 ----
if ($SelfTest) {
    Write-Host '=== 离线自检（不构建、不读 APK）==='
    $ok = $true
    # 夹具按「当前期望值」动态拼装，避免改了期望值后自检自己变红（也顺便验证期望值本身自洽）
    $fixtureOld = @"
package: name='$ExpectedPackage' versionCode='$ExpectedVersionCode' versionName='$ExpectedVersion' platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='35' compileSdkVersionCodename='15'
sdkVersion:'$ExpectedMinSdk'
targetSdkVersion:'$ExpectedTargetSdk'
application-label:'Screen OCR'
"@
    $b = Get-Badging $fixtureOld
    if ($b.package -eq $ExpectedPackage -and $b.versionName -eq $ExpectedVersion -and $b.versionCode -eq $ExpectedVersionCode) {
        Pass '自检·aapt2 badging 解析（正样本，老版本 sdkVersion 写法）' ("package={0} versionCode={1} versionName={2} sdk={3} target={4}" -f $b.package, $b.versionCode, $b.versionName, $b.sdkVersion, $b.targetSdkVersion)
    } else { Fail '自检·aapt2 badging 解析（正样本，老版本 sdkVersion 写法）' ("解析结果 package={0} versionCode={1} versionName={2}" -f $b.package, $b.versionCode, $b.versionName); $ok = $false }

    # aapt2 35 实际打的是 minSdkVersion（大写 S），老版本是 sdkVersion —— 两种都必须解析出期望的 minSdk，且不能把 target 误当 minSdk
    $fixtureNew = $fixtureOld.Replace("sdkVersion:'$ExpectedMinSdk'", "minSdkVersion:'$ExpectedMinSdk'")
    $bNew = Get-Badging $fixtureNew
    if ($bNew.sdkVersion -eq "$ExpectedMinSdk" -and $bNew.targetSdkVersion -eq "$ExpectedTargetSdk") {
        Pass '自检·badging 解析 minSdkVersion（aapt2 35 大写 S 写法）' ("min={0} target={1}" -f $bNew.sdkVersion, $bNew.targetSdkVersion)
    } else { Fail '自检·badging 解析 minSdkVersion（aapt2 35 大写 S 写法）' ("min={0} target={1}（min 空或把 target 当 min 即回归）" -f $bNew.sdkVersion, $bNew.targetSdkVersion); $ok = $false }

    # 负样本：包名退回旧值、版本名退回旧值 —— 必须被判不出来（否则断言形同虚设）
    $bad = $fixtureOld.Replace("'$ExpectedPackage'", "'com.dsh.screenocr'").Replace("'$ExpectedVersion'", "'1.3'").Replace("versionCode='$ExpectedVersionCode'", "versionCode='4'")
    $b2 = Get-Badging $bad
    if ($b2.package -ne $ExpectedPackage -and $b2.versionName -ne $ExpectedVersion -and $b2.versionCode -ne $ExpectedVersionCode) {
        Pass '自检·aapt2 badging 解析（负样本会被判不通过）' ("package={0} versionCode={1} versionName={2}" -f $b2.package, $b2.versionCode, $b2.versionName)
    } else { Fail '自检·aapt2 badging 解析（负样本会被判不通过）' '负样本未被识别'; $ok = $false }

    $g = "android {`n    namespace = `"$ExpectedNamespace`"`n    defaultConfig {`n        applicationId = `"$ExpectedPackage`"`n        versionCode = $ExpectedVersionCode`n        versionName = `"$ExpectedVersion`"`n    }`n}"
    $v1 = Get-BuildGradleValue $g 'applicationId'; $v2 = Get-BuildGradleValue $g 'versionName'; $v3 = Get-BuildGradleValue $g 'namespace'
    if ($v1 -eq $ExpectedPackage -and $v2 -eq $ExpectedVersion -and $v3 -eq $ExpectedNamespace) {
        Pass '自检·build.gradle.kts 取值解析' ("applicationId={0} versionName={1} namespace={2}" -f $v1, $v2, $v3)
    } else { Fail '自检·build.gradle.kts 取值解析' ("applicationId={0} versionName={1} namespace={2}" -f $v1, $v2, $v3); $ok = $false }

    $gradleKts = Join-Path $ProjectDir 'app\build.gradle.kts'
    if (Test-Path $gradleKts) {
        $real1 = Get-BuildGradleValue (Read-Utf8Text $gradleKts) 'applicationId'
        if ($real1) { Pass '自检·UTF-8 读取真实 build.gradle.kts 能取到 applicationId（防 GBK 误读回归）' "applicationId=$real1" }
        else { Fail '自检·UTF-8 读取真实 build.gradle.kts 能取到 applicationId（防 GBK 误读回归）' '取值为空 → 读文件编码或正则有问题'; $ok = $false }
    } else { Warn '自检·真实 build.gradle.kts' "找不到 $gradleKts（跳过）" }

    $aapt2 = Get-ChildItem (Join-Path $BuildToolsDir '*\aapt2.exe') -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1
    if ($aapt2) { Pass '自检·aapt2.exe 存在' $aapt2.FullName } else { Fail '自检·aapt2.exe 存在' "在 $BuildToolsDir 下未找到"; $ok = $false }

    $script:Results.Clear()
    if ($ok) { Write-Host "`n自检结论: 通过"; exit 0 } else { Write-Host "`n自检结论: 不通过"; exit 1 }
}

# --------------------------------------------------------- 1. 环境事实 ----
Write-Host ("独立验证 · APK 构建与产物  {0}" -f (Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))
Write-Host ('口径：docs\接口约定-SoM与解析服务.md §5（applicationId={0}, versionName={1}）' -f $ExpectedPackage, $ExpectedVersion)

if (Test-Path $ProjectDir) { Pass '项目目录存在' $ProjectDir } else { Fail '项目目录存在' $ProjectDir }
if (Test-Path (Join-Path $JavaHome 'bin\java.exe')) { Pass 'JAVA_HOME 下 java.exe 存在' $JavaHome } else { Fail 'JAVA_HOME 下 java.exe 存在' $JavaHome }
if (Test-Path $GradleBat) { Pass 'gradle.bat 存在' $GradleBat } else { Fail 'gradle.bat 存在' $GradleBat }

$aapt2 = Get-ChildItem (Join-Path $BuildToolsDir '*\aapt2.exe') -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1
if ($aapt2) { Pass 'aapt2.exe 存在' $aapt2.FullName } else { Fail 'aapt2.exe 存在' "在 $BuildToolsDir 下未找到" }

# ------------------------------------------- 1.5 ICU 兼容性静态检查（R0）----
# 为什么必须有：Android 的 java.util.regex 底层是 ICU，不认 Java 专有内联标志（(?U)/(?d)/(?u)）等；
# 宿主机 JDK 却支持 —— 本仓库曾因此漏掉一次「真机启动即崩」（PatternSyntaxException 发生在服务构造函数里）。
# 所以每次产物验证都先静态扫一遍 app/**/*.kt。检查器：verify\check_regex_icu.py（含 --selftest）。
if ($SkipRegexScan) {
    Warn 'R0 ICU 兼容性静态检查' '按 -SkipRegexScan 跳过（**不推荐**：宿主机能跑不代表真机能跑）'
} else {
    $icu = Join-Path $ProjectDir 'verify\check_regex_icu.py'
    if (-not (Test-Path $icu)) {
        Warn 'R0 ICU 兼容性静态检查' "找不到 $icu（跳过）"
    } else {
        if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
        $icuLog = Join-Path $OutDir 'regex-icu-scan.log'
        $icuOut = (cmd /c "python `"$icu`" --root app 2>&1")
        $icuCode = $LASTEXITCODE
        try { $icuOut | Set-Content -Path $icuLog -Encoding UTF8 } catch {}
        if ($icuOut -match 'not recognized|找不到|No such file|不是内部或外部命令') {
            Warn 'R0 ICU 兼容性静态检查' "python 不可用，未能扫描（详见 $icuLog）"
        } elseif ($icuCode -eq 0) {
            Pass 'R0 ICU 兼容性静态检查（无 P0/P1）' ("退出码 0；末行={0}；日志={1}" -f ($icuOut | Select-Object -Last 1), $icuLog)
        } else {
            Fail 'R0 ICU 兼容性静态检查' ("发现 Android/ICU 不支持的 Java 专有正则构造（退出码 {0}）→ 真机可能启动即崩；详见 {1}" -f $icuCode, $icuLog)
        }
    }
}

if (Test-Path (Join-Path $JavaHome 'bin\java.exe')) {
    # 经 cmd 调用以免 java -version 写 stderr 被 PowerShell 记成 NativeCommandError 噪声
    $jv = (cmd /c "`"$JavaHome\bin\java.exe`" -version 2>&1") -join ' | '
    Write-Host ("      · java -version: {0}" -f $jv.Trim())
}

# ----------------------------------------------------- 2. 源码侧静态核对 ----
$gradleKts = Join-Path $ProjectDir 'app\build.gradle.kts'
if (Test-Path $gradleKts) {
    $kts = Read-Utf8Text $gradleKts
    $declApp = Get-BuildGradleValue $kts 'applicationId'
    $declVer = Get-BuildGradleValue $kts 'versionName'
    $declNs  = Get-BuildGradleValue $kts 'namespace'
    if ($declApp -eq $ExpectedPackage) { Pass 'S1 源码 applicationId == 契约值' "app\build.gradle.kts: applicationId=$declApp" }
    else { Fail 'S1 源码 applicationId == 契约值' "期望 $ExpectedPackage，实际 $declApp（app\build.gradle.kts）" }
    if ($declVer -eq $ExpectedVersion) { Pass 'S2 源码 versionName == 契约值' "app\build.gradle.kts: versionName=$declVer" }
    else { Fail 'S2 源码 versionName == 契约值' "期望 $ExpectedVersion，实际 $declVer（app\build.gradle.kts）" }
    if ($declNs -eq $ExpectedNamespace) { Pass 'S3 源码 namespace 保持 com.dsh.screenocr（契约 §5）' "namespace=$declNs" }
    else { Fail 'S3 源码 namespace 保持 com.dsh.screenocr（契约 §5）' "期望 $ExpectedNamespace，实际 $declNs" }
} else { Fail 'S1-S3 源码核对' "找不到 $gradleKts" }

# --------------------------------------------------------- 3. gradle 构建 ----
$apkPath = $null
$buildStart = Get-Date
$gradleTasks = @()
if ($Clean) { $gradleTasks += 'clean' }
$gradleTasks += 'assembleDebug'
if ($SkipBuild) {
    Warn 'G1 gradle assembleDebug' '按 -SkipBuild 跳过构建，只核对已有 APK（本次结论不代表本次改动可构建）'
} else {
    $logPath = Join-Path $OutDir 'gradle-assembleDebug.log'
    if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
    $env:JAVA_HOME = $JavaHome
    Write-Host ("      · 命令: `$env:JAVA_HOME='{0}'; & '{1}' -p '{2}' {3} --console=plain" -f $JavaHome, $GradleBat, $ProjectDir, ($gradleTasks -join ' '))
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    & $GradleBat -p $ProjectDir @gradleTasks --console=plain *>&1 | Tee-Object -FilePath $logPath | Out-Null
    $code = $LASTEXITCODE
    $sw.Stop()
    Write-Host ("      · gradle 退出码={0}  耗时={1:N1}s  日志={2}" -f $code, $sw.Elapsed.TotalSeconds, $logPath)
    if ($code -eq 0) { Pass 'G1 gradle 构建退出码 0' ("exit={0}，任务 [{1}]，耗时 {2:N1}s" -f $code, ($gradleTasks -join ' '), $sw.Elapsed.TotalSeconds) }
    else { Fail 'G1 gradle 构建退出码 0' ("exit={0}，任务 [{1}]，耗时 {2:N1}s，详见 {3}（tail: {4}）" -f $code, ($gradleTasks -join ' '), $sw.Elapsed.TotalSeconds, $logPath, ((Get-Content $logPath -Tail 3 -Encoding UTF8 -ErrorAction SilentlyContinue) -join ' / ')) }
    if ($Clean) {
        $upToDate = @(Select-String -Path $logPath -Pattern 'Task :app:assembleDebug UP-TO-DATE' -ErrorAction SilentlyContinue).Count
        if ($upToDate -gt 0) { Warn 'G2 clean 后 assembleDebug 不应 UP-TO-DATE' "日志出现 $upToDate 处 UP-TO-DATE 提示；产物 mtime 判定可能失真" }
        else { Pass 'G2 clean 后 assembleDebug 未走 UP-TO-DATE' 'clean + assembleDebug 已强制重编译' }
    }
}

# --------------------------------------------------------- 4. APK 产物 ----
$candidates = @()
$primary = Join-Path $ProjectDir 'app\build\outputs\apk\debug'
if (Test-Path $primary) { $candidates += Get-ChildItem (Join-Path $primary '*.apk') -ErrorAction SilentlyContinue }
if (-not $candidates) { $candidates += Get-ChildItem (Join-Path $ProjectDir 'app\build\outputs\apk') -Recurse -Filter '*.apk' -ErrorAction SilentlyContinue }
if ($candidates.Count -gt 0) {
    $apk = $candidates | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $apkPath = $apk.FullName
    $hash = (Get-FileHash $apkPath -Algorithm SHA256).Hash
    Pass 'A1 找到 APK 产物' ("{0}  {1} 字节  mtime={2}  SHA256={3}" -f $apkPath, $apk.Length, $apk.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'), $hash)
    if (-not $SkipBuild) {
        if ($apk.LastWriteTime -ge $buildStart.AddSeconds(-5)) { Pass 'A2 APK 是本次构建产物（mtime 不早于构建开始）' ("mtime={0} buildStart={1}" -f $apk.LastWriteTime, $buildStart) }
        else { Fail 'A2 APK 是本次构建产物（mtime 不早于构建开始）' ("mtime={0} 早于 buildStart={1}，可能是旧产物" -f $apk.LastWriteTime, $buildStart) }
    }
    if ($ExpectedSha256) {
        if ($hash -eq $ExpectedSha256.ToUpper()) {
            Pass 'A3 APK SHA256 == Lead 验收的产物 hash' ("$hash（$($apk.Length) 字节）")
        } else {
            Fail 'A3 APK SHA256 == Lead 验收的产物 hash' ("**Lead 验收的不是这个产物**：期望 $($ExpectedSha256.ToUpper())，实际 $hash（本次 $($apk.Length) 字节，mtime $($apk.LastWriteTime)）")
        }
    }
} else {
    Fail 'A1 找到 APK 产物' ("在 {0} 下未找到 *.apk" -f (Join-Path $ProjectDir 'app\build\outputs\apk'))
}

# ------------------------------------------------------- 5. aapt2 badging ----
if ($apkPath -and $aapt2) {
    $badgingLog = Join-Path $OutDir 'aapt2-badging.txt'
    # aapt2 输出是 UTF-8；不设 [Console]::OutputEncoding 时 PS 5.1 会按 GBK 解码，中文 label 变乱码
    $prevEnc = [Console]::OutputEncoding
    try {
        [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
        $badgingOut = (& $aapt2.FullName dump badging $apkPath 2>&1 | Out-String)
        $badgingCode = $LASTEXITCODE
    } finally {
        try { [Console]::OutputEncoding = $prevEnc } catch {}
    }
    try { $badgingOut | Set-Content -Path $badgingLog -Encoding UTF8 } catch {}
    if ($badgingCode -eq 0) { Pass 'B1 aapt2 dump badging 退出码 0' "日志=$badgingLog" }
    else { Fail 'B1 aapt2 dump badging 退出码 0' ("exit={0}，输出: {1}" -f $badgingCode, ($badgingOut -replace "`r?`n", ' ').Substring(0, [Math]::Min(300, $badgingOut.Length))) }

    $b = Get-Badging $badgingOut
    Write-Host ("      · badging: package={0} versionCode={1} versionName={2} sdk={3} target={4} label={5}" -f $b.package, $b.versionCode, $b.versionName, $b.sdkVersion, $b.targetSdkVersion, $b.label)
    if ($b.package -eq $ExpectedPackage) { Pass 'B2 APK package == 契约值' "实际 $($b.package)" }
    else { Fail 'B2 APK package == 契约值' "期望 $ExpectedPackage，实际 $($b.package)" }
    if ($b.versionName -eq $ExpectedVersion) { Pass 'B3 APK versionName == 契约值' "实际 $($b.versionName)" }
    else { Fail 'B3 APK versionName == 契约值' "期望 $ExpectedVersion，实际 $($b.versionName)" }
    if (-not $ExpectedVersionCode -or $b.versionCode -eq $ExpectedVersionCode) { Pass 'B4 APK versionCode' "实际 $($b.versionCode)" }
    else { Fail 'B4 APK versionCode' "期望 $ExpectedVersionCode，实际 $($b.versionCode)" }
    if ($b.sdkVersion -eq "$ExpectedMinSdk") { Pass 'B5 minSdk' "实际 $($b.sdkVersion)" }
    else { Fail 'B5 minSdk' "期望 $ExpectedMinSdk，实际 $($b.sdkVersion)" }
    if ($b.targetSdkVersion -eq "$ExpectedTargetSdk") { Pass 'B6 targetSdk' "实际 $($b.targetSdkVersion)" }
    else { Fail 'B6 targetSdk' "期望 $ExpectedTargetSdk，实际 $($b.targetSdkVersion)" }
    if ($b.package -eq 'com.dsh.screenocr') { Warn 'B7 与原 APK 并存安装' 'package 仍为 com.dsh.screenocr，无法与原 v1.3 并存安装（契约 §5 要求改为 com.dsh.screenocr.omni）' }
} elseif (-not $apkPath) {
    Fail 'B1-B6 aapt2 核对' '没有 APK 可核对'
} else {
    Fail 'B1-B6 aapt2 核对' 'aapt2.exe 缺失'
}

exit (Dump-Summary)
