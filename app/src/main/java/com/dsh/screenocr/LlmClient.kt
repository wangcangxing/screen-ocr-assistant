package com.dsh.screenocr

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * OpenAI 兼容的 /chat/completions 客户端。
 * 只用 HttpURLConnection + org.json，不额外引入网络库。
 * 回调统一切回主线程。
 */
object LlmClient {

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "llm-io") }
    private val main = Handler(Looper.getMainLooper())

    private const val MAX_RESPONSE_CHARS = 200_000

    data class Answer(
        val isQuestion: Boolean,
        val answerLabel: String,
        val answerText: String,
        val confidence: Double,
        val explanation: String,
        val rawContent: String
    )

    data class Result(
        val ok: Boolean,
        val answer: Answer? = null,
        val error: String? = null,
        val httpCode: Int = 0,
        val latencyMs: Long = 0,
        val url: String = ""
    )

    /** 把 base url 补全成 chat/completions 端点 */
    fun endpointOf(baseUrl: String): String {
        val b = stripEndpoint(baseUrl)
        return if (b.endsWith("/chat/completions")) b else "$b/chat/completions"
    }

    /** 把 base url 补全成 models 端点（GET，用于列出真实可用的模型） */
    fun modelsEndpointOf(baseUrl: String): String = stripEndpoint(baseUrl) + "/models"

    private fun stripEndpoint(baseUrl: String): String {
        var b = baseUrl.trim().trimEnd('/')
        if (b.endsWith("/chat/completions")) b = b.removeSuffix("/chat/completions").trimEnd('/')
        return b
    }

    /**
     * 单个模型的信息。字段名来自 DeepSeek `GET /models` 的响应 schema：
     * id / name / context_window / max_output_tokens / input_modalities / output_modalities。
     */
    data class ModelInfo(
        val id: String,
        val name: String,
        val contextWindow: Int,
        val inputModalities: List<String>,
        val supportedEfforts: List<String> = emptyList(),
        val defaultEffort: String = ""
    ) {
        val supportsImage: Boolean get() = inputModalities.any { it.equals("image", true) }

        /**
         * 该模型实际接受的推理强度里，最低的那一档。
         * 官方默认 effort 是 high；本应用想跑低档，所以优先挑 low，没有就取服务端给出的第一档。
         */
        fun lowestEffort(): String = when {
            supportedEfforts.isEmpty() -> ""
            supportedEfforts.any { it.equals("low", true) } -> "low"
            else -> supportedEfforts.first()
        }

        fun label(): String = buildString {
            append(id)
            if (name.isNotBlank() && name != id) append("（").append(name).append("）")
            append(if (supportsImage) "  [可看图片]" else "  [纯文本]")
            if (contextWindow > 0) append("  上下文 ").append(contextWindow)
            if (supportedEfforts.isNotEmpty()) append("  强度[").append(supportedEfforts.joinToString("/")).append("]")
        }
    }

    data class ModelListResult(
        val ok: Boolean,
        val models: List<ModelInfo> = emptyList(),
        val error: String? = null,
        val httpCode: Int = 0
    )

    /** GET {base}/models —— 用真实返回决定「有哪些模型、哪个能看图」 */
    fun listModels(prefs: Prefs, onResult: (ModelListResult) -> Unit) {
        io.execute {
            val url = modelsEndpointOf(prefs.baseUrl)
            var conn: HttpURLConnection? = null
            val r = try {
                if (prefs.apiKey.isBlank()) {
                    ModelListResult(false, error = "未配置 API Key")
                } else {
                    conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 15_000
                        readTimeout = prefs.timeoutMs.coerceIn(3_000, 180_000)
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("Authorization", "Bearer ${prefs.apiKey}")
                    }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val body = stream?.let { readAll(it) } ?: ""
                    if (code !in 200..299) {
                        ModelListResult(false, error = "HTTP $code: ${body.take(300)}", httpCode = code)
                    } else {
                        val arr = JSONObject(body).optJSONArray("data")
                        val list = ArrayList<ModelInfo>()
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val o = arr.optJSONObject(i) ?: continue
                                val id = o.optString("id", "").trim()
                                if (id.isEmpty()) continue
                                val mods = ArrayList<String>()
                                o.optJSONArray("input_modalities")?.let { m ->
                                    for (k in 0 until m.length()) mods.add(m.optString(k, ""))
                                }
                                val efforts = ArrayList<String>()
                                val effortObj = o.optJSONObject("effort")
                                effortObj?.optJSONArray("supported_levels")?.let { m ->
                                    for (k in 0 until m.length()) efforts.add(m.optString(k, ""))
                                }
                                list.add(
                                    ModelInfo(
                                        id = id,
                                        name = o.optString("name", ""),
                                        contextWindow = o.optInt("context_window", 0),
                                        inputModalities = mods,
                                        supportedEfforts = efforts,
                                        defaultEffort = effortObj?.optString("default_level", "") ?: ""
                                    )
                                )
                            }
                        }
                        if (list.isEmpty()) {
                            ModelListResult(false, error = "响应里没有 data[] 模型列表：${body.take(200)}")
                        } else {
                            ModelListResult(true, models = list)
                        }
                    }
                }
            } catch (t: Throwable) {
                ModelListResult(false, error = "${t.javaClass.simpleName}: ${t.message}")
            } finally {
                runCatching { conn?.disconnect() }
            }
            main.post { onResult(r) }
        }
    }

    fun ask(
        prefs: Prefs,
        question: Question,
        fullText: String,
        imageJpegBase64: String?,
        onResult: (Result) -> Unit
    ) {
        val content = buildUserContent(question, fullText)
        io.execute {
            val r = doAsk(prefs, content, imageJpegBase64)
            main.post { onResult(r) }
        }
    }

    /** 设置界面里的「测试接口连通性」 */
    fun ping(prefs: Prefs, onResult: (Result) -> Unit) {
        val content = "这是一次连通性测试，屏幕上没有题目。请按系统提示要求的格式回复。"
        io.execute {
            val r = doAsk(prefs, content, null)
            main.post {
                // 图片相关失败时给出可操作的提示，而不是让用户对着 400 发呆
                if (!r.ok && r.httpCode == 400 && prefs.sendScreenshot) {
                    onResult(
                        r.copy(
                            error = (r.error ?: "") +
                                "  ← 已开启「发送屏幕截图」，若当前模型不支持图片，请关闭该开关或换用带 image 模态的模型"
                        )
                    )
                } else {
                    onResult(r)
                }
            }
        }
    }

    fun buildUserContent(q: Question, fullText: String): String {
        val sb = StringBuilder()
        sb.append("【屏幕文字】\n").append(fullText.take(4000)).append("\n\n")
        if (q.stem.isNotBlank()) {
            sb.append("【题干】\n").append(q.stem).append("\n\n")
        }
        if (q.options.isNotEmpty()) {
            sb.append("【选项】\n")
            for (o in q.options) {
                sb.append(o.label).append(". ").append(o.text).append('\n')
            }
            sb.append('\n')
        }
        sb.append("请按系统提示要求的格式作答。")
        return sb.toString()
    }

    private fun doAsk(prefs: Prefs, userContent: String, imageJpegBase64: String?): Result {
        val started = System.currentTimeMillis()
        val url = endpointOf(prefs.baseUrl)

        if (prefs.apiKey.isBlank()) return Result(false, error = "未配置 API Key", url = url)
        if (prefs.model.isBlank()) return Result(false, error = "未配置模型名", url = url)
        if (prefs.baseUrl.isBlank()) return Result(false, error = "未配置接口地址", url = url)

        var conn: HttpURLConnection? = null
        try {
            // 有图片时 user.content 必须是「块数组」；图片只能放在 user 消息里（system/assistant 带图会被 400 拒绝）
            val userContentValue: Any = if (imageJpegBase64.isNullOrEmpty()) {
                userContent
            } else {
                JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", userContent)
                    })
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply {
                            put("url", "data:image/jpeg;base64,$imageJpegBase64")
                            put("detail", "high")
                        })
                    })
                }
            }

            val payload = JSONObject().apply {
                put("model", prefs.model)
                // 思考模式下 temperature 被服务端忽略（官方文档明确说明），但在非思考模型上仍有效，故保留
                put("temperature", 0)
                put("stream", false)
                // 官方默认 effort 是 high；这里按设置发低档，求快与省
                val effort = prefs.reasoningEffort
                if (effort.isNotEmpty()) put("reasoning_effort", effort)
                put(
                    "messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", prefs.systemPrompt)
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", userContentValue)
                        })
                    }
                )
            }.toString()

            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = prefs.timeoutMs.coerceIn(3_000, 180_000)
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer ${prefs.apiKey}")
            }

            conn.outputStream.use { os ->
                os.write(payload.toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val code = conn.responseCode
            val stream: InputStream? = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let { readAll(it) } ?: ""
            val latency = System.currentTimeMillis() - started

            if (code !in 200..299) {
                return Result(
                    false,
                    error = "HTTP $code: ${body.take(300)}",
                    httpCode = code,
                    latencyMs = latency,
                    url = url
                )
            }

            val message = JSONObject(body)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
            val content = message?.optString("content").orEmpty()
            // 思考模式下思维链在 reasoning_content 里，与 content 同级
            val reasoning = message?.optString("reasoning_content").orEmpty()
            if (reasoning.isNotEmpty()) {
                AppLog.i("   模型思考了 ${reasoning.length} 字（reasoning_content），本次 effort=${prefs.reasoningEffort.ifEmpty { "服务端默认" }}")
            }

            if (content.isBlank()) {
                // 只有思维链、没有最终答案：通常是 max_tokens 不够被截断，或模型只输出了思考
                val hint = if (reasoning.isNotEmpty())
                    "只有 reasoning_content、没有 content（思考被截断？），思维链开头：${reasoning.take(200)}"
                else "响应中没有 choices[0].message.content"
                return Result(
                    false,
                    error = hint,
                    httpCode = code,
                    latencyMs = latency,
                    url = url
                )
            }

            val answer = parseAnswer(content)
                ?: return Result(
                    false,
                    error = "无法从模型输出中解析出 JSON：${content.take(300)}",
                    httpCode = code,
                    latencyMs = latency,
                    url = url
                )

            return Result(true, answer = answer, httpCode = code, latencyMs = latency, url = url)
        } catch (t: Throwable) {
            return Result(
                false,
                error = "${t.javaClass.simpleName}: ${t.message}",
                latencyMs = System.currentTimeMillis() - started,
                url = url
            )
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 容忍两种协议：
     *  A) JSON —— 用户把预设提示词改成要求 JSON 时走这条；
     *  B) 纯文本 —— 预设提示词是「只输出选项，不要输出其他内容」，回复可能就是 "B"。
     * 两种都解析不出来时返回 null（调用方据此放弃，不会乱点）。
     */
    fun parseAnswer(content: String): Answer? {
        val cleaned = TextMatch.stripDecorations(content)
        if (cleaned.isEmpty()) return null

        // A) JSON
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start >= 0 && end > start) {
            runCatching {
                val o = JSONObject(cleaned.substring(start, end + 1))
                if (o.has("is_question") || o.has("answer_label") || o.has("answer_text")) {
                    val conf = o.optDouble("confidence", 0.0).let { if (it.isNaN()) 0.0 else it }
                    return Answer(
                        isQuestion = o.optBoolean("is_question", true),
                        answerLabel = o.optString("answer_label", "").trim(),
                        answerText = o.optString("answer_text", "").trim(),
                        confidence = conf.coerceIn(0.0, 1.0),
                        explanation = o.optString("explanation", "").trim(),
                        rawContent = content
                    )
                }
            }
        }

        // B) 纯文本
        if (TextMatch.isNoneReply(cleaned)) {
            return Answer(
                isQuestion = false,
                answerLabel = "",
                answerText = "",
                confidence = 0.0,
                explanation = "模型回复「没有题目」",
                rawContent = content
            )
        }

        val line = TextMatch.firstMeaningfulLine(cleaned)
        if (line.isEmpty()) return null

        if (line.length > 60) {
            // 太长的自由文本，无法安全映射到某个选项 —— 保留原文供人看，但标签留空
            return Answer(
                isQuestion = true,
                answerLabel = "",
                answerText = line.take(60),
                confidence = 0.2,
                explanation = "模型输出较长，未按「只给选项」的格式回复",
                rawContent = content
            )
        }

        val label = TextMatch.normalizeLabel(line)
        return Answer(
            isQuestion = true,
            answerLabel = label,
            answerText = line,
            confidence = if (label.isNotEmpty()) 0.9 else 0.3,
            explanation = "",
            rawContent = content
        )
    }

    private fun readAll(ins: InputStream): String {
        val sb = StringBuilder()
        BufferedReader(InputStreamReader(ins, Charsets.UTF_8)).use { br ->
            val buf = CharArray(4096)
            while (true) {
                val n = br.read(buf)
                if (n <= 0) break
                sb.append(buf, 0, n)
                if (sb.length > MAX_RESPONSE_CHARS) break
            }
        }
        return sb.toString()
    }
}
