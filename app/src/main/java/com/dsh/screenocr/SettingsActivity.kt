package com.dsh.screenocr

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 高级设置页（v1.4 起从主页拆出来）：低频项集中在这里。
 *
 *  - 大模型接口（地址 / Key / 模型列表 / 推理强度 / 系统提示词 / 视觉模式）与连通性测试
 *  - 识别与触发参数（发图最长边、轮询、OCR 缩放、判题阈值、去抖、置信度、去重、超时）
 *  - SoM 元素上限
 *  - 解析服务（PC 侧 OmniParser）：开关 / 地址 / 超时 / 测试
 *  - 运行日志
 *
 * 主页只留每天要动的开关，见 [MainActivity]。两页共用 [PrefsUi] 做读写与状态文本，
 * 并且 [PrefsUi.save] 只写「当前页面存在的控件」，所以任一页保存都不会清掉另一页的字段。
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())

    /** 界面上只显示最近这么多条（完整日志仍在 AppLog 与 logcat 里，长按日志区可复制全部） */
    private val logUiLines = 25

    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

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
        setContentView(R.layout.activity_settings)
        title = getString(R.string.title_settings)
        prefs = Prefs(this)

        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)

        // 长按日志区把全部日志复制走（替代 textIsSelectable，避免它抢初始焦点）
        logText.setOnLongClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("ScreenOcr 日志", AppLog.tail(400)))
            toast("日志已复制到剪贴板")
            true
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            savePrefs()
            toast("已保存")
        }
        findViewById<Button>(R.id.btnDefaultPrompt).setOnClickListener {
            prefs.restoreDefaultSystemPrompt()
            findViewById<EditText>(R.id.etSystemPrompt).setText(prefs.systemPrompt)
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
        findViewById<Button>(R.id.btnTestOmni).setOnClickListener {
            savePrefs()
            testOmniParser()
        }
        findViewById<Button>(R.id.btnRefreshLog).setOnClickListener { logListener(AppLog.snapshot()) }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener { AppLog.clear() }

        // ---- v1.4：开关**一拨即存** ----
        // 拆页后「保存配置」按钮在页面顶部，而开关（视觉模式/解析服务）在页面底部，
        // 只保存文本字段 = 用户拨了开关却没落盘，开关还会被 loadPrefs 弹回原状（真机踩到：
        // 拨了开关却没落盘、还会被 loadPrefs 弹回）。
        // 开关语义本来就是"立即生效"，所以这里统一即时写盘；文本字段仍走「保存配置」。
        // 顺序很重要：**先 loadPrefs 再挂监听** —— 否则程序化设置 isChecked 会触发
        // OnCheckedChangeListener，把刚读出来的值又写回去（还会刷一条假日志）。
        loadPrefs()

        wireSwitch(R.id.swSendScreenshot) { prefs.sendScreenshot = it }
        wireSwitch(R.id.swOmniParser) { prefs.omniParserEnabled = it }

        AppLog.addListener(logListener)
        val root = findViewById<ScrollView>(R.id.settingsScroll)
        root.post { root.fullScroll(ScrollView.FOCUS_UP) }
        ui.post(statusTicker)
    }

    /**
     * 给一个开关挂上「拨动即写盘 + 写日志」，避免"拨了没保存、被 loadPrefs 弹回"的假交互。
     * 注意：必须等 [loadPrefs] 之后再挂监听，否则程序化设置 `isChecked` 会自己触发一次写入。
     */
    private fun wireSwitch(id: Int, apply: (Boolean) -> Unit) {
        val sw = findViewById<android.widget.Switch>(id) ?: return
        sw.setOnCheckedChangeListener { _, checked ->
            apply(checked)
            AppLog.i("开关已即时保存：${resources.getResourceEntryName(id)}=$checked")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLog.removeListener(logListener)
        ui.removeCallbacks(statusTicker)
    }

    // ------------------------------------------------------------------ 读写配置

    private fun loadPrefs() = PrefsUi.load(this, prefs)

    private fun savePrefs() {
        val ok = PrefsUi.save(this, prefs)
        if (!ok) toast("部分字段无效，已保留原值")
        refreshStatus()
    }

    private fun refreshStatus() {
        PrefsUi.refreshStatus(this, prefs, OmniHealthStatus.text)
    }

    // ------------------------------------------------------------------ 网络测试

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
     * 「测试解析服务」：GET {url}/health（契约 1.1）。
     * 结果经 [OmniHealthStatus] 写进状态区（主页与设置页都会显示），方便在设备上直接看。
     */
    private fun testOmniParser() {
        val url = findViewById<EditText>(R.id.etOmniParserUrl).text.toString().trim().trimEnd('/')
        if (url.isBlank()) {
            toast("请先填写解析服务地址")
            return
        }
        val timeout = PrefsUi.intOf(
            findViewById<EditText>(R.id.etOmniParserTimeout).text.toString(), 4000, 1_000, 60_000
        )
        toast("正在请求解析服务…")
        AppLog.i("— GET ${OmniParserClient.healthEndpointOf(url)}（超时 ${timeout}ms）—")
        OmniParserClient.health(url, timeout) { r ->
            OmniHealthStatus.update(
                if (r.ok) {
                    "上次测试：正常（HTTP ${r.httpCode}，mode=${r.mode.ifBlank { "?" }}，backend=${r.backend.ifBlank { "?" }}，${r.latencyMs}ms）"
                } else {
                    "上次测试失败：${r.error?.take(60)}"
                }
            )
            if (r.ok) {
                AppLog.i("— 解析服务正常：service=${r.service} version=${r.version} mode=${r.mode} backend=${r.backend}（${r.latencyMs}ms）")
                toast("解析服务正常（${r.latencyMs}ms）")
            } else {
                AppLog.e("— 解析服务测试失败：${r.error}")
                toast("解析服务失败：${r.error?.take(80)}")
            }
            refreshStatus()
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

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
