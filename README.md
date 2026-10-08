# 屏幕答题助手（ScreenOcrAssistant）

Android 无障碍辅助应用：**截屏 → 端侧 OCR 取字 → 本地启发式判断「像不像一道题」→ 调用 OpenAI 兼容大模型接口拿答案 → 用无障碍能力点中对应选项。**

支持把**屏幕截图直接发给多模态模型**（视觉模式）。全部识别与决策都在本机发起；**只有识别出的文字、以及（开启视觉模式时）压缩后的截图会发送到你配置的接口**，不做任何其他上传。

## 工作流程

```
两条触发路径（互相排斥，共用同一套分析流程）
  A. 无障碍事件（内容变化/窗口变化/滚动）→ 去抖 1200ms
  B. 定时轮询（默认 2000ms 一次）—— 视频/动画/游戏画面靠它
     · 轮询会自行跳过「本应用自己」与 systemui（用窗口列表里 isFocused 的窗口判定）
      └─ AccessibilityService.takeScreenshot()  → Bitmap (整屏)
          └─ 识别来源（按顺序）：
             ① **无障碍节点树**（NodeReader 把节点伪装成 OcrResult）—— 原生控件与 WebView 里的文字都能读到
                （弹题实测：判断题/题干/A/对/B/错/关闭 全带 bounds）；不受「模拟器上 takeScreenshot 返回黑图」影响；
                选项标签零误读；坐标即 getBoundsInScreen()。读不到可用文字时 →
             ② ML Kit 中文 OCR（端侧离线，可降采样省 CPU）→ 文字行 + 包围盒
              └─ 本地启发式打分（阈值默认 3）→ 是否「疑似题目」
                  └─ 题目去重（排序后选项的编辑距离相似度：12s 内 0.82，之后 0.90；窗口 45s）
                      └─ 视觉模式? 截图 → JPEG(长边≤1600) → base64
                          └─ POST /chat/completions（text 块 + image_url 块，reasoning_effort）
                              └─ 解析回复（纯文本「B」或 JSON 两种协议都支持）
                                  └─ 定位选项：无障碍节点 ACTION_CLICK ↺ 回退 OCR 框中心坐标点击
                                      （点击后 3s 冷却，兜住漏判导致的重复点击）
                                      └─ 答完收尾：等 1.2s 点「关闭」把结果浮层关掉
                                         （不关的话浮层一直挡着视频、之后每轮轮询都重复看到这道题；
                                           查按钮走**节点遍历** —— WebView 里按文本查找的 API 找不到，见坑点 #37）
```

每一步都写日志，应用内「运行日志」区可看（长按可复制），也可 `adb logcat -s ScreenOcr`。

## 视频 / 动画里随机出现的题目（重要）

**为什么原来发现不了**：视频、动画、游戏、`Canvas` 的画面是直接绘制的，**无障碍节点树不会变化**，因此一个 `TYPE_WINDOW_CONTENT_CHANGED` 事件都不会发出。只靠事件触发时，应用根本不会去截图。

**实测对照**（Canvas 上画出的题目，显示 20 秒）：

| 配置 | 截图次数 | 点击次数 |
|---|---|---|
| `轮询间隔 = 0`（只靠事件） | **0** | **0** |
| `轮询间隔 = 2000` | 11 | **1**（其余 10 次被去重挡下） |

**怎么调**：

- **轮询间隔**（默认 2000ms）：题目只出现几秒就消失时调小（如 `800`），代价是更费电；画面基本静止时调大或设 `0` 关闭。
- **OCR 缩放**（默认 100%）：轮询会持续跑 OCR，调到 `70` 左右可以明显省 CPU，代价是画面里的小字可能漏认。
- **同一屏去重时长**（默认 45s）：要大于题目在画面里停留的最长时间，否则同一道题会被重复调用接口。

**一条场景限制，需要你知道**：视频里画出来的选项**没有可点节点**，应用只能按 OCR 框中心做坐标点击。如果这个视频本身不接受点击（纯播放），点一下只会落在画面上、不会真正提交答案；这种情况下应用能做的是**识别题目并给出答案**（答案写在运行日志里）。

## 环境要求

| 项 | 要求 | 依据 |
|---|---|---|
| 设备 | **Android 11（API 30）及以上，未设上限** | `AccessibilityService.takeScreenshot` 是 API 30 引入 |
| 接口 | OpenAI 兼容 `/chat/completions` 与 `/models` | 视觉与模型发现按 DeepSeek 官方文档实现 |

### 支持的安卓版本

`minSdkVersion = 30`（Android 11）、`targetSdkVersion = 35`（Android 15）、无 `maxSdkVersion`。

