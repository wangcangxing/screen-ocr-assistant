package com.dsh.screenocr

/**
 * 把「模型回复的各种形态」归一成「选项标签」的公共工具。
 *
 * 预设提示词要求模型只回一个选项字母（例如 "B"），但真实世界里它会回
 * "B。"、"答案是 B"、"(B)"、"```B```"、"B. 北京" 甚至整段 JSON。
 * 归一化逻辑集中在这里，LlmClient（解析回复）与 ClickPlanner（选选项）共用一份。
 */
object TextMatch {

    private val CIRCLED = "①②③④⑤⑥⑦⑧⑨⑩"

    private val PREFIX = Regex("^(选项|答案|正确答案|正确选项|应选|选)\\s*[:：]?\\s*")

    /** 短文本里独立出现的选项字母，前后允许是空白/标点/“是/为” */
    private val STANDALONE_LETTER =
        Regex("(?:^|[\\s是为：:。，,、（(\\[「])([A-Ha-h])(?=[\\s。，,、！!？?）)\\]」．.：:]|$)")

    private val ANY_LETTER = Regex("[A-Ha-h]")
    private val ANY_DIGIT = Regex("[1-9]")

    /** 模型表示「屏幕上没有题目」时的几种说法 */
    private val NONE_WORDS = listOf(
        "none", "null", "n/a", "na",
        "无", "没有", "无题", "无题目", "没有题目", "不是题目", "非题目",
        "无法确定", "不确定", "无正确答案"
    )

    /** 去掉 ``` 代码块包裹与首尾引号 */
    fun stripDecorations(input: String): String {
        var t = input.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```")
            val nl = t.indexOf('\n')
            if (nl >= 0) {
                val head = t.substring(0, nl).trim().lowercase()
                if (head.isEmpty() || head.all { it.isLetter() }) t = t.substring(nl + 1)
            }
            val end = t.lastIndexOf("```")
            if (end >= 0) t = t.substring(0, end)
        }
        return t.trim().trim('"', '“', '”', '`', ' ')
    }

    /** 取第一行有内容的文本 */
    fun firstMeaningfulLine(s: String): String =
        s.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""

    /** 是否明确的「没有题目」回复（用精确匹配，避免误伤正常答案） */
    fun isNoneReply(s: String): Boolean {
        var t = stripDecorations(s)
        t = t.trim('。', '.', '！', '!', '？', '?', '，', ',', ':', '：', ' ')
        return NONE_WORDS.any { t.equals(it, ignoreCase = true) }
    }

    /**
     * 从任意答案文本里抽出选项标签：认得出返回 "A"/"1" 这类标签，
     * 认不出则原样返回（交给「与选项正文比对」那条路），空输入返回空串。
     */
    fun normalizeLabel(raw: String): String {
        var t = stripDecorations(raw)
        if (t.isEmpty()) return ""

        t = t.replace(PREFIX, "")
        t = t.trim('(', ')', '（', '）', '.', '、', '．', ':', '：', '[', ']', '【', '】', ' ')
        if (t.isEmpty()) return ""

        if (t.length == 1) {
            val c = t[0]
            val ci = CIRCLED.indexOf(c)
            if (ci >= 0) return (ci + 1).toString()
            if (c.isLetter()) return c.uppercase()
            if (c.isDigit()) return c.toString()
        }

        if (t.length <= 12) {
            STANDALONE_LETTER.find(t)?.let { return it.groupValues[1].uppercase() }
        }
        if (t.length <= 4 && !t[0].isLetter()) {
            ANY_LETTER.find(t)?.let { return it.value.uppercase() }
            ANY_DIGIT.find(t)?.let { return it.value }
        }
        return t.uppercase()
    }

    /** 最长公共子串长度 / 较短串长度，取值 0..1。适合作「子串包含」判断，不适合抗错别字。 */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return longestCommonSubstring(a, b).toDouble() / minOf(a.length, b.length)
    }

    /**
     * 编辑距离相似度：1 - Levenshtein(a,b) / max(|a|,|b|)，取值 0..1。
     * 用来判定「两次 OCR 是不是同一道题」—— 实测同一画面 OCR 会有一两个字的抖动
     * （「美于」/「关干」、「氢」/「氯」），只有基于编辑距离的度量才扛得住；
     * 最长公共子串会因为中间差一个字就被截断，把相似度算成 0.4。
     */
    fun editSimilarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val maxLen = maxOf(a.length, b.length)
        return 1.0 - editDistance(a, b).toDouble() / maxLen.toDouble()
    }

    private fun editDistance(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }

    private fun longestCommonSubstring(a: String, b: String): Int {
        var best = 0
        val prev = IntArray(b.length + 1)
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                cur[j] = if (a[i - 1] == b[j - 1]) prev[j - 1] + 1 else 0
                if (cur[j] > best) best = cur[j]
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return best
    }
}
