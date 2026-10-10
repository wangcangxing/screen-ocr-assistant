package com.dsh.screenocr

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.Toast

/**
 * 主页面（v1.4 起拆页）：只放**每天要动的东西**。
 *
 *  - 运行状态（只读，1.5s 刷新一次）
 *  - 「打开无障碍设置」入口
 *  - 行为与触发：屏幕处理 / 自动点击 / 试运行
 *  - SoM 编号标注开关
 *  - 答完自动下一题（开关 + 连击上限）
 *  - 答题自动提交（三选一收尾模式）
 *  - 「高级设置」入口 → [SettingsActivity]
 *
 * 接口地址/Key、识别参数、解析服务、日志等低频项都在 [SettingsActivity]。
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())

    private val statusTicker = object : Runnable {
        override fun run() {
            refreshStatus()
            ui.postDelayed(this, 1500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        findViewById<Button>(R.id.btnOpenA11y).setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .onFailure { toast("打不开系统无障碍设置：${it.message}") }
        }
        findViewById<Button>(R.id.btnOpenSettings).setOnClickListener {
            // 进设置页前先落盘本页的开关，避免两页来回切时丢改动
            saveHomePrefs()
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        loadPrefs()
        // 首次运行（还没配 Key）时，尝试从外部配置文件自动导入一次
        ConfigFile.autoImportIfUnconfigured(this, prefs)
        loadPrefs()

        val rootScroll = findViewById<android.widget.ScrollView>(R.id.rootScroll)
        rootScroll.post { rootScroll.fullScroll(android.widget.ScrollView.FOCUS_UP) }
        ui.post(statusTicker)
        showStartupNotice()
    }

    override fun onResume() {
        super.onResume()
        // 从高级设置页返回时，把它那边改过的开关/参数重新读出来
        loadPrefs()
        refreshStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(statusTicker)
    }

    // ------------------------------------------------------------------ 读写配置

    private fun loadPrefs() = PrefsUi.load(this, prefs)

    /** 主页只写自己这几个开关；设置页的字段不受影响（[PrefsUi.save] 只写存在的控件）。 */
    private fun saveHomePrefs() {
        val ok = PrefsUi.save(this, prefs)
        if (!ok) toast("部分字段无效，已保留原值")
        refreshStatus()
    }

    private fun refreshStatus() {
        PrefsUi.refreshStatus(this, prefs, OmniHealthStatus.text)
    }

    // ------------------------------------------------------------------ 签名自校验与启动告示

    /**
     * 本项目官方 release 签名 SHA-256（用 `apksigner verify --print-certs` 取得）。
     *
     * v1.4 起 OmniParser 增强已**合并回本工程主线**，仍沿用 origin 的签名密钥
     * `keystore/screenocr-release.jks`（不入库），所以自校验指纹回到 origin 的那把 ——
     * 这样原 v1.3 用户能原地升级，而曾单独装过 `com.dsh.screenocr.omni`（独立密钥）的包
     * 因签名不同必须卸载重装。
     *
     * **为什么靠签名**：Android 不允许同一个包名存在两个签名，所以任何改动过的 APK 都**必须重新签名** ——
     * 于是「签名对不上」就等价于「这个包被第三方重新打包过」，正是「改掉告示再拿去卖」的场景。
     * 二次打包者当然也能把这段校验 patch 掉，但那比删一行文案难得多，而且他得同时做两件事。
     */
    private val OFFICIAL_SIGNATURE_SHA256 =
        "8d09734c18c27c0cdc229532b571ea416f44ce57b67228f46c67da1d4ccc6aa7"

    private val OFFICIAL_REPO = "https://github.com/wangcangxing/screen-ocr-assistant"

    /** 当前安装包的签名 SHA-256（十六进制小写）；取不到时返回空串 */
    private fun currentSignatureSha256(): String = runCatching {
        val pm = packageManager
        val certs = if (android.os.Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures
        }
        val first = certs?.firstOrNull() ?: return ""
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(first.toByteArray()).joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    /** 是否为官方构建；debug 构建不参与校验，避免误伤自己编译的人 */
    private fun isOfficialBuild(): Boolean {
        val debuggable = (applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) return true
        val cur = currentSignatureSha256()
        return cur.isEmpty() || cur.equals(OFFICIAL_SIGNATURE_SHA256, ignoreCase = true)
    }

    /**
     * 启动告示弹窗。
     *
     * **故意把文案硬编码在这里**（不放进 `strings.xml`、不读 `Prefs`、不读 config.json）：
     * 这样二次打包的人不容易顺手改掉。它是给「**付费**买到这个软件」的人看的 ——
     * 本项目以 PolyForm Noncommercial 1.0.0 发布，任何人售卖它都违反许可证。
     *
     * 签名对不上时显示**更硬的版本**：告诉用户这个包被第三方重打包过、别付钱、去举报。
     */
    private fun showStartupNotice() {
        val official = isOfficialBuild()
        val text = if (official) buildString {
            append("本软件【免费且源码公开】（PolyForm Noncommercial License 1.0.0），禁止任何商业使用。\n\n")
            append("如果你是【花钱】得到的它，说明有人在拿它牟利 —— 这违反了许可证。\n")
            append("建议你向出售方所在的平台或应用商店【举报】，并要求退款。\n\n")
            append("作者从未在任何平台售卖过本软件。唯一官方发布地址：\n$OFFICIAL_REPO\n\n")
            append("（本告示由程序内置、不读取任何配置；删改它同样违反许可证。）")
        } else buildString {
            append("【本安装包的签名与官方版本不一致 —— 它被第三方重新打包过。】\n\n")
            append("官方版本完全免费、源码公开；作者从未在任何平台售卖，也没有任何付费版或授权码。\n")
            append("如果你为它付过钱：请立即向出售方所在平台【举报】并要求退款。\n\n")
            append("官方唯一发布地址：\n$OFFICIAL_REPO\n\n")
            append("可自行核对：apksigner verify --print-certs 你手上的.apk\n")
            append("官方签名 SHA-256 = $OFFICIAL_SIGNATURE_SHA256")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(if (official) "⚠️ 请先读这段告示" else "⛔ 本应用已被第三方重新打包")
            .setMessage(text)
            .setCancelable(false)                       // 必须点掉，避免被忽略
            .setPositiveButton(if (official) "我已阅读" else "我知道了") { _, _ -> }
            .show()
        if (!official) {
            AppLog.w("本安装包签名与官方不一致（当前 ${currentSignatureSha256()}）—— 疑似被第三方重新打包")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
