package com.dsh.screenocr

import android.content.Context
import android.content.SharedPreferences

/**
 * 全部可调参数集中在这里，键名与默认值见 companion。
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    init {
        migrateSystemPrompt()
    }

    /**
     * 预设提示词升级：只有当用户还停留在旧的内置默认值（或从未设置）时才覆盖，
     * 用户自己改过的提示词绝不动。
     */
    private fun migrateSystemPrompt() {
        val stored = sp.getString(K_SYSTEM_PROMPT, null)
        val version = sp.getInt(K_PROMPT_VERSION, 0)
        if (version >= PROMPT_VERSION) return
        val untouched = stored == null || stored.isBlank() ||
            stored.trim() == LEGACY_PROMPT_V1.trim() ||
            stored.trim() == D_SYSTEM_PROMPT
        if (!untouched) return
        sp.edit()
            .putString(K_SYSTEM_PROMPT, D_SYSTEM_PROMPT)
            .putInt(K_PROMPT_VERSION, PROMPT_VERSION)
            .apply()
    }

    /** 设置界面上的「恢复默认提示词」 */
    fun restoreDefaultSystemPrompt() {
        sp.edit()
            .putString(K_SYSTEM_PROMPT, D_SYSTEM_PROMPT)
            .putInt(K_PROMPT_VERSION, PROMPT_VERSION)
            .apply()
    }

    private fun str(key: String, def: String): String = sp.getString(key, def) ?: def

    /** OpenAI 兼容接口的 base url，去掉结尾的 '/' */
    var baseUrl: String
        get() = str(K_BASE_URL, D_BASE_URL).trim().trim('/', ' ')
        set(v) = sp.edit().putString(K_BASE_URL, v.trim()).apply()

    var apiKey: String
        get() = str(K_API_KEY, "").trim()
        set(v) = sp.edit().putString(K_API_KEY, v.trim()).apply()

    var model: String
        get() = str(K_MODEL, D_MODEL).trim()
        set(v) = sp.edit().putString(K_MODEL, v.trim()).apply()

    var systemPrompt: String
        get() = str(K_SYSTEM_PROMPT, D_SYSTEM_PROMPT)
        set(v) = sp.edit().putString(K_SYSTEM_PROMPT, v).apply()

    /** 屏幕处理总开关（关掉后无障碍服务仍在，但不截图不调用接口） */
    var processingEnabled: Boolean
        get() = sp.getBoolean(K_PROCESSING, true)
        set(v) = sp.edit().putBoolean(K_PROCESSING, v).apply()

    var autoClick: Boolean
        get() = sp.getBoolean(K_AUTO_CLICK, true)
        set(v) = sp.edit().putBoolean(K_AUTO_CLICK, v).apply()

    /** 试运行：只算不点 */
    var dryRun: Boolean
        get() = sp.getBoolean(K_DRY_RUN, false)
        set(v) = sp.edit().putBoolean(K_DRY_RUN, v).apply()

    /**
     * 视觉模式：把屏幕截图一并放进 user 消息发给多模态模型。
     * OCR 仍然要做 —— 定位「点哪里」靠的是 OCR 的包围盒，模型没法告诉我们坐标。
     * 默认关闭：默认模型不一定是多模态的。点「获取可用模型」选中带 image 模态的模型时会自动打开。
     */
    var sendScreenshot: Boolean
        get() = sp.getBoolean(K_SEND_SCREENSHOT, false)
        set(v) = sp.edit().putBoolean(K_SEND_SCREENSHOT, v).apply()

    /** 发图前把长边缩到这个像素数以内（0 = 不缩放）。1080x2400 原图 JPEG 约 0.5MB，缩小可省流量 */
    var imageMaxSide: Int
        get() = sp.getInt(K_IMAGE_MAX_SIDE, 1600)
        set(v) = sp.edit().putInt(K_IMAGE_MAX_SIDE, v).apply()

    /** 图片 JPEG 质量 */
    var imageQuality: Int
        get() = sp.getInt(K_IMAGE_QUALITY, 85)
        set(v) = sp.edit().putInt(K_IMAGE_QUALITY, v).apply()

    /**
     * 轮询间隔（毫秒）。**0 = 关闭轮询**，只靠无障碍事件触发。
     *
     * 视频 / 动画 / 游戏画面不会产生无障碍事件，必须靠轮询才能发现里面出现的题目。
     * 间隔越短越不容易漏（题目可能只出现几秒），但越费电。
     */
    var pollIntervalMs: Int
        get() = sp.getInt(K_POLL_INTERVAL, D_POLL_INTERVAL)
        set(v) = sp.edit().putInt(K_POLL_INTERVAL, v).apply()

    /** 送进 OCR 前把截图缩到百分之多少（30~100）。轮询模式下调低可显著省 CPU，代价是小字可能漏认 */
    var ocrScalePercent: Int
        get() = sp.getInt(K_OCR_SCALE, 100)
        set(v) = sp.edit().putInt(K_OCR_SCALE, v).apply()

    /**
     * 推理强度（思考模式的 effort）。
     * 官方默认是「思考模式打开 + effort=high」，对本应用太慢太贵，故默认取低档。
     * 留空 = 请求里不带 reasoning_effort（交给服务端默认值）。
     */
    var reasoningEffort: String
        get() = sp.getString(K_REASONING_EFFORT, D_REASONING_EFFORT)!!.trim()
        set(v) = sp.edit().putString(K_REASONING_EFFORT, v.trim()).apply()

    /** 题目判定分值阈值 */
    var minScore: Int
        get() = sp.getInt(K_MIN_SCORE, 3)
        set(v) = sp.edit().putInt(K_MIN_SCORE, v).apply()

    var debounceMs: Int
        get() = sp.getInt(K_DEBOUNCE, 1200)
        set(v) = sp.edit().putInt(K_DEBOUNCE, v).apply()

    var minConfidence: Double
        get() = sp.getFloat(K_MIN_CONF, 0.5f).toDouble()
        set(v) = sp.edit().putFloat(K_MIN_CONF, v.toFloat()).apply()

    var dedupSeconds: Int
        get() = sp.getInt(K_DEDUP, 45)
        set(v) = sp.edit().putInt(K_DEDUP, v).apply()

    var timeoutMs: Int
        get() = sp.getInt(K_TIMEOUT, 30000)
        set(v) = sp.edit().putInt(K_TIMEOUT, v).apply()

    companion object {
        private const val FILE = "screen_ocr_prefs"

        private const val K_BASE_URL = "base_url"
        private const val K_API_KEY = "api_key"
        private const val K_MODEL = "model"
        private const val K_SYSTEM_PROMPT = "system_prompt"
        private const val K_PROMPT_VERSION = "system_prompt_version"
        private const val K_PROCESSING = "processing_enabled"
        private const val K_AUTO_CLICK = "auto_click"
        private const val K_DRY_RUN = "dry_run"
        private const val K_SEND_SCREENSHOT = "send_screenshot"
        private const val K_IMAGE_MAX_SIDE = "image_max_side"
        private const val K_IMAGE_QUALITY = "image_quality"
        private const val K_POLL_INTERVAL = "poll_interval_ms"
        private const val K_OCR_SCALE = "ocr_scale_percent"
        private const val K_REASONING_EFFORT = "reasoning_effort"
        private const val K_MIN_SCORE = "min_score"
        private const val K_DEBOUNCE = "debounce_ms"
        private const val K_MIN_CONF = "min_confidence"
        private const val K_DEDUP = "dedup_seconds"
        private const val K_TIMEOUT = "timeout_ms"

        const val D_BASE_URL = "https://api.deepseek.com/v1"
        const val D_MODEL = "deepseek-chat"

        /** 默认推理强度：官方默认是 high，这里主动降到低档以求快与省 */
        const val D_REASONING_EFFORT = "low"

        /** 默认轮询间隔：视频里题目可能只出现几秒，2 秒一查是比较稳的折中 */
        const val D_POLL_INTERVAL = 2000

        /** 预设提示词版本：改 D_SYSTEM_PROMPT 时 +1，老用户会被自动升级（仅当没自己改过） */
        const val PROMPT_VERSION = 2

        /**
         * 预设提示词：直接给出选项，不输出任何其他内容。
         * 之所以留一个 NONE 出口：本地启发式只是「疑似题目」，需要模型给一个明确的否决信号，
         * 否则模型会被迫在非题目画面上硬选一个选项，导致误点。
         */
        val D_SYSTEM_PROMPT = """
你是答题助手。用户会给你一段从手机屏幕上 OCR 识别出来的文字。
如果其中包含需要作答的题目，只输出正确选项的字母（例如 B）或编号（例如 2）。
只输出这一个字符：不要解释、不要复述题干、不要加标点、不要输出任何其他内容。
如果这段文字里没有需要作答的题目，只输出 NONE。
""".trim()

        /** 旧版（JSON 协议）预设提示词，仅用于识别「用户没改过、可以安全升级」的存量安装 */
        val LEGACY_PROMPT_V1 = """
你是手机屏幕助手。用户会把从手机屏幕上 OCR 识别出来的文字给你。
请判断其中是否包含一道需要作答的题目（选择题／判断题／填空题／问答题等）。

只输出一个 JSON 对象，不要输出任何解释性文字，不要用 ``` 代码块包裹。字段含义：
{"is_question": 布尔值，是否是一道需要作答的题目,
 "answer_label": 若为选择题，给出正确选项的字母或编号（如 A、B、C、D、1、2）；否则空字符串,
 "answer_text": 若为选择题，给出正确选项的原文（与屏幕上的选项文字尽量逐字一致）；否则空字符串,
 "confidence": 0 到 1 之间的小数，表示你对答案的把握,
 "explanation": 一句话理由}

注意：OCR 结果可能有错别字或漏字，请结合上下文理解后再作答。
若屏幕文字里没有题目，is_question 填 false。
""".trim()
    }
}
