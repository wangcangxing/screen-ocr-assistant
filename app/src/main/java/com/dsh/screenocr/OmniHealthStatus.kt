package com.dsh.screenocr

/**
 * 解析服务（[OmniParserClient]）最近一次 `GET /health` 的结果文本，供状态区显示。
 *
 * 拆页后「测试解析服务」在 [SettingsActivity]、状态文本由 [PrefsUi] 同时渲染到主页与设置页，
 * 所以这份结果需要放在两页都能读到的地方（进程内单例即可，重启后回到「未测试」）。
 * 它只反映「测试过/没测试过」，**不代表服务常驻可用**。
 */
object OmniHealthStatus {
    var text: String = "未测试"
        private set

    fun update(value: String) {
        text = value
        AppLog.i("解析服务健康状态：$value")
    }

    fun reset() {
        text = "未测试"
    }
}
