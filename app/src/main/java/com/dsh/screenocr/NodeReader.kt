package com.dsh.screenocr

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 把「无障碍节点树」伪装成 [OcrResult]，让判题/点击逻辑**零改动**复用。
 *
 * 为什么要它：视频里画出来的题目没有节点（只能靠 OCR），但**弹题浮层通常是原生控件或 WebView**——
 * 实测（MuMu Android 12 + 智慧树 App）WebView 里的 `判断题`、题干、`A`、`对`、`B`、`错`、`关闭`
 * 全都能作为**带 bounds 的节点**读到。节点读得到时就不必 OCR：
 *
 * · 不受模拟器/部分设备上 `takeScreenshot` 返回黑图的影响（本应用在 MuMu 上就踩过：截图"成功"但 OCR 0 行）；
 * · 坐标直接取 `getBoundsInScreen()`（屏幕坐标系），不需要降采样换算，也不会有 OCR 误读（"巳错"之类）。
 *
 * 要做的唯一加工：节点树把选项拆成**两个节点**（`A` 与 `对`），而 OCR 给的是一行 `A. 对`；
 * 这里按「同一行、字母在左、正文在右」合回一行，[QuestionDetector] 才认得出成组选项。
 */
object NodeReader {

    /** 遍历上限：防止某些 App 的节点树异常庞大时拖慢分析 */
    private const val MAX_NODES = 400
    private const val MAX_DEPTH = 40

    /** 判定「同一行」的垂直容差（像素） */
    private const val SAME_ROW_PX = 30

    /** 字母节点的宽度上限；超过它就不是「选项标签」而是正文里的字母 */
    private const val LETTER_MAX_W = 120

    private val LETTER = Regex("^[A-H]$")

    /**
     * 「A货物」这种**字母紧贴正文、没有分隔符**的紧凑选项 —— 实测智慧树的多选题节点就是这样
     * （判断题是拆成 `A` + `对` 两个节点，多选题则是 `A货物` 一个节点）。
     * 补上分隔符后交给 [QuestionDetector]，它才认得出成组选项。
     */
    private val COMPACT_OPTION = Regex("^([A-H])([^\\s.、．)）:：].*)$")

    /** 紧凑选项的正文长度上限：太长就不是选项而是「A方案是…」这类正文了 */
    private const val COMPACT_MAX_LEN = 40

    /** 按钮/装饰节点不参与判题（留着只会稀释题干、干扰关键词打分） */
    private val NOISE = setOf("关闭", "上一题", "下一题", "确定", "提交", "取消", "返回")

    private data class Item(val text: String, val box: Rect)

    /**
     * 读节点树并转成 [OcrResult]；节点太少或读不到东西时返回 **null**，让调用方回退 OCR。
     *
     * @param width/height 屏幕（截图）尺寸 —— 直接作为 OcrResult 的图像尺寸，使坐标换算系数为 1。
     */
    fun toOcrResult(root: AccessibilityNodeInfo?, width: Int, height: Int, minLines: Int = 3): OcrResult? {
        if (root == null || width <= 0 || height <= 0) return null
        val items = ArrayList<Item>()
        collect(root, items, 0)
        val lines = merge(items)
        if (lines.size < minLines) return null
        return OcrResult(
            fullText = lines.joinToString("\n") { it.text },
            lines = lines,
            imageWidth = width,
            imageHeight = height,
            sourceWidth = width,
            sourceHeight = height
        )
    }

    /**
     * 遍历节点树，找出**文本正好等于** [words] 之一的节点（保留节点引用，供点击用）。
     *
     * 为什么不用 `findAccessibilityNodeInfosByText`：实测 WebView 里的弹题节点用那个 API **找不到**
     * （收尾点「关闭」时踩到：日志「没找到关闭类按钮」，但同一个节点按子节点遍历就能读到）。
     */
    fun findTextNodes(root: AccessibilityNodeInfo?, words: Collection<String>,
                      maxDepth: Int = MAX_DEPTH, limit: Int = 20): List<Pair<AccessibilityNodeInfo, Rect>> {
        val out = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > maxDepth || out.size >= limit) return
            val t = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()
            if (t.isNotEmpty() && words.contains(t)) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) out.add(n to r)
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(root, 0)
        return out
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<Item>, depth: Int) {
        if (node == null || depth > MAX_DEPTH || out.size >= MAX_NODES) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val s = if (text.isNotEmpty()) text else desc
        if (s.isNotEmpty()) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) out.add(Item(s, r))
        }
        for (i in 0 until node.childCount) {
            collect(node.getChild(i), out, depth + 1)
        }
    }

    /** 按行排序后，把 `A` + `对` 合回 `A. 对`。 */
    private fun merge(items: List<Item>): List<OcrLine> {
        val sorted = items.sortedWith(compareBy({ it.box.centerY() }, { it.box.left }))
        val used = BooleanArray(sorted.size)
        val out = ArrayList<OcrLine>()

        for (i in sorted.indices) {
            if (used[i]) continue
            val cur = sorted[i]
            if (cur.text in NOISE) continue

            val isLabel = LETTER.matches(cur.text) && cur.box.width() <= LETTER_MAX_W
            if (isLabel) {
                var body: Item? = null
                for (k in i + 1 until sorted.size) {
                    if (used[k]) continue
                    val cand = sorted[k]
                    if (Math.abs(cand.box.centerY() - cur.box.centerY()) > SAME_ROW_PX) continue
                    if (!LETTER.matches(cand.text) && cand.text !in NOISE) {
                        body = cand
                        used[k] = true
                        break
                    }
                }
                used[i] = true
                val text = if (body != null) "${cur.text}. ${body.text}" else cur.text
                val box = if (body != null) Rect(cur.box).apply { union(body.box) } else cur.box
                out.add(OcrLine(text, box))
                continue
            }

            used[i] = true
            val compact = COMPACT_OPTION.find(cur.text)
            val text = if (compact != null && cur.text.length <= COMPACT_MAX_LEN) {
                "${compact.groupValues[1]}. ${compact.groupValues[2]}"   // A货物 → A. 货物
            } else {
                cur.text
            }
            out.add(OcrLine(text, cur.box))
        }
        return out
    }
}
