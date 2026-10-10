package com.dsh.screenocr

import android.graphics.Rect

/**
 * 一个「候选点击元素」—— 也就是 SoM（Set-of-Mark）里的一个编号框。
 *
 * 三路来源（见 docs/接口约定-SoM与解析服务.md 2.1）合并成一个列表后**统一重新编号** `E1..EN`：
 * 该编号既画在标注图上，也写进发给模型的元素清单，模型回编号，本地据此点框中心。
 *
 * @param id     1 起的编号（对应 `E<id>`）；0 表示还没编号的候选
 * @param label  给模型看的短标签（文本节点/OCR 行就是原文；解析服务的图标元素是语义描述）
 * @param box    **屏幕坐标系**下的框（不是图片坐标，也不是归一化坐标）
 * @param kind   [KIND_TEXT] / [KIND_ICON]
 * @param source [SOURCE_NODE] / [SOURCE_OCR] / [SOURCE_OMNI]
 */
data class SomElement(
    val id: Int,
    val label: String,
    val box: Rect,
    val kind: String,
    val source: String
) {
    /** `E1`、`E7` … */
    val tag: String get() = "E$id"

    /** 发给模型的一行（契约 2.2）：`E1 文本 关闭` / `E3 图标 magnifying glass` */
    fun describe(): String = "$tag ${if (kind == KIND_ICON) "图标" else "文本"} ${label.trim()}"

    companion object {
        const val KIND_TEXT = "text"
        const val KIND_ICON = "icon"

        const val SOURCE_NODE = "node"
        const val SOURCE_OCR = "ocr"
        const val SOURCE_OMNI = "omniparser"

        /** 丢弃规则：任一边小于它（像素） */
        const val MIN_SIDE_PX = 8

        /** 丢弃规则：面积超过屏幕面积的这个比例 */
        const val MAX_AREA_RATIO = 0.6

        /** 同一个框只保留一个的判据之一：IoU 大于它 */
        const val IOU_THRESHOLD = 0.6

        /**
         * 包含关系去重时允许的面积倍差（契约 v1.1 §2.1）。
         *
         * 两框有包含关系、且**面积相差不到这个倍数**才算「同一个框」（「节点行 vs 同一行的 OCR 行」就是这种）。
         * 容器比子元素大 ≥ 这个倍数时**两个都保留** —— 否则一个覆盖整卡的容器文本节点会把
         * A/B/C/D 选项框全吞掉，SoM 清单里只剩容器那一行（真实反例见 verify/验证报告.md「大框吞小框」）。
         */
        const val CONTAIN_AREA_TOLERANCE = 4

        /** 判定「同一行」的垂直容差（像素），与 NodeReader 的取值一致 */
        const val SAME_ROW_PX = 30

        /**
         * 造一个**还没编号**（id=0）的候选；标签为空或框太小/太小时返回 null。
         * 真正能不能留下要看 [merge] 的整屏规则（面积、重叠、上限）。
         */
        fun candidate(label: String, box: Rect, kind: String, source: String): SomElement? {
            val t = label.trim()
            if (t.isEmpty()) return null
            if (box.width() < MIN_SIDE_PX || box.height() < MIN_SIDE_PX) return null
            return SomElement(0, t, Rect(box), kind, source)
        }

        /**
         * 合并 + 去重 + 编号。
         *
         * 调用方**必须按来源优先级把候选排好序**（node > ocr > omniparser，契约 2.1）——
         * 同一个框「先到先得」，所以先传进来的胜出。
         *
         * @param candidates  带框候选，顺序 = 优先级顺序
         * @param screenWidth/screenHeight 屏幕（截图）尺寸，用于「面积 > 60% 屏幕」这条丢弃规则
         * @param maxElements 保留上限（`prefs.somMaxElements`，默认 40），保序截断
         * @return 已编号的 `E1..EN`；没留下任何元素时返回空列表
         */
        fun merge(
            candidates: List<SomElement>,
            screenWidth: Int,
            screenHeight: Int,
            maxElements: Int
        ): List<SomElement> {
            if (maxElements <= 0 || screenWidth <= 0 || screenHeight <= 0) return emptyList()
            val screenArea = screenWidth.toLong() * screenHeight.toLong()
            val maxArea = (screenArea * MAX_AREA_RATIO).toLong()

            val kept = ArrayList<SomElement>(minOf(candidates.size, maxElements))
            for (c in candidates) {
                if (kept.size >= maxElements) break
                val w = c.box.width()
                val h = c.box.height()
                if (w < MIN_SIDE_PX || h < MIN_SIDE_PX) continue
                if (w.toLong() * h.toLong() > maxArea) continue
                if (kept.any { overlaps(it.box, c.box) }) continue
                kept.add(c)
            }
            return number(kept)
        }

        /**
         * 两个框算不算「同一个」（契约 v1.1 §2.1）：
         *  - `IoU > ` [IOU_THRESHOLD]：大小相近的同一处（节点行与同一行的 OCR 行）；
         *  - 存在包含关系**且两框面积相差不到** [CONTAIN_AREA_TOLERANCE] 倍；
         *
         * 容器比子元素大 4 倍以上 → **不算重复，两个都保留**（否则大框会吞掉里面所有选项框）。
         */
        fun overlaps(a: Rect, b: Rect): Boolean {
            if (iou(a, b) > IOU_THRESHOLD) return true
            if (!(a.contains(b) || b.contains(a))) return false
            val areaA = a.width().toLong() * a.height().toLong()
            val areaB = b.width().toLong() * b.height().toLong()
            val small = minOf(areaA, areaB)
            if (small <= 0L) return false
            val big = maxOf(areaA, areaB)
            return big < small * CONTAIN_AREA_TOLERANCE
        }

        /** 交并比；无交集返回 0 */
        fun iou(a: Rect, b: Rect): Double {
            val left = maxOf(a.left, b.left)
            val top = maxOf(a.top, b.top)
            val right = minOf(a.right, b.right)
            val bottom = minOf(a.bottom, b.bottom)
            if (right <= left || bottom <= top) return 0.0
            val inter = (right - left).toLong() * (bottom - top).toLong()
            val union = a.width().toLong() * a.height().toLong() +
                b.width().toLong() * b.height().toLong() - inter
            if (union <= 0L) return 0.0
            return inter.toDouble() / union.toDouble()
        }

        /**
         * 最终编号顺序（契约 2.1）：按框**从上到下、同一行从左到右**。
         * 具体做法：先按 `centerY` 排序，再把与当前行首 `centerY` 相差不超过 [SAME_ROW_PX] 的视为同一行，
         * 行内按 `left` 排序，然后依次编 `E1..EN`。
         */
        private fun number(kept: List<SomElement>): List<SomElement> {
            if (kept.isEmpty()) return emptyList()
            val sorted = kept.sortedWith(compareBy({ it.box.centerY() }, { it.box.left }))
            val rows = ArrayList<MutableList<SomElement>>()
            var rowStartY = Int.MIN_VALUE
            for (e in sorted) {
                if (rows.isEmpty() || e.box.centerY() - rowStartY > SAME_ROW_PX) {
                    rows.add(ArrayList<SomElement>())
                    rowStartY = e.box.centerY()
                }
                rows.last().add(e)
            }
            val out = ArrayList<SomElement>(kept.size)
            var n = 1
            for (row in rows) {
                for (e in row.sortedWith(compareBy({ it.box.left }, { it.box.top }))) {
                    out.add(e.copy(id = n++))
                }
            }
            return out
        }
    }
}
