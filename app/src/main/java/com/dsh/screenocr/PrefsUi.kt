package com.dsh.screenocr

import android.app.Activity
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView

/**
 * 两页共享的「偏好 ↔ 界面控件」绑定器（v1.4 拆页时抽出来）。
 *
 * 拆页后 [MainActivity]（主页）与 [SettingsActivity]（高级设置）各持有一部分控件，
 * 如果两边各写一份 load/save，很容易出现「同一个键在两页里默认值或范围不一致」的漂移。
 * 所以这里集中两件事：
 *  1. [load]：把当前偏好灌进**当前页面存在的**控件（按 `R.id` 逐个 `when` 判断，控件不存在就跳过）；
 *  2. [save]：把**当前页面存在的**控件写回偏好（同样只写自己有的键，不碰另一页的字段）。
 *
 * 状态文本 [statusText] 也在这里生成，保证主页与设置页显示同一份口径。
 */
object PrefsUi {

    // ------------------------------------------------------------------ 载入

    fun load(act: Activity, prefs: Prefs) {
        fun et(id: Int): EditText? = act.findViewById(id)
        fun sw(id: Int): Switch? = act.findViewById(id)

        et(R.id.etBaseUrl)?.setText(prefs.baseUrl)
        et(R.id.etApiKey)?.setText(prefs.apiKey)
        et(R.id.etModel)?.setText(prefs.model)
        et(R.id.etReasoningEffort)?.setText(prefs.reasoningEffort)
        et(R.id.etImageMaxSide)?.setText(prefs.imageMaxSide.toString())
        et(R.id.etPollInterval)?.setText(prefs.pollIntervalMs.toString())
        et(R.id.etOcrScale)?.setText(prefs.ocrScalePercent.toString())
        et(R.id.etSystemPrompt)?.setText(prefs.systemPrompt)
        et(R.id.etMinScore)?.setText(prefs.minScore.toString())
        et(R.id.etDebounce)?.setText(prefs.debounceMs.toString())
        et(R.id.etMinConf)?.setText(prefs.minConfidence.toString())
        et(R.id.etDedup)?.setText(prefs.dedupSeconds.toString())
        et(R.id.etTimeout)?.setText(prefs.timeoutMs.toString())
        et(R.id.etSomMaxElements)?.setText(prefs.somMaxElements.toString())
        et(R.id.etOmniParserUrl)?.setText(prefs.omniParserUrl)
        et(R.id.etOmniParserTimeout)?.setText(prefs.omniParserTimeoutMs.toString())
        et(R.id.etAutoAdvanceMaxStreak)?.setText(prefs.autoAdvanceMaxStreak.toString())

        act.findViewById<RadioGroup>(R.id.rgFinishMode)?.let { rg ->
            val target = when (prefs.finishMode) {
                Prefs.FINISH_SUBMIT -> R.id.rbFinishSubmit
                Prefs.FINISH_IDLE -> R.id.rbFinishIdle
                else -> R.id.rbFinishOff
            }
            if (rg.checkedRadioButtonId != target) rg.check(target)
        }

        sw(R.id.swProcessing)?.isChecked = prefs.processingEnabled
        sw(R.id.swAutoClick)?.isChecked = prefs.autoClick
        sw(R.id.swDryRun)?.isChecked = prefs.dryRun
        sw(R.id.swSendScreenshot)?.isChecked = prefs.sendScreenshot
        sw(R.id.swSom)?.isChecked = prefs.somEnabled
        sw(R.id.swOmniParser)?.isChecked = prefs.omniParserEnabled
        sw(R.id.swAutoAdvance)?.isChecked = prefs.autoAdvanceEnabled
    }

    // ------------------------------------------------------------------ 保存

