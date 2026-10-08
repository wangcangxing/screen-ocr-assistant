package com.dsh.screenocr

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 一次点击计划。
 * node != null -> 用无障碍 ACTION_CLICK；否则回退到 (x, y) 坐标手势点击。
 */
data class ClickPlan(
    val node: AccessibilityNodeInfo?,
    val x: Int,
    val y: Int,
    val how: String
)

/**
 * 把「大模型的答案」映射到「屏幕上可点的目标」。
 *
 * 两条路：
 *   1) 首选：按选项文字在无障碍节点树里找，再向上找可点击祖先（最稳，能点中复选框/列表项）；
 *   2) 回退：用 OCR 那一行的包围盒中心做坐标点击。
 * 两条都拿不到目标就返回 null ——「宁可不点，不要点错」。
 */
object ClickPlanner {

    private const val MAX_ANCESTOR_DEPTH = 8

    /** 单选场景的便捷入口（多选请用 [plans]）。 */
    fun plan(
        root: AccessibilityNodeInfo?,
        answer: LlmClient.Answer,
        options: List<Option>,
        scaleBox: (android.graphics.Rect) -> android.graphics.Rect
    ): ClickPlan? = plans(root, answer, options, scaleBox).firstOrNull()

    /**
     * 一次回答可能要**点多个**选项。
     *
     * 实测（智慧树多选题）：平台没有"提交"按钮，是**逐个点选、选满后自动判定** ——
     * 点 A 时无反应，点上 A+B 后立刻显示「正确答案：AB」。而模型回的也是 `AB`。
     * 所以这里返回**一组**点击计划，由调用方依次执行。
     */
    fun plans(
        root: AccessibilityNodeInfo?,
        answer: LlmClient.Answer,
        options: List<Option>,
        scaleBox: (android.graphics.Rect) -> android.graphics.Rect
    ): List<ClickPlan> {
        if (options.isEmpty()) return emptyList()
        return chooseOptions(answer, options).mapNotNull { chosen -> planFor(root, chosen, scaleBox) }
    }

    /** 抽出回答里的**所有**选项标签（`AB` / `A、B` / `A B` 都认）；不足两个就退回原来的单选匹配。 */
    fun chooseOptions(answer: LlmClient.Answer, options: List<Option>): List<Option> {
        val labels = Regex("[A-H]").findAll(answer.answerLabel.uppercase())
            .map { it.value }.distinct().toList()
        val hits = labels.mapNotNull { l ->
            options.firstOrNull { TextMatch.normalizeLabel(it.label) == l }
        }
        if (hits.size >= 2) return hits
        return listOfNotNull(chooseOption(answer, options))
    }

    private fun planFor(
        root: AccessibilityNodeInfo?,
        chosen: Option,
        scaleBox: (android.graphics.Rect) -> android.graphics.Rect
    ): ClickPlan? {
        if (root != null) {
            val needles = listOf(
                chosen.text,
                chosen.text.take(12),
                chosen.text.take(8),
                chosen.text.take(5)
            ).filter { it.length >= 2 }.distinct()

            for (needle in needles) {
                val nodes = runCatching { root.findAccessibilityNodeInfosByText(needle) }
                    .getOrNull().orEmpty()
                for (n in nodes) {
                    val hit = clickableAncestor(n)
                    if (hit != null) {
                        return ClickPlan(hit, 0, 0, "无障碍节点点击（文本「${needle.take(20)}」）")
                    }
                }
            }

            // 退一步：按选项标签找，例如文本恰好是 "A" 或形如 "A. xxx"
            val label = chosen.label
            if (label.isNotBlank()) {
                val nodes = runCatching { root.findAccessibilityNodeInfosByText(label) }
                    .getOrNull().orEmpty()
                for (n in nodes) {
                    val t = n.text?.toString()?.trim().orEmpty()
                    if (t == label || t.startsWith("$label.") || t.startsWith("$label、") ||
                        t.startsWith("$label．") || t.startsWith("$label）") || t.startsWith("$label)")
                    ) {
                        val hit = clickableAncestor(n)
                        if (hit != null) {
                            return ClickPlan(hit, 0, 0, "无障碍节点点击（选项$label）")
                        }
                    }
                }
            }
        }

        // 回退：坐标点击 OCR/节点框中心
        val box = chosen.box ?: return null
        val r = scaleBox(box)
        if (r.width() <= 0 || r.height() <= 0) return null
        return ClickPlan(null, r.centerX(), r.centerY(), "坐标点击（框中心 ${r.centerX()},${r.centerY()}）")
    }

    /** 把模型给的答案对到某个选项上；对不上就返回 null（不点） */
    fun chooseOption(answer: LlmClient.Answer, options: List<Option>): Option? {
        if (options.isEmpty()) return null

        val label = TextMatch.normalizeLabel(answer.answerLabel)
        if (label.isNotEmpty()) {
            options.firstOrNull { TextMatch.normalizeLabel(it.label) == label }?.let { return it }
        }

        val at = answer.answerText.trim()
        if (at.isNotEmpty()) {
            options.firstOrNull { it.text == at }?.let { return it }
            options.firstOrNull { it.text.contains(at) && at.length >= 2 }?.let { return it }
            options.firstOrNull { at.contains(it.text) && it.text.length >= 2 }?.let { return it }
            val best = options.maxByOrNull { TextMatch.similarity(it.text, at) }
            if (best != null && TextMatch.similarity(best.text, at) >= 0.55) return best
            // 再退一步：把整段答案也当标签抽一次（模型可能回「答案是B」这种）
            val atLabel = TextMatch.normalizeLabel(at)
            if (atLabel.isNotEmpty() && atLabel != at.uppercase()) {
                options.firstOrNull { TextMatch.normalizeLabel(it.label) == atLabel }?.let { return it }
            }
        }

        return null
    }

    /** 从某节点向上找「可点且可用」的祖先（也供收尾点「关闭」复用）。 */
    fun clickableAncestor(start: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var n = start
        var depth = 0
        while (n != null && depth < MAX_ANCESTOR_DEPTH) {
            if (n.isClickable && n.isEnabled) return n
            n = n.parent
            depth++
        }
        return null
    }
}
