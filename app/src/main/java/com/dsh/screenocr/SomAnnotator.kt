package com.dsh.screenocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * 把候选元素画成**带编号的框**贴进截图（Set-of-Mark 的"标注图"），并提供 JPEG base64 编码。
 *
 * 两件事在同一个类里，是因为它们必须用**同一套缩放系数**：
 * 标注图先按 `imageMaxSide` 缩到目标尺寸，编号框要按同一比例换算到图片坐标；
 * 而发给模型的 base64 就是这张已经缩好的图 —— 若两边各缩一次，框与内容就会错位。
 *
 * 注意（契约 3）：`somEnabled=false` 时调用方**不要**调 [annotate]，直接走 [encodeJpegBase64]，
 * 那样发出去的图与原 v1.3 逐字一致。
 */
object SomAnnotator {

    /** 框线颜色（不透明红） */
    private const val BOX_COLOR = 0xFFE53935.toInt()

    /**
     * 截屏 → JPEG → Base64（不带换行，供 `data:image/jpeg;base64,...` 直接拼装）。
     * 长边超过 [maxSide] 时等比缩小（0 表示不缩）。缩小只为省流量，识别仍靠模型自己缩放。
     *
     * 这段逻辑**逐字搬自原 ScreenOcrAccessibilityService.encodeJpegBase64**（v1.3 行为不变）。
     */
    fun encodeJpegBase64(src: Bitmap, maxSide: Int, quality: Int): String? {
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

    /**
     * 标注图（=发给模型的图）在给定 [maxSide] 下的尺寸。
     * 与 [annotate] / [encodeJpegBase64] 用**同一套缩放公式**，日志里报的尺寸才不会与真实图片不一致。
     */
    fun scaledSize(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
        val longSide = maxOf(width, height)
        return if (maxSide > 0 && longSide > maxSide) {
            val s = maxSide.toFloat() / longSide.toFloat()
            (width * s).toInt().coerceAtLeast(1) to (height * s).toInt().coerceAtLeast(1)
        } else {
            width to height
        }
    }

    /**
     * 在**截图副本**上画编号框，返回一张**新的**位图（调用方负责 recycle），原图不动。
     *
     * @param src       原始截图（屏幕坐标系）
     * @param elements  已编号的 SoM 元素（[SomElement.box] 是屏幕坐标）
     * @param maxSide   输出图长边上限（0 = 不缩放），与 [encodeJpegBase64] 用同一个值
     * @return 标注图；缩放/复制失败时返回 null（调用方据此退回发原图）
     */
    fun annotate(src: Bitmap, elements: List<SomElement>, maxSide: Int): Bitmap? {
        if (elements.isEmpty()) return null
        val (outW, outH) = scaledSize(src.width, src.height, maxSide)
        val scale = outW.toFloat() / src.width.toFloat()   // 缩放后/原尺寸，用于把框换算到图片坐标

        // 必须拿副本：createScaledBitmap 在尺寸相同时会**原样返回 src**，直接在它上面画就改了原截图。
        val out: Bitmap = if (outW == src.width && outH == src.height) {
            src.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            runCatching { Bitmap.createScaledBitmap(src, outW, outH, true) }.getOrNull()
        } ?: return null
        if (out === src) return null

        return try {
            val canvas = Canvas(out)
            val stroke = Paint().apply {
                style = Paint.Style.STROKE
                color = BOX_COLOR
                isAntiAlias = true
                strokeWidth = maxOf(2f, out.width / 360f)
            }
            val badgeBg = Paint().apply {
                style = Paint.Style.FILL
                color = BOX_COLOR
                isAntiAlias = true
            }
            val badgeText = Paint().apply {
                color = Color.WHITE
                isAntiAlias = true
                typeface = Typeface.DEFAULT_BOLD
                textSize = maxOf(18f, out.width / 45f)   // 1080 宽 → 24px，缩到 720 宽（scale=2/3）时框也等比例缩小，仍看得清
            }
            val pad = badgeText.textSize * 0.30f
            val fm = badgeText.fontMetrics
            val badgeH = (fm.descent - fm.ascent) + pad * 2f

            for (el in elements) {
                // 屏幕坐标 → 图片坐标（同一个 scale，保证框与内容对齐）
                val r = Rect(
                    (el.box.left * scale).roundToInt(),
                    (el.box.top * scale).roundToInt(),
                    (el.box.right * scale).roundToInt(),
                    (el.box.bottom * scale).roundToInt()
                )
                canvas.drawRect(r, stroke)

                // 编号徽标画在框左上角：白字 + 实心底，越界时贴边
                val label = el.tag
                val badgeW = badgeText.measureText(label) + pad * 2f
                val bx = r.left.toFloat().coerceIn(0f, (out.width - badgeW).coerceAtLeast(0f))
                val by = r.top.toFloat().coerceIn(0f, (out.height - badgeH).coerceAtLeast(0f))
                canvas.drawRect(bx, by, bx + badgeW, by + badgeH, badgeBg)
                canvas.drawText(label, bx + pad, by + pad - fm.ascent, badgeText)
            }
            out
        } catch (t: Throwable) {
            AppLog.e("SoM 标注绘制失败：${t.javaClass.simpleName}: ${t.message}")
            runCatching { out.recycle() }
            null
        }
    }
}