    /**
     * 把当前页面的控件写回偏好。**只写存在的控件**，所以主页保存不会把设置页的字段清零。
     * 接口地址做了规范化校验（写坏了之后每次请求都失败，且很难看出少了字符）。
     * 返回 `true` 表示没有字段因非法被拒。
     */
    fun save(act: Activity, prefs: Prefs): Boolean {
        var allOk = true
        fun et(id: Int): EditText? = act.findViewById(id)
        fun sw(id: Int): Switch? = act.findViewById(id)

        et(R.id.etBaseUrl)?.let { v ->
            val raw = v.text.toString().trim()
            val normalized = normalizeBaseUrl(raw)
            if (normalized == null) {
                AppLog.e("接口地址无效，已保留原值 '${prefs.baseUrl}'；本次输入为 '$raw'")
                v.setText(prefs.baseUrl)
                allOk = false
            } else {
                prefs.baseUrl = normalized
                if (normalized != raw) AppLog.i("接口地址已规范化为 '$normalized'（原输入 '$raw'）")
            }
        }
        et(R.id.etApiKey)?.let { prefs.apiKey = it.text.toString() }
        et(R.id.etModel)?.let { prefs.model = it.text.toString() }
        et(R.id.etReasoningEffort)?.let { prefs.reasoningEffort = it.text.toString() }
        et(R.id.etImageMaxSide)?.let { prefs.imageMaxSide = intOf(it.text.toString(), 1600, 0, 8192) }
        et(R.id.etPollInterval)?.let { prefs.pollIntervalMs = intOf(it.text.toString(), 2000, 0, 60_000) }
        et(R.id.etOcrScale)?.let { prefs.ocrScalePercent = intOf(it.text.toString(), 100, 30, 100) }
        et(R.id.etSystemPrompt)?.let { prefs.systemPrompt = it.text.toString() }
        et(R.id.etMinScore)?.let { prefs.minScore = intOf(it.text.toString(), 3, 0, 99) }
        et(R.id.etDebounce)?.let { prefs.debounceMs = intOf(it.text.toString(), 1200, 200, 10_000) }
        et(R.id.etMinConf)?.let { prefs.minConfidence = doubleOf(it.text.toString(), 0.5, 0.0, 1.0) }
        et(R.id.etDedup)?.let { prefs.dedupSeconds = intOf(it.text.toString(), 45, 0, 3600) }
        et(R.id.etTimeout)?.let { prefs.timeoutMs = intOf(it.text.toString(), 30_000, 3_000, 180_000) }
        et(R.id.etSomMaxElements)?.let { prefs.somMaxElements = intOf(it.text.toString(), 40, 1, 200) }
        et(R.id.etAutoAdvanceMaxStreak)?.let { prefs.autoAdvanceMaxStreak = intOf(it.text.toString(), 3, 1, 10) }

        // 解析服务地址：允许留空（= 不配置）
        et(R.id.etOmniParserUrl)?.let { v ->
            val omniRaw = v.text.toString().trim()
            if (omniRaw.isBlank()) {
                prefs.omniParserUrl = ""
            } else {
                val omniNorm = normalizeBaseUrl(omniRaw)
                if (omniNorm == null) {
                    AppLog.e("解析服务地址无效，已保留原值 '${prefs.omniParserUrl}'；本次输入为 '$omniRaw'")
                    v.setText(prefs.omniParserUrl)
                    allOk = false
                } else {
                    prefs.omniParserUrl = omniNorm
                }
            }
        }
        et(R.id.etOmniParserTimeout)?.let { prefs.omniParserTimeoutMs = intOf(it.text.toString(), 4000, 1_000, 60_000) }

        sw(R.id.swProcessing)?.let { prefs.processingEnabled = it.isChecked }
        sw(R.id.swAutoClick)?.let { prefs.autoClick = it.isChecked }
        sw(R.id.swDryRun)?.let { prefs.dryRun = it.isChecked }
        sw(R.id.swSendScreenshot)?.let { prefs.sendScreenshot = it.isChecked }
        sw(R.id.swSom)?.let { prefs.somEnabled = it.isChecked }
        sw(R.id.swOmniParser)?.let { prefs.omniParserEnabled = it.isChecked }
        sw(R.id.swAutoAdvance)?.let { prefs.autoAdvanceEnabled = it.isChecked }

        act.findViewById<RadioGroup>(R.id.rgFinishMode)?.let { rg ->
            prefs.finishMode = when (rg.checkedRadioButtonId) {
                R.id.rbFinishSubmit -> Prefs.FINISH_SUBMIT
                R.id.rbFinishIdle -> Prefs.FINISH_IDLE
                else -> Prefs.FINISH_OFF
            }
        }

        AppLog.i(
            "已保存配置：接口=${prefs.baseUrl} 模型=${prefs.model} 视觉=${prefs.sendScreenshot}" +
                " 强度=${prefs.reasoningEffort.ifEmpty { "(默认)" }}" +
                " SoM=${prefs.somEnabled}(上限${prefs.somMaxElements})" +
                " 解析服务=${prefs.omniParserEnabled}@${prefs.omniParserUrl.ifBlank { "(未配置)" }}" +
                " 自动推进=${prefs.autoAdvanceEnabled}(连击上限${prefs.autoAdvanceMaxStreak})" +
                " 收尾=${prefs.finishMode}"
        )
        return allOk
    }

