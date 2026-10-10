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
    /**
     * 「A货物」这种**字母紧贴正文、没有分隔符**的紧凑选项 —— 实测智慧树的多选题节点就是这样
     * （判断题是拆成 `A` + `对` 两个节点，多选题则是 `A货物` 一个节点）。
     * 补上分隔符后交给 [QuestionDetector]，它才认得出成组选项。
     *
     * 注意：正文**必须以中文开头**（`[\u4e00-\u9fff]`）。
     * 原先写的是「首字符不是空白/标点」，结果把标题 **`AI随堂练习`** 也当成了 `A` + `I随堂练习`，
     * 判题里于是多出一个 `A@540,270(w948,h132)`（宽 948 = 整行，其实是标题）；
     * `chooseOption` 选中它、点到标题上 —— 于是**选项永远点不中**，提交被判「暂未作答」。
     */
    private val COMPACT_OPTION = Regex("^([A-H])([\\u4e00-\\u9fff].*)$")

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
    fun toOcrResult(root: AccessibilityNodeInfo?, width: Int, height: Int, minLines: Int = 3): OcrResult? =
        toOcrResult(listOf(root), width, height, minLines)

    /**
     * 同上，但**一次读多个窗口**。
     *
     * 为什么需要：实测（16416 实例）屏幕上明明有浮层、`uiautomator dump` 也能看到，
     * 但 `mCurrentFocus` / `mFocusedApp` 都是 null，于是 `rootInActiveWindow` **退化成桌面窗口** ——
     * 只看它就会判成"非题目"、完全读不到题（而同一界面在 16384 上焦点正常时又能读到）。
     * 结论：浮层可能是**独立窗口**，必须遍历 `AccessibilityService.getWindows()` 的所有窗口。
     */
    fun toOcrResult(roots: List<AccessibilityNodeInfo?>, width: Int, height: Int, minLines: Int = 3): OcrResult? {
        if (width <= 0 || height <= 0) return null
        val items = ArrayList<Item>()
        for (r in roots) collect(r, items, 0)
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

    /** 同上，但**跨多个窗口**查找（浮层可能在独立窗口里，只看焦点窗口会找不到按钮）。 */
    fun findTextNodes(roots: List<AccessibilityNodeInfo?>, words: Collection<String>,
                      maxDepth: Int = MAX_DEPTH, limit: Int = 20): List<Pair<AccessibilityNodeInfo, Rect>> {
        val out = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        for (r in roots) {
            if (out.size >= limit) break
            out.addAll(findTextNodes(r, words, maxDepth, limit - out.size))
        }
        return out
    }

    /**
     * 同 [findTextNodes]，但用**谓词**筛节点文本。
     *
     * 为什么需要：契约 §6.4 的推进按钮判定是「前缀 + 装饰后缀白名单」（`下一题(1/10)` 要命中），
     * 用 `words.contains(t)` 表达不了。文本同样取 `text`，为空时取 `contentDescription` ——
     * 纯图标按钮只有后者。
     */
    fun findTextNodesBy(
        roots: List<AccessibilityNodeInfo?>,
        predicate: (String) -> Boolean,
        maxDepth: Int = MAX_DEPTH,
        limit: Int = 20
    ): List<Pair<AccessibilityNodeInfo, Rect>> {
        val out = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        for (r in roots) {
            if (out.size >= limit) break
            walkBy(r, predicate, maxDepth, limit, out, 0)
        }
        return out
    }

    private fun walkBy(
        n: AccessibilityNodeInfo?,
        predicate: (String) -> Boolean,
        maxDepth: Int,
        limit: Int,
        out: MutableList<Pair<AccessibilityNodeInfo, Rect>>,
        depth: Int
    ) {
        if (n == null || depth > maxDepth || out.size >= limit) return
        // 契约 §6.4 v1.2.1：取 text；**空/全空白**才退回 contentDescription（纯图标按钮只有后者）
        val t = (n.text?.toString()?.takeIf { it.isNotBlank() }
            ?: n.contentDescription?.toString() ?: "").trim()
        if (t.isNotEmpty() && predicate(t)) {
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) out.add(n to r)
        }
        for (i in 0 until n.childCount) walkBy(n.getChild(i), predicate, maxDepth, limit, out, depth + 1)
    }

    /**
     * 按屏幕坐标找「能点的那个节点」：**可点击、且框覆盖该点**，多个命中时取**面积最小**的（最具体的那个）。
     *
     * 为什么需要它：`dispatchGesture` 的坐标手势在模拟器上会「返回成功却不生效」
     * （实测 MuMu：日志 `⑦ 点击结果：已发送`，屏幕上选项却毫无变化）；
     * 而 `performAction(ACTION_CLICK)` 是**直接在目标窗口上执行**的，不走输入子系统，因此可靠得多。
     * 拿到坐标后先在节点树里捞一下对应节点，捞到就用节点点击，捞不到才退回手势。
     */
    /**
     * 覆盖该点的**最深节点**（通常是叶子）。
     *
     * 为什么要单独有这个：WebView 里 `ACTION_CLICK` 打在**叶子**上才可能被响应，
     * 打在容器/祖先上会「返回 true 却不生效」（坑点 #38）。实测选项叶子 `clickable=false`、
     * 点它的"可点祖先"无效 —— 所以点击顺序改为：**先叶子 → 再可点祖先 → 最后坐标手势**。
     */
    fun findDeepestAt(roots: List<AccessibilityNodeInfo?>, x: Int, y: Int,
                      maxDepth: Int = MAX_DEPTH): AccessibilityNodeInfo? {
        var deepest: AccessibilityNodeInfo? = null
        var deepestDepth = -1
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > maxDepth) return
            val r = Rect()
            runCatching { n.getBoundsInScreen(r) }
            if (r.contains(x, y) && d > deepestDepth) {
                deepestDepth = d
                deepest = n
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        for (r in roots) walk(r, 0)
        return deepest
    }

    fun findClickableAt(roots: List<AccessibilityNodeInfo?>, x: Int, y: Int,
                        maxDepth: Int = MAX_DEPTH): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null          // 覆盖该点、且自身可点的最小节点
        var bestArea = Int.MAX_VALUE
        var deepest: AccessibilityNodeInfo? = null       // 覆盖该点的最深节点（用于向上回溯）
        var deepestDepth = -1
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > maxDepth) return
            val r = Rect()
            runCatching { n.getBoundsInScreen(r) }
            if (r.contains(x, y)) {
                if (n.isClickable) {
                    val area = r.width() * r.height()
                    if (area in 1 until bestArea) {
                        bestArea = area
                        best = n
                    }
                }
                if (d > deepestDepth) {
                    deepestDepth = d
                    deepest = n
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        for (r in roots) walk(r, 0)
        if (best != null) return best

        // 兜底：从「覆盖该点的最深节点」往上找第一个可点的祖先。
        // 实测 WebView 里真正可点的常是**父容器**，选项叶子节点自身 clickable=false ——
        // 这正是「选项点了却没选中」的原因（同一浮层的提交/关闭按钮却是自身可点）。
        var cur = deepest
        var hops = 0
        while (cur != null && hops < MAX_DEPTH) {
            if (cur.isClickable) return cur
            cur = runCatching { cur.parent }.getOrNull()
            hops++
        }
        return null
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
