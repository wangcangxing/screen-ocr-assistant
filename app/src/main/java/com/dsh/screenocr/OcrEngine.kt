package com.dsh.screenocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/** 一行 OCR 文字及其在**送进 OCR 的那张位图**坐标系中的包围盒 */
data class OcrLine(val text: String, val box: Rect?)

data class OcrResult(
    val fullText: String,
    val lines: List<OcrLine>,
    /** 实际送进 OCR 的位图尺寸（可能被降采样） */
    val imageWidth: Int,
    val imageHeight: Int,
    /** 原始截图尺寸；两者不同时，OCR 坐标要按比例换算回屏幕坐标 */
    val sourceWidth: Int,
    val sourceHeight: Int
) {
    /**
     * 坐标换算系数 = 原始截图 / OCR 输入位图。
     * 注意：**不能**用 `resources.displayMetrics` 算这个比值 ——
     * 那是「应用可用窗口」（不含导航栏），而截图与 dispatchGesture 同属整屏坐标系。
     */
    val scaleX: Float get() = if (imageWidth > 0) sourceWidth.toFloat() / imageWidth else 1f
    val scaleY: Float get() = if (imageHeight > 0) sourceHeight.toFloat() / imageHeight else 1f

    val downscaled: Boolean get() = imageWidth != sourceWidth || imageHeight != sourceHeight

    /** 去掉所有空白后的文本，用于「同一屏只处理一次」的去重比对 */
    val signature: String by lazy { WHITESPACE.replace(fullText, "") }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

/**
 * ML Kit 端侧文字识别（中文模型，同时覆盖拉丁字母）。模型随 APK 打包，无需 Google Play 服务。
 *
 * @param scalePercent 送进 OCR 前把截图缩到百分之多少（30~100）。轮询模式下降低它可显著省 CPU。
 *                     坐标会按 [OcrResult.scaleX] 换算回屏幕坐标，所以识别框仍然点得准。
 */
class OcrEngine {

    private val recognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    fun recognize(source: Bitmap, scalePercent: Int, onDone: (OcrResult?) -> Unit) {
        val percent = scalePercent.coerceIn(30, 100)
        val scaled: Bitmap = if (percent < 100) {
            val s = percent / 100f
            Bitmap.createScaledBitmap(
                source,
                (source.width * s).toInt().coerceAtLeast(1),
                (source.height * s).toInt().coerceAtLeast(1),
                true
            )
        } else {
            source
        }

        val input = InputImage.fromBitmap(scaled, 0)
        recognizer.process(input)
            .addOnSuccessListener { text ->
                val lines = ArrayList<OcrLine>()
                for (block in text.textBlocks) {
                    for (line in block.lines) {
                        val t = line.text.trim()
                        if (t.isNotEmpty()) lines.add(OcrLine(t, line.boundingBox))
                    }
                }
                onDone(
                    OcrResult(
                        fullText = text.text,
                        lines = lines,
                        imageWidth = scaled.width,
                        imageHeight = scaled.height,
                        sourceWidth = source.width,
                        sourceHeight = source.height
                    )
                )
            }
            .addOnFailureListener { e ->
                AppLog.e("OCR 识别失败：${e.javaClass.simpleName}: ${e.message}")
                onDone(null)
            }
            .addOnCompleteListener {
                // 只回收我们自己造出来的缩放副本；原图归调用方管
                if (scaled !== source) runCatching { scaled.recycle() }
            }
    }

    fun close() {
        runCatching { recognizer.close() }
    }
}
