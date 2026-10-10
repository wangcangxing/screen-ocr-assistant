package com.dsh.screenocr

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 从外部配置文件导入设置。
 *
 * 用途：用 adb 部署时不必在手机上敲一长串 API Key，也方便备份/迁移配置。
 * 路径：/sdcard/Android/data/<包名>/files/config.json
 *   adb push config.json /sdcard/Android/data/com.dsh.screenocr.omni/files/config.json
 *
 * 只覆盖配置文件里写了、且非空的字段；其余保持应用内已有设置不变。
 */
object ConfigFile {

    const val FILE_NAME = "config.json"

    val TEMPLATE = """
{
  "base_url": "https://api.deepseek.com/v1",
  "model": "deepseek-chat",
  "api_key": "",
  "system_prompt": "",
  "som_enabled": true,
  "som_max_elements": 40,
  "omni_parser_enabled": false,
  "omni_parser_url": "",
  "omni_parser_timeout_ms": 4000,
  "auto_advance_enabled": true,
  "auto_advance_max_streak": 3,
  "finish_mode": "off"
}
""".trim()

    data class Result(val ok: Boolean, val message: String)

    fun fileOf(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, FILE_NAME)
    }

    /** 应用还没配 Key 时，启动自动导入一次；已配置过就不打扰 */
    fun autoImportIfUnconfigured(context: Context, prefs: Prefs) {
        if (prefs.apiKey.isNotBlank()) return
        val f = fileOf(context)
        if (!f.isFile) return
        AppLog.i("启动自动导入配置：${import(context, prefs).message}")
    }

    fun import(context: Context, prefs: Prefs): Result {
        val f = fileOf(context)
        if (!f.isFile) {
            runCatching {
                f.parentFile?.mkdirs()
                f.writeText(TEMPLATE, Charsets.UTF_8)
            }
            return Result(false, "未找到 ${f.absolutePath}（已生成模板，填好后重试）")
        }
        return try {
            val o = JSONObject(f.readText(Charsets.UTF_8))
            val applied = ArrayList<String>(4)
            o.optString("base_url", "").trim().takeIf { it.isNotEmpty() }
                ?.let { prefs.baseUrl = it; applied.add("base_url") }
            o.optString("model", "").trim().takeIf { it.isNotEmpty() }
                ?.let { prefs.model = it; applied.add("model") }
            o.optString("api_key", "").trim().takeIf { it.isNotEmpty() }
                ?.let { prefs.apiKey = it; applied.add("api_key") }
            o.optString("system_prompt", "").trim().takeIf { it.isNotEmpty() }
                ?.let { prefs.systemPrompt = it; applied.add("system_prompt") }
            if (o.has("poll_interval_ms")) {
                prefs.pollIntervalMs = o.optInt("poll_interval_ms", prefs.pollIntervalMs).coerceIn(0, 60_000)
                applied.add("poll_interval_ms=${prefs.pollIntervalMs}")
            }
            if (o.has("ocr_scale_percent")) {
                prefs.ocrScalePercent = o.optInt("ocr_scale_percent", prefs.ocrScalePercent).coerceIn(30, 100)
                applied.add("ocr_scale_percent=${prefs.ocrScalePercent}")
            }
            if (o.has("send_screenshot")) {
                prefs.sendScreenshot = o.optBoolean("send_screenshot", prefs.sendScreenshot)
                applied.add("send_screenshot=${prefs.sendScreenshot}")
            }
            if (o.has("reasoning_effort")) {
                prefs.reasoningEffort = o.optString("reasoning_effort", prefs.reasoningEffort)
                applied.add("reasoning_effort=${prefs.reasoningEffort.ifEmpty { "(空)" }}")
            }
            // ---- v1.4 新增：SoM 编号标注 + OmniParser 解析服务（键名与 Prefs 属性一一对应） ----
            if (o.has("som_enabled")) {
                prefs.somEnabled = o.optBoolean("som_enabled", prefs.somEnabled)
                applied.add("som_enabled=${prefs.somEnabled}")
            }
            if (o.has("som_max_elements")) {
                prefs.somMaxElements = o.optInt("som_max_elements", prefs.somMaxElements).coerceIn(1, 200)
                applied.add("som_max_elements=${prefs.somMaxElements}")
            }
            if (o.has("omni_parser_enabled")) {
                prefs.omniParserEnabled = o.optBoolean("omni_parser_enabled", prefs.omniParserEnabled)
                applied.add("omni_parser_enabled=${prefs.omniParserEnabled}")
            }
            o.optString("omni_parser_url", "").trim().takeIf { it.isNotEmpty() }
                ?.let { prefs.omniParserUrl = it; applied.add("omni_parser_url=$it") }
            if (o.has("omni_parser_timeout_ms")) {
                prefs.omniParserTimeoutMs = o.optInt("omni_parser_timeout_ms", prefs.omniParserTimeoutMs)
                    .coerceIn(1_000, 60_000)
                applied.add("omni_parser_timeout_ms=${prefs.omniParserTimeoutMs}")
            }
            // ---- v1.5 新增：答完自动推进 ----
            if (o.has("auto_advance_enabled")) {
                prefs.autoAdvanceEnabled = o.optBoolean("auto_advance_enabled", prefs.autoAdvanceEnabled)
                applied.add("auto_advance_enabled=${prefs.autoAdvanceEnabled}")
            }
            if (o.has("auto_advance_max_streak")) {
                prefs.autoAdvanceMaxStreak = o.optInt("auto_advance_max_streak", prefs.autoAdvanceMaxStreak)
                    .coerceIn(1, 10)
                applied.add("auto_advance_max_streak=${prefs.autoAdvanceMaxStreak}")
            }
            // ---- v1.6 新增：答题完毕收尾模式（off/submit/idle，契约 §7）----
            if (o.has("finish_mode")) {
                val raw = o.optString("finish_mode", prefs.finishMode).trim().lowercase()
                prefs.finishMode = raw
                val appliedValue = prefs.finishMode            // 取值由 Prefs 做合法性归一
                if (appliedValue != raw) {
                    AppLog.w("配置里的 finish_mode=\"$raw\" 不是 off/submit/idle 之一，已按 off 处理")
                }
                applied.add("finish_mode=$appliedValue")
            }
            if (applied.isEmpty()) Result(false, "配置文件里没有可用的字段")
            else Result(true, "已导入：" + applied.joinToString("、"))
        } catch (t: Throwable) {
            Result(false, "配置解析失败：${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