| Android | API | 验证状态 |
|---|---|---|
| 11 | 30 | 配置支持（下限，由 `takeScreenshot` 决定），**未实测** |
| 12 / 12L | 31 / 32 | **31 已实测**（OPPO PCRM00 / 1080×2400 / ColorOS） |
| 13 / 14 / 15 | 33 / 34 / 35 | 配置支持，**未实测** |
| 16+ | 36+ | **36 已实测**（vivo V2338A / 1260×2800 / Android 16） |

即「两端已验证、中间按配置支持」。要支持 Android 10 及以下，必须把截图方案从 `AccessibilityService.takeScreenshot` 换成 `MediaProjection`（需额外录屏授权 + 前台服务通知）。

**Android 15/16 的边到边适配已处理**：根布局用 `fitsSystemWindows` + `clipToPadding`，内容不会被状态栏/标题栏压住；启动时键盘不会自动弹出。

**OEM 提醒**：ColorOS / MIUI / vivo 等定制系统常会回收后台应用，建议把本应用加入「自启动 / 后台运行」白名单。实测 ColorOS 不允许 adb 修改无障碍开关（必须手动开），而 vivo 允许。

## 构建

本机已验证：JDK 17（`D:\JAVA\JAVA17`）、Android SDK `D:\Android\sdk`（platform 35 + build-tools 35）、Gradle 8.11.1（`D:\Android\gradle`）。

```powershell
$env:JAVA_HOME='D:\JAVA\JAVA17'
& 'D:\Android\gradle\gradle-8.11.1\bin\gradle.bat' -p 'D:\program\screen-ocr-assistant' assembleRelease

# 产物（单个 APK，未做 ABI 拆分）：
#   app\build\outputs\apk\release\app-release.apk
adb install -r -t app\build\outputs\apk\release\app-release.apk
```

**签名**：正式签名信息放在 `local.properties`（不入库），密钥库在 `keystore/screenocr-release.jks`（同样不入库）。

```properties
release.storeFile=keystore/screenocr-release.jks
release.storePassword=…
release.keyAlias=screenocr
release.keyPassword=…
```

缺这段配置时 release 会自动退回 debug 签名，保证任何机器上都能构建。

**关于体积**：APK 约 49 MB，其中约 39 MB 是 ML Kit 的 `libmlkit_google_ocr_pipeline.so` 在四种 ABI（arm64-v8a / armeabi-v7a / x86 / x86_64）下的副本。要瘦身可在 `defaultConfig` 里加 `ndk { abiFilters += "arm64-v8a" }`（代价：32 位机与 x86 模拟器装不上），或改用 `play-services-mlkit-text-recognition-chinese` 让模型改为运行时下载。

**换密钥后无法盖装**：如果之前装的是 debug 版，装正式版会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，需先 `adb uninstall com.dsh.screenocr`（卸载会一并关掉无障碍开关，要重新打开一次）。

## 使用步骤

1. 打开应用 → 点「打开无障碍设置」→ 找到「屏幕答题助手」→ 打开开关 → 允许。
2. 填「接口地址」（如 `https://api.deepseek.com/v1`）和「API Key」→ 点「保存配置」。
3. 点「**获取可用模型**」→ 从接口真实返回的列表里选一个模型（会显示是否 `[可看图片]`、上下文长度、可用推理强度）。
   - 选到带 image 模态的模型 → **自动打开视觉模式**
   - 按该模型的 `effort.supported_levels` **自动把推理强度落到低档**（优选 `low`）
   - 也可用「测试接口连通性」先确认能通。
4. 回到目标 App 正常答题，助手会自动识别并点击。

### 参数

| 参数 | 默认 | 说明 |
|---|---|---|
| 接口地址 | `https://api.deepseek.com/v1` | 保存前会校验是否为合法 http(s) 地址 |
| 推理强度 | `low` | 思考模式 effort。官方默认是 `high`，本应用主动降档求快与省；留空=不发送该字段 |
| 视觉模式 | 关 | 开=随请求发送截图；需模型支持 image 模态 |
| 发图最长边 | 1600 px | 只影响上传体积（0=不缩放）。服务端还会自己缩到约 1300×1300，每图最多 1024 token |
| **轮询间隔** | **2000 ms** | **视频/动画场景的核心开关**；`0` = 关闭轮询只靠无障碍事件 |
| **OCR 缩放** | **100 %** | 轮询时调低（如 70）可省 CPU，代价是小字可能漏认 |
| 题目判定阈值 | 3 | 本地启发式分值达到多少才调用接口，调高更保守 |
| 截图去抖 | 1200 ms | 无障碍事件路径下，屏幕变化后等多久再截图 |
| 点击所需最低置信度 | 0.5 | 纯文本协议下标签可识别时给 0.9 |
| 同一屏去重时长 | 45 s | 同一道题在此时间内不重复作答；**需大于题目在画面里的停留时间** |
| 接口超时 | 30000 ms | |

## 预设提示词

**只输出选项、不输出任何其他内容**：