    // ------------------------------------------------------------------ 状态文本

    /** 主页与设置页共用的一份状态文本（内容没变时调用方自行决定要不要 setText）。 */
    fun statusText(act: Activity, prefs: Prefs, omniHealthText: String): String {
        val sb = StringBuilder()
        sb.append("无障碍服务：")
            .append(if (ScreenOcrAccessibilityService.isConnected()) "已连接 ✅" else "未连接 ❌")
        sb.append('\n')
        sb.append("系统无障碍列表中已启用：").append(if (isAccessibilityEnabledInSettings(act)) "是" else "否")
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
        sb.append("视觉模式：")
            .append(if (prefs.sendScreenshot) "开（每次提问随截图一起发送，最长边 ${prefs.imageMaxSide}px）" else "关（只发 OCR 文字）")
        sb.append('\n')
        sb.append("推理强度：").append(prefs.reasoningEffort.ifBlank { "（留空，用服务端默认）" })
        sb.append('\n')
        sb.append("轮询：").append(
            if (prefs.pollIntervalMs > 0) "每 ${prefs.pollIntervalMs}ms 一次（视频/动画场景靠它发现题目）"
            else "已关闭（只靠无障碍事件，视频里的题目发现不到）"
        )
        sb.append('\n')
        sb.append("OCR 缩放：").append(prefs.ocrScalePercent).append('%')
        sb.append('\n')
        sb.append("SoM 编号标注：").append(
            if (prefs.somEnabled) "开（最多 ${prefs.somMaxElements} 个编号框，模型可回 E<编号>）"
            else "关（不发标注图/清单，行为回到 v1.3）"
        )
        sb.append('\n')
        sb.append("解析服务：").append(
            if (!prefs.omniParserEnabled) "关"
            else prefs.omniParserUrl.ifBlank { "开，但未配置地址" } + "　" + omniHealthText
        )
        sb.append('\n')
        sb.append("答完自动推进：").append(
            if (!prefs.autoAdvanceEnabled) "关"
            else "开（先点「下一题」类按钮，没有则向下滚动；连击上限 ${prefs.autoAdvanceMaxStreak} 次）"
        )
        sb.append('\n')
        sb.append("答题完毕收尾：").append(
            when (prefs.finishMode) {
                Prefs.FINISH_SUBMIT -> "直接提交（自动点「提交作业」并在弹窗里确认；读不到按钮/确认就不提交）"
                Prefs.FINISH_IDLE -> "继续答题（整卷答完即停手，不再操作本应用）"
                else -> "关（不做收尾动作）"
            }
        )
        return sb.toString()
    }

    /** 状态文本只写进当前页面存在的那个 TextView（主页 `statusText` / 设置页 `statusTextSettings`）。 */
    fun renderStatus(act: Activity, text: String) {
        val views = listOfNotNull(
            act.findViewById<TextView>(R.id.statusText),
            act.findViewById<TextView>(R.id.statusTextSettings)
        )
        for (v in views) if (v.text.toString() != text) v.text = text
    }

    /** 把状态文本写进指定控件（`refreshStatus` 的两页共用实现）。 */
    fun refreshStatus(act: Activity, prefs: Prefs, omniHealthText: String) {
        renderStatus(act, statusText(act, prefs, omniHealthText))
    }

    // ------------------------------------------------------------------ 小工具

    /** 规范化后的 base url；不是合法 http(s) 地址则返回 null */
    fun normalizeBaseUrl(raw: String): String? {
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

    fun intOf(raw: String, def: Int, lo: Int, hi: Int): Int {
        val v = raw.trim().toIntOrNull() ?: def
        return v.coerceIn(lo, hi)
    }

    fun doubleOf(raw: String, def: Double, lo: Double, hi: Double): Double {
        val v = raw.trim().toDoubleOrNull() ?: def
        return v.coerceIn(lo, hi)
    }

    /** 无障碍服务是否已在本机系统设置里启用（主页与设置页共用） */
    fun isAccessibilityEnabledInSettings(act: Activity): Boolean {
        val raw = Settings.Secure.getString(
            act.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expected = "${act.packageName}/${ScreenOcrAccessibilityService::class.java.name}"
        return raw.split(':').any { it.equals(expected, true) }
    }

    /** 让一个 View 在存在时才可见 */
    fun setVisible(v: View?, visible: Boolean) {
        v?.visibility = if (visible) View.VISIBLE else View.GONE
    }
}
