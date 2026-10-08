package com.dsh.quiztest

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 用于验证「屏幕答题助手」的**无障碍节点点击**主路径。
 *
 * 模拟网课/题库 App 的真实形态：视频画面上延迟弹出一层**原生的、可点的选项**。
 * 选项是真正的 View，所以无障碍节点树里能看到它们的文字、也能点。
 * 点中后界面会显示点到了哪个选项，用来判断助手点得对不对。
 */
class MainActivity : Activity() {

    private lateinit var result: TextView

    private val options = listOf(
        "A. 水由氢元素和氧元素组成",
        "B. 水由氢气和氧气组成",
        "C. 水由两个氢原子和一个氧原子组成",
        "D. 水分子中含有氢分子"
    )
    private val answerIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(24), dp(60), dp(24), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "视频播放中… 题目即将出现"
            setTextColor(Color.parseColor("#666666"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })

        root.addView(TextView(this).apply {
            text = "下列关于水的说法中，正确的是（  ）"
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }, lp(top = 24))

        result = TextView(this).apply {
            text = "尚未点击任何选项"
            setTextColor(Color.parseColor("#C2185B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        }

        // 延迟 5 秒才把可点的选项挂上去 —— 模拟「视频播放中随机弹出题目」。
        // 这一刻节点树发生变化，正常情况下无障碍事件会被触发。
        Handler(Looper.getMainLooper()).postDelayed({
            options.forEachIndexed { i, text ->
                root.addView(Button(this).apply {
                    this.text = text
                    isAllCaps = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    maxLines = 1
                    setOnClickListener {
                        result.text = "被点击：" + ('A' + i) +
                            if (i == answerIndex) " — 命中正确选项" else " — 点错了（正确是 A）"
                    }
                }, lp(top = 10, height = 64))
            }
            root.addView(result, lp(top = 24))
        }, 5000)

        setContentView(root)
    }

    private fun lp(top: Int = 0, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height)).apply {
            topMargin = dp(top)
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