```
你是答题助手。用户会给你一段从手机屏幕上 OCR 识别出来的文字。
如果其中包含需要作答的题目，只输出正确选项的字母（例如 B）或编号（例如 2）。
只输出这一个字符：不要解释、不要复述题干、不要加标点、不要输出任何其他内容。
如果这段文字里没有需要作答的题目，只输出 NONE。
```

- 改过提示词后想回到这版：点「恢复默认提示词」。
- 提示词版本升级时**只自动升级「没被用户改过」的安装**。
- 解析器同时兼容旧版 JSON 协议，把提示词改回「输出 JSON」也能用。
- 保留 `NONE` 出口是刻意的：本地启发式只是「疑似题目」，需要模型给一个明确的否决信号，否则模型会被迫在非题目画面上硬选一个选项。

## 从文件导入配置（可选）

不想在手机上敲一长串 Key 时可用 adb 部署：

```powershell
# 字段：base_url / model / api_key / system_prompt（只覆盖写了且非空的字段）
adb push my-config.json /sdcard/Android/data/com.dsh.screenocr/files/config.json
```

首次运行且还没配 Key 时自动导入一次；之后可用界面上的「从文件导入配置」。

## 视觉模式的协议细节（按官方文档实现）

- `user.content` 必须是**块数组**：`[{"type":"text",...},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,...","detail":"high"}}]`
- **图片只能放在 `user` 消息里**（`system`/`assistant` 带图会被 400 拒绝）——本应用的系统提示词是纯文本，不受影响
- 支持格式 JPEG/PNG/GIF/WebP，按文件实际内容判断；单图 ≤32 MiB、请求体 ≤48 MiB
- 本应用截图经 JPEG（质量 85）压缩，实测 1080×2400 缩到 720×1600 后约 **101 KB**，远低于限制

## 已知限制（如实说明）

- **点不到就不点**：定位不到与答案匹配的选项时直接放弃，宁可不点也不点错。
- **判断题**：**已支持**。节点树路径下 `A` 与 `对` 会合回 `A. 对`，因此能识别成组字母选项并点击（实测 MuMu 上答对；OCR 路径下仍依赖「A. / (A) / ① / 1.」这类标记，缺标记时只给答案不点击）。
- **OCR 不是逐字确定的**：同一画面两次识别会出现「氢/氯」「关于/美于」这类抖动，行序也会变。去重因此用**编辑距离相似度**而非精确匹配，选项键按标签排序。选项正文换行的后半句可能不在选项里，匹配时靠子串包含兜底。
- **视觉模式的流量**：每次提问会带一张约 100~140 KB 的 base64 图片；只在本地判定为「疑似题目」且未命中去重时才会发。
- **答案只在日志里**：没有做悬浮窗/通知栏展示（避免再申请悬浮窗权限）。
- **无网络时**：调用失败写日志；失败后同一题 10 秒后允许重试。

## 目录结构

```
app/src/main/java/com/dsh/screenocr/
  MainActivity.kt                  配置界面（状态、参数、模型列表选择、日志）
  ScreenOcrAccessibilityService.kt 主流程编排 + JPEG 编码 + 点击执行
  OcrEngine.kt                     ML Kit 中文 OCR 封装
  QuestionDetector.kt              本地启发式：像不像一道题 + 抽选项
  LlmClient.kt                     /chat/completions + /models，双协议回复解析
  TextMatch.kt                     选项标签归一化、相似度（编辑距离/最长公共子串）
  ClickPlanner.kt                  答案 → 屏幕可点目标（节点点击 ↺ 坐标点击）
  Prefs.kt                         全部可调参数 + 预设提示词与版本迁移
  ConfigFile.kt                    外部 config.json 导入
  AppLog.kt                        应用内日志环形缓冲 + logcat
app/src/main/res/
  xml/accessibility_service_config.xml  无障碍能力声明（截图/手势/节点）
  layout/activity_main.xml              配置界面布局
tools/
  mock_llm_server.py       本地模拟服务：/v1/chat/completions、/models、/quiz 测试页；
                           会解码并保存收到的图片，便于核对视觉协议
  quiz-test.html           真机验证用的中文选择题页
  install-sdk.ps1          下载安装 Android SDK 到 D:\Android\sdk
  analyze_key.py           用真实请求重建去重键并比较相似度，用于定阈值
  diff_requests.py         逐行 diff 相邻请求，定位 OCR 抖动
  kill-app.sh              以应用 uid 结束进程的尝试（本机 SELinux 拒绝，未成功）
  config.mock.json / prefs.default.xml / mock-received-image.jpg  测试用
```

---

## 许可（License）

本项目以 **PolyForm Noncommercial License 1.0.0** 发布：**源码公开，禁止商业使用**。完整条款见 [LICENSE](LICENSE)。

需要商用授权请联系作者。说明：该协议**不是** OSI 认可的开源协议（OSI 要求允许商用），准确说法是「源码公开 + 非商业许可」。
