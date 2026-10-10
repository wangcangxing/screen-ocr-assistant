package com.dsh.screenocr

import android.graphics.Rect
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
import kotlin.math.roundToInt

/**
 * OmniParser 本地解析服务客户端（PC 侧 Python 服务，契约见 docs/接口约定-SoM与解析服务.md 第 1 节）。
 *
 * 仿 [LlmClient] 的风格：`HttpURLConnection` + `org.json` + 单线程 executor，回调统一切回主线程。
 * 只有两个端点：`GET {url}/health`、`POST {url}/parse`。
 *
 * **失败纪律（契约 3）**：连不上 / 超时 / 非 2xx / JSON 不合契约，一律返回 `ok=false` + `error`，
 * 不抛异常、不中断主流程 —— 调用方按"没有它"继续。
 */
object OmniParserClient {

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "omni-io") }
    private val main = Handler(Looper.getMainLooper())

    /** 响应体上限：`annotate:false` 时不带图，正常响应很小；给足余量防脏服务拖垮内存 */
    private const val MAX_RESPONSE_CHARS = 4_000_000

    /** 契约 1.2：一个元素（字段名逐字对齐） */
    data class Element(
        /** 服务端编号（注意：**不是**发给模型的编号；APK 会重新编 E1..EN） */
        val id: Int,
        /** `"text"` | `"icon"` */
        val type: String,
        /** 给模型/人看的短标签 */
        val label: String,
        /** text 元素的原文；icon 元素为空串 */
        val text: String,
        /** `[x1,y1,x2,y2]`，0..1 归一化，相对**提交图片** */
        val bboxRatio: FloatArray?,
        /** 同一框的像素整数版，相对**提交图片** */
        val bboxPx: IntArray?,
        val interactable: Boolean,
        /** `"ocr"` | `"icon"` */
        val source: String
    ) {
        /** 优先用 [bboxRatio] 换算到屏幕坐标；没有合法 ratio 时退回 [bboxPx]（需知道提交图尺寸） */
        fun screenBox(screenWidth: Int, screenHeight: Int, imageWidth: Int, imageHeight: Int): Rect? {
            if (screenWidth <= 0 || screenHeight <= 0) return null
            val r = bboxRatio
            if (r != null && r.size == 4) {
                val box = Rect(
                    (r[0] * screenWidth).roundToInt(),
                    (r[1] * screenHeight).roundToInt(),
                    (r[2] * screenWidth).roundToInt(),
                    (r[3] * screenHeight).roundToInt()
                )
                if (box.right > box.left && box.bottom > box.top) return box
            }
            val p = bboxPx
            if (p != null && p.size == 4 && imageWidth > 0 && imageHeight > 0) {
                val sx = screenWidth.toFloat() / imageWidth.toFloat()
                val sy = screenHeight.toFloat() / imageHeight.toFloat()
                val box = Rect(
                    (p[0] * sx).roundToInt(),
                    (p[1] * sy).roundToInt(),
                    (p[2] * sx).roundToInt(),
                    (p[3] * sy).roundToInt()
                )
                if (box.right > box.left && box.bottom > box.top) return box
            }
            return null
        }
    }

    data class HealthResult(
        val ok: Boolean,
        val service: String = "",
        val version: String = "",
        /** `real` | `mock` */
        val mode: String = "",
        /** `lite` | `upstream` */
        val backend: String = "",
        val latencyMs: Long = 0,
        val httpCode: Int = 0,
        val error: String? = null,
        val url: String = ""
    )

    data class ParseResult(
        val ok: Boolean,
        val elements: List<Element> = emptyList(),
        val mode: String = "",
        /** **提交图片**的像素尺寸 */
        val imageWidth: Int = 0,
        val imageHeight: Int = 0,
        /** 服务端本次推理耗时 */
        val serverElapsedMs: Long = 0,
        val latencyMs: Long = 0,
        val httpCode: Int = 0,
        val error: String? = null,
        val url: String = ""
    )

    // ------------------------------------------------------------------ 端点

    /** 把用户填的地址补全成 /health 端点；地址为空返回 "" */
    fun healthEndpointOf(raw: String): String = endpointOf(raw, "health")

    /** 把用户填的地址补全成 /parse 端点；地址为空返回 "" */
    fun parseEndpointOf(raw: String): String = endpointOf(raw, "parse")

    private fun endpointOf(raw: String, path: String): String {
        var b = raw.trim().trimEnd('/')
        if (b.endsWith("/health")) b = b.removeSuffix("/health").trimEnd('/')
        if (b.endsWith("/parse")) b = b.removeSuffix("/parse").trimEnd('/')
        if (b.isEmpty()) return ""
        return "$b/$path"
    }

    // ------------------------------------------------------------------ 异步入口（回调切主线程）

    /** `GET {url}/health`：设置界面的「测试解析服务」用 */
    fun health(url: String, timeoutMs: Int, onResult: (HealthResult) -> Unit) {
        io.execute {
            val r = doHealth(url, timeoutMs)
            main.post { onResult(r) }
        }
    }

    /** `POST {url}/parse`（协程外的异步版，回调在主线程） */
    fun parse(
        url: String,
        imageJpegBase64: String,
        maxElements: Int,
        timeoutMs: Int,
        onResult: (ParseResult) -> Unit
    ) {
        io.execute {
            val r = doParse(url, imageJpegBase64, maxElements, timeoutMs)
            main.post { onResult(r) }
        }
    }

    // ------------------------------------------------------------------ 同步入口（后台线程用）

    /**
     * 同步版 `GET /health`。
     *
     * **只能在别的后台线程调用**（例如 Service 的 `shot-encode` 线程）：
     * 它直接走网络，若放在本类的 io 单线程里会把自己堵死。
     */
    fun healthBlocking(url: String, timeoutMs: Int): HealthResult = doHealth(url, timeoutMs)

    /** 同步版 `POST /parse`。同上：只能在别的后台线程调用。 */
    fun parseBlocking(
        url: String,
        imageJpegBase64: String,
        maxElements: Int,
        timeoutMs: Int
    ): ParseResult = doParse(url, imageJpegBase64, maxElements, timeoutMs)

    // ------------------------------------------------------------------ 实现

    private fun doHealth(url: String, timeoutMs: Int): HealthResult {
        val ep = healthEndpointOf(url)
        if (ep.isEmpty()) return HealthResult(false, error = "未配置解析服务地址")
        val started = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        return try {
            val to = timeoutMs.coerceIn(500, 60_000)
            conn = (URL(ep).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = to.coerceAtMost(10_000)
                readTimeout = to
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.let { readAll(it) } ?: ""
            val latency = System.currentTimeMillis() - started
            if (code !in 200..299) {
                HealthResult(
                    false,
                    error = "HTTP $code: ${body.take(300)}",
                    httpCode = code, latencyMs = latency, url = ep
                )
            } else {
                val o = JSONObject(body)
                if (!o.optBoolean("ok", false)) {
                    HealthResult(
                        false,
                        error = o.optString("error", "响应 ok=false"),
                        httpCode = code, latencyMs = latency, url = ep
                    )
                } else {
                    HealthResult(
                        true,
                        service = o.optString("service", ""),
                        version = o.optString("version", ""),
                        mode = o.optString("mode", ""),
                        backend = o.optString("backend", ""),
                        latencyMs = latency, httpCode = code, url = ep
                    )
                }
            }
        } catch (t: Throwable) {
            HealthResult(
                false,
                error = "${t.javaClass.simpleName}: ${t.message}",
                latencyMs = System.currentTimeMillis() - started,
                url = ep
            )
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun doParse(
        url: String,
        imageJpegBase64: String,
        maxElements: Int,
        timeoutMs: Int
    ): ParseResult {
        val ep = parseEndpointOf(url)
        if (ep.isEmpty()) return ParseResult(false, error = "未配置解析服务地址")
        if (imageJpegBase64.isEmpty()) return ParseResult(false, error = "图片为空", url = ep)
        val started = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        return try {
            // 契约 1.2：annotate 缺省 false（编号由 APK 自己做）；超出 max_elements 服务端保序截断
            val payload = JSONObject().apply {
                put("image_base64", imageJpegBase64)
                put("annotate", false)
                put("max_elements", maxElements.coerceIn(1, 500))
            }.toString()

            val to = timeoutMs.coerceIn(500, 60_000)
            conn = (URL(ep).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = to.coerceAtMost(10_000)
                readTimeout = to
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { os ->
                os.write(payload.toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.let { readAll(it) } ?: ""
            val latency = System.currentTimeMillis() - started
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(body).optString("error", "") }.getOrDefault("")
                return ParseResult(
                    false,
                    error = "HTTP $code: " + (msg.ifBlank { body.take(300) }),
                    httpCode = code, latencyMs = latency, url = ep
                )
            }

            val o = JSONObject(body)
            if (!o.optBoolean("ok", false)) {
                return ParseResult(
                    false,
                    error = o.optString("error", "响应 ok=false"),
                    httpCode = code, latencyMs = latency, url = ep
                )
            }
            val arr = o.optJSONArray("elements")
                ?: return ParseResult(
                    false, error = "响应里没有 elements[]（不合契约）",
                    httpCode = code, latencyMs = latency, url = ep
                )
            val image = o.optJSONObject("image")
            ParseResult(
                true,
                elements = parseElements(arr),
                mode = o.optString("mode", ""),
                imageWidth = image?.optInt("width", 0) ?: 0,
                imageHeight = image?.optInt("height", 0) ?: 0,
                serverElapsedMs = o.optLong("elapsed_ms", 0L),
                latencyMs = latency, httpCode = code, url = ep
            )
        } catch (t: Throwable) {
            ParseResult(
                false,
                error = "${t.javaClass.simpleName}: ${t.message}",
                latencyMs = System.currentTimeMillis() - started,
                url = ep
            )
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 严格按契约解析 `elements[]`；不合契约的条目**丢掉**，不让它污染后面的点击 */
    private fun parseElements(arr: JSONArray): List<Element> {
        val out = ArrayList<Element>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val type = o.optString("type", "").trim().lowercase()
            if (type != "text" && type != "icon") continue
            val label = o.optString("label", "").trim()
            val text = o.optString("text", "").trim()
            if (label.isEmpty() && text.isEmpty()) continue

            val ratio = floatArrayOf4(o.optJSONArray("bbox_ratio"))?.let { r ->
                val a = floatArrayOf(
                    r[0].coerceIn(0f, 1f), r[1].coerceIn(0f, 1f),
                    r[2].coerceIn(0f, 1f), r[3].coerceIn(0f, 1f)
                )
                if (a[2] <= a[0] || a[3] <= a[1]) null else a
            }
            val px = intArrayOf4(o.optJSONArray("bbox_px"))
            if (ratio == null && px == null) continue

            out.add(
                Element(
                    id = o.optInt("id", i + 1),
                    type = type,
                    label = label,
                    text = text,
                    bboxRatio = ratio,
                    bboxPx = px,
                    interactable = o.optBoolean("interactable", true),
                    source = o.optString("source", if (type == "icon") "icon" else "ocr")
                )
            )
        }
        return out
    }

    private fun floatArrayOf4(a: JSONArray?): FloatArray? {
        if (a == null || a.length() < 4) return null
        return FloatArray(4) { a.optDouble(it, Double.NaN).toFloat() }
            .takeIf { f -> f.none { it.isNaN() } }
    }

    private fun intArrayOf4(a: JSONArray?): IntArray? {
        if (a == null || a.length() < 4) return null
        val out = IntArray(4)
        for (i in 0..3) {
            val d = a.optDouble(i, Double.NaN)
            if (d.isNaN()) return null
            out[i] = d.roundToInt()
        }
        return out
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
