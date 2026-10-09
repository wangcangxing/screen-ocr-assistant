package com.dsh.screenocr

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 主流程：无障碍事件 -> 去抖 -> 截图 -> OCR -> 题目判定 -> 大模型 -> 匹配选项 -> 点击。
 * 每一步都写 AppLog，便于在应用内界面或 `adb logcat -s ScreenOcr` 里核对走到了哪一步。
 */
class ScreenOcrAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val ocr = OcrEngine()

    /** 点完答案后等多久再关结果浮层 —— 留给平台渲染判定结果。 */
    private val ANSWER_SETTLE_MS = 1200L

    /** 点完选项后等多久再找「提交作答」（有些题型选完必须提交才判定）。 */
    private val SUBMIT_DELAY_MS = 700L

    /**
     * 收尾找「关闭」的重试次数与间隔。
     * 为什么必须重试：实测答题后 WebView 会短暂重绘，那一刻节点树里**连「关闭」和选项都消失**
     * （日志表现为「收尾：没找到关闭类按钮」+「依据：无；选项 0 个」），一两秒后才回来。
     * 只赌一次就会漏关浮层 —— 16416 实例上实测踩到过。
     */
    private val CLOSE_MAX_ATTEMPTS = 4
    private val CLOSE_RETRY_MS = 1500L

    /** 缓存的「关闭」按钮位置最多用这么久（避免拿上一次弹题的坐标去点） */
    private val CLOSE_CACHE_TTL_MS = 30_000L

    /**
     * 读题那一刻缓存下来的「关闭」类按钮（label + 屏幕坐标）。
     *
     * 为什么必须有它：实测**点完选项后浮层会从无障碍树里彻底消失**（屏幕上还在，用户截图可见），
     * 那一刻实时查找必然失败 —— 重试多少次都没用（16416 实例实测重试 4 次全败，
     * 日志同时打「没找到关闭」和「选项 0 个」）。
     * 而同一道题的浮层里「关闭」位置不会变，所以在**读题时**先把它记下来，收尾直接用。
     */
    private var cachedClose: Pair<String, Rect>? = null
    private var cachedCloseAt = 0L

    /**
     * 点击坐标的最大随机偏移（像素），**防检测**。
     *
     * 每次都在元素正中心、且同一按钮每次落在同一个整数坐标，是自动化最明显的特征之一。
     * 取 10px：实测本类界面的可点元素都远大于它 —— 选项框约 828×135、"提交作答"约 852×144、
     * 连最小的「关闭」也有 87×45（半高 22px），所以 ±10 不会偏到相邻选项。
     */
    private val JITTER_MAX_PX = 10
    private val jitterRandom = java.util.Random()
    private val busy = AtomicBoolean(false)
    private var pending: Runnable? = null

    /** JPEG 编码专用线程：主线程上压缩 1080x2400 会卡顿 */
    private val imageEncoder = Executors.newSingleThreadExecutor { r -> Thread(r, "shot-encode") }

    /** 已处理过的屏幕指纹 -> 处理时刻，避免同一屏反复调用接口 */
    private val recent = LinkedHashMap<String, Long>()

    private var lastShotAt = 0L
    private var lastScaleWarnAt = 0L
    private var lastFgDesc = ""

    /** API 对截图有频率限制，两次截图至少隔这么久 */
    private val minShotIntervalMs = 700L

    /** 接口失败时，该屏多少毫秒后才允许重试 */
    private val failureRetryDelayMs = 10_000L

    /**
     * 题目去重阈值，分两档：
     *  - 刚答完的 [RECENT_WINDOW_MS] 内放宽到 [SIMILARITY_THRESHOLD_RECENT]：这段时间画面里还是同一道题，
     *    逐帧 OCR 抖动最大，用严格阈值会漏判、导致同一题被重复调用接口 / 重复点击（实测踩过 3.7 秒内点两次）。
     *  - 之后恢复 [SIMILARITY_THRESHOLD]，避免把「选项措辞相近的新题」误当成已答过。
     */
    private val SIMILARITY_THRESHOLD = 0.90
    private val SIMILARITY_THRESHOLD_RECENT = 0.82
    private val RECENT_WINDOW_MS = 12_000L

    /**
     * 两次点击之间的最小间隔。
     * 去重靠的是「选项文字的编辑距离相似度」，而逐帧 OCR 抖动偶尔会让同一道题的相似度
     * 掉到阈值以下（实测 Canvas 场景出现过 3.7 秒内点两次）。这道冷却不依赖内容比对，
     * 直接兜住这类重复点击。取得比较短，不会挡住在它之后出现的新题。
     */
    private val clickCooldownMs = 3_000L
    private var lastClickAt = 0L

    private val WHITESPACE = Regex("\\s+")

    /** 状态栏时间之类每分钟都在变的内容 */
    private val CLOCK = Regex("\\d{1,2}:\\d{2}")

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // 关键：允许获取**所有**交互窗口的节点树。
        // 实测（16416）：屏幕上明明有浮层，mCurrentFocus/mFocusedApp 却是 null，
        // rootInActiveWindow 于是退化成桌面窗口 —— 不开这个 flag，浮层所在的独立窗口根本读不到。
        runCatching {
            val info = serviceInfo
            if (info != null) {
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                serviceInfo = info
                AppLog.i("已开启「获取所有交互窗口」标志（读取浮层所在的独立窗口所必需）")
            }
        }
        val caps = serviceInfo?.capabilities ?: 0
        val canShot = caps and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0
        val canGesture = caps and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0
        AppLog.i("无障碍服务已连接：capabilities=$caps（可截图=$canShot，可手势=$canGesture）")
        if (!canShot) {
            AppLog.w("服务未获得截图能力，请确认 accessibility_service_config.xml 中 canTakeScreenshot=\"true\"")
        }
        startPolling()
    }

    /**
     * 当前要读的窗口根节点 —— **取所有 display 上的窗口**。
     *
     * 关键坑（实测 16416）：MuMu 这类模拟器会把应用渲染在**非默认 display** 上
     * （`dumpsys input_method` 里 client 的 `displayId=7`），此时 `getWindows()` **只返回默认 display**
     * 的窗口 —— 也就是桌面与系统栏，智慧树的窗口一个都读不到，而 `dumpsys window` 里它明明有焦点：
     *   `mCurrentFocus=Window{… StudyCourseVideoActivity}`、`mFocusedWindow=Window{…}`
     * （`dumpsys window` 打印的是**所有** display，所以看起来"焦点正常"，App 却瞎了。）
     *
     * `getWindowsOnAllDisplays()`（API 30+，本项目 minSdk 30）才是对的。
     */
    /**
     * 所有 display 上的窗口。
     *
     * `getWindowsOnAllDisplays()` 返回的是 `SparseArray<List<AccessibilityWindowInfo>>`（key = displayId），
     * **它没有 `values()`**，只能按下标摊平（别照搬 `windows` 的写法）。
     */
    private fun allWindows(): List<android.view.accessibility.AccessibilityWindowInfo> {
        val out = ArrayList<android.view.accessibility.AccessibilityWindowInfo>()
        runCatching {
            val arr = windowsOnAllDisplays
            for (i in 0 until arr.size()) out.addAll(arr.valueAt(i))
        }
        return out
    }

    private fun allWindowRoots(): List<AccessibilityNodeInfo?> {
        val out = ArrayList<AccessibilityNodeInfo?>()
        runCatching {
            for (w in allWindows()) {
                val r = w.root ?: continue
                out.add(r)
            }
        }
        if (out.isEmpty()) out.add(rootInActiveWindow)   // 兜底：拿不到窗口列表时仍用焦点窗口
        return out
    }

    // ------------------------------------------------------------------ 轮询（视频场景必需）

    /**
     * 定时截图分析，不依赖无障碍事件。
     *
     * 为什么必须有它：视频、动画、游戏画面是直接绘制的，**不会产生 TYPE_WINDOW_CONTENT_CHANGED
     * 之类的无障碍事件**。只靠事件触发的话，视频里随机出现的题目永远不会被截图，也就永远不会被识别。
     */
    private fun startPolling() {
        handler.removeCallbacks(pollTicker)
        handler.postDelayed(pollTicker, 1_000L)
    }

    private val pollTicker = object : Runnable {
        override fun run() {
            var next = 0L
            try {
                val prefs = Prefs(this@ScreenOcrAccessibilityService)
                next = prefs.pollIntervalMs.toLong()
                if (next > 0 && prefs.processingEnabled && isScreenOn() && !busy.get() && foregroundIsAnalyzable()) {
                    analyze("轮询")
                }
            } catch (t: Throwable) {
                AppLog.e("轮询异常：${t.javaClass.simpleName}: ${t.message}")
                next = 2_000L
            } finally {
                // 间隔变了也能下一次生效；0 表示已关闭轮询，就不再排下一拍（改设置后重新拉起即可）
                if (next > 0) handler.postDelayed(this, next.coerceIn(500L, 60_000L))
                else AppLog.i("轮询已关闭")
            }
        }
    }

    /**
     * 轮询是「无条件定时截图」，所以必须自己把不该看的前台窗口排掉 ——
     * 否则它会一直分析本应用自己的设置界面（甚至可能点中自己的按钮）。
     * 无障碍事件路径本来就有这层过滤，轮询这里不能漏。
     *
     * 注意：实测 `rootInActiveWindow` 在本应用位于前台时**不一定**返回本应用的包名，
     * 因此优先用窗口列表里 `isFocused` 的那个窗口来判定。
     */
    private fun foregroundIsAnalyzable(): Boolean {
        val viaRoot = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        val viaWindow = runCatching {
            // 同样要取**所有 display**：MuMu 把应用渲染在非默认 display（实测 displayId=7），
            // 只用 windows 会看到"焦点在桌面"，前台判定随之出错。
            allWindows().firstOrNull { it.isFocused }?.root?.packageName?.toString()
        }.getOrNull()
        val fg = viaWindow ?: viaRoot

        val desc = "root=$viaRoot window=$viaWindow"
        if (desc != lastFgDesc) {
            lastFgDesc = desc
            AppLog.i("前台包名判定：$desc -> 采用 ${fg ?: "(未知)"}")
        }

        if (fg == null) return false
        if (fg == packageName) return false
        if (fg == "com.android.systemui") return false
        return true
    }

    /** OCR 兜底的最小间隔：只在节点树不像题目时才考虑，且别每轮都跑（OCR 约几百毫秒） */
    private val OCR_FALLBACK_INTERVAL_MS = 3000L
    private var lastOcrFallbackAt = 0L

    /**
     * 节点树读到的东西**不像题目**时，是否改用截图 OCR 再判一次。
     *
     * 为什么需要：实测 16416 上无障碍服务只看得到"系统栏 + 桌面"（`mCurrentFocus` / `mFocusedApp`
     * 都是 null 的异常状态），而屏幕上、系统窗口列表里**确实有那道题**。
     * 截图来自 `takeScreenshot`，不受窗口与焦点影响 —— 那是这种情况唯一的退路。
     */
    private fun needOcrFallback(res: OcrResult, prefs: Prefs): Boolean {
        if (QuestionDetector.detect(res, prefs.minScore).isCandidate) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastOcrFallbackAt < OCR_FALLBACK_INTERVAL_MS) return false
        lastOcrFallbackAt = now
        return true
    }

    private fun isScreenOn(): Boolean {
        return runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isInteractive
        }.getOrDefault(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val type = e.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) {
            return
        }

        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (pkg == "com.android.systemui") return

        val prefs = Prefs(this)
        if (!prefs.processingEnabled) return

        schedule("${eventName(type)} @$pkg", prefs.debounceMs.toLong())
    }

    override fun onInterrupt() {
        // 无需处理
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AppLog.i("无障碍服务已解绑")
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        handler.removeCallbacks(pollTicker)
        pending?.let { handler.removeCallbacks(it) }
        pending = null
        imageEncoder.shutdownNow()
        ocr.close()
        AppLog.i("无障碍服务已销毁")
    }

    // ------------------------------------------------------------------ 调度

    private fun schedule(reason: String, delayMs: Long) {
        pending?.let { handler.removeCallbacks(it) }
        val r = Runnable { analyze(reason) }
        pending = r
        handler.postDelayed(r, delayMs.coerceIn(200L, 10_000L))
    }

    private fun analyze(reason: String) {
        if (!busy.compareAndSet(false, true)) {
            AppLog.w("跳过：上一次分析还没结束")
            return
        }
        val prefs = Prefs(this)

        val now = SystemClock.elapsedRealtime()
        if (now - lastShotAt < minShotIntervalMs) {
            AppLog.i("跳过：距上次截图过近（系统对截图有频率限制）")
            busy.set(false)
            return
        }
        lastShotAt = now

        AppLog.i("① 截图开始（$reason）")
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val bmp = hardwareBufferToBitmap(result)
                        if (bmp == null) {
                            busy.set(false)
                            return
                        }
                        AppLog.i("② 截图成功 ${bmp.width}x${bmp.height}")
                        // 识别来源：**无障碍节点树优先**（原生控件与 WebView 都能读，且不受模拟器截图黑图影响），
                        // 节点树读不到可用文字时才回退 OCR。两者产出同一种 OcrResult，判题与点击逻辑完全共用。
                        val roots = allWindowRoots()
                        if (roots.size > 1) {
                            val names = roots.map { r ->
                                runCatching { r?.packageName?.toString() }.getOrNull() ?: "?"
                            }
                            AppLog.i("   本次读到 ${roots.size} 个窗口：" + names.joinToString(","))
                        }
                        val fromNodes = NodeReader.toOcrResult(roots, bmp.width, bmp.height)
                        if (fromNodes != null) {
                            // 读得到题目的这一刻，「关闭」按钮一定也在树里 —— 先把它记下来给收尾用
                            cacheCloseTarget(roots)
                            AppLog.i("③ 识别来源：无障碍节点树（${fromNodes.lines.size} 行文本，免 OCR）")
                            // **截图 OCR 兜底**：实测 16416 上屏幕明明有题、系统窗口列表里也有智慧树窗口，
                            // 但无障碍服务只给得到"系统栏 + 桌面"（mCurrentFocus=null 的异常状态）。
                            // takeScreenshot 不受窗口与焦点影响 —— 那是这种情况唯一的退路。
                            if (needOcrFallback(fromNodes, prefs)) {
                                AppLog.i("   节点树里不像题目 → 改用截图 OCR 再判一次")
                                ocr.recognize(bmp, prefs.ocrScalePercent) { res ->
                                    handleOcrResult(prefs, res, bmp)
                                }
                            } else {
                                handleOcrResult(prefs, fromNodes, bmp)
                            }
                        } else {
                            AppLog.i("③ 节点树没读到可用文字，回退 OCR")
                            ocr.recognize(bmp, prefs.ocrScalePercent) { res ->
                                handleOcrResult(prefs, res, bmp)
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        AppLog.e("① 截图失败：${screenshotError(errorCode)}")
                        busy.set(false)
                    }
                }
            )
        } catch (t: Throwable) {
            AppLog.e("① 截图异常：${t.javaClass.simpleName}: ${t.message}")
            busy.set(false)
        }
    }

    /**
     * OCR 之后接管 [bmp] 的生命周期：从这里开始，每条退出路径都必须恰好 recycle 一次。
     */
    private fun handleOcrResult(prefs: Prefs, res: OcrResult?, bmp: Bitmap) {
        if (res == null) {
            AppLog.e("② OCR 失败")
            bmp.recycle()
            busy.set(false)
            return
        }
        AppLog.i("③ 识别完成：${res.lines.size} 行 / ${res.fullText.length} 字" +
            if (res.downscaled) "（已降采样到 ${res.imageWidth}x${res.imageHeight}）" else "")
        onOcr(prefs, res, bmp)
    }

    private fun hardwareBufferToBitmap(result: ScreenshotResult): Bitmap? {
        val hb = result.hardwareBuffer
        return try {
            val wrapped = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
            // ML Kit 不能直接读硬件位图，复制成 ARGB_8888
            wrapped?.copy(Bitmap.Config.ARGB_8888, false)
        } catch (t: Throwable) {
            AppLog.e("截图转 Bitmap 失败：${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            runCatching { hb.close() }
        }
    }

    // ------------------------------------------------------------------ OCR 之后

    private fun onOcr(prefs: Prefs, res: OcrResult, bmp: Bitmap) {
        if (res.fullText.isBlank()) {
            AppLog.i("③ 屏幕上没识别到文字，结束")
            bmp.recycle()
            busy.set(false)
            return
        }

        val q = QuestionDetector.detect(res, prefs.minScore)
        logIfSizeMismatch(res)
        AppLog.i("④ 题目判定：score=${q.score} 阈值=${prefs.minScore} -> ${if (q.isCandidate) "疑似题目" else "判定为非题目"}")
        AppLog.i("   依据：${q.signals.joinToString("、").ifBlank { "无" }}；选项 ${q.options.size} 个")
        // 诊断（临时）：打出识别到的选项标签与**节点给的坐标**。
        // 要回答的问题：节点坐标与屏幕坐标是不是同一坐标系 ——
        // 实测程序点 (540,270)，而截图里该处是标题、「对/错」在 y≈880，差了 600 多像素。
        if (q.options.isNotEmpty()) {
            AppLog.i("   选项明细：" + q.options.joinToString(" ｜ ") {
                val b = it.box
                if (b == null) "${it.label}@(无框)"
                else "${it.label}@${b.centerX()},${b.centerY()}(w${b.width()},h${b.height()})"
            })
        }
        if (!q.isCandidate) {
            bmp.recycle()
            busy.set(false)
            return
        }

        val now = System.currentTimeMillis()
        val key = questionKey(q)
        if (isRecentlyAnswered(key, now, prefs.dedupSeconds * 1000L)) {
            // 去重只说明"记录里处理过"，**不代表真的答上了**：上一次可能只点中了选项、提交却失败
            //   （点完选项后浮层会从无障碍树里消失，实测踩到过）。
            // 所以先看浮层里的按钮：还亮着「提交作答」＝没答成 → **重新走一遍完整答题**；
            // 显示「已提交 / 回答正确」＝真答过 → 只做收尾，省掉那次接口调用。
            when (submitState()) {
                SubmitState.PENDING -> {
                    val n = retriedKeys[key] ?: 0
                    if (retriedKeys.size > 50) retriedKeys.clear()
                    retriedKeys[key] = n + 1
                    when (n) {
                        0 -> {
                            // 第一次：上次的**点击多半已经生效、只是提交没做**，所以**只补提交、绝不重复点选项**。
                            // 关键原因：WebView 的选项是 toggle，重复点会把已选中的**取消**掉
                            //（实测：60 秒延迟下程序把已勾选的 3 个选项又点了一遍，平台随即"取消作答"）。
                            AppLog.i("④ 判为答过但按钮仍亮 → 只补一次提交，**不重复点选项**（避免 toggle 取消）")
                            handler.postDelayed({
                                submitIfPresent()
                                handler.postDelayed({ closeAnswerOverlay() }, ANSWER_SETTLE_MS)
                            }, SUBMIT_DELAY_MS)
                            bmp.recycle()
                            busy.set(false)
                            return
                        }
                        1 -> AppLog.i("④ 补提交后按钮仍亮 → 说明上次点击确实没生效，重新完整作答")
                        else -> {
                            AppLog.i("④ 已重答过、按钮仍亮 → 只补收尾，不再重复调用接口（防死循环）")
                            handler.postDelayed({
                                submitIfPresent()
                                handler.postDelayed({ closeAnswerOverlay() }, ANSWER_SETTLE_MS)
                            }, SUBMIT_DELAY_MS)
                            bmp.recycle()
                            busy.set(false)
                            return
                        }
                    }
                }
                SubmitState.DONE -> {
                    AppLog.i("④ 这道题确实已答成：只跳过 API 调用，收尾动作（提交/关闭）照做")
                    handler.postDelayed({
                        submitIfPresent()
                        handler.postDelayed({ closeAnswerOverlay() }, ANSWER_SETTLE_MS)
                    }, SUBMIT_DELAY_MS)
                    bmp.recycle()
                    busy.set(false)
                    return
                }
                SubmitState.UNKNOWN -> {
                    // 读不到答题状态（浮层多半正在重绘）：不猜，只补收尾，避免白花一次接口调用
                    AppLog.i("④ 判为答过、且读不到答题状态：只补收尾，不重复调用接口")
                    handler.postDelayed({
                        submitIfPresent()
                        handler.postDelayed({ closeAnswerOverlay() }, ANSWER_SETTLE_MS)
                    }, SUBMIT_DELAY_MS)
                    bmp.recycle()
                    busy.set(false)
                    return
                }
            }
        }

        // 只有确定要调接口了才编码图片；JPEG 压缩放后台线程，不占主线程
        imageEncoder.execute {
            val jpeg = try {
                if (prefs.sendScreenshot) encodeJpegBase64(bmp, prefs.imageMaxSide, prefs.imageQuality)
                else null
            } catch (t: Throwable) {
                AppLog.e("截图编码失败：${t.javaClass.simpleName}: ${t.message}")
                null
            } finally {
                bmp.recycle()
            }

            val visionNote = if (jpeg == null) {
                if (prefs.sendScreenshot) "视觉模式=开但编码失败，改发纯文本" else "纯文本模式"
            } else {
                "视觉模式=开，图片 base64 ≈ ${jpeg.length / 1024} KB"
            }
            AppLog.i("⑤ 调用大模型：${prefs.model} @ ${prefs.baseUrl}（$visionNote，effort=${prefs.reasoningEffort.ifEmpty { "服务端默认" }}）")

            LlmClient.ask(prefs, q, res.fullText, jpeg) { r -> onLlmResult(prefs, q, res, key, r) }
        }
    }

    private fun onLlmResult(prefs: Prefs, q: Question, res: OcrResult, key: String, r: LlmClient.Result) {
        try {
            if (!r.ok || r.answer == null) {
                AppLog.e("⑤ 大模型调用失败（${r.latencyMs}ms）：${r.error}")
                return
            }
            val a = r.answer
            AppLog.i("⑤ 大模型返回（${r.latencyMs}ms, HTTP ${r.httpCode}）：is_question=${a.isQuestion} label=\"${a.answerLabel}\" text=\"${a.answerText.take(40)}\" confidence=${a.confidence}")
            AppLog.i("   原始回复：${a.rawContent.replace('\n', ' ').take(160)}")
            if (a.explanation.isNotBlank()) AppLog.i("   说明：${a.explanation}")

            if (!a.isQuestion) {
                AppLog.i("⑥ 模型判定这不是题目，不点击")
                return
            }

            AppLog.i("⑥ 答案：${describeAnswer(a)}")

            if (!prefs.autoClick) {
                AppLog.i("   自动点击已关闭，只显示答案")
                return
            }
            if (a.confidence < prefs.minConfidence) {
                AppLog.w("   置信度 ${a.confidence} 低于阈值 ${prefs.minConfidence}，放弃点击")
                return
            }
            if (q.options.isEmpty()) {
                AppLog.i("   本题没有识别到可点的选项，只给出答案")
                return
            }

            val root = rootInActiveWindow
            if (root == null) AppLog.w("   拿不到当前窗口节点树，将只能用坐标点击")

            val plans = ClickPlanner.plans(root, a, q.options) { box -> scaleToScreen(res, box) }
            if (plans.isEmpty()) {
                AppLog.w("   未能在屏幕上定位到「${describeAnswer(a)}」对应的选项，宁可不点")
                return
            }

            if (prefs.dryRun) {
                AppLog.i("⑥ 试运行模式：本应执行 ${plans.joinToString("；") { it.how }}，已跳过")
                return
            }

            val sinceClick = System.currentTimeMillis() - lastClickAt
            if (sinceClick < clickCooldownMs) {
                AppLog.i("   距上次点击仅 ${sinceClick}ms（< ${clickCooldownMs}ms 冷却），本次不点，避免同一题被重复点击")
                return
            }

            var ok = false
            for ((i, plan) in plans.withIndex()) {
                if (i > 0) Thread.sleep(600)          // 多选：逐项点，留出 UI 反应时间
                val one = performPlan(plan)
                ok = ok || one
                lastClickAt = System.currentTimeMillis()
                AppLog.i("⑦ 点击结果：${if (one) "已发送" else "失败"} —— ${plan.how}")
            }
            if (ok) {
                // 答完收尾分两步：
                //   ① 有些题型选完必须点「提交作答」才判定（实测智慧树「AI 随堂练习」的单选题）；
                //   ② 等判定结果渲染出来再点「关闭」，免得浮层一直挡着视频、之后每轮轮询都重复看到这道题。
                handler.postDelayed({
                    submitIfPresent()
                    handler.postDelayed({ closeAnswerOverlay() }, ANSWER_SETTLE_MS)
                }, SUBMIT_DELAY_MS)
            }
        } finally {
            markProcessed(prefs, key, r.ok)
            busy.set(false)
        }
    }

    /**
     * 同一道题最多「重新作答」几次。
     *
     * 为什么必须有：实测某多选界面**提交后按钮文字不变**（一直是「提交作答」），
     * 于是 `submitState()` 永远返回 PENDING → 每轮都重新作答 → **无限循环、每轮真实调用接口**。
     * 宁可不重答（少答一道题），也绝不允许这种方式烧接口。
     */
    /**
     * 是否用 root 执行 `input tap` —— **默认关闭，且经复核并不需要**。
     *
     * 复核结论（用户现场确认）：真正让选项被勾选的是 **「覆盖该点的最深叶子节点的 ACTION_CLICK」**
     * （`NodeReader.findDeepestAt` 那条路），**不是 root**。
     * 此前我从"提交作答按钮仍亮"推断"叶子无效"是**误读**：那个"仍亮"是当时把 `SUBMIT_DELAY_MS`
     * 临时调成 60 秒导致的（提交还没执行）；后续日志出现 `④ 这道题确实已答成` 正是提交成功的证据。
     * root 方式保留为**可选兜底**（真机 / 无无障碍权限等场景），默认关闭。
     */
    private val USE_ROOT_TAP = false

    private val MAX_RETRY_ANSWER = 1
    private val retriedKeys = HashMap<String, Int>()

    /** 浮层里的答题状态 */
    private enum class SubmitState { PENDING, DONE, UNKNOWN }

    /**
     * 读浮层里的按钮，判断这道题**是否真的答成了**。
     *
     * 为什么需要：去重记录只能说明"处理过"，不能说明"提交成功" —— 点完选项后浮层会从无障碍树里消失、
     * 提交常常失败，而记录此时已经写下，光看去重就会把**没答成的题**当成答过
     * （实测：两道题被跳过，学习记录里仍是未作答）。
     *
     * 判据（都用节点文本精确匹配，实测这些文案就是整节点文本）：
     *   还亮着「提交作答」→ [SubmitState.PENDING]；显示「已提交 / 回答正确 / 回答错误」→ [SubmitState.DONE]；
     *   两者都读不到（浮层正在重绘）→ [SubmitState.UNKNOWN]。
     */
    private fun submitState(): SubmitState {
        val roots = allWindowRoots()
        if (roots.isEmpty()) return SubmitState.UNKNOWN
        if (NodeReader.findTextNodes(roots, listOf("提交作答", "提交答案", "确认作答", "提交")).isNotEmpty()) {
            return SubmitState.PENDING
        }
        if (NodeReader.findTextNodes(roots, listOf("已提交", "回答正确", "回答错误")).isNotEmpty()) {
            return SubmitState.DONE
        }
        return SubmitState.UNKNOWN
    }

    /**
     * 点完选项后，屏幕上若有「提交作答」这类按钮就点它。
     *
     * 实测：智慧树的「AI 随堂练习」（单选题 + 大按钮）**选完必须提交才判定**；
     * 弹题浮层那种则是点选即判定、没有提交按钮 —— 所以这里「有就点、没有就跳过」。
     */
    private fun submitIfPresent() {
        val words = listOf("提交作答", "提交答案", "确认作答", "提交")
        for ((node, box) in NodeReader.findTextNodes(allWindowRoots(), words)) {
            val label = node.text?.toString()?.trim().orEmpty()
            if (box.width() > 0 && clickAtPoint(box.centerX(), box.centerY(), "提交")) {
                AppLog.i("⑧ 收尾：已点「$label」提交（坐标 ${box.centerX()},${box.centerY()}）")
                return
            }
        }
        AppLog.i("⑧ 收尾：本次没有提交按钮（点选即判定的题型），跳过")
    }

    /** 读题时顺带找出「关闭」类按钮，记下它的位置供收尾使用（跨所有窗口找）。 */
    private fun cacheCloseTarget(roots: List<AccessibilityNodeInfo?>) {
        val words = listOf("关闭", "继续观看", "继续播放", "继续", "我知道了", "知道了", "确定")
        for ((node, box) in NodeReader.findTextNodes(roots, words)) {
            if (box.width() > 0 && box.height() > 0) {
                val label = node.text?.toString()?.trim().orEmpty()
                cachedClose = label to Rect(box)
                cachedCloseAt = SystemClock.elapsedRealtime()
                AppLog.i("   已缓存「$label」位置 (${box.centerX()},${box.centerY()})，供收尾使用")
                return
            }
        }
        // 这里**绝不能清空缓存**：点完选项后浮层会从无障碍树里消失，之后每轮读题都会走到这里；
        // 一旦清空，收尾就没坐标可用（实测踩过：日志里"已缓存"与"第 1 次没找到关闭"同时出现）。
        // 旧坐标由 CLOSE_CACHE_TTL_MS 兜底过期。
    }

    /**
     * 答完后的收尾：把结果浮层关掉。
     *
     * ① 先用**读题时缓存的按钮位置** —— 点完选项后浮层常从无障碍树里消失，实时查找会失败；
     * ② 再实时查找（「找哪个」用子节点遍历，坑点 #37；「怎么点」一律走坐标，坑点 #38），
     *    并最多重试 [CLOSE_MAX_ATTEMPTS] 次兜住 WebView 重绘间隙（坑点 #41）。
     */
    private fun closeAnswerOverlay(attempt: Int = 1) {
        val cached = cachedClose
        if (attempt == 1 && cached != null &&
            SystemClock.elapsedRealtime() - cachedCloseAt < CLOSE_CACHE_TTL_MS
        ) {
            val (label, box) = cached
            if (box.width() > 0 && clickAtPoint(box.centerX(), box.centerY(), "关闭")) {
                AppLog.i("⑧ 收尾：已点「$label」（坐标 ${box.centerX()},${box.centerY()}，用读题时缓存的按钮位置）")
                // 不清缓存：这一下未必生效（WebView 未必认这个手势），下一轮补善后还要用；由 TTL 兜底过期
                return
            }
        }
        val words = listOf("关闭", "继续观看", "继续播放", "继续", "我知道了", "知道了", "确定")
        for ((node, box) in NodeReader.findTextNodes(allWindowRoots(), words)) {
            val label = node.text?.toString()?.trim().orEmpty()
            if (box.width() > 0 && clickAtPoint(box.centerX(), box.centerY(), "关闭")) {
                AppLog.i("⑧ 收尾：已点「$label」（坐标 ${box.centerX()},${box.centerY()}）" +
                    if (attempt > 1) "（第 $attempt 次尝试）" else "")
                return
            }
        }
        if (attempt < CLOSE_MAX_ATTEMPTS) {
            AppLog.i("⑧ 收尾：第 $attempt 次没找到「关闭」（可能是重绘间隙）→ ${CLOSE_RETRY_MS}ms 后重试")
            handler.postDelayed({ closeAnswerOverlay(attempt + 1) }, CLOSE_RETRY_MS)
        } else {
            AppLog.i("⑧ 收尾：重试 $attempt 次仍未找到「关闭」类按钮（浮层可能已自行消失）")
        }
    }

    /**
     * 去重键用「题目本身」（题干主体 + 排序后的选项），而不是整屏文字。
     * 两个原因（都是实测踩出来的）：
     *   1) 点击后页面会多出「已答对」之类提示，整屏文字一变，同一道题就会被反复作答；
     *   2) 端侧 OCR 对同一画面并不逐字确定 —— 同一道题两次识别出「美于」/「关干」，
     *      选项行的先后顺序也会变。所以选项要排序，且比对时用模糊相似度（见 [isRecentlyAnswered]）。
     */
    private fun questionKey(q: Question): String {
        // 有选项时只用选项：实测它最稳（同一题两次 OCR 相似度 0.965~0.983，
        // 而把题干也算进去只有 0.80~0.98，因为题干里混着状态栏时钟和浏览器 chrome）。
        val optsPart = q.options.map { it.label.uppercase() + ":" + it.text }
            .sorted()
            .joinToString("|")
            .replace(WHITESPACE, "")
        if (optsPart.isNotEmpty()) return optsPart.take(400)
        // 没有选项的题（问答/填空）：退回题干，并剔掉每分钟都变的时间
        return q.stem.replace(CLOCK, "").replace(WHITESPACE, "").takeLast(200)
    }

    /** 精确命中，或与最近答过的题目相似度达标时，认为这题已经答过 */
    private fun isRecentlyAnswered(key: String, now: Long, windowMs: Long): Boolean {
        val it = recent.entries.iterator()
        var matched = false
        var bestSim = 0.0
        var bestThreshold = SIMILARITY_THRESHOLD

        while (it.hasNext()) {
            val e = it.next()
            val age = now - e.value
            if (age > windowMs) {
                it.remove()
                continue
            }
            if (e.key == key) {
                matched = true
                continue
            }
            val sim = TextMatch.editSimilarity(e.key, key)
            val threshold =
                if (age <= RECENT_WINDOW_MS) SIMILARITY_THRESHOLD_RECENT else SIMILARITY_THRESHOLD
            if (sim > bestSim) {
                bestSim = sim
                bestThreshold = threshold
            }
            if (sim >= threshold) {
                AppLog.i("   去重：与 ${age / 1000}s 前答过的题相似度 ${"%.3f".format(sim)} ≥ $threshold，视为同一题")
                matched = true
            }
        }

        // 没命中但相似度不低时把实际值打出来 —— 否则「为什么又答了一遍」无从排查
        if (!matched && bestSim >= 0.60) {
            AppLog.i("   去重未命中：与最近答过的题最高相似度 ${"%.3f".format(bestSim)}，本轮阈值 $bestThreshold")
        }
        return matched
    }

    /**
     * 记录这道题已处理。
     * 成功时按完整去重窗口压制；失败时只压制 [failureRetryDelayMs]，让偶发网络错误还能重试一次。
     */
    private fun markProcessed(prefs: Prefs, key: String, ok: Boolean) {
        val now = System.currentTimeMillis()
        val window = prefs.dedupSeconds * 1000L
        val offset = if (ok) 0L else (window - failureRetryDelayMs).coerceAtLeast(0L)
        recent[key] = now - offset
    }

    private fun performPlan(plan: ClickPlan): Boolean {
        val node = plan.node
        if (node != null) {
            val ok = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                .getOrDefault(false)
            if (ok) return true
            // 节点点击失败，退到坐标点击
            val b = Rect()
            runCatching { node.getBoundsInScreen(b) }
            if (b.width() > 0 && b.height() > 0) {
                AppLog.w("   节点 ACTION_CLICK 返回 false，改用坐标点击 ${b.centerX()},${b.centerY()}")
                return tapAt(b.centerX(), b.centerY())
            }
            return false
        }
        // 坐标计划：**先在节点树里找「能点的那个节点」用 ACTION_CLICK 点**（它直接作用于目标窗口，
        // 不受「dispatchGesture 在模拟器上返回成功却不生效」影响），确实找不到才退回坐标手势。
        return clickAtPoint(plan.x, plan.y, "选项")
    }

    /**
     * 在某点点击：**优先用无障碍节点点击**，找不到可点节点才退回坐标手势。
     *
     * 为什么需要：实测 MuMu 上 `dispatchGesture` 会「返回成功却不生效」——
     * 日志打 `⑦ 点击结果：已发送`，屏幕上选项/按钮毫无变化；
     * 而 `performAction(ACTION_CLICK)` 直接作用于目标窗口、不走输入子系统，可靠得多。
     */
    private fun clickAtPoint(x: Int, y: Int, label: String): Boolean {
        val roots = allWindowRoots()
        // ① 先试**最深叶子节点**：WebView 里 ACTION_CLICK 只有打在叶子上才可能被响应
        //    （打容器/祖先会「返回 true 却不生效」——选项就是这种情况）
        val deep = NodeReader.findDeepestAt(roots, x, y)
        if (deep != null &&
            runCatching { deep.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
        ) {
            AppLog.i("   改用无障碍节点点击（$label $x,$y 命中最深叶子）")
            return true
        }
        // ② 再试「覆盖该点、自身可点」的最小节点
        val hit = NodeReader.findClickableAt(roots, x, y)
        if (hit != null &&
            runCatching { hit.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
        ) {
            AppLog.i("   改用无障碍节点点击（$label $x,$y 命中可点节点）")
            return true
        }
        // ③ 再试 **root 执行 input tap**：MuMu 这类带 root 的模拟器上，
        //    WebView 选项既不吃 ACTION_CLICK（返回 true 却不生效）、坐标手势又在多 display 下失效，
        //    `input tap` 是实测唯一有效的注入方式（adb 侧同一条命令已验证生效）。
        if (USE_ROOT_TAP && rootTap(x, y)) {
            AppLog.i("   改用 root input tap（$label $x,$y）")
            return true
        }
        // ④ 最后才是坐标手势
        return tapAt(x, y)
    }

    /**
     * 用 root 执行 `input tap`。
     *
     * MuMu 的 root 默认放行（本机已开），所以 `su` 不会弹窗；带 3 秒超时兜住"万一弹窗"的情况，
     * 免得阻塞分析线程。
     */
    private fun rootTap(x: Int, y: Int): Boolean = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "input tap $x $y"))
        p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        true
    }.getOrDefault(false)

    private fun tapAt(x: Int, y: Int): Boolean {
        // 防检测：落点加 ±JITTER_MAX_PX 随机偏移，避免每次都是同一个精确坐标
        val jx = x + jitterRandom.nextInt(JITTER_MAX_PX * 2 + 1) - JITTER_MAX_PX
        val jy = y + jitterRandom.nextInt(JITTER_MAX_PX * 2 + 1) - JITTER_MAX_PX
        if (jx != x || jy != y) {
            AppLog.i("   落点抖动：($x,$y) → ($jx,$jy)（防检测 ±$JITTER_MAX_PX px）")
        }
        return runCatching {
            val path = Path().apply { moveTo(jx.toFloat(), jy.toFloat()) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        }.getOrElse {
            AppLog.e("手势点击异常：${it.javaClass.simpleName}: ${it.message}")
            false
        }
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 把 OCR 位图坐标系里的框换算回**屏幕坐标**。
     *
     * 截图与 `dispatchGesture` 同属整屏坐标系，所以只要 OCR 没有降采样，比例就是 1:1。
     * 降采样时用 `原始截图 / OCR 输入位图` 的比例换算 —— 这个比例由我们自己控制，是可靠的。
     *
     * 千万别用 `resources.displayMetrics`：它是「应用可用窗口」（本机 2400 高 vs 整屏 2800），
     * 拿它缩放会让点击整体偏移。这个坑踩过，别再改回去。
     */
    private fun scaleToScreen(res: OcrResult, box: Rect): Rect {
        if (!res.downscaled) return Rect(box)
        val sx = res.scaleX
        val sy = res.scaleY
        return Rect(
            (box.left * sx).toInt(),
            (box.top * sy).toInt(),
            (box.right * sx).toInt(),
            (box.bottom * sy).toInt()
        )
    }

    /** 只用于诊断：确认「截图尺寸」与「应用可用窗口」的差异（那是导航栏造成的，属正常） */
    private fun logIfSizeMismatch(res: OcrResult) {
        val dm = resources.displayMetrics
        if (res.sourceWidth != dm.widthPixels || res.sourceHeight != dm.heightPixels) {
            if (SystemClock.elapsedRealtime() - lastScaleWarnAt > 20_000) {
                lastScaleWarnAt = SystemClock.elapsedRealtime()
                AppLog.i("提示：截图 ${res.sourceWidth}x${res.sourceHeight} ≠ 应用窗口 ${dm.widthPixels}x${dm.heightPixels}" +
                    "（差值通常是导航栏）。截图与手势同属屏幕坐标系，故坐标用截图比例换算，不用窗口尺寸。")
            }
        }
    }

    private fun describeAnswer(a: LlmClient.Answer): String {
        val label = a.answerLabel.trim()
        val text = a.answerText.trim()
        val parts = ArrayList<String>(2)
        if (label.isNotEmpty()) parts.add("选项 $label")
        // 纯文本协议下 answerText 常常就等于标签本身，别重复打印
        if (text.isNotEmpty() && !text.equals(label, ignoreCase = true)) parts.add("「${text.take(60)}」")
        return if (parts.isEmpty()) "（模型未给出具体答案）" else parts.joinToString(" ")
    }

    /**
     * 截屏 → JPEG → Base64（不带换行，供 `data:image/jpeg;base64,...` 直接拼装）。
     * 长边超过 [maxSide] 时等比缩小（0 表示不缩）。缩小只为省流量，识别仍靠模型自己缩放。
     */
    private fun encodeJpegBase64(src: Bitmap, maxSide: Int, quality: Int): String? {
        val longSide = maxOf(src.width, src.height)
        val scaled: Bitmap = if (maxSide > 0 && longSide > maxSide) {
            val s = maxSide.toFloat() / longSide.toFloat()
            Bitmap.createScaledBitmap(
                src,
                (src.width * s).toInt().coerceAtLeast(1),
                (src.height * s).toInt().coerceAtLeast(1),
                true
            )
        } else {
            src
        }
        try {
            val bos = ByteArrayOutputStream(512 * 1024)
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 100), bos)) return null
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        } finally {
            if (scaled !== src) scaled.recycle()
        }
    }

    private fun eventName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "窗口变化"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "内容变化"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "窗口集合变化"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "滚动"
        else -> "事件$type"
    }

    private fun screenshotError(code: Int): String = when (code) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "内部错误($code)"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "无障碍服务无权限($code)"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "距上次截图太近($code)"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "无效显示($code)"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "无效窗口($code)"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "当前窗口禁止截屏($code)"
        else -> "未知错误($code)"
    }

    companion object {
        @Volatile
        var instance: ScreenOcrAccessibilityService? = null

        fun isConnected(): Boolean = instance != null
    }
}
