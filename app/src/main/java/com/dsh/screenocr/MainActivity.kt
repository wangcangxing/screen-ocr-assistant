package com.dsh.screenocr

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 配置界面：无障碍开关入口 + 大模型接口参数 + 触发参数 + 实时日志。
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var statusText: TextView
    private lateinit var etBaseUrl: EditText
    private lateinit var etApiKey: EditText
    private lateinit var etModel: EditText
    private lateinit var etReasoningEffort: EditText
    private lateinit var etImageMaxSide: EditText
    private lateinit var etPollInterval: EditText
    private lateinit var etOcrScale: EditText
    private lateinit var etSystemPrompt: EditText
    private lateinit var etMinScore: EditText
    private lateinit var etDebounce: EditText
    private lateinit var etMinConf: EditText
    private lateinit var etDedup: EditText
    private lateinit var etTimeout: EditText
    private lateinit var swProcessing: Switch
    private lateinit var swAutoClick: Switch
    private lateinit var swDryRun: Switch
    private lateinit var swSendScreenshot: Switch
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var rootScroll: ScrollView

    /** 界面上只显示最近这么多条（完整日志仍在 AppLog 与 logcat 里，长按日志区可复制全部） */
    private val logUiLines = 25

    private val logListener: (List<String>) -> Unit = { lines ->
        ui.post {
            // 先判断「用户是不是本来就在底部」，只有贴底时才自动跟随最新：
            // 否则手动往上翻时会被新日志一次次拽回底部 —— 那样看着就像"不能滚动"。
            val atBottom = logScroll.height == 0 ||
                (logScroll.scrollY + logScroll.height) >= (logText.height - 24)
            logText.text = lines.takeLast(logUiLines).joinToString("\n")
            if (atBottom) logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private val statusTicker = object : Runnable {
        override fun run() {
            refreshStatus()
            ui.postDelayed(this, 1500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        statusText = findViewById(R.id.statusText)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        etApiKey = findViewById(R.id.etApiKey)
        etModel = findViewById(R.id.etModel)
        etReasoningEffort = findViewById(R.id.etReasoningEffort)
        etImageMaxSide = findViewById(R.id.etImageMaxSide)
        etPollInterval = findViewById(R.id.etPollInterval)
        etOcrScale = findViewById(R.id.etOcrScale)
        etSystemPrompt = findViewById(R.id.etSystemPrompt)
        etMinScore = findViewById(R.id.etMinScore)
        etDebounce = findViewById(R.id.etDebounce)
        etMinConf = findViewById(R.id.etMinConf)
        etDedup = findViewById(R.id.etDedup)
        etTimeout = findViewById(R.id.etTimeout)
        swProcessing = findViewById(R.id.swProcessing)
        swAutoClick = findViewById(R.id.swAutoClick)
        swDryRun = findViewById(R.id.swDryRun)
        swSendScreenshot = findViewById(R.id.swSendScreenshot)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        rootScroll = findViewById(R.id.rootScroll)

        // 长按日志区把全部日志复制走（替代 textIsSelectable，避免它抢初始焦点）
        logText.setOnLongClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("ScreenOcr 日志", AppLog.tail(400)))
            toast("日志已复制到剪贴板")
            true
        }

        findViewById<Button>(R.id.btnOpenA11y).setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .onFailure { toast("打不开系统无障碍设置：${it.message}") }
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            savePrefs()
            toast("已保存")
        }
        findViewById<Button>(R.id.btnDefaultPrompt).setOnClickListener {
            prefs.restoreDefaultSystemPrompt()
            etSystemPrompt.setText(prefs.systemPrompt)
            toast("已恢复预设提示词")
        }
        findViewById<Button>(R.id.btnImportConfig).setOnClickListener {
            val r = ConfigFile.import(this, prefs)
            AppLog.i("手动导入配置：${r.message}")
            loadPrefs()
            toast(r.message)
        }
        findViewById<Button>(R.id.btnListModels).setOnClickListener {
            savePrefs()
            fetchModels()
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener {
            savePrefs()
            testConnection()
        }
        findViewById<Button>(R.id.btnRefreshLog).setOnClickListener { logListener(AppLog.snapshot()) }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener { AppLog.clear() }

        loadPrefs()
        // 首次运行（还没配 Key）时，尝试从外部配置文件自动导入一次
        ConfigFile.autoImportIfUnconfigured(this, prefs)
        loadPrefs()
        AppLog.addListener(logListener)
        // 保证每次进入都停在页面顶部（日志区变长时不该把界面顶到底部）
        rootScroll.post { rootScroll.fullScroll(ScrollView.FOCUS_UP) }
        ui.post(statusTicker)
        showStartupNotice()
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLog.removeListener(logListener)
        ui.removeCallbacks(statusTicker)
    }

    // ------------------------------------------------------------------ 读写配置

    private fun loadPrefs() {
        etBaseUrl.setText(prefs.baseUrl)
        etApiKey.setText(prefs.apiKey)
        etModel.setText(prefs.model)
        etReasoningEffort.setText(prefs.reasoningEffort)
        etImageMaxSide.setText(prefs.imageMaxSide.toString())
        etPollInterval.setText(prefs.pollIntervalMs.toString())
        etOcrScale.setText(prefs.ocrScalePercent.toString())
        etSystemPrompt.setText(prefs.systemPrompt)
        etMinScore.setText(prefs.minScore.toString())
        etDebounce.setText(prefs.debounceMs.toString())
        etMinConf.setText(prefs.minConfidence.toString())
        etDedup.setText(prefs.dedupSeconds.toString())
        etTimeout.setText(prefs.timeoutMs.toString())
        swProcessing.isChecked = prefs.processingEnabled
        swAutoClick.isChecked = prefs.autoClick
        swDryRun.isChecked = prefs.dryRun
        swSendScreenshot.isChecked = prefs.sendScreenshot
    }

    private fun savePrefs() {
        // 接口地址先校验再落盘：写坏了以后每次请求都失败，且用户很难看出少没少字符
        val raw = etBaseUrl.text.toString().trim()
        val normalized = normalizeBaseUrl(raw)
        if (normalized == null) {
            AppLog.e("接口地址无效，已保留原值 '${prefs.baseUrl}'；本次输入为 '$raw'")
            toast("接口地址无效，未保存该字段")
            etBaseUrl.setText(prefs.baseUrl)
        } else {
            prefs.baseUrl = normalized
            if (normalized != raw) AppLog.i("接口地址已规范化为 '$normalized'（原输入 '$raw'）")
        }
        prefs.apiKey = etApiKey.text.toString()
        prefs.model = etModel.text.toString()
        prefs.reasoningEffort = etReasoningEffort.text.toString()
        prefs.imageMaxSide = intOf(etImageMaxSide.text.toString(), 1600, 0, 8192)
        prefs.pollIntervalMs = intOf(etPollInterval.text.toString(), 2000, 0, 60_000)
        prefs.ocrScalePercent = intOf(etOcrScale.text.toString(), 100, 30, 100)
        prefs.systemPrompt = etSystemPrompt.text.toString()
        prefs.minScore = intOf(etMinScore.text.toString(), 3, 0, 99)
        prefs.debounceMs = intOf(etDebounce.text.toString(), 1200, 200, 10_000)
        prefs.minConfidence = doubleOf(etMinConf.text.toString(), 0.5, 0.0, 1.0)
        prefs.dedupSeconds = intOf(etDedup.text.toString(), 45, 0, 3600)
        prefs.timeoutMs = intOf(etTimeout.text.toString(), 30_000, 3_000, 180_000)
        prefs.processingEnabled = swProcessing.isChecked
        prefs.autoClick = swAutoClick.isChecked
        prefs.dryRun = swDryRun.isChecked
        prefs.sendScreenshot = swSendScreenshot.isChecked
        AppLog.i("已保存配置：接口=${prefs.baseUrl} 模型=${prefs.model} 视觉=${prefs.sendScreenshot} 强度=${prefs.reasoningEffort.ifEmpty { "(默认)" }}")
        refreshStatus()
    }

    /** 返回规范化后的 base url；不是合法的 http(s) 地址则返回 null */
    private fun normalizeBaseUrl(raw: String): String? {
        val t = raw.trim().trimEnd('/')
        if (t.isEmpty()) return null
        if (!t.startsWith("http://") && !t.startsWith("https://")) return null
        return try {
            val u = java.net.URI(t)
            if (u.host.isNullOrBlank()) null else t
        } catch (t2: Throwable) {
            null
        }
    }

    private fun intOf(raw: String, def: Int, lo: Int, hi: Int): Int {
        val v = raw.trim().toIntOrNull() ?: def
        return v.coerceIn(lo, hi)
    }

    private fun doubleOf(raw: String, def: Double, lo: Double, hi: Double): Double {
        val v = raw.trim().toDoubleOrNull() ?: def
        return v.coerceIn(lo, hi)
    }

    // ------------------------------------------------------------------ 状态

    private fun refreshStatus() {
        val sb = StringBuilder()
        sb.append("无障碍服务：").append(if (ScreenOcrAccessibilityService.isConnected()) "已连接 ✅" else "未连接 ❌")
        sb.append('\n')
        sb.append("系统无障碍列表中已启用：").append(if (isAccessibilityEnabledInSettings()) "是" else "否")
        sb.append('\n')
        sb.append("屏幕处理开关：").append(if (prefs.processingEnabled) "开" else "关")
        sb.append('\n')
        sb.append("自动点击：").append(if (prefs.autoClick) "开" else "关")
        if (prefs.dryRun) sb.append("（试运行，不会真的点）")
        sb.append('\n')
        sb.append("接口：").append(prefs.baseUrl.ifBlank { "未配置" })
        sb.append('\n')
        sb.append("模型：").append(prefs.model.ifBlank { "未配置" })
        sb.append("　Key：").append(if (prefs.apiKey.isBlank()) "未配置" else "已配置(${prefs.apiKey.length}位)")
        sb.append('\n')
        sb.append("视觉模式：").append(if (prefs.sendScreenshot) "开（每次提问随截图一起发送，最长边 ${prefs.imageMaxSide}px）" else "关（只发 OCR 文字）")
        sb.append('\n')
        sb.append("推理强度：").append(prefs.reasoningEffort.ifBlank { "（留空，用服务端默认）" })
        sb.append('\n')
        sb.append("轮询：").append(
            if (prefs.pollIntervalMs > 0) "每 ${prefs.pollIntervalMs}ms 一次（视频/动画场景靠它发现题目）"
            else "已关闭（只靠无障碍事件，视频里的题目发现不到）"
        )
        sb.append('\n')
        sb.append("OCR 缩放：").append(prefs.ocrScalePercent).append('%')
        val text = sb.toString()
        // 只有内容变了才 setText，避免每 1.5 秒刷新一次造成无谓的重排/闪烁
        if (statusText.text.toString() != text) statusText.text = text
    }

    private fun isAccessibilityEnabledInSettings(): Boolean {
        val raw = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expected = "$packageName/${ScreenOcrAccessibilityService::class.java.name}"
        return raw.split(':').any { it.equals(expected, true) }
    }

    private fun testConnection() {
        toast("正在请求接口…")
        AppLog.i("— 开始接口连通性测试 —")
        LlmClient.ping(prefs) { r ->
            if (r.ok && r.answer != null) {
                AppLog.i("— 接口测试成功：HTTP ${r.httpCode} / ${r.latencyMs}ms；模型回复「${r.answer.rawContent.replace('\n', ' ').take(80)}」")
                toast("接口正常（${r.latencyMs}ms）")
            } else {
                AppLog.e("— 接口测试失败：${r.error}")
                toast("接口失败：${r.error?.take(80)}")
            }
        }
    }

    /**
     * GET /models：用接口真实返回决定「有哪些模型、哪个能看图、支持哪些推理强度」。
     * 选中模型后会自动：带 image 模态 -> 打开视觉模式；按 supported_levels 落到最低档（优先 low）。
     */
    private fun fetchModels() {
        toast("正在获取模型列表…")
        AppLog.i("— GET ${LlmClient.modelsEndpointOf(prefs.baseUrl)} —")
        LlmClient.listModels(prefs) { r ->
            if (!r.ok) {
                AppLog.e("— 获取模型列表失败：${r.error}")
                toast("获取失败：${r.error?.take(90)}")
                return@listModels
            }
            AppLog.i("— 获取到 ${r.models.size} 个模型：")
            for (m in r.models) AppLog.i("   ${m.label()}")

            val labels = r.models.map { it.label() }.toTypedArray()
            android.app.AlertDialog.Builder(this)
                .setTitle("选择模型（共 ${r.models.size} 个）")
                .setItems(labels) { _, which -> applyModel(r.models[which]) }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun applyModel(m: LlmClient.ModelInfo) {
        prefs.model = m.id
        val vision = m.supportsImage
        prefs.sendScreenshot = vision

        val supported = m.supportedEfforts
        val chosen = m.lowestEffort()
        val effortNote = when {
            supported.isEmpty() -> "接口未提供 effort 档位，保留当前设置 ${prefs.reasoningEffort.ifEmpty { "(留空)" }}"
            chosen.isEmpty() -> "接口未给出可用档位，保留当前设置"
            else -> {
                prefs.reasoningEffort = chosen
                "推理强度设为「$chosen」（该模型支持 ${supported.joinToString("/")}" +
                    (if (m.defaultEffort.isNotEmpty()) "，服务端默认 ${m.defaultEffort}" else "") + "）"
            }
        }

        loadPrefs()
        AppLog.i("— 已选模型 ${m.id}：${if (vision) "支持图片，已打开视觉模式（会发送屏幕截图）" else "纯文本模型，已关闭视觉模式"}；$effortNote")
        toast("已选 ${m.id}")
    }

    /**
     * 官方 release 包的签名 SHA-256（用 `apksigner verify --print-certs` 取得）。
     *
     * **为什么靠签名**：Android 不允许同一个包名存在两个签名，所以任何改动过的 APK 都**必须重新签名** ——
     * 于是「签名对不上」就等价于「这个包被第三方重新打包过」，正是「改掉告示再拿去卖」的场景。
     * 二次打包者当然也能把这段校验 patch 掉，但那比删一行文案难得多，而且他得同时做两件事。
     */
    private val OFFICIAL_SIGNATURE_SHA256 =
        "8d09734c18c27c0cdc229532b571ea416f44ce57b67228f46c67da1d4ccc6aa7"

    private val OFFICIAL_REPO = "https://github.com/wangcangxing/screen-ocr-assistant"

    /** 当前安装包的签名 SHA-256（十六进制小写）；取不到时返回空串 */
    private fun currentSignatureSha256(): String = runCatching {
        val pm = packageManager
        val certs = if (android.os.Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures
        }
        val first = certs?.firstOrNull() ?: return ""
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(first.toByteArray()).joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    /** 是否为官方构建；debug 构建不参与校验，避免误伤自己编译的人 */
    private fun isOfficialBuild(): Boolean {
        val debuggable = (applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) return true
        val cur = currentSignatureSha256()
        return cur.isEmpty() || cur.equals(OFFICIAL_SIGNATURE_SHA256, ignoreCase = true)
    }

    /**
     * 启动告示弹窗。
     *
     * **故意把文案硬编码在这里**（不放进 `strings.xml`、不读 `Prefs`、不读 config.json）：
     * 这样二次打包的人不容易顺手改掉。它是给「**付费**买到这个软件」的人看的 ——
     * 本项目以 PolyForm Noncommercial 1.0.0 发布，任何人售卖它都违反许可证。
     *
     * 签名对不上时显示**更硬的版本**：告诉用户这个包被第三方重打包过、别付钱、去举报。
     */
    private fun showStartupNotice() {
        val official = isOfficialBuild()
        val text = if (official) buildString {
            append("本软件【免费且源码公开】（PolyForm Noncommercial License 1.0.0），禁止任何商业使用。\n\n")
            append("如果你是【花钱】得到的它，说明有人在拿它牟利 —— 这违反了许可证。\n")
            append("建议你向出售方所在的平台或应用商店【举报】，并要求退款。\n\n")
            append("作者从未在任何平台售卖过本软件。唯一官方发布地址：\n$OFFICIAL_REPO\n\n")
            append("（本告示由程序内置、不读取任何配置；删改它同样违反许可证。）")
        } else buildString {
            append("【本安装包的签名与官方版本不一致 —— 它被第三方重新打包过。】\n\n")
            append("官方版本完全免费、源码公开；作者从未在任何平台售卖，也没有任何付费版或授权码。\n")
            append("如果你为它付过钱：请立即向出售方所在平台【举报】并要求退款。\n\n")
            append("官方唯一发布地址：\n$OFFICIAL_REPO\n\n")
            append("可自行核对：apksigner verify --print-certs 你手上的.apk\n")
            append("官方签名 SHA-256 = $OFFICIAL_SIGNATURE_SHA256")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(if (official) "⚠️ 请先读这段告示" else "⛔ 本应用已被第三方重新打包")
            .setMessage(text)
            .setCancelable(false)                       // 必须点掉，避免被忽略
            .setPositiveButton(if (official) "我已阅读" else "我知道了") { _, _ -> }
            .show()
        if (!official) {
            AppLog.w("本安装包签名与官方不一致（当前 ${currentSignatureSha256()}）—— 疑似被第三方重新打包")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
