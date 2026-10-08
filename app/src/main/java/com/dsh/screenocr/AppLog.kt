package com.dsh.screenocr

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 同时写 logcat 与内存环形缓冲，供 MainActivity 界面直接查看。
 * 真机调试时用 `adb logcat -s ScreenOcr` 也能看到同一批内容。
 */
object AppLog {

    const val TAG = "ScreenOcr"

    private const val MAX_LINES = 400
    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<(List<String>) -> Unit>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    private fun add(level: String, msg: String) {
        val line = "${fmt.format(Date())} $level $msg"
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        when (level) {
            "E" -> Log.e(TAG, msg)
            "W" -> Log.w(TAG, msg)
            else -> Log.i(TAG, msg)
        }
        notifyListeners()
    }

    fun i(msg: String) = add("I", msg)

    fun w(msg: String) = add("W", msg)

    fun e(msg: String) = add("E", msg)

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() {
        lines.clear()
        notifyListeners()
    }

    private fun notifyListeners() {
        val snap = snapshot()
        for (l in listeners) {
            runCatching { l(snap) }
        }
    }

    fun addListener(l: (List<String>) -> Unit) {
        listeners.add(l)
        runCatching { l(snapshot()) }
    }

    fun removeListener(l: (List<String>) -> Unit) {
        listeners.remove(l)
    }

    /**
     * 最近 [n] 行，供日志上报/粘贴。
     */
    fun tail(n: Int): String = snapshot().takeLast(n).joinToString("\n")
}
