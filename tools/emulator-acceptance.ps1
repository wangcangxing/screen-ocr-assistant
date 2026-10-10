# 真机（MuMu 模拟器 emulator-5554）端到端验收脚本 —— Lead 独占运行，勿与其他 adb 操作并发
#
# 前置：主机上已跑 ① mock 大模型服务（8080）② 解析服务（8010，mock 或 real 均可）
# 用法：pwsh -File tools\emulator-acceptance.ps1
# 产出：控制台日志 + tools\acceptance-out\ 下的 logcat / 截图 / 收到的标注图副本
$ErrorActionPreference = 'Stop'
$adb = 'C:\Users\23106\AppData\Local\Android\platform-tools\adb.exe'
$root = Split-Path -Parent $PSScriptRoot
$pkg = 'com.dsh.screenocr.omni'
$outDir = Join-Path $PSScriptRoot 'acceptance-out'
New-Item -ItemType Directory -Path $outDir -Force | Out-Null

function Step($msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }

Step "0. 环境：设备 / 端口转发 / APK"
& $adb devices -l
& $adb reverse tcp:8080 tcp:8080
& $adb reverse tcp:8010 tcp:8010
$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) { throw "APK 不存在：$apk（先跑 gradle assembleDebug）" }
"HAPK = $apk ($((Get-Item $apk).Length) 字节)"

Step "1. 安装新包（com.dsh.screenocr.omni，与原 v1.3 并存）"
& $adb install -r $apk

Step "2. 推送 config.json（必须在启动前推：自动导入只在启动那一瞬试一次）"
& $adb shell "mkdir -p /sdcard/Android/data/$pkg/files"
& $adb push (Join-Path $PSScriptRoot 'config.omni.mock.json') "/sdcard/Android/data/$pkg/files/config.json"

Step "3. 只启用新包的无障碍服务（停用原包，避免两个服务同时答题）"
& $adb shell "settings put secure enabled_accessibility_services $pkg/com.dsh.screenocr.ScreenOcrAccessibilityService"
& $adb shell "settings put secure accessibility_enabled 1"
& $adb shell am force-stop com.dsh.screenocr
& $adb shell settings get secure enabled_accessibility_services

Step "4. 清空 logcat 并启动 App（触发自动导入配置）"
& $adb logcat -c
& $adb shell am start -n "$pkg/com.dsh.screenocr.MainActivity"
Start-Sleep -Seconds 6
& $adb shell input keyevent 66     # 关掉启动告示弹窗

Step "5. 打开模拟服务的题库页（题目在浏览器里）"
& $adb shell am start -a android.intent.action.VIEW -d "http://127.0.0.1:8080/quiz"
Write-Host "等待 30 秒，让轮询发现题目并走完链路…" -ForegroundColor Yellow
Start-Sleep -Seconds 30

Step "6. 收集证据"
& $adb logcat -d -s ScreenOcr | Out-File -Encoding utf8 (Join-Path $outDir 'logcat-ScreenOcr.txt')
# 注意：pwsh 的 `>` 会把二进制当文本写坏，截图必须交给 cmd 重定向
$png = (Join-Path $outDir 'screen.png')
cmd /c "`"$adb`" exec-out screencap -p > `"$png`""
"前台窗口："; & $adb shell "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'" 2>&1 | Select-Object -First 4
$received = Join-Path $PSScriptRoot 'mock-received-image.jpg'
if (Test-Path $received) { Copy-Item $received (Join-Path $outDir 'llm-received-image.jpg') -Force }
Get-ChildItem $outDir | Select-Object Name, Length

Step "日志里的 SoM / 解析服务 / 点击关键行"
& $adb logcat -d -s ScreenOcr | Select-String -Pattern 'SoM|解析服务|元素编号|识别源|题目判定|大模型返回|点击结果|收尾' | ForEach-Object { $_.Line }
