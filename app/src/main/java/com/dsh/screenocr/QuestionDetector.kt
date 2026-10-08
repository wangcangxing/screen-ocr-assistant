package com.dsh.screenocr

import android.graphics.Rect

/** 一个选项：标签（A/B/①/1）+ 正文 + 它在屏幕上的位置 */
data class Option(val label: String, val text: String, val box: Rect?)

data class Question(
    val isCandidate: Boolean,
    val score: Int,
    val signals: List<String>,
    val stem: String,
    val options: List<Option>
)

/**
 * 本地启发式：判断当前屏幕「像不像一道题」，用来决定要不要花一次大模型调用。
 * 真正的最终判断仍由大模型返回的 is_question 字段把关（见 ScreenOcrAccessibilityService）。
 *
 * 打分规则（可在设置里调阈值）：
 *   出现 ? 或 ？                    +2
 *   出现疑问/题干类关键词            +2
 *   出现 （  ） 或 ____ 之类的空      +1
 *   识别出成组的选项                 +3
 */
object QuestionDetector {

    private val LETTER_DOT = Regex("^\\s*([A-Ha-h])\\s*[.、．)）:：]\\s*(\\S.*)$")
    private val LETTER_PAREN = Regex("^\\s*[（(]\\s*([A-Ha-h])\\s*[)）]\\s*(\\S.*)$")
    private val CIRCLED = Regex("^\\s*([①②③④⑤⑥⑦⑧])\\s*[.、．]?\\s*(\\S.*)$")
    private val NUM_DOT = Regex("^\\s*([1-8])\\s*[.、．]\\s*(\\S.*)$")

    private val KEYWORDS = listOf(
        "下列", "以下", "哪一", "哪个", "哪些", "哪项", "正确的", "错误的", "不正确",
        "选择", "选出", "单选", "多选", "判断", "填空", "计算", "求解", "请问",
        "为什么", "是什么", "多少", "等于", "解释", "翻译", "简述", "说明", "分析", "下面"
    )

    private val BLANK = Regex("[（(]\\s*[)）]|_{2,}")

    fun detect(result: OcrResult, minScore: Int): Question {
        val signals = ArrayList<String>()
        var score = 0
        val text = result.fullText

        val qMarks = Regex("[?？]").findAll(text).count()
        if (qMarks > 0) {
            score += 2
            signals.add("问号x$qMarks")
        }

        val hits = KEYWORDS.filter { text.contains(it) }
        if (hits.isNotEmpty()) {
            score += 2
            signals.add("关键词:" + hits.take(4).joinToString("/"))
        }

        if (BLANK.containsMatchIn(text)) {
            score += 1
            signals.add("括号/下划线空")
        }

        // ---- 逐行匹配选项，按「族」聚合，取最可信的一族 ----
        val letterOptions = ArrayList<Option>()
        val circledOptions = ArrayList<Option>()
        val numberOptions = ArrayList<Option>()

        result.lines.forEach { line ->
            val t = line.text.trim()

            var m = LETTER_DOT.find(t) ?: LETTER_PAREN.find(t)
            if (m != null) {
                val body = m.groupValues[2].trim()
                if (body.isNotEmpty()) {
                    letterOptions.add(Option(m.groupValues[1].uppercase(), body, line.box))
                }
                return@forEach
            }

            val c = CIRCLED.find(t)
            if (c != null) {
                val body = c.groupValues[2].trim()
                if (body.isNotEmpty()) {
                    circledOptions.add(Option(c.groupValues[1], body, line.box))
                }
                return@forEach
            }

            val n = NUM_DOT.find(t)
            if (n != null) {
                val body = n.groupValues[2].trim()
                if (body.isNotEmpty()) {
                    numberOptions.add(Option(n.groupValues[1], body, line.box))
                }
            }
        }

        // 数字选项容易和普通编号列表混淆，要求至少 3 个不同编号
        val families = listOf(
            Triple(letterOptions, 2, "字母选项"),
            Triple(circledOptions, 2, "圈号选项"),
            Triple(numberOptions, 3, "数字选项")
        )

        var options: List<Option> = emptyList()
        var familyName = ""
        var bestDistinct = 0
        for ((list, need, name) in families) {
            val distinct = list.map { it.label }.distinct().size
            if (distinct >= need && distinct > bestDistinct) {
                bestDistinct = distinct
                options = list
                familyName = name
            }
        }
        if (options.isNotEmpty()) {
            score += 3
            signals.add("$familyName x$bestDistinct")
        }

        // ---- 题干 = 第一个选项之前的文字 ----
        var stem = ""
        if (options.isNotEmpty()) {
            val firstBox = options.first().box
            val firstIdx = if (firstBox != null) result.lines.indexOfFirst { it.box == firstBox } else -1
            val cut = if (firstIdx > 0) firstIdx else (result.lines.size - options.size).coerceAtLeast(0)
            stem = result.lines.subList(0, cut).joinToString("\n") { it.text }.takeLast(600)
        }
        if (stem.isBlank()) stem = text.takeLast(600)

        return Question(
            isCandidate = score >= minScore,
            score = score,
            signals = signals,
            stem = stem,
            options = options
        )
    }
}
