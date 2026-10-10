package com.dsh.screenocr

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 主流程：无障碍事件 -> 去抖 -> 截图 -> OCR -> 题目判定 -> 大模型 -> 匹配选项 -> 点击。
 * 每一步都写 AppLog，便于在应用内界面或 `adb logcat -s ScreenOcr` 里核对走到了哪一步。
 */
class ScreenOcrAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val ocr = OcrEngine()

    /** 点完答案后等多久再关结果浮层 —— 留给平台渲染判定结果。 */
    private val ANSWER_SETTLE_MS = 1200L

    /** 点完选项后等多久再找「提交作答」（有些题型选完必须提交才判定）。 */
    private val SUBMIT_DELAY_MS = 700L

    /**
     * 收尾找「关闭」的重试次数与间隔。
     * 为什么必须重试：实测答题后 WebView 会短暂重绘，那一刻节点树里**连「关闭」和选项都消失**
     * （日志表现为「收尾：没找到关闭类按钮」+「依据：无；选项 0 个」），一两秒后才回来。
     * 只赌一次就会漏关浮层 —— 16416 实例上实测踩到过。
     */
    private val CLOSE_MAX_ATTEMPTS = 4
    private val CLOSE_RETRY_MS = 1500L

    /** 缓存的「关闭」按钮位置最多用这么久（避免拿上一次弹题的坐标去点） */
    private val CLOSE_CACHE_TTL_MS = 30_000L

    /**
     * 读题那一刻缓存下来的「关闭」类按钮（label + 屏幕坐标）。
     *
     * 为什么必须有它：实测**点完选项后浮层会从无障碍树里彻底消失**（屏幕上还在，用户截图可见），
     * 那一刻实时查找必然失败 —— 重试多少次都没用（16416 实例实测重试 4 次全败，
     * 日志同时打「没找到关闭」和「选项 0 个」）。
     * 而同一道题的浮层里「关闭」位置不会变，所以在**读题时**先把它记下来，收尾直接用。
     */
    private var cachedClose: Pair<String, Rect>? = null
    private var cachedCloseAt = 0L

    /**
     * 点击坐标的最大随机偏移（像素），**防检测**。
     *
     * 每次都在元素正中心、且同一按钮每次落在同一个整数坐标，是自动化最明显的特征之一。
     * 取 10px：实测本类界面的可点元素都远大于它 —— 选项框约 828×135、"提交作答"约 852×144、
     * 连最小的「关闭」也有 87×45（半高 22px），所以 ±10 不会偏到相邻选项。
     */
    private val JITTER_MAX_PX = 10
    private val jitterRandom = java.util.Random()
    private val busy = AtomicBoolean(false)
    private var pending: Runnable? = null

    /** JPEG 编码专用线程：主线程上压缩 1080x2400 会卡顿 */
    private val imageEncoder = Executors.newSingleThreadExecutor { r -> Thread(r, "shot-encode") }

    /** 已处理过的屏幕指纹 -> 处理时刻，避免同一屏反复调用接口 */
    private val recent = LinkedHashMap<String, Long>()

    /** v1.4 去重诊断：上一次的判定结果与键前缀（只在变化时打日志，排查"新题被判成答过"用） */
    private var lastDedupVerdict: Boolean? = null
    private var lastDedupKeyPrefix: String? = null
    private var dedupCallCount = 0

    /** v1.4 换卷检测：最近一次看到的卷面标题，以及"停手时那份卷"的标题 */
    private var currentPaperTitle = ""
    private var finishedPaperTitle = ""

    /** v1.4 换卷检测用的屏幕尺寸：停手期间不截图，就用最近一次分析得到的尺寸 */
    private var lastScreenW = 0
    private var lastScreenH = 0

    /** v1.4 长题页内滚动：按**题号**计数（题号不随滚动变化），换题清零（上限见 READ_MAX_SCROLLS_PER_QUESTION） */
    private var longQuestionScrollNo = ""
    private var longQuestionScrollCount = 0

    /**
     * v1.4 卷面进度（按题号，不再依赖 App 自己的点击账本）：
     *  - [paperTotalN]：这份卷子一共几题 —— 识别到卷面标题后**先点开题卡读一遍题号**，取最大值；
     *  - [currentQuestionNo]：当前屏是第几题（从 `5. 判断题（2分）` 这类题号行解析）。
     * 推进规则（用户口径）：**当前题号 < 总题数就继续作答**。
     */
    private var paperTotalN = 0
    private var currentQuestionNo = 0

    /** v1.4 busy 看门狗用的「busy 是什么时候被拿走的」；0 表示未持有。
     *  不用逐处 `busy.set(false)` 维护它：看门狗每轮对比的是**最近一次成功 acquire 的时刻**，
     *  只要持有超过 [BUSY_WATCHDOG_MS] 就说明有路径漏放，强制释放即可。 */
    private var busySinceMs = 0L

    /** v1.4：是否有一次"强制收尾校验"已在排队（防止每次已答事件都排一次，形成调度风暴） */
    private var finishRetryPending = false

    /** v1.4：待执行的强制收尾校验（在 onOcr 里置位，在下一轮 analyze 开头 busy 已释放时执行） */
    private var pendingFinishCheck = false

    /** v1.4：本轮是否需要"扫题卡取总题数"（由 analyze 主体置位，由 analyze 收尾发起） */
    private var needTotalScan = false

    /** v1.4：本轮识别结果，供 analyze 收尾时发起扫题卡用（那时 res 已在主体作用域内不可见） */
    private var lastAnalyzeRes: OcrResult? = null

    private var lastShotAt = 0L
    private var lastScaleWarnAt = 0L
    private var lastFgDesc = ""

    /** API 对截图有频率限制，两次截图至少隔这么久 */
    private val minShotIntervalMs = 700L

    /** 接口失败时，该屏多少毫秒后才允许重试 */
    private val failureRetryDelayMs = 10_000L

    /**
     * 解析服务失败后的退避时长。
     *
     * 为什么需要（真机实测）：用户开了「OmniParser 解析服务」但 PC 侧没起服务是很常见的状态 ——
     * 此时每轮分析都会先 POST 一次 /parse、**白等一个 omniParserTimeoutMs**（默认 4s）才继续，
     * 屏幕上的题目静止不动时就会一直这么空转（实测 25 秒里失败 10 次）。
     * 所以失败后退避一段时间，期间直接跳过请求，其余链路（节点/OCR 元素、判题、点击）完全不受影响。
     */
    private val OMNI_FAILURE_COOLDOWN_MS = 30_000L

    /**
     * 解析服务在此时刻（[SystemClock.elapsedRealtime]）之前不再尝试。
     * 主线程读、编码线程写（请求就在那个线程上做），故加 @Volatile。
     */
    @Volatile
    private var omniCooldownUntilMs = 0L

    /**
     * 同一屏解析结果的复用时长。
     *
     * 为什么需要（真机实测）：解析服务一次推理在 CPU 机器上要 **10~32 秒**，而屏幕上的题目静止时
     * 每 2 秒一轮轮询，每轮都重新推理一次 —— 整条分析链被反复卡住，开了这个开关就近乎不可用。
     * 屏幕指纹没变、尺寸/上限没变时，30 秒内直接复用上次的解析结果（0 元素同样复用：那也是一次昂贵推理）。
     */
    private val OMNI_REUSE_TTL_MS = 30_000L

    /** 解析结果的缓存键（契约之外的实现细节）：同一屏 + 同尺寸/上限才复用 */
    private data class OmniCacheKey(
        val signature: String,
        val width: Int,
        val height: Int,
        val maxElements: Int
    )

    /** 一次成功的解析结果 + 它的键 + 成功时刻 */
    private data class OmniCache(
        val key: OmniCacheKey,
        val result: OmniParserClient.ParseResult,
        val atMs: Long
    )

    /**
     * 解析结果缓存。**只存成功**（失败走 [omniCooldownUntilMs] 退避，不进缓存）。
     * 主线程读、编码线程写，所以整条缓存放在**一个 @Volatile 引用**里一起发布，避免读到半更新的缓存。
     */
    @Volatile
    private var omniCache: OmniCache? = null

    // ------------------------------------------------------------------ 答完自动推进（task-7）

    /** 两次推进之间的最小间隔 */
    private val ADVANCE_COOLDOWN_MS = 4_000L

    /** 判为「已到页尾 / 连续推进仍无新题」后的暂停时长 */
    private val ADVANCE_BLOCK_MS = 60_000L

    /**
     * 推进窗口（契约 v1.2.2 §6.5）：以**最近一次成功作答时刻**为基准，超出窗口就不再推进，
     * 直到下一次作答再激活。**成功推进不刷新窗口** —— 否则等于没有窗口。
     * 目的：答完题后最多推进约 60s（4s 冷却下约 15 次，足够走到下一页/下一题），
     * 避免在视频 / 长文档页面无限滚动打扰用户。
     */
    private val ADVANCE_WINDOW_MS = 60_000L

    /**
     * 推进动作的延迟：等「提交作答 → 关闭浮层」先走完再动手
     * （= SUBMIT_DELAY_MS + ANSWER_SETTLE_MS，再留 500ms 余量，保证同一时刻排队的「关闭」先执行）。
     * 满足契约 §6.5「在 ANSWER_SETTLE_MS 之后」的时机要求，也不和收尾抢同一个浮层。
     */
    private val ADVANCE_DELAY_MS = SUBMIT_DELAY_MS + ANSWER_SETTLE_MS + 500L

    /** 节点遍历深度上限（与 NodeReader 一致） */
    private val SCROLL_MAX_DEPTH = 40

    /**
     * 推进按钮关键词（契约 §6.4 v1.2.1，**按长度从长到短**，多命中取最长）。
     *
     * **故意不含裸「继续」**：它已经在收尾的「关闭」词表里（继续观看/继续播放/继续），
     * 两处都用会把关闭浮层当成推进，属于自相矛盾。
     */
    private val ADVANCE_WORDS = listOf(
        "继续下一题", "下一部分", "下一题", "下一页", "下页", "下一节",
        "下一讲", "下一章", "下一关", "下一步", "下一个"
    ).sortedByDescending { it.length }

    /**
     * 成对括号包裹的整段（括号内任意内容，成对即可）——匹配前先整段删掉。
     * 覆盖 `（…）(…)【…】[…]《…》〈…〉«…»`。
     */
    private val BRACKET_GROUP = Regex("[（(【\\[《〈«][^）)】\\]》〉»]*[）)】\\]》〉»]")

    /**
     * 删掉括号段后的**否定式**判据（契约 §6.4 v1.2.3）：后缀只要不含「字母类字符」就算合法。
     *
     * 为什么是「不得含字母」而不是白名单（真机逼出来的）：
     * 真实 App 的按钮文本常带**图标字体私有区字符** —— 实测智慧树考试页的「下一题」节点文本是
     * `下一题` + **U+E641**（PUA 的箭头图标）。白名单（空白/数字/标点）必然漏掉它，
     * 于是明明可见的按钮找不到 → 退化成滚动 → 在不滚动页面上卡死。
     * 白名单永远追不上各家图标字体（U+E000–U+F8FF、U+F0000–U+FFFFD），
     * 而「后缀含中文/字母才算另一个词」才是真正的意图（`下一题练习`/`下一步骤` 正是靠这条排除）。
     */
    private fun advanceSuffixOk(rest: String): Boolean = rest.none { Character.isLetter(it) }

    /** 本应用**本次会话**成功点击选项的次数；>0 才允许推进。前台包名变化时清零 */
    private var answeredThisSession = 0
    private var lastForegroundPkg = ""

    /**
     * 最近一次**成功作答**的时刻（[SystemClock.elapsedRealtime]）；0 = 本会话还没答过。
     * 推进窗口（[ADVANCE_WINDOW_MS]）以它为基准，**成功推进不刷新它**（契约 v1.2.2 §6.5）。
     */
    private var lastAnsweredAtMs = 0L

    /** 连续推进次数（期间没再见到新题）；达到 prefs.autoAdvanceMaxStreak 就暂停并清零 */
    private var advanceStreak = 0
    private var lastAdvanceAt = 0L
    private var advanceBlockedUntilMs = 0L

    /** 上次滚动时的屏幕签名：下一次分析若签名没变 → 判定「已到页尾」 */
    private var pendingScrollSignature: String? = null

    /** 上一次推进用的是**节点滚动**还是手势（只在 [pendingScrollSignature] 有效期内有意义） */
    private var lastScrollWasNode = false

    /**
     * 节点滚动在这台机器/这个页面上**无效**（`ACTION_SCROLL_FORWARD` 返回 true 但画面没动）。
     *
     * 真机实测（MuMu + `/quiz-long`）：第 1 次节点滚动真滚了，第 2 次返回成功却毫无位移 ——
     * 若直接按「签名未变 = 页尾」暂停 60s，长页面就永远卡在第一屏。所以改成两段式：
     * 节点滚动无位移 → 记住「无效」并改用上滑手势重试；**只有手势也无位移才判页尾**。
     */
    private var nodeScrollIneffective = false

    /**
     * 上一次节点滚动的连击**还没结算**（F5 定稿：无效推进不计入连击）。
     *
     * 节点滚动"返回成功"不等于真滚了，所以它的 +1 推迟到下一次分析确认签名变了才补上；
     * 否则「节点滚动假成功」会把连击顶到上限、在刚滚动到位时反而暂停 60s（正好卡住长页面场景）。
     */
    private var pendingNodeScrollStreak = false

    /**
     * 按钮推进「点了没反应」的退避时长（契约 §6.5 · D-e）。
     *
     * 真机反例：考试页只有 1 道题时「下一题」是**无目标按钮**（点了页面不变，属平台行为、不是缺陷），
     * App 于是每 4s 点一次；去重窗口过掉后还会把同一题重答一遍 → 「作答 → 刷新推进窗口 → 再连点」
     * 无限循环，既反复点击又反复真调接口（花钱）。压 5 分钟足以脱离这种静态页面。
     */
    private val ADVANCE_BUTTON_DEAD_MS = 300_000L

    /**
     * 按钮点击「有没有反应」的检测状态（D-e，与滚动的 `pendingScroll*` **并列且互不干扰**）：
     * 点击前的屏幕签名 + 点击时刻。下一轮分析比对签名；4s 内没变就判「点了没反应」。
     */
    private var pendingButtonSignature: String? = null
    private var pendingButtonClickedAt = 0L

    // ------------------------------------------------------------------ 答题完毕收尾（契约 §7 / task-10）

    /** 题卡入口的整节点文本（契约 §7.1 步骤 1） */
    private val CARD_WORD = "题卡"

    /** 题卡里的题号：纯数字文本（契约 §7.1 步骤 2，1~3 位） */
    private val CARD_QUESTION_NO = Regex("^\\d{1,3}$")

    /**
     * 卷面标题（v1.4）：用于识别「同一 App 内换了一份卷子」。
     *
     * 实测智慧树标题形如 `第五章单元测试` / `第八章单元测试`。这里**不用严格正则**：
     * 最初写成 `^第.{1,8}(章|单元|节|讲|关|部分).{0,10}$`，结果对「第八章单元测试」
     * （"单元"之后有 12 个字）匹配失败，真机上打出「(未识别到)」。
     * 现在改成「短文本 + 必须同时含『第』与（章/单元/节/讲/关/部分）」，宽松但够用。
     */
    private fun looksLikePaperTitle(t: String): Boolean =
        t.length in 4..30 && t.contains("第") &&
            (t.contains("章") || t.contains("单元") || t.contains("节") || t.contains("讲") || t.contains("关") || t.contains("部分"))

    /** 提交按钮词表（契约 §7.2，整节点 `text‖contentDescription` 精确匹配） */
    private val SUBMIT_WORDS = listOf("提交作业", "提交考卷", "交卷", "提交答案", "确认提交")

    /** 确认按钮词表（契约 §7.2） */
    private val CONFIRM_WORDS = listOf("确认", "确定", "确认提交", "提交", "好的", "是")

    /** 弹窗里出现这些词 → 说明还有未作答，**取消提交**（契约 §7.2 安全底线） */
    private val UNANSWERED_HINTS = listOf("未作答", "还有", "未完成", "不能提交")

    /** 未作答弹窗的取消/关闭词表（契约 §7.2；找不到就只是停手） */
    private val CANCEL_WORDS = listOf("取消", "关闭")

    /** 点开题卡 / 点提交后等界面渲染的时间（契约 §7.1「等 ~1.2s」） */
    private val FINISH_WAIT_MS = 1_200L

    /** 题卡检查限频（契约 §7.4：两次检查之间至少 20s） */
    private val FINISH_CHECK_INTERVAL_MS = 20_000L

    /**
     * 已停手（契约 §7.3）：判为整卷答完并执行完动作后置位。
     * 停手期间不做任何自动化动作（不判题、不调接口、不点击、不推进），只在每轮写一行日志；
     * **前台包名变化时解除**（切到别的 App 再回来视为新一轮）。
     */
    private var finishStopped = false

    /** 上次题卡检查时刻（限频用） */
    private var lastFinishCheckAt = 0L

    /** 题卡入口坐标：关闭题卡要**再点同一坐标**（契约 §7.1 步骤 3，严禁用返回键） */
    private var cardTapX = 0
    private var cardTapY = 0

    /**
     * 题卡校验闸门（v1.6 新增）。
     *
     * **为什么必须有**：题卡校验是「点开面板 → 等 1.2s 数题号 → 再点关闭 → 等 1.2s」的异步序列。
     * 真机实测（第一章单元测试，5 题）：面板打开期间 App 仍在点选项与「下一题」，**点击全部落在面板上**，
     * 结果题卡里只有第 1 题有作答标记、`M` 却虚增到 5 —— 既丢作答又误判「已答完」。
     * 置位期间：`analyze()` 被 `busy` 挡住、推进被 `busy` 挡住、`submitIfPresent`/`closeAnswerOverlay`
     * 直接返回；且**校验开始前必须没有在飞的大模型请求**（否则回调会在面板打开时点选项）。
     */
    private var cardPanelBusy = false

    /** 题卡闸门置位时刻（看门狗用：万一某条路径忘了放闸，15s 后强制放掉，避免永久卡死） */
    private var cardGateAtMs = 0L

    /** 有大模型请求在飞（题卡校验必须等它落地后再开始） */
    private var llmInFlight = false

    /** 面板关不掉时的冷却（避免反复开关把页面搅乱） */
    private var cardCheckBlockedUntilMs = 0L

    /**
     * 「题卡面板关不掉」的冷却时长。
     *
     * **v1.4 从 5 分钟降到 15 秒**：真机踩到 `⑩ 收尾：题卡校验冷却中（还有 253s）` ——
     * 一次"关不掉"判定（很可能只是重绘间隙的误判）就把收尾校验锁死 5 分钟，
     * 表现为"答完了、也不提交，一直说冷却中"。15s 足够避开反复开关，又不会卡死功能。
     */
    private val CARD_FAIL_BLOCK_MS = 15_000L


    /**
     * 完成判定用的作答计数：**试运行也计数**（`dryRun` 跳过点击时按「本应作答」计）。
     * 与推进闸门用的 `answeredThisSession` 分开，互不影响。
     */
    private var answeredForFinish = 0

    /**
     * 「已经推不动了」的时刻（推进按钮点了没反应 / 滚动画面不动）。
     *
     * **为什么需要它**：题卡校验要开关面板，而真机实测（第一章单元测试）**关闭面板后 WebView 的无障碍树会变陈旧**
     * —— 题目与选项从树里消失（截图里明明还在），App 于是连续 150 秒判「非题目」；手动点一次
     * 「上一题→下一题」才恢复。所以题卡校验**只在推不动（到卷尾）时才做**，绝不边答题边开面板。
     */
    private var stuckAtEndMs = 0L

    /**
     * 题目去重阈值，分两档：
     *  - 刚答完的 [RECENT_WINDOW_MS] 内放宽到 [SIMILARITY_THRESHOLD_RECENT]：这段时间画面里还是同一道题，
     *    逐帧 OCR 抖动最大，用严格阈值会漏判、导致同一题被重复调用接口 / 重复点击（实测踩过 3.7 秒内点两次）。
     *  - 之后恢复 [SIMILARITY_THRESHOLD]，避免把「选项措辞相近的新题」误当成已答过。
     */
    private val SIMILARITY_THRESHOLD = 0.90
    private val SIMILARITY_THRESHOLD_RECENT = 0.82
    private val RECENT_WINDOW_MS = 12_000L

    /**
     * 去重键里「选项本身够不够区分一道题」的门槛（v1.4）。
     * 最长选项正文短于这个字符数时（判断题「错」「对」只有 1~2 字），键必须带题干。
     */
    private val MIN_OPTION_TEXT_FOR_KEY = 4

    /** 一道题最多为「读全题面」在页内滚动几次（防止在长题上反复滚动） */
    private val READ_MAX_SCROLLS_PER_QUESTION = 2

    /** busy 通用看门狗时长：任何路径拿了 busy 超过这么久没还，就强制释放并留日志 */
    private val BUSY_WATCHDOG_MS = 20_000L

    /** 题号行，例如 `5. 判断题（2分）`、`2. 单选题（2分）`（长题滚动按题号计数用） */
    private val QUESTION_NO_LINE = Regex("^\\d{1,3}\\s*[.、．]\\s*\\S{0,6}题")

    /** 去重键的最大长度：留足题干区分度（实测同屏两题的差异出现在第 54 字符附近，200 足够） */
    private val KEY_MAX_LEN = 200

    /**
     * 两次点击之间的最小间隔。
     * 去重靠的是「选项文字的编辑距离相似度」，而逐帧 OCR 抖动偶尔会让同一道题的相似度
     * 掉到阈值以下（实测 Canvas 场景出现过 3.7 秒内点两次）。这道冷却不依赖内容比对，
     * 直接兜住这类重复点击。取得比较短，不会挡住在它之后出现的新题。
     */
    private val clickCooldownMs = 3_000L
    private var lastClickAt = 0L

    private val WHITESPACE = Regex("\\s+")

    /** 状态栏时间之类每分钟都在变的内容 */
    private val CLOCK = Regex("\\d{1,2}:\\d{2}")

    /**
     * 平台「卷尾提示」的候选文案（v1.4）。实测智慧树为「这已经是最后一道题了」。
     * 按整句变体列出，避免为了适配别的措辞去改判定逻辑。
     */
    private val END_PAPER_KEYWORDS = listOf(
        "这已经是最后一道题了",
        "这已经是最后一道题",
        "已是最后一道题",
        "已经是最后一题",
        "这已是最后一题",
        "已是最后一题"
    )

    /** 参与卷尾提示扫描的文本节点最大长度（题干里出现"最后"的句子通常比提示长得多） */
    private val END_PAPER_MAX_LEN = 40

    /** 只有这么短的节点才当作"提示本身"（实测提示 10 个字） */
    private val END_PAPER_PROMPT_MAX_LEN = 20


    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // 关键：允许获取**所有**交互窗口的节点树。
        // 实测（16416）：屏幕上明明有浮层，mCurrentFocus/mFocusedApp 却是 null，
        // rootInActiveWindow 于是退化成桌面窗口 —— 不开这个 flag，浮层所在的独立窗口根本读不到。
        runCatching {
            val info = serviceInfo
            if (info != null) {
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                serviceInfo = info
                AppLog.i("已开启「获取所有交互窗口」标志（读取浮层所在的独立窗口所必需）")
            }
        }
        val caps = serviceInfo?.capabilities ?: 0
        val canShot = caps and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0
        val canGesture = caps and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0
        AppLog.i("无障碍服务已连接：capabilities=$caps（可截图=$canShot，可手势=$canGesture）")
        if (!canShot) {
            AppLog.w("服务未获得截图能力，请确认 accessibility_service_config.xml 中 canTakeScreenshot=\"true\"")
        }
        startPolling()
    }

    /**
     * 当前要读的窗口根节点 —— **取所有 display 上的窗口**。
     *
     * 关键坑（实测 16416）：MuMu 这类模拟器会把应用渲染在**非默认 display** 上
     * （`dumpsys input_method` 里 client 的 `displayId=7`），此时 `getWindows()` **只返回默认 display**
     * 的窗口 —— 也就是桌面与系统栏，智慧树的窗口一个都读不到，而 `dumpsys window` 里它明明有焦点：
     *   `mCurrentFocus=Window{… StudyCourseVideoActivity}`、`mFocusedWindow=Window{…}`
     * （`dumpsys window` 打印的是**所有** display，所以看起来"焦点正常"，App 却瞎了。）
     *
     * `getWindowsOnAllDisplays()`（API 30+，本项目 minSdk 30）才是对的。
     */
    /**
     * 所有 display 上的窗口。
     *
     * `getWindowsOnAllDisplays()` 返回的是 `SparseArray<List<AccessibilityWindowInfo>>`（key = displayId），
     * **它没有 `values()`**，只能按下标摊平（别照搬 `windows` 的写法）。
     */
    private fun allWindows(): List<android.view.accessibility.AccessibilityWindowInfo> {
        val out = ArrayList<android.view.accessibility.AccessibilityWindowInfo>()
        runCatching {
            val arr = windowsOnAllDisplays
            for (i in 0 until arr.size()) out.addAll(arr.valueAt(i))
        }
        return out
    }

    private fun allWindowRoots(): List<AccessibilityNodeInfo?> {
        val out = ArrayList<AccessibilityNodeInfo?>()
        runCatching {
            for (w in allWindows()) {
                val r = w.root ?: continue
                out.add(r)
            }
        }
        if (out.isEmpty()) out.add(rootInActiveWindow)   // 兜底：拿不到窗口列表时仍用焦点窗口
        return out
    }

    // ------------------------------------------------------------------ 轮询（视频场景必需）

    /**
     * 定时截图分析，不依赖无障碍事件。
     *
     * 为什么必须有它：视频、动画、游戏画面是直接绘制的，**不会产生 TYPE_WINDOW_CONTENT_CHANGED
     * 之类的无障碍事件**。只靠事件触发的话，视频里随机出现的题目永远不会被截图，也就永远不会被识别。
     */
    private fun startPolling() {
        handler.removeCallbacks(pollTicker)
        handler.postDelayed(pollTicker, 1_000L)
    }

    private val pollTicker = object : Runnable {
        override fun run() {
            var next = 0L
            try {
                val prefs = Prefs(this@ScreenOcrAccessibilityService)
                next = prefs.pollIntervalMs.toLong()
                if (next > 0 && prefs.processingEnabled && isScreenOn() && !busy.get() && foregroundIsAnalyzable()) {
                    analyze("轮询")
                }
            } catch (t: Throwable) {
                AppLog.e("轮询异常：${t.javaClass.simpleName}: ${t.message}")
                next = 2_000L
            } finally {
                // 间隔变了也能下一次生效；0 表示已关闭轮询，就不再排下一拍（改设置后重新拉起即可）
                if (next > 0) handler.postDelayed(this, next.coerceIn(500L, 60_000L))
                else AppLog.i("轮询已关闭")
            }
        }
    }

    /**
     * 轮询是「无条件定时截图」，所以必须自己把不该看的前台窗口排掉 ——
     * 否则它会一直分析本应用自己的设置界面（甚至可能点中自己的按钮）。
     * 无障碍事件路径本来就有这层过滤，轮询这里不能漏。
     *
     * 注意：实测 `rootInActiveWindow` 在本应用位于前台时**不一定**返回本应用的包名，
     * 因此优先用窗口列表里 `isFocused` 的那个窗口来判定。
     */
    private fun foregroundIsAnalyzable(): Boolean {
        val viaRoot = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        val viaWindow = runCatching {
            // 同样要取**所有 display**：MuMu 把应用渲染在非默认 display（实测 displayId=7），
            // 只用 windows 会看到"焦点在桌面"，前台判定随之出错。
            allWindows().firstOrNull { it.isFocused }?.root?.packageName?.toString()
        }.getOrNull()
        val fg = viaWindow ?: viaRoot

        val desc = "root=$viaRoot window=$viaWindow"
        if (desc != lastFgDesc) {
            lastFgDesc = desc
            AppLog.i("前台包名判定：$desc -> 采用 ${fg ?: "(未知)"}")
        }

        // 推进护栏（契约 §6.5）：前台包名一变就清掉"本次会话答过题"计数
        noteForegroundPkg(fg)

        if (fg == null) return false
        if (fg == packageName) return false
        if (fg == "com.android.systemui") return false
        return true
    }

    /** OCR 兜底的最小间隔：只在节点树不像题目时才考虑，且别每轮都跑（OCR 约几百毫秒） */
    private val OCR_FALLBACK_INTERVAL_MS = 3000L
    private var lastOcrFallbackAt = 0L

    /**
     * 节点树读到的东西**不像题目**时，是否改用截图 OCR 再判一次。
     *
     * 为什么需要：实测 16416 上无障碍服务只看得到"系统栏 + 桌面"（`mCurrentFocus` / `mFocusedApp`
     * 都是 null 的异常状态），而屏幕上、系统窗口列表里**确实有那道题**。
     * 截图来自 `takeScreenshot`，不受窗口与焦点影响 —— 那是这种情况唯一的退路。
     */
    private fun needOcrFallback(res: OcrResult, prefs: Prefs): Boolean {
        if (QuestionDetector.detect(res, prefs.minScore).isCandidate) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastOcrFallbackAt < OCR_FALLBACK_INTERVAL_MS) return false
        lastOcrFallbackAt = now
        return true
    }

    private fun isScreenOn(): Boolean {
        return runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isInteractive
        }.getOrDefault(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val type = e.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) {
            return
        }

        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (pkg == "com.android.systemui") return

        // 推进护栏（契约 §6.5）：前台应用换人了 → 清零"本次会话答过题"计数
        noteForegroundPkg(pkg)

        val prefs = Prefs(this)
        if (!prefs.processingEnabled) return

        schedule("${eventName(type)} @$pkg", prefs.debounceMs.toLong())
    }

    override fun onInterrupt() {
        // 无需处理
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AppLog.i("无障碍服务已解绑")
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        handler.removeCallbacks(pollTicker)
        pending?.let { handler.removeCallbacks(it) }
        pending = null
        imageEncoder.shutdownNow()
        ocr.close()
        AppLog.i("无障碍服务已销毁")
    }

    // ------------------------------------------------------------------ 调度

    private fun schedule(reason: String, delayMs: Long) {
        pending?.let { handler.removeCallbacks(it) }
        val r = Runnable { analyze(reason) }
        pending = r
        handler.postDelayed(r, delayMs.coerceIn(200L, 10_000L))
    }

    private fun analyze(reason: String) {
        // 契约 §7.3 停手状态：不再做任何自动化动作（连截图/判题都不做），每轮只写一行日志；
        // 无障碍服务与轮询保持存活，等前台包名变化时由 noteForegroundPkg 解除。
        if (finishStopped) {
            // v1.4：**同一 App 内手动换卷**时包名不变，停手永远解不开（真机踩到：答完第七章、
            // 手动切到第八章后一直「已停手：等待前台切换」）。换卷语义上就是新的一轮，
            // 所以这里每轮看一次可见文本里的**卷面标题**，发现不是刚答完的那份就自行解除停手。
            if (paperChangedWhileStopped()) {
                finishStopped = false
                answeredForFinish = 0
                answeredThisSession = 0
                stuckAtEndMs = 0L
                lastAnsweredAtMs = 0L
                advanceStreak = 0
                advanceBlockedUntilMs = 0L
                lastFinishCheckAt = 0L
                paperTotalN = 0                 // 换卷 → 重新扫题卡取总题数
                currentQuestionNo = 0
                AppLog.i("⑩ 收尾：检测到换卷（$finishedPaperTitle → $currentPaperTitle）→ 解除停手，按新卷继续答题")
            } else {
                // 停手期间不截图，但换卷检测要看屏幕内的节点 → 刷新一次屏幕尺寸
                runCatching {
                    val dm = resources.displayMetrics
                    lastScreenW = dm.widthPixels
                    lastScreenH = dm.heightPixels
                }
                AppLog.i("⑩ 已停手：本应用已答完，等待前台切换")
                return
            }
        }
        // 看门狗（v1.6）：题卡闸门万一被某条异常路径漏放，15s 后强制放掉，避免永久卡死
        if (cardPanelBusy && SystemClock.elapsedRealtime() - cardGateAtMs > 15_000L) {
            AppLog.w("⑩ 收尾：题卡闸门超时（15s），强制放行")
            endCardGate()
        }
        // 看门狗（v1.4）：**通用** busy 看门狗。上面的闸门看门狗只在 cardPanelBusy 时生效，
        // 而真机上出现过 busy 被永久占住、什么分析都做不了的情况（`跳过：上一次分析还没结束` 无限刷）。
        // 这里对 busy 本身兜底：任何路径把 busy 拿了超过 20s 没还，就强制放掉并留日志（便于定位是谁）。
        if (busy.get() && busySinceMs > 0L && SystemClock.elapsedRealtime() - busySinceMs > BUSY_WATCHDOG_MS) {
            AppLog.w("⑩ 看门狗：busy 被占用超过 ${BUSY_WATCHDOG_MS / 1000}s（疑似某条退出路径漏放），强制释放" +
                "（cardPanelBusy=$cardPanelBusy）")
            busy.set(false)
            busySinceMs = 0L
            cardPanelBusy = false
        }
        // v1.4：待执行的**强制收尾校验**（已到最后一题 / 卷尾提示）。放在这里执行的原因同下面的扫题卡：
        // `startCardCheck` 要求 busy==false，而它在 onOcr 里被要求时 analyze 正持有 busy，
        // 直接调用会被静默挡回。轮询的下一轮开头 busy 必为空，正是安全的执行点。
        if (pendingFinishCheck && !cardPanelBusy) {
            val r = lastAnalyzeRes
            if (r != null) {
                pendingFinishCheck = false
                AppLog.i("⑩ 收尾：执行排队的强制校验（题号 $currentQuestionNo/$paperTotalN）")
                startCardCheck(Prefs(this), r, lastScreenW, lastScreenH)
                return
            }
            pendingFinishCheck = false
        }
        // v1.4：识别到卷面标题、但还不知道本卷总题数时，**在这一轮开始处置**（此刻 busy 必为空）：
        // 先点开题卡读一遍题号、取最大值当总题数，读完再正常分析。
        // 为什么放在这里：`startCardCheck` 与 `analyze` 都要求持有/不持有 busy，二者互斥；
        // 在 analyze 内部调用会空转（真机踩到），而放在轮询的下一轮开始处既简单又不会打架。
        if (needTotalScan && paperTotalN <= 0 && currentPaperTitle.isNotEmpty() && !cardPanelBusy) {
            val r = lastAnalyzeRes
            if (r != null) {
                needTotalScan = false
                startCardCheck(Prefs(this), r, lastScreenW, lastScreenH) {
                    schedule("扫题卡取总题数后继续", 600L)
                }
                return
            }
        }
        needTotalScan = false        // 没有可用识别结果就不再空等，等下一轮重新识别后置位
        if (!busy.compareAndSet(false, true)) {
            AppLog.w("跳过：上一次分析还没结束")
            return
        }
        busySinceMs = SystemClock.elapsedRealtime()
        val prefs = Prefs(this)

        val now = SystemClock.elapsedRealtime()
        if (now - lastShotAt < minShotIntervalMs) {
            AppLog.i("跳过：距上次截图过近（系统对截图有频率限制）")
            busy.set(false)
            return
        }
        lastShotAt = now

        AppLog.i("① 截图开始（$reason）")
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val bmp = hardwareBufferToBitmap(result)
                        if (bmp == null) {
                            busy.set(false)
                            return
                        }
                        AppLog.i("② 截图成功 ${bmp.width}x${bmp.height}")
                        // 记录屏幕尺寸：停手期间的换卷检测要用（那时不截图）
                        lastScreenW = bmp.width
                        lastScreenH = bmp.height
                        // 每轮顺手记下卷面标题：停手/提交那一刻弹窗盖住页面，那时读不到标题，
                        // 必须靠平时积累（实测「第八章单元测试」在提交时读成 "(未识别到)"）
                        runCatching {
                            findPaperTitle(bmp.width, bmp.height).takeIf { it.isNotEmpty() }
                                ?.let { if (it != currentPaperTitle) {
                                    AppLog.i("⑩ 收尾：卷面标题识别为「$it」（换卷检测基线）")
                                    currentPaperTitle = it
                                    paperTotalN = 0          // 新卷 → 需要重新扫题卡取总题数
                                    currentQuestionNo = 0
                                } }
                        }
                        // 识别来源：**无障碍节点树优先**（原生控件与 WebView 都能读，且不受模拟器截图黑图影响），
                        // 节点树读不到可用文字时才回退 OCR。两者产出同一种 OcrResult，判题与点击逻辑完全共用。
                        val roots = allWindowRoots()
                        if (roots.size > 1) {
                            val names = roots.map { r ->
                                runCatching { r?.packageName?.toString() }.getOrNull() ?: "?"
                            }
                            AppLog.i("   本次读到 ${roots.size} 个窗口：" + names.joinToString(","))
                        }
                        val fromNodes = NodeReader.toOcrResult(roots, bmp.width, bmp.height)
                        if (fromNodes != null) {
                            // 读得到题目的这一刻，「关闭」按钮一定也在树里 —— 先把它记下来给收尾用
                            cacheCloseTarget(roots)
                            AppLog.i("③ 识别来源：无障碍节点树（${fromNodes.lines.size} 行文本，免 OCR）")
                            // **截图 OCR 兜底**：实测 16416 上屏幕明明有题、系统窗口列表里也有智慧树窗口，
                            // 但无障碍服务只给得到"系统栏 + 桌面"（mCurrentFocus=null 的异常状态）。
                            // takeScreenshot 不受窗口与焦点影响 —— 那是这种情况唯一的退路。
                            if (needOcrFallback(fromNodes, prefs)) {
                                AppLog.i("   节点树里不像题目 → 改用截图 OCR 再判一次")
                                ocr.recognize(bmp, prefs.ocrScalePercent) { res ->
                                    handleOcrResult(prefs, res, bmp, fromOcr = true)
                                }
                            } else {
                                handleOcrResult(prefs, fromNodes, bmp, fromOcr = false)
                            }
                        } else {
                            AppLog.i("③ 节点树没读到可用文字，回退 OCR")
                            ocr.recognize(bmp, prefs.ocrScalePercent) { res ->
                                handleOcrResult(prefs, res, bmp, fromOcr = true)
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        AppLog.e("① 截图失败：${screenshotError(errorCode)}")
                        busy.set(false)
                    }
                }
            )
        } catch (t: Throwable) {
            AppLog.e("① 截图异常：${t.javaClass.simpleName}: ${t.message}")
            busy.set(false)
        }
    }

    /**
     * OCR 之后接管 [bmp] 的生命周期：从这里开始，每条退出路径都必须恰好 recycle 一次。
     *
     * @param fromOcr true 表示 [res] 来自**截图 OCR**（坐标需按 scaleX/scaleY 换算回屏幕）；
     *                false 表示来自**无障碍节点树**（坐标本来就是屏幕坐标）。
     *                这个标志只用于决定 SoM 元素要不要把 [res] 的行算作 `source="ocr"`。
     */
    private fun handleOcrResult(prefs: Prefs, res: OcrResult?, bmp: Bitmap, fromOcr: Boolean) {
        if (res == null) {
            AppLog.e("② OCR 失败")
            bmp.recycle()
            busy.set(false)
            return
        }
        AppLog.i("③ 识别完成：${res.lines.size} 行 / ${res.fullText.length} 字" +
            if (res.downscaled) "（已降采样到 ${res.imageWidth}x${res.imageHeight}）" else "")
        onOcr(prefs, res, bmp, fromOcr)
    }

    private fun hardwareBufferToBitmap(result: ScreenshotResult): Bitmap? {
        val hb = result.hardwareBuffer
        return try {
            val wrapped = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
            // ML Kit 不能直接读硬件位图，复制成 ARGB_8888
            wrapped?.copy(Bitmap.Config.ARGB_8888, false)
        } catch (t: Throwable) {
            AppLog.e("截图转 Bitmap 失败：${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            runCatching { hb.close() }
        }
    }

    // ------------------------------------------------------------------ OCR 之后

    private fun onOcr(prefs: Prefs, res: OcrResult, bmp: Bitmap, fromOcr: Boolean) {
        // v1.4：记下本轮识别结果。**必须在这里赋值** —— 「扫题卡取总题数」与「排队的强制收尾校验」
        // 都在下一轮 analyze 开头执行，它们要靠这份 res 才能点题卡、数题号。
        // 真机踩到：漏了这行 → 两个排队逻辑每次都在 `r == null` 处静默清掉待办，功能完全不起作用。
        lastAnalyzeRes = res
        if (res.fullText.isBlank()) {
            AppLog.i("③ 屏幕上没识别到文字，结束")
            bmp.recycle()
            busy.set(false)
            return
        }

        val q = QuestionDetector.detect(res, prefs.minScore)
        logIfSizeMismatch(res)
        AppLog.i("④ 题目判定：score=${q.score} 阈值=${prefs.minScore} -> ${if (q.isCandidate) "疑似题目" else "判定为非题目"}")
        AppLog.i("   依据：${q.signals.joinToString("、").ifBlank { "无" }}；选项 ${q.options.size} 个")
        // 诊断（临时）：打出识别到的选项标签与**节点给的坐标**。
        // 要回答的问题：节点坐标与屏幕坐标是不是同一坐标系 ——
        // 实测程序点 (540,270)，而截图里该处是标题、「对/错」在 y≈880，差了 600 多像素。
        if (q.options.isNotEmpty()) {
            AppLog.i("   选项明细：" + q.options.joinToString(" ｜ ") {
                val b = it.box
                if (b == null) "${it.label}@(无框)"
                else "${it.label}@${b.centerX()},${b.centerY()}(w${b.width()},h${b.height()})"
            })
        }

        // ---- v1.4：卷尾提示（平台自己给的「最后一道题」文案）----
        // 比「App 自己数答了几题」可靠：它是平台对我们点「下一题」的直接回应。
        // 命中时**立刻**允许做整卷校验，不再死等 `stuckAtEndMs`（那要"推不动"才置位，
        // 而推不动的触发条件既慢又依赖滚动/按钮无进展检测）。
        val endPaperPrompt = findEndPaperPrompt(res)
        if (endPaperPrompt != null) {
            // 安全闸（v1.4，真机踩到后补）：**当前这道题还没答过时，绝不开题卡**。
            // 为什么：开/关题卡会让 WebView 无障碍树变陈旧（已知坑点 #19），App 之后再也读不到题目，
            // 于是把当前屏误判成"答过"、推进又被无进展暂停挡住 —— 真机实测：一套新卷上提示一出现就开面板，
            // 结果整套卷卡死（`⑨ 推进：暂停中（还有 237s）` + 反复「判为答过、状态未知」）。
            // 判据用现成的 `isRecentlyAnswered`（题目去重记录）：这道题在去重集里 = 已经处理过。
            // 提示只是平台对我们点「下一题」的回应，**不代表这题答完了** —— 在两者不一致时以"先答题"为准。
            val qForPrompt = QuestionDetector.detect(res, prefs.minScore)
            val keyForPrompt = if (qForPrompt.isCandidate) questionKey(qForPrompt) else ""
            val currentAnswered = keyForPrompt.isNotEmpty() && isRecentlyAnswered(
                keyForPrompt, System.currentTimeMillis(), prefs.dedupSeconds * 1000L
            )
            if (!currentAnswered) {
                // 这套卷还没做完 → 不动题卡，只提示"到卷尾了"，让推进/答题照常走
                AppLog.i("⑩ 收尾：识别到卷尾提示「$endPaperPrompt」，但当前题尚未作答 → 不开题卡（避免把树弄陈旧）")
            } else {
                // 当场把「题卡」入口解析出来（提示只闪约 1 秒，晚了树就陈旧了；见 startCardCheck 的参数说明）
                val cardTarget = findCardToggle(res.sourceWidth, res.sourceHeight)
                AppLog.i("⑩ 收尾：识别到卷尾提示「$endPaperPrompt」且当前题已作答 → 立即做整卷校验" +
                    (cardTarget?.let { "（题卡入口已解析：${it.first}@${it.second.centerX()},${it.second.centerY()}）" } ?: "（本轮没解析到题卡入口，稍后重试）"))
                scheduleFinishOrAdvance(prefs, res, "卷尾提示", forceFinishCheck = true, cardTarget = cardTarget)
            }
        }

        // 推进的无进展检测（契约 §6.5）：上一次滚动时记下的签名与本次分析比对，没变就是到页尾了
        checkScrollProgress(prefs, res.signature)
        // 按钮推进的「点了有没有反应」检测（D-e，与上面那套并列）
        checkButtonProgress(res.signature)

        // ---- 解析服务（识别源③ + SoM 的图标元素来源）----
        // 判定顺序（F4）：① 失败退避 → ② 同一屏缓存命中 → ③ 真正发请求。
        // 只要用户开了它、也填了地址，前两者都没命中时**每次都会请求**：图标语义是端侧 OCR 拿不到的，
        // 属于 SoM 的独有来源；但「用它的文字元素重建 OcrResult 再判一次」只在本地「不像题目 / 没有选项」时才做（契约 3）。
        val omniConfigured = prefs.omniParserEnabled && prefs.omniParserUrl.isNotBlank()
        val cooldownLeftMs = omniCooldownUntilMs - SystemClock.elapsedRealtime()
        val omniCooling = omniConfigured && cooldownLeftMs > 0
        if (omniCooling) {
            AppLog.i("解析服务最近失败过，还有 ${(cooldownLeftMs + 999) / 1000}s 不再尝试（避免每轮白等超时）")
        }

        // 缓存键就直接用现成的「屏幕指纹 + 尺寸 + 上限」：signature 是 OcrResult 里已有的去空白全文，
        // 与既有「同一屏只处理一次」同思路，不另造指纹算法。
        val omniKey = OmniCacheKey(res.signature, bmp.width, bmp.height, prefs.somMaxElements)
        val cached = omniCache
        val omniReuse = if (omniConfigured && !omniCooling && cached != null && cached.key == omniKey &&
            SystemClock.elapsedRealtime() - cached.atMs <= OMNI_REUSE_TTL_MS
        ) {
            cached
        } else {
            null
        }
        if (omniReuse != null) {
            AppLog.i("解析服务：屏幕与上次解析相同，复用上次 ${omniReuse.result.elements.size} 个元素（省一次推理，TTL ${OMNI_REUSE_TTL_MS / 1000}s）")
        }
        val omniOn = omniConfigured && !omniCooling && omniReuse == null
        // 本轮「解析服务这一路」是否可用（复用也算可用，元素一样能进 SoM / 兜底判题）
        val omniAvailable = omniConfigured && !omniCooling

        if (!q.isCandidate && !omniAvailable) {
            // 屏幕不是题目：仍要判断「本页题都做完了」→ 允许推进（契约 §6.1 情形 1 / v1.2.2 §6.5 补充 1）。
            // 注意：这里**不能直接 return 掉推进调度** —— 解析服务默认关闭，这是最常见的主路径；
            // v1.2/v1.2.1 把它吞掉了，真机上表现为「滚进无题区域后推进永久停住」（D-d）。
            AppLog.i("④ 判定为非题目（解析服务未开启/不可用）→ 交给推进判断")
            bmp.recycle()
            busy.set(false)
            scheduleFinishOrAdvance(prefs, res, "本地判定为非题目")
            return
        }

        // 截图编码 + 解析服务请求都放后台线程（v1.3 起 JPEG 压缩就是这么做的；主线程只做判定与点击决策）。
        // 注意：解析服务**任何失败都不得中断主流程** —— 记日志、当成"没有它"继续（契约 3）。
        imageEncoder.execute {
            // 命中缓存时不需要为解析服务编码（那张 JPEG 只喂给 POST /parse）；但视觉模式仍要图
            val needPlainJpeg = prefs.sendScreenshot || omniOn
            val plainJpeg = try {
                if (needPlainJpeg) {
                    SomAnnotator.encodeJpegBase64(bmp, prefs.imageMaxSide, prefs.imageQuality)
                } else {
                    null
                }
            } catch (t: Throwable) {
                AppLog.e("截图编码失败：${t.javaClass.simpleName}: ${t.message}")
                null
            }
            val omni = when {
                // ② 同一屏 30 秒内已解析过：直接复用，不发 HTTP
                omniReuse != null -> omniReuse.result
                // ③ 真正发请求
                omniOn -> {
                    if (plainJpeg == null) {
                        AppLog.w("解析服务不可用（截图编码失败），按无它继续")
                        null
                    } else {
                        AppLog.i("识别源③：请求解析服务 ${prefs.omniParserUrl}（超时 ${prefs.omniParserTimeoutMs}ms，上限 ${prefs.somMaxElements} 个元素）")
                        val r = OmniParserClient.parseBlocking(
                            prefs.omniParserUrl, plainJpeg, prefs.somMaxElements, prefs.omniParserTimeoutMs
                        )
                        if (!r.ok) {
                            // 失败（连不上 / 超时 / 非 2xx / JSON 不合契约）：退避一段时间，别每轮白等超时；**不进缓存**
                            omniCooldownUntilMs = SystemClock.elapsedRealtime() + OMNI_FAILURE_COOLDOWN_MS
                            AppLog.w("解析服务不可用（${r.error}），按无它继续；${OMNI_FAILURE_COOLDOWN_MS / 1000}s 内不再尝试")
                        } else {
                            omniCooldownUntilMs = 0L
                            // 只缓存成功（**含成功返回 0 元素**：0 元素同样是一次昂贵的 CPU 推理，值得复用）
                            omniCache = OmniCache(omniKey, r, SystemClock.elapsedRealtime())
                            AppLog.i("解析服务返回 ${r.elements.size} 个元素（mode=${r.mode.ifBlank { "?" }}，服务端 ${r.serverElapsedMs}ms，往返 ${r.latencyMs}ms）")
                        }
                        r
                    }
                }
                else -> null
            }
            handler.post { afterShotEncoded(prefs, q, res, fromOcr, bmp, plainJpeg, omni) }
        }
    }

    /**
     * 「截图已编码、解析服务已问过」之后的全部判定。
     *
     * **必须跑在主线程**：它读写 `recent` / `retriedKeys` / `lastClickAt` / `bmp` 生命周期，
     * 与 `onLlmResult`（也在主线程）是同一线程，才不会被并发访问（v1.3 的 onOcr 也是主线程）。
     */
    private fun afterShotEncoded(
        prefs: Prefs,
        q0: Question,
        res0: OcrResult,
        fromOcr: Boolean,
        bmp: Bitmap,
        plainJpeg: String?,
        omni: OmniParserClient.ParseResult?
    ) {
        var q = q0
        var res = res0
        var resFromOmni = false

        // ---- 识别源③：本地「不像题目」或「没有选项」时，用解析服务的**文字元素**重建 OcrResult 再判一次 ----
        val needRebuild = !q0.isCandidate || q0.options.isEmpty()
        if (omni != null && omni.ok) {
            if (!needRebuild) {
                AppLog.i("识别源③：本地已判出题目，解析服务只用来补充 SoM 元素（不用它重建判题）")
            } else if (omni.elements.isEmpty()) {
                AppLog.i("识别源③：解析服务返回 0 个元素")
            } else {
                val rebuilt = rebuildFromOmniText(omni, bmp.width, bmp.height)
                if (rebuilt == null) {
                    AppLog.i("识别源③：解析服务没有可用的文字元素")
                } else {
                    val q3 = QuestionDetector.detect(rebuilt, prefs.minScore)
                    AppLog.i("识别源③：解析服务文字 ${rebuilt.lines.size} 行 → score=${q3.score}，选项 ${q3.options.size} 个")
                    if (q3.isCandidate && q3.options.isNotEmpty()) {
                        q = q3
                        res = rebuilt
                        resFromOmni = true
                        AppLog.i("   采用解析服务重建的结果（本地原本 score=${q0.score}，选项 ${q0.options.size} 个）")
                    }
                }
            }
        }

        if (!q.isCandidate) {
            AppLog.i("④ 解析服务兜底后仍判为非题目，结束")
            bmp.recycle()
            busy.set(false)
            // 契约 §6.1 情形 1：整屏不像题目 → 本页没有仍需作答的题目
            scheduleFinishOrAdvance(prefs, res, "本地判定为非题目")
            return
        }

        val now = System.currentTimeMillis()
        val key = questionKey(q)
        if (isRecentlyAnswered(key, now, prefs.dedupSeconds * 1000L)) {
            // 去重只说明"记录里处理过"，**不代表真的答上了**：上一次可能只点中了选项、提交却失败
            //   （点完选项后浮层会从无障碍树里消失，实测踩到过）。
            // 所以先看浮层里的按钮：还亮着「提交作答」＝没答成 → **重新走一遍完整答题**；
            // 显示「已提交 / 回答正确」＝真答过 → 只做收尾，省掉那次接口调用。
            when (submitState()) {
                SubmitState.PENDING -> {
                    val n = retriedKeys[key] ?: 0
                    if (retriedKeys.size > 50) retriedKeys.clear()
                    retriedKeys[key] = n + 1
                    when (n) {
                        0 -> {
                            // 第一次：上次的**点击多半已经生效、只是提交没做**，所以**只补提交、绝不重复点选项**。
                            // 关键原因：WebView 的选项是 toggle，重复点会把已选中的**取消**掉
                            //（实测：60 秒延迟下程序把已勾选的 3 个选项又点了一遍，平台随即"取消作答"）。
                            AppLog.i("④ 判为答过但按钮仍亮 → 只补一次提交，**不重复点选项**（避免 toggle 取消）")
                            handler.postDelayed({
                                submitIfPresent()
                                handler.postDelayed({ closeAnswerOverlay() }, ANSWER_SETTLE_MS)
                            }, SUBMIT_DELAY_MS)
                            bmp.recycle()
                            busy.set(false)
                            AppLog.i("⑨ 推进：当前屏仍有未作答题目，不推进")
                            return
                        }
                        1 -> AppLog.i("④ 补提交后按钮仍亮 → 说明上次点击确实没生效，重新完整作答")
                        else -> {
                            AppLog.i("④ 已重答过、按钮仍亮 → 只补收尾，不再重复调用接口（防死循环）")
                            handler.postDelayed({
                                submitIfPresent()
                                handler.postDelayed({ closeAnswerOverlay() }, ANSWER_SETTLE_MS)
                            }, SUBMIT_DELAY_MS)
                            bmp.recycle()
                            busy.set(false)
                            AppLog.i("⑨ 推进：当前屏仍有未作答题目，不推进")
                            return
                        }
                    }
                }
                SubmitState.DONE -> {
                    AppLog.i("④ 这道题确实已答成：只跳过 API 调用，收尾动作（提交/关闭）照做")
                    handler.postDelayed({
                        submitIfPresent()
                        handler.postDelayed({ closeAnswerOverlay() }, ANSWER_SETTLE_MS)
                    }, SUBMIT_DELAY_MS)
                    bmp.recycle()
                    busy.set(false)
                    // 契约 §6.1 情形 2：刚答过的重复且已答成 → 本页这题不再需要作答
                    afterAnsweredQuestion(prefs, res, "判为答过且已答成（SubmitState.DONE）")
                    return
                }
                SubmitState.UNKNOWN -> {
                    // 读不到答题状态（浮层多半正在重绘）：不猜，只补收尾，避免白花一次接口调用
                    AppLog.i("④ 判为答过、且读不到答题状态：只补收尾，不重复调用接口")
                    handler.postDelayed({
                        submitIfPresent()
                        handler.postDelayed({ closeAnswerOverlay() }, ANSWER_SETTLE_MS)
                    }, SUBMIT_DELAY_MS)
                    bmp.recycle()
                    busy.set(false)
                    // 契约 §6.1 情形 2：补提交后仍读不到状态，同样按「不需要重复作答」处理
                    afterAnsweredQuestion(prefs, res, "判为答过、状态未知（SubmitState.UNKNOWN）")
                    return
                }
            }
        }

        // ---- v1.4：识别到卷面标题后，先点开题卡读一遍题号，取最大值当总题数 ----
        // 用户口径：「识别到标题（第十章…）后，先点题卡翻页找到最后一题的题号，取最大数字；
        // 只要当前题号小于那个数字就继续作答」。这样进度判定完全不依赖"App 自己点过几题"（M），
        // 也不依赖"点得动下一题"——正好绕开推进被误判锁死的那个死结。
        //
        // **不能在这里直接调 startCardCheck**：它要求 `busy == false`，而 analyze() 此刻正持有 busy，
        // 真机会变成"退出→重试→再退出"的空转（每轮还撞 700ms 截图频率限制）。
        // 正解：记下需求，等 analyze() 正常结束、busy 释放后再发起扫题卡。
        needTotalScan = paperTotalN <= 0 && currentPaperTitle.isNotEmpty()

        // ---- v1.4：题目超出页面 → 先在页内滚动把内容读全，再决定答案 ----
        // 真机场景：题干或选项很长时，WebView 里的题面会超出屏幕，端侧只读到可见部分，
        // 拿残缺题干去问模型必然答错（而且看不到某些选项）。
        // 这里最多滚 READ_MAX_SCROLLS_PER_QUESTION 次，每次滚动后**立刻重开一轮分析**，
        // 用新一屏（更完整的题干/选项）再判一次；滚动本身让屏幕指纹变化，
        // 不会触发"推不动=到页尾"的误判（那套只看滚动后有没有位移）。
        if (!longQuestionScrollDone(prefs, q, res)) {
            bmp.recycle()
            busy.set(false)
            return
        }

        // ---- SoM 元素（契约 2.1）：node > ocr > omniparser，合并后重新编号 E1..EN ----
        val somElements = if (prefs.somEnabled) {
            val list = assembleSomElements(prefs, res, fromOcr && !resFromOmni, omni, bmp.width, bmp.height)
            val byNode = list.count { it.source == SomElement.SOURCE_NODE }
            val byOcr = list.count { it.source == SomElement.SOURCE_OCR }
            val byOmni = list.count { it.source == SomElement.SOURCE_OMNI }
            AppLog.i("SoM：合并 ${list.size} 个元素（节点 $byNode / OCR $byOcr / 解析服务 $byOmni），上限 ${prefs.somMaxElements}")
            list
        } else {
            emptyList()
        }

        // 只有确定要调接口了才编码图片。
        // 发**标注图**时：把「画框 + JPEG 压缩」放回后台线程（主线程不做压缩），完了再回主线程发请求。
        if (somElements.isNotEmpty() && prefs.sendScreenshot) {
            val src = bmp
            val elements = somElements
            imageEncoder.execute {
                var annotatedSize: Pair<Int, Int>? = null
                val annotatedJpeg = try {
                    val annotated = SomAnnotator.annotate(src, elements, prefs.imageMaxSide)
                    if (annotated == null) {
                        null
                    } else {
                        annotatedSize = annotated.width to annotated.height
                        try {
                            SomAnnotator.encodeJpegBase64(annotated, 0, prefs.imageQuality)
                        } finally {
                            annotated.recycle()
                        }
                    }
                } catch (t: Throwable) {
                    AppLog.e("SoM 标注失败：${t.javaClass.simpleName}: ${t.message}")
                    null
                } finally {
                    src.recycle()
                }
                // P2：只有**标注图确实编码成功**时才算"编号图已发出"（编码失败会退回原图 → 不发清单、不认编号）
                val size = if (annotatedJpeg != null) annotatedSize else null
                val jpeg = annotatedJpeg
                handler.post { dispatchLlm(prefs, q, res, key, elements, jpeg ?: plainJpeg, size) }
            }
        } else {
            bmp.recycle()
            // 契约 v1.1 §3：没开视觉模式时，这张 JPEG（可能只是为了问解析服务才编的）**绝不能**传给大模型；
            // 解析服务要用的那份已经在后台线程里 POST 出去了，这里只决定"给大模型发什么"。
            dispatchLlm(prefs, q, res, key, somElements, if (prefs.sendScreenshot) plainJpeg else null, null)
        }
    }

    /**
     * 组装 SoM 候选元素（契约 2.1），**顺序即优先级**：
     *   ① `source="node"`：无障碍节点树里的有界文本节点（文本最准、坐标最准）；
     *   ② `source="ocr"`：端侧 OCR 行（按 `scaleX/scaleY` 换算回屏幕坐标）；
     *   ③ `source="omniparser"`：解析服务元素（图标语义是它的独有价值）。
     *
     * 节点树这里**重新读一次**（不是复用 [res]）：此刻的坐标才是要发给模型、也用来点击的那一份。
     *
     * @param resFromOcr [res] 是否来自截图 OCR —— 只有是时才把它的行算作 `source="ocr"`，
     *                   避免把节点树的坐标又当成 OCR 坐标抄一遍。
     */
    private fun assembleSomElements(
        prefs: Prefs,
        res: OcrResult,
        resFromOcr: Boolean,
        omni: OmniParserClient.ParseResult?,
        screenWidth: Int,
        screenHeight: Int
    ): List<SomElement> {
        val candidates = ArrayList<SomElement>()

        val nodeRes = runCatching {
            NodeReader.toOcrResult(allWindowRoots(), screenWidth, screenHeight, minLines = 1)
        }.getOrNull()
        if (nodeRes != null) {
            for (line in nodeRes.lines) {
                val box = line.box ?: continue
                SomElement.candidate(line.text, box, SomElement.KIND_TEXT, SomElement.SOURCE_NODE)
                    ?.let { candidates.add(it) }
            }
        }

        if (resFromOcr) {
            for (line in res.lines) {
                val box = line.box ?: continue
                SomElement.candidate(line.text, scaleToScreen(res, box), SomElement.KIND_TEXT, SomElement.SOURCE_OCR)
                    ?.let { candidates.add(it) }
            }
        }

        if (omni != null && omni.ok) {
            for (e in omni.elements) {
                val box = e.screenBox(screenWidth, screenHeight, omni.imageWidth, omni.imageHeight) ?: continue
                val label = e.label.ifBlank { e.text }
                val kind = if (e.type == SomElement.KIND_ICON) SomElement.KIND_ICON else SomElement.KIND_TEXT
                SomElement.candidate(label, box, kind, SomElement.SOURCE_OMNI)?.let { candidates.add(it) }
            }
        }

        return SomElement.merge(candidates, screenWidth, screenHeight, prefs.somMaxElements)
    }

    /**
     * 用解析服务返回的**文字元素**重建一份 [OcrResult]，交给 [QuestionDetector] 再判一次（识别源③）。
     *
     * 坐标：`bbox_ratio` 是相对**提交图片**的 0..1 归一化值；图片是整屏等比缩放的，
     * 等比缩放不改变归一化坐标，所以直接乘屏幕尺寸即可。
     */
    private fun rebuildFromOmniText(
        omni: OmniParserClient.ParseResult,
        screenWidth: Int,
        screenHeight: Int
    ): OcrResult? {
        val lines = ArrayList<OcrLine>()
        for (e in omni.elements) {
            if (e.type != SomElement.KIND_TEXT) continue
            val text = e.text.ifBlank { e.label }.trim()
            if (text.isEmpty()) continue
            val box = e.screenBox(screenWidth, screenHeight, omni.imageWidth, omni.imageHeight) ?: continue
            lines.add(OcrLine(text, box))
        }
        if (lines.isEmpty()) return null
        return OcrResult(
            fullText = lines.joinToString("\n") { it.text },
            lines = lines,
            imageWidth = screenWidth,
            imageHeight = screenHeight,
            sourceWidth = screenWidth,
            sourceHeight = screenHeight
        )
    }

    /**
     * 发大模型请求（⑤ 步）。标注图 / 原图 / 纯文本三种形态共用这一处，日志按**实际发出去的东西**如实打。
     *
     * **契约 v1.1 §3（硬规矩）**：`sendScreenshot=false` 时**任何路径都不得把截图发给大模型** ——
     * 解析服务要用的那张 JPEG 只用于 `POST /parse`。这里在唯一出口再兜一道，杜绝以后有人加调用点时漏掉。
     *
     * **P2 决策（Lead）**：编号是「图里的框」的编号 —— 只有本次**真的把标注图放进 user 消息**时，
     * 才附【界面元素】清单、也才让 `elementId` 参与点击决策；否则一律按 v1.3 走
     * （不发清单、不解析编号、不存在"按编号点击"这条路径）。
     */
    private fun dispatchLlm(
        prefs: Prefs,
        q: Question,
        res: OcrResult,
        key: String,
        somElements: List<SomElement>,
        jpeg: String?,
        annotatedSize: Pair<Int, Int>?
    ) {
        // 有新题要问 → 说明页面还在前进：清掉「推不动」标记，题卡校验留到真正到卷尾时再做
        stuckAtEndMs = 0L
        val jpegToSend = if (prefs.sendScreenshot) jpeg else null
        val annotated = if (jpegToSend != null) annotatedSize else null
        val imageSent = jpegToSend != null
        val listSent = annotated != null && imageSent
        val elementsToModel = if (listSent) somElements else emptyList()

        when {
            annotated != null && imageSent ->
                AppLog.i("SoM：已把编号图发给模型（图片 ${annotated.first}x${annotated.second}，${elementsToModel.size} 个编号）")
            !prefs.somEnabled ->
                AppLog.i(if (prefs.sendScreenshot) "SoM 关闭，发原图" else "SoM 关闭，只发纯文本")
            !imageSent ->
                AppLog.i("SoM：本次没发图 → 不发元素清单、不认元素编号（与 v1.3 一致）")
            somElements.isEmpty() ->
                AppLog.i("SoM：本次没有可编号的元素，按原图发送 → 不发元素清单")
            else ->
                AppLog.w("SoM：标注图生成失败，发原图 → 不发元素清单、不认元素编号")
        }

        val visionNote = if (jpegToSend == null) {
            if (prefs.sendScreenshot) "视觉模式=开但编码失败，改发纯文本" else "纯文本模式"
        } else {
            "视觉模式=开，图片 base64 ≈ ${jpegToSend.length / 1024} KB"
        }
        AppLog.i("⑤ 调用大模型：${prefs.model} @ ${prefs.baseUrl}（$visionNote，effort=${prefs.reasoningEffort.ifEmpty { "服务端默认" }}）")

        // 又见到一道要作答的题目 → 推进连击清零、节点滚动无效标记清零（契约 §6.5「新题出现时也清零」）
        advanceStreak = 0
        nodeScrollIneffective = false

        llmInFlight = true
        LlmClient.ask(prefs, q, res.fullText, jpegToSend, elementsToModel) { r ->
            onLlmResult(prefs, q, res, key, elementsToModel, r)
        }
    }

    private fun onLlmResult(
        prefs: Prefs,
        q: Question,
        res: OcrResult,
        key: String,
        somElements: List<SomElement>,
        r: LlmClient.Result
    ) {
        llmInFlight = false
        // v1.4：服务已销毁（解绑）时不要继续作答：此时 `rootInActiveWindow` 拿不到、坐标点击也会失败，
        // 真机踩到的是"三次点击全部失败、却把这题记为已答"。直接放弃本轮，等新实例重连后再来。
        if (instance !== this) {
            AppLog.w("⑤ 大模型返回时无障碍服务已不在（解绑/销毁），放弃本轮作答")
            return
        }
        // v1.4：本轮是否**真的点中过选项**。原来 finally 里只按 `r.ok`（模型成功）就把这题写成"已处理"，
        // 导致"模型答对、但一次都没点中"的题被判作答完、不再重试（真机踩到：服务中途解绑 → 三次点击全失败）。
        var clicked = false
        try {
            if (!r.ok || r.answer == null) {
                AppLog.e("⑤ 大模型调用失败（${r.latencyMs}ms）：${r.error}")
                return
            }
            val a = r.answer
            AppLog.i("⑤ 大模型返回（${r.latencyMs}ms, HTTP ${r.httpCode}）：is_question=${a.isQuestion} label=\"${a.answerLabel}\" text=\"${a.answerText.take(40)}\" confidence=${a.confidence} elementId=${a.elementId ?: "-"}")
            AppLog.i("   原始回复：${a.rawContent.replace('\n', ' ').take(160)}")
            if (a.explanation.isNotBlank()) AppLog.i("   说明：${a.explanation}")

            if (!a.isQuestion) {
                AppLog.i("⑥ 模型判定这不是题目，不点击")
                return
            }

            AppLog.i("⑥ 答案：${describeAnswer(a)}")

            if (!prefs.autoClick) {
                AppLog.i("   自动点击已关闭，只显示答案")
                return
            }
            if (a.confidence < prefs.minConfidence) {
                AppLog.w("   置信度 ${a.confidence} 低于阈值 ${prefs.minConfidence}，放弃点击")
                return
            }
            // SoM 关闭时不认编号（契约 3：不解析编号，行为回到 v1.3）
            val somHit = if (prefs.somEnabled) {
                a.elementId?.let { id -> somElements.firstOrNull { it.id == id } }
            } else {
                null
            }

            if (q.options.isEmpty() && somHit == null) {
                AppLog.i("   本题没有识别到可点的选项，只给出答案")
                return
            }

            val root = rootInActiveWindow
            if (root == null) AppLog.w("   拿不到当前窗口节点树，将只能用坐标点击")

            // 点击优先级（契约 2.4）：① 模型给的编号（在本轮 SoM 清单里）→ ② 现有 ClickPlanner → ③ 都不行就不点
            val screenW = res.sourceWidth
            val screenH = res.sourceHeight
            var plans: List<ClickPlan> = emptyList()
            // 编号目标不在屏幕内时**不点它**（契约 §6.2）：此时也不退回 ClickPlanner，
            // 否则「屏幕外的编号点不到」会被兜底掩盖成「点了别的目标」，直接转为滚动把它带进来。
            var somTargetOffScreen: Rect? = null
            if (somHit != null) {
                when {
                    conflictsWithAnswerLetter(somHit, a) ->
                        AppLog.w("   元素编号与选项字母矛盾：模型答「${a.answerLabel}」但 ${somHit.tag} 的标签是「${somHit.label.take(30)}」→ 不用它，退回 ClickPlanner")
                    !isVisibleOnScreen(somHit.box, screenW, screenH) -> {
                        somTargetOffScreen = somHit.box
                        AppLog.i("   编号目标 ${somHit.tag} 不在屏幕内，不点它（转为滚动带进来）")
                    }
                    else -> {
                        AppLog.i("⑥ 模型给出元素编号 ${somHit.tag} → 用该框点击 (${somHit.box.centerX()},${somHit.box.centerY()})")
                        plans = listOf(
                            ClickPlan(
                                null,
                                somHit.box.centerX(),
                                somHit.box.centerY(),
                                "SoM 编号点击（${somHit.tag} ${somHit.label.take(20)}）"
                            )
                        )
                    }
                }
            } else if (prefs.somEnabled && a.elementId != null) {
                // 能走到这里说明本次确实发了编号清单（P2：没发图时 parseAnswer 不解析编号，elementId 恒为 null）
                AppLog.w("   模型给了元素编号 E${a.elementId}，但本次编号清单里没有它 → 退回 ClickPlanner")
            }

            if (plans.isEmpty() && somTargetOffScreen == null) {
                plans = ClickPlanner.plans(root, a, q.options) { box -> scaleToScreen(res, box) }
            }

            // 可见性（契约 §6.2）：长页面里折叠线以下的目标 bounds 在屏幕外，点它的中心会落到别处。
            // 屏幕外的目标一律不点，改为滚动把它带进来（这条同时修掉长页面里点到屏幕外的既有隐患）。
            val offScreenPlans = plans.filter { plan ->
                val box = planTargetBox(plan)
                box != null && !isVisibleOnScreen(box, screenW, screenH)
            }
            if (somTargetOffScreen != null || (offScreenPlans.isNotEmpty() && offScreenPlans.size == plans.size)) {
                AppLog.i("⑨ 推进：目标在屏幕外，先滚动把它带进来")
                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs - lastAdvanceAt < ADVANCE_COOLDOWN_MS) {
                    AppLog.i("⑨ 推进：滚动冷却中（距上次 ${"%.1f".format((nowMs - lastAdvanceAt) / 1000.0)}s < ${"%.1f".format(ADVANCE_COOLDOWN_MS / 1000.0)}s），本轮不滚动")
                } else if (prefs.dryRun) {
                    AppLog.i("⑨ 试运行：本应推进（滚动把目标带进来），已跳过")
                } else {
                    lastAdvanceAt = nowMs
                    val cy = (somTargetOffScreen ?: offScreenPlans.first().let { planTargetBox(it) })?.centerY() ?: 0
                    val kind = scrollTargetIntoView(cy, screenW, screenH)
                    // D-c：这条滚动也必须进同一套无位移检测 —— 否则 swipe 返回 true 但页面没动时，
                    // 会每 4s 空滚一次、永不暂停（真机 /quiz-long 就是这个现象）。
                    // 注意：修复性滚动**不计入推进连击**（它不是"推进"），但同样能触发"节点滚动无效/页尾暂停"。
                    if (kind != ScrollKind.NONE) {
                        pendingScrollSignature = res.signature
                        lastScrollWasNode = (kind == ScrollKind.NODE)
                    }
                }
                return
            }
            if (offScreenPlans.isNotEmpty()) {
                // 多选：只丢掉屏幕外的那些，屏幕内的照点
                plans = plans.filter { it !in offScreenPlans }
            }

            if (plans.isEmpty()) {
                AppLog.w("   未能在屏幕上定位到「${describeAnswer(a)}」对应的选项，宁可不点")
                return
            }

            if (prefs.dryRun) {
                // 试运行也计入「本应作答」，否则题卡校验里 M 恒为 0、`本应提交` 永远不出现
                answeredForFinish++
                AppLog.i("⑥ 试运行模式：本应执行 ${plans.joinToString("；") { it.how }}，已跳过")
                return
            }

            val sinceClick = System.currentTimeMillis() - lastClickAt
            if (sinceClick < clickCooldownMs) {
                AppLog.i("   距上次点击仅 ${sinceClick}ms（< ${clickCooldownMs}ms 冷却），本次不点，避免同一题被重复点击")
                return
            }

            var ok = false
            for ((i, plan) in plans.withIndex()) {
                if (i > 0) Thread.sleep(600)          // 多选：逐项点，留出 UI 反应时间
                val one = performPlan(plan)
                ok = ok || one
                lastClickAt = System.currentTimeMillis()
                AppLog.i("⑦ 点击结果：${if (one) "已发送" else "失败"} —— ${plan.how}")
            }
            clicked = ok                              // v1.4：供 finally 判断"是否真的点中过"
            if (ok) {
                // 契约 §6.5：成功点过选项才允许后续自动推进（本次会话计数，前台包名变化时清零）；
                // 同时记下作答时刻 —— 推进窗口以它为基准，且**成功推进不会刷新它**
                answeredThisSession++
                answeredForFinish++
                lastAnsweredAtMs = SystemClock.elapsedRealtime()
                // 答完收尾分两步：
                //   ① 有些题型选完必须点「提交作答」才判定（实测智慧树「AI 随堂练习」的单选题）；
                //   ② 等判定结果渲染出来再点「关闭」，免得浮层一直挡着视频、之后每轮轮询都重复看到这道题。
                handler.postDelayed({
                    submitIfPresent()
                    handler.postDelayed({ closeAnswerOverlay() }, ANSWER_SETTLE_MS)
                }, SUBMIT_DELAY_MS)
            }
        } finally {
            // v1.4 修复：原来传的是 `r.ok`（**大模型是否返回成功**），于是"模型答对了、但选项一个都没点中"
            // 也会被写进"已处理"记录 —— 真机踩到：服务在答题中途解绑，三次点击全部 `失败`，
            // 却仍然 `MARK# ok=true`，从此这题被判"答过"、不再重试，整卷卡在最后一题上死循环。
            // 正确语义：只有**模型成功且至少点中一次**才算处理过；否则用短退避（失败窗）让它能重试。
            val answered = r.ok && clicked
            markProcessed(prefs, key, answered)
            if (!answered && r.ok) {
                AppLog.w("   本题未点中任何选项（模型成功）→ 不记为已答，${failureRetryDelayMs / 1000}s 后允许重试")
            }
            busy.set(false)
        }
    }

    /**
     * 同一道题最多「重新作答」几次。
     *
     * 为什么必须有：实测某多选界面**提交后按钮文字不变**（一直是「提交作答」），
     * 于是 `submitState()` 永远返回 PENDING → 每轮都重新作答 → **无限循环、每轮真实调用接口**。
     * 宁可不重答（少答一道题），也绝不允许这种方式烧接口。
     */
    /**
     * 是否用 root 执行 `input tap` —— **默认关闭，且经复核并不需要**。
     *
     * 复核结论（用户现场确认）：真正让选项被勾选的是 **「覆盖该点的最深叶子节点的 ACTION_CLICK」**
     * （`NodeReader.findDeepestAt` 那条路），**不是 root**。
     * 此前我从"提交作答按钮仍亮"推断"叶子无效"是**误读**：那个"仍亮"是当时把 `SUBMIT_DELAY_MS`
     * 临时调成 60 秒导致的（提交还没执行）；后续日志出现 `④ 这道题确实已答成` 正是提交成功的证据。
     * root 方式保留为**可选兜底**（真机 / 无无障碍权限等场景），默认关闭。
     */
    private val USE_ROOT_TAP = false

    private val MAX_RETRY_ANSWER = 1
    private val retriedKeys = HashMap<String, Int>()

    /** 浮层里的答题状态 */
    private enum class SubmitState { PENDING, DONE, UNKNOWN }

    /**
     * 读浮层里的按钮，判断这道题**是否真的答成了**。
     *
     * 为什么需要：去重记录只能说明"处理过"，不能说明"提交成功" —— 点完选项后浮层会从无障碍树里消失、
     * 提交常常失败，而记录此时已经写下，光看去重就会把**没答成的题**当成答过
     * （实测：两道题被跳过，学习记录里仍是未作答）。
     *
     * 判据（都用节点文本精确匹配，实测这些文案就是整节点文本）：
     *   还亮着「提交作答」→ [SubmitState.PENDING]；显示「已提交 / 回答正确 / 回答错误」→ [SubmitState.DONE]；
     *   两者都读不到（浮层正在重绘）→ [SubmitState.UNKNOWN]。
     */
    private fun submitState(): SubmitState {
        val roots = allWindowRoots()
        if (roots.isEmpty()) return SubmitState.UNKNOWN
        if (NodeReader.findTextNodes(roots, listOf("提交作答", "提交答案", "确认作答", "提交")).isNotEmpty()) {
            return SubmitState.PENDING
        }
        if (NodeReader.findTextNodes(roots, listOf("已提交", "回答正确", "回答错误")).isNotEmpty()) {
            return SubmitState.DONE
        }
        return SubmitState.UNKNOWN
    }

    /**
     * 点完选项后，屏幕上若有「提交作答」这类按钮就点它。
     *
     * 实测：智慧树的「AI 随堂练习」（单选题 + 大按钮）**选完必须提交才判定**；
     * 弹题浮层那种则是点选即判定、没有提交按钮 —— 所以这里「有就点、没有就跳过」。
     */
    private fun submitIfPresent() {
        // 题卡面板开着时不点（会把点击打到面板上）
        if (cardPanelBusy) {
            AppLog.i("⑧ 收尾：题卡校验进行中，本轮不点提交")
            return
        }
        val words = listOf("提交作答", "提交答案", "确认作答", "提交")
        for ((node, box) in NodeReader.findTextNodes(allWindowRoots(), words)) {
            val label = node.text?.toString()?.trim().orEmpty()
            if (box.width() > 0 && clickAtPoint(box.centerX(), box.centerY(), "提交")) {
                AppLog.i("⑧ 收尾：已点「$label」提交（坐标 ${box.centerX()},${box.centerY()}）")
                return
            }
        }
        AppLog.i("⑧ 收尾：本次没有提交按钮（点选即判定的题型），跳过")
    }

    /** 读题时顺带找出「关闭」类按钮，记下它的位置供收尾使用（跨所有窗口找）。 */
    private fun cacheCloseTarget(roots: List<AccessibilityNodeInfo?>) {
        val words = listOf("关闭", "继续观看", "继续播放", "继续", "我知道了", "知道了", "确定")
        for ((node, box) in NodeReader.findTextNodes(roots, words)) {
            if (box.width() > 0 && box.height() > 0) {
                val label = node.text?.toString()?.trim().orEmpty()
                cachedClose = label to Rect(box)
                cachedCloseAt = SystemClock.elapsedRealtime()
                AppLog.i("   已缓存「$label」位置 (${box.centerX()},${box.centerY()})，供收尾使用")
                return
            }
        }
        // 这里**绝不能清空缓存**：点完选项后浮层会从无障碍树里消失，之后每轮读题都会走到这里；
        // 一旦清空，收尾就没坐标可用（实测踩过：日志里"已缓存"与"第 1 次没找到关闭"同时出现）。
        // 旧坐标由 CLOSE_CACHE_TTL_MS 兜底过期。
    }

    /**
     * 答完后的收尾：把结果浮层关掉。
     *
     * ① 先用**读题时缓存的按钮位置** —— 点完选项后浮层常从无障碍树里消失，实时查找会失败；
     * ② 再实时查找（「找哪个」用子节点遍历，坑点 #37；「怎么点」一律走坐标，坑点 #38），
     *    并最多重试 [CLOSE_MAX_ATTEMPTS] 次兜住 WebView 重绘间隙（坑点 #41）。
     */
    /**
     * 「关闭」的点击冷却。
     *
     * 为什么需要：多条收尾路径（正常答题后的收尾、去重命中后的补收尾）会各排一个收尾任务，
     * 于是「关闭」被连点两次 —— **第二次浮层已经没了，就点在视频页上**，
     * 误触返回、一路退到桌面（实测踩到过）。冷却期内的收尾直接跳过。
     */
    private val CLOSE_COOLDOWN_MS = 3000L
    private var lastCloseAt = 0L

    private fun closeAnswerOverlay(attempt: Int = 1) {
        // 题卡面板开着时不点（会把「关闭」打到面板上）
        if (cardPanelBusy) return
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastCloseAt < CLOSE_COOLDOWN_MS) {
            AppLog.i("   刚点过「关闭」（${nowMs - lastCloseAt}ms 前），本次跳过，避免重复点击误触")
            return
        }
        lastCloseAt = nowMs
        val cached = cachedClose
        if (attempt == 1 && cached != null &&
            SystemClock.elapsedRealtime() - cachedCloseAt < CLOSE_CACHE_TTL_MS
        ) {
            val (label, box) = cached
            if (box.width() > 0 && clickAtPoint(box.centerX(), box.centerY(), "关闭")) {
                AppLog.i("⑧ 收尾：已点「$label」（坐标 ${box.centerX()},${box.centerY()}，用读题时缓存的按钮位置）")
                // 不清缓存：这一下未必生效（WebView 未必认这个手势），下一轮补善后还要用；由 TTL 兜底过期
                return
            }
        }
        val words = listOf("关闭", "继续观看", "继续播放", "继续", "我知道了", "知道了", "确定")
        for ((node, box) in NodeReader.findTextNodes(allWindowRoots(), words)) {
            val label = node.text?.toString()?.trim().orEmpty()
            if (box.width() > 0 && clickAtPoint(box.centerX(), box.centerY(), "关闭")) {
                AppLog.i("⑧ 收尾：已点「$label」（坐标 ${box.centerX()},${box.centerY()}）" +
                    if (attempt > 1) "（第 $attempt 次尝试）" else "")
                return
            }
        }
        if (attempt < CLOSE_MAX_ATTEMPTS) {
            AppLog.i("⑧ 收尾：第 $attempt 次没找到「关闭」（可能是重绘间隙）→ ${CLOSE_RETRY_MS}ms 后重试")
            handler.postDelayed({ closeAnswerOverlay(attempt + 1) }, CLOSE_RETRY_MS)
        } else {
            AppLog.i("⑧ 收尾：重试 $attempt 次仍未找到「关闭」类按钮（浮层可能已自行消失）")
        }
    }

    /**
     * 去重键用「题目本身」，而不是整屏文字。
     *
     * 两个原因（都是实测踩出来的）：
     *   1) 点击后页面会多出「已答对」之类提示，整屏文字一变，同一道题就会被反复作答；
     *   2) 端侧 OCR 对同一画面并不逐字确定 —— 同一道题两次识别出「美于」/「关干」，
     *      选项行的先后顺序也会变。所以选项要排序，且比对时用模糊相似度（见 [isRecentlyAnswered]）。
     *
     * **v1.4 修复：不能一律「只用选项」**。真机实测（第六章单元测试，判断题）：
     * 判断题的选项恒为「错」「对」，于是键恒为 `A:错|B:对`（**只有 7 个字符**）——
     * 同一套卷里**所有判断题共用一个键**，答过一道之后其余判断题全被判成"已回答"，
     * App 从此一次接口都不调用、原地卡死（日志：`DEDUP# ... matched=true keyLen=7`）。
     * 判据：**最长选项文本短于 [MIN_OPTION_TEXT_FOR_KEY] 时，说明选项本身不具区分度，
     * 键必须带上题干**（这类题反而是题干长、区分度高）；选项够长时仍按老办法只用选项，
     * 保留"抗 OCR 抖动"的优点。
     */
    private fun questionKey(q: Question): String {
        val optsPart = q.options.map { it.label.uppercase() + ":" + it.text }
            .sorted()
            .joinToString("|")
            .replace(WHITESPACE, "")
        val stemPart = q.stem.replace(CLOCK, "").replace(WHITESPACE, "")
        val longestOption = q.options.maxOfOrNull { it.text.replace(WHITESPACE, "").length } ?: 0
        return when {
            // 选项够长：沿用 v1.3 口径（只用选项，实测同一题两次 OCR 相似度 0.965~0.983）
            optsPart.isNotEmpty() && longestOption >= MIN_OPTION_TEXT_FOR_KEY ->
                optsPart.take(400)
            // 选项太短（判断题、对错题）：必须带题干才有区分度
            optsPart.isNotEmpty() -> ("S:" + stemPart.take(200) + "|" + optsPart).take(400)
            // 没有选项的题（问答/填空），或选项文本不具区分度时的兜底：
            // 用「题干」并**先剥掉状态栏与页头噪声** —— 实测原来的 `stem.takeLast(200)` 把
            // `WLAN信号满格。手机信号满格。正在充电，已完成94%。第十章单元测试提交作业提交作业`
            // 也算进了键（状态栏每轮都在变，而"提交作业"在节点树里出现两次），
            // 这类键既不稳定也不该参与"同一题"的判定。
            else -> ("S:" + stripScreenChrome(stemPart)).take(KEY_MAX_LEN)
        }
    }

    /**
     * 剥掉题干里的屏幕级噪声（v1.4）：状态栏电量/信号文本、页头里的重复标题与「提交作业」。
     *
     * 只做**保守的前缀裁剪**：从题干开头起，跳过含电量/信号/时间等状态栏特征的片段，
     * 以及连续的页头关键字；剩下的部分当题干。
     */
    private fun stripScreenChrome(stem: String): String {
        var s = stem
        // 状态栏特征：电量百分比、"WLAN/信号/充电"、时间
        while (true) {
            val cut = listOf(
                "已完成", "电量", "WLAN", "wifi", "WiFi", "信号", "充电", "正在充电"
            ).mapNotNull { s.indexOf(it) }.minOrNull()
            if (cut == null || cut > 30) break
            // 把这一段（到下一个句号/换行）整体去掉
            val end = listOf(s.indexOf('。', cut), s.indexOf('\n', cut)).filter { it > cut }.minOrNull() ?: s.length
            s = s.removeRange(cut, end + 1)
        }
        // 页头重复：「提交作业」在一屏里会出现两次，属于按钮文本而非题干
        s = s.replace("提交作业", "")
        return s.trim()
    }

    /**
     * 精确命中，或与最近答过的题目相似度达标时，认为这题已经答过。
     *
     * v1.4 诊断补充：**精确命中这条路以前不打日志** —— 真机上出现「明明是新题却被判成答过、
     * 一次接口都不调用」时完全没法查。现在两种情况都会打出证据：
     *  - 精确命中：给出键前缀与记录时间（可判断是不是同一题被记录了两次）；
     *  - 近似命中：给出相似度与阈值。
     * 为避免每 2 秒刷屏，只在**判定结果发生变化**时打一次。
     */
    private fun isRecentlyAnswered(key: String, now: Long, windowMs: Long): Boolean {
        val it = recent.entries.iterator()
        var matched = false
        var bestSim = 0.0
        var bestThreshold = SIMILARITY_THRESHOLD
        var exactAge: Long? = null

        while (it.hasNext()) {
            val e = it.next()
            val age = now - e.value
            if (age > windowMs) {
                it.remove()
                continue
            }
            if (e.key == key) {
                matched = true
                exactAge = age
                // v1.4：精确命中时把**存下来的那个键**也打出来。真机上曾出现"一道新题被精确命中"，
                // 不比对两者的键内容就无法判断是"同一题被写了两次"还是"键被算成了同一个"。
                AppLog.i("DEDUP-MATCH# age=${age / 1000}s storedLen=${e.key.length} curLen=${key.length}" +
                    "\n   stored=${e.key.take(60)}\n   cur   =${key.take(60)}")
                continue
            }
            val sim = TextMatch.editSimilarity(e.key, key)
            val threshold =
                if (age <= RECENT_WINDOW_MS) SIMILARITY_THRESHOLD_RECENT else SIMILARITY_THRESHOLD
            if (sim > bestSim) {
                bestSim = sim
                bestThreshold = threshold
            }
            if (sim >= threshold) {
                AppLog.i("   去重：与 ${age / 1000}s 前答过的题相似度 ${"%.3f".format(sim)} ≥ $threshold，视为同一题")
                matched = true
            }
        }

        // 只在结果变化时打诊断，避免每轮刷屏
        dedupCallCount++
        // ASCII 标记（DEDUP#）+ 计数：排查"新题被判成答过"时，要先确认这个方法到底被调用了没有、
        // 命中的是"精确命中"还是"相似度命中"。中文行在控制台可能因编码看不出来，ASCII 标记不会。
        AppLog.i("DEDUP# call=$dedupCallCount matched=$matched bestSim=${"%.3f".format(bestSim)}" +
            " exactAge=${(exactAge ?: -1L) / 1000}s recentSize=${recent.size} keyLen=${key.length}")
        if (matched != lastDedupVerdict) {
            lastDedupVerdict = matched
            if (matched) {
                AppLog.i("   去重判定：判为答过（精确命中，记录于 ${(exactAge ?: 0L) / 1000}s 前）" +
                    "；本题键=${key.take(80)}")
                if (lastDedupKeyPrefix != null && lastDedupKeyPrefix != key.take(80)) {
                    AppLog.i("   去重诊断：上一次判为答过的键=$lastDedupKeyPrefix（与本次不同）")
                }
            } else if (bestSim >= 0.60) {
                AppLog.i("   去重判定：判为未答过；与最近答过的题最高相似度 ${"%.3f".format(bestSim)}，本轮阈值 $bestThreshold" +
                    "；本题键=${key.take(80)}")
            }
        }
        lastDedupKeyPrefix = key.take(80)
        return matched
    }

    /**
     * 记录这道题已处理。
     * 成功时按完整去重窗口压制；失败时只压制 [failureRetryDelayMs]，让偶发网络错误还能重试一次。
     */
    private fun markProcessed(prefs: Prefs, key: String, ok: Boolean) {
        val now = System.currentTimeMillis()
        val window = prefs.dedupSeconds * 1000L
        val offset = if (ok) 0L else (window - failureRetryDelayMs).coerceAtLeast(0L)
        recent[key] = now - offset
        // v1.4 去重写入埋点（ASCII 标记）：排查「新题被判成答过」时必须知道**这条记录是谁写的**。
        // 真机踩到：一道从未答过的判断题被精确命中判定为"已答过"，而 `⑤ 调用大模型` 计数为 0，
        // 说明有非预期的写入路径 —— 没有这行日志就永远查不出写入者。
        AppLog.i("MARK# ok=$ok keyLen=${key.length} recentSize=${recent.size} key=${key.take(60)}")
    }

    private fun performPlan(plan: ClickPlan): Boolean {
        val node = plan.node
        if (node != null) {
            val ok = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                .getOrDefault(false)
            if (ok) return true
            // 节点点击失败，退到坐标点击
            val b = Rect()
            runCatching { node.getBoundsInScreen(b) }
            if (b.width() > 0 && b.height() > 0) {
                AppLog.w("   节点 ACTION_CLICK 返回 false，改用坐标点击 ${b.centerX()},${b.centerY()}")
                return tapAt(b.centerX(), b.centerY())
            }
            return false
        }
        // 坐标计划：**先在节点树里找「能点的那个节点」用 ACTION_CLICK 点**（它直接作用于目标窗口，
        // 不受「dispatchGesture 在模拟器上返回成功却不生效」影响），确实找不到才退回坐标手势。
        return clickAtPoint(plan.x, plan.y, "选项")
    }

    /**
     * 在某点点击：**优先用无障碍节点点击**，找不到可点节点才退回坐标手势。
     *
     * 为什么需要：实测 MuMu 上 `dispatchGesture` 会「返回成功却不生效」——
     * 日志打 `⑦ 点击结果：已发送`，屏幕上选项/按钮毫无变化；
     * 而 `performAction(ACTION_CLICK)` 直接作用于目标窗口、不走输入子系统，可靠得多。
     */
    private fun clickAtPoint(x: Int, y: Int, label: String): Boolean {
        val roots = allWindowRoots()
        // ① 先试**最深叶子节点**：WebView 里 ACTION_CLICK 只有打在叶子上才可能被响应
        //    （打容器/祖先会「返回 true 却不生效」——选项就是这种情况）
        val deep = NodeReader.findDeepestAt(roots, x, y)
        if (deep != null &&
            runCatching { deep.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
        ) {
            AppLog.i("   改用无障碍节点点击（$label $x,$y 命中最深叶子）")
            return true
        }
        // ② 再试「覆盖该点、自身可点」的最小节点
        val hit = NodeReader.findClickableAt(roots, x, y)
        if (hit != null &&
            runCatching { hit.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
        ) {
            AppLog.i("   改用无障碍节点点击（$label $x,$y 命中可点节点）")
            return true
        }
        // ③ 再试 **root 执行 input tap**：MuMu 这类带 root 的模拟器上，
        //    WebView 选项既不吃 ACTION_CLICK（返回 true 却不生效）、坐标手势又在多 display 下失效，
        //    `input tap` 是实测唯一有效的注入方式（adb 侧同一条命令已验证生效）。
        if (USE_ROOT_TAP && rootTap(x, y)) {
            AppLog.i("   改用 root input tap（$label $x,$y）")
            return true
        }
        // ④ 最后才是坐标手势
        return tapAt(x, y)
    }

    // ------------------------------------------------------------------ 答题完毕收尾（契约 §7 / task-10）

    /**
     * 「本轮无事可做」时的分流：`finishMode != off` 先走**题卡校验**（§7.1），否则维持原有推进。
     *
     * 两者位置相同（都是 §6.1 判定成立后）：题卡校验本身要点开/关闭面板，所以它自己做了
     * 20s 限频；限频命中或没找到题卡等情况下，回落到原有的「答完自动推进」，保证页面还能往下走。
     *
     * @param forceFinishCheck v1.4：**卷尾提示命中**时置 `true` —— 平台自己说了「这已经是最后一道题了」，
     *   这就是比 `stuckAtEndMs`（要"推不动"才置位，慢且依赖无进展检测）更直接的**整卷结束信号**。
     *   注意仍然要过 `finishMode` 与限频两道闸：**提示说的是"到卷尾了"，不等于"每题都作答了"**，
     *   所以真正要不要提交，仍旧由后面的题卡校验 + M/N 判据决定。
     * @param cardTarget v1.4：识别到卷尾提示的**同一时刻**就解析好的「题卡」入口（文本 + 坐标）。
     *   为什么必须当场解析：实测卷尾提示只闪约 1 秒，而题卡校验被 `ADVANCE_DELAY_MS` 推迟 2.4s 才跑；
     *   等它跑起来时 WebView 的树已变回陈旧状态（只剩 21 行、没有「题卡」节点，见已知坑点 #19），
     *   于是必然报「没找到「题卡」入口」而放弃 —— 真机实测就卡在这一步。
     *   传进来后 [startCardCheck] 直接用这份坐标，不再重新遍历一次树。
     */
    private fun scheduleFinishOrAdvance(
        prefs: Prefs,
        res: OcrResult,
        reason: String,
        forceFinishCheck: Boolean = false,
        cardTarget: Pair<String, Rect>? = null
    ) {
        if (!prefs.processingEnabled) return
        if (prefs.finishMode == Prefs.FINISH_OFF) {
            scheduleAdvance(prefs, res, reason)
            return
        }
        if (finishStopped) return                       // 已停手：什么都别调度
        // §7.1 触发条件（v1.6 收紧）：**只在推不动（到卷尾）时才开题卡**。
        // 为什么：题卡校验要开关面板，而关面板会让 WebView 无障碍树变陈旧（题目/选项从树里消失），
        // 边答题边开面板会把后续答题全部弄瞎（真机实测连续 150 秒判「非题目」）。
        // v1.4 例外：**卷尾提示**是平台自己给的可信信号，等效于"到卷尾"，放行。
        if (stuckAtEndMs <= 0L && !forceFinishCheck) {
            scheduleAdvance(prefs, res, reason)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val sinceCheck = now - lastFinishCheckAt
        if (sinceCheck < FINISH_CHECK_INTERVAL_MS) {
            // v1.4：**强制请求（卷尾提示 / 已到最后一题）不走限频**。
            // 原先这里把强制请求也拦下来、再排队重试，真机结果是**空转**：重试回调自己又刷新了
            // `lastFinishCheckAt`，于是每次都"还有 19s 到期"，题卡校验一次都没真正跑起来 ——
            // 日志里只剩 `强制校验遇限频 … 后重试` 和 `已有一次强制校验在排队` 无限刷。
            // 代价可控：题卡校验自身有 `cardPanelBusy` 互斥 + 15s 看门狗，重复开面板的风险有限。
            if (!forceFinishCheck) {
                AppLog.i("⑩ 收尾：题卡检查限频（距上次 ${sinceCheck / 1000}s < ${FINISH_CHECK_INTERVAL_MS / 1000}s），本轮改为推进")
                scheduleAdvance(prefs, res, reason)
                return
            }
            AppLog.i("⑩ 收尾：强制校验（$reason）跳过限频（距上次仅 ${sinceCheck / 1000}s）")
        }
        lastFinishCheckAt = now
        AppLog.i("⑩ 收尾：本轮无需作答（$reason）→ 检查题卡判定整卷是否答完")
        if (forceFinishCheck) {
            // v1.4：**不能在 onOcr 内部直接调 startCardCheck** —— 那时 analyze 仍持有 busy，
            // 而 startCardCheck 要求 busy==false（真机踩到：强制校验每 2s 发起一次，
            // 每次都被 `上一次分析还没结束` 静默挡回，题卡校验一次都没真正跑起来）。
            // 与"扫题卡取总题数"同一手法：记下待办，等下一轮 analyze 开头 busy 已释放时执行。
            pendingFinishCheck = true
            AppLog.i("⑩ 收尾：强制校验已排队，等本轮 analyze 结束后执行")
            return
        }
        handler.postDelayed({
            startCardCheck(prefs, res, res.sourceWidth, res.sourceHeight, cardTarget)
        }, ADVANCE_DELAY_MS)
    }

    /**
     * 找平台的「卷尾提示」（v1.4 新增）。
     *
     * 实测文案（真机 dump，`bounds=[255,924][825,1068]`，是**页面里的 TextView 节点**，不是 Toast）：
     *   `text="这已经是最后一道题了"`
     * 因为它就是一个普通文本节点，端侧节点树里**直接读得到**，不需要截图 OCR、也不需要额外权限。
     *
     * 匹配规则（按关键字，不锁死整句）：
     *  - 同一节点文本里同时出现「最后」+「题」，或出现下面 [END_PAPER_KEYWORDS] 里的任意整句变体；
     *  - 只扫**短文本节点**（[END_PAPER_MAX_LEN] 以内）：问卷题干里也可能出现"最后"（例如
     *    「以下哪个是最后一步」），限制长度可以避开绝大多数误判；命中仍然只做「整卷校验」，
     *    要真提交还得过题卡 + M/N 两道闸，所以误判的代价是"多点开一次题卡"，不是"误提交"。
     */
    private fun findEndPaperPrompt(res: OcrResult): String? {
        for (line in res.lines) {
            val t = line.text.trim()
            if (t.isEmpty() || t.length > END_PAPER_MAX_LEN) continue
            val hit = END_PAPER_KEYWORDS.any { t.contains(it) } ||
                (t.contains("最后") && t.contains("题"))
            if (hit && t.length <= END_PAPER_PROMPT_MAX_LEN) return t
        }
        return null
    }

    /** §7.1 步骤 1–3：点开题卡 → 等 1.2s 数题号 → **再点一次题卡**关闭（严禁返回键）。 */
    private fun startCardCheck(
        prefs: Prefs,
        res: OcrResult,
        screenW: Int,
        screenH: Int,
        knownCardTarget: Pair<String, Rect>? = null,
        onFinished: (() -> Unit)? = null
    ) {
        if (finishStopped || !prefs.processingEnabled) {
            onFinished?.invoke()
            return
        }
        if (prefs.finishMode == Prefs.FINISH_OFF) {
            onFinished?.invoke()
            return
        }
        val nowMs = SystemClock.elapsedRealtime()
        if (cardPanelBusy) {
            AppLog.i("⑩ 收尾：上一次题卡校验还没结束，本轮跳过")
            onFinished?.invoke()
            return
        }
        if (cardCheckBlockedUntilMs > nowMs) {
            AppLog.i("⑩ 收尾：题卡校验冷却中（还有 ${(cardCheckBlockedUntilMs - nowMs) / 1000}s），本轮改为推进")
            scheduleAdvance(prefs, res, "收尾：题卡冷却")
            return
        }
        if (llmInFlight) {
            // 大模型回调马上会来点选项 —— 此刻开面板一定会被点坏，等下一轮
            AppLog.i("⑩ 收尾：有大模型请求在飞，本轮不做题卡校验")
            scheduleAdvance(prefs, res, "收尾：等大模型落地")
            return
        }
        if (busy.get()) {
            // 注意（v1.4 踩到）：`analyze()` 全程持有 busy，而「识别到标题后先扫题卡取总题数」
            // 这条流程正是在 busy 为 true 时调用本函数的 —— 直接 return 会让 analyze 的锁永不放，
            // 之后再也不会分析（真机现象：`跳过：上一次分析还没结束` 无限刷）。
            // 所以这里**必须**把调用方的收尾回调放行，不能静默 return。
            AppLog.i("⑩ 收尾：上一次分析还没结束，本轮不做题卡校验")
            onFinished?.invoke()
            return
        }
        // v1.4：卷尾提示命中时，入口是**当场**解析好的 —— 此刻树已变陈旧，不能再用它找「题卡」。
        val card = knownCardTarget ?: findCardToggle(screenW, screenH)
        if (card == null) {
            AppLog.i("⑩ 收尾：没找到「题卡」入口，放弃判定")
            onFinished?.invoke()
            scheduleAdvance(prefs, res, "收尾：找不到题卡")
            return
        }
        if (knownCardTarget != null) {
            AppLog.i("⑩ 收尾：用卷尾提示那一刻解析到的「${knownCardTarget.first}」入口(${knownCardTarget.second.centerX()},${knownCardTarget.second.centerY()})，不重新找树")
        }
        cardTapX = card.second.centerX()
        cardTapY = card.second.centerY()
        // 闸门（v1.6）：从点开面板到读判完成，全程不许别的流程点屏幕
        cardPanelBusy = true
        cardGateAtMs = nowMs
        busy.set(true)
        if (!clickAtPoint(cardTapX, cardTapY, "题卡")) {
            AppLog.w("⑩ 收尾：点「题卡」未生效（${cardTapX},${cardTapY}），放弃判定")
            endCardGate()
            scheduleAdvance(prefs, res, "收尾：点题卡失败")
            return
        }
        handler.postDelayed({
            try {
                val numbers = cardQuestionNumbers(screenW, screenH)
                val n = numbers.size
                if (n <= 0 || n > 200) {
                    AppLog.i("⑩ 收尾：题卡没读到题号，放弃判定")
                    // v1.4：同样不关面板（提交/翻页会关掉它），直接把闸门放掉继续下一轮
                    endCardGate()
                    onFinished?.invoke()
                    scheduleAdvance(prefs, res, "收尾：题卡没有可信题号")
                    return@postDelayed
                }
                AppLog.i("⑩ 收尾：点开题卡统计题目（共 $n 题）")
                // 额外明细：便于真机干跑核对 N 是否等于卷面题数（题卡外的纯数字节点也会被计入）
                AppLog.i("⑩ 收尾：题号明细（$n 个纯数字节点）：" +
                    numbers.take(12).joinToString(",") + if (n > 12) "…" else "")
                // v1.4 用户口径：这份卷子一共几题 = 题卡里**最大的那个题号**
                val maxNo = numbers.filter { it.isNotBlank() }.mapNotNull { it.trim().toIntOrNull() }.maxOrNull() ?: n
                if (paperTotalN <= 0 || maxNo > paperTotalN) {
                    paperTotalN = maxNo
                    // 顺手刷新当前题号再打日志：原先把上一轮的旧值显示成"当前第 0 题"，日志不可信
                    currentQuestionNo = parseCurrentQuestionNo(res)
                    AppLog.i("⑩ 进度：本卷总题数 N=$paperTotalN（取题卡最大题号），当前第 $currentQuestionNo 题" +
                        " → ${if (currentQuestionNo in 1 until paperTotalN) "还有题要答" else "已在最后一题"}")
                }
                // v1.4（用户口径）：**不关面板，直接进判定/提交**。
                // 依据：① 提交本身会让面板自动关闭（实measure：点「提交作业」→「确认提交」后页面切走）；
                // ② 关面板这一步在这台真机上根本关不掉（坐标点 4 次都在打 y=343），却把整个收尾卡死
                //    —— `题卡面板关不掉` → 校验提前 return → 永远不提交。
                // 所以这里直接调 onCardCounted（它在该提交时会点「提交作业」）。
                handler.postDelayed({
                    try {
                        onCardCounted(prefs, res, n, screenW, screenH)
                    } finally {
                        endCardGate()
                        onFinished?.invoke()
                    }
                }, FINISH_WAIT_MS)
            } catch (t: Throwable) {
                AppLog.e("⑩ 收尾：题卡校验异常：${t.javaClass.simpleName}: ${t.message}")
                endCardGate()
            }
        }, FINISH_WAIT_MS)
    }

    /** 放闸：解除题卡互斥。任何退出路径都必须走到这里（另有 `analyze()` 里的 15s 看门狗兜底）。 */
    private fun endCardGate() {
        cardPanelBusy = false
        busy.set(false)
    }

    /**
     * 找「题卡」开关：**取最靠上的那个可见节点**。
     *
     * 为什么不能只取第一个命中的：面板打开后树里**同时有两个**「题卡」——
     * 面板顶部那个（实测 `[432,267][648,420]`）与页面底部那个（`[489,1824][588,1884]`，被面板盖住、
     * 点它没有任何作用）。真机实测：用底部坐标去关，面板一直开着，随后所有点击都落在面板上（作答全丢）。
     */
    private fun findCardToggle(screenW: Int, screenH: Int): Pair<String, Rect>? =
        NodeReader.findTextNodesBy(allWindowRoots(), { it == CARD_WORD })
            .filter { isVisibleOnScreen(it.second, screenW, screenH) }
            .map { (it.first.text?.toString()?.trim().orEmpty()) to it.second }
            .filter { it.first.isNotEmpty() }
            .minByOrNull { it.second.centerY() }

    // v1.4 已删除 `closeCardAndVerify()` 与 `isCardPanelOpen()`（连 `CARD_PART_TITLE` 一起）：
    // 收尾流程不再关闭题卡面板 —— 真机上那个面板用坐标点 4 次都关不掉，反而把整条收尾卡死
    // （`题卡面板关不掉` → 校验提前 return → 永不提交）。现在读完题号直接进入判定/提交，
    // 提交本身会让平台关闭面板（用户现场确认）。留着这两个函数只会误导后来人。

    /**
     * 找出当前屏幕上的**卷面标题**（v1.4 换卷检测用）。
     *
     * 只在节点树里找形如「第X章/单元/节」的短文本；取**最长**的一个（页面上可能同时出现
     * 「第五章单元测试」与其他零碎文本）。找不到就返回空串 —— 调用方按"无法判定"处理，
     * 不会因为读不到标题就误判成换卷。
     */
    private fun findPaperTitle(screenW: Int, screenH: Int): String {
        val all = runCatching { allWindowRoots() }.getOrNull() ?: return ""
        val candidates = NodeReader.findTextNodesBy(all, { looksLikePaperTitle(it.trim()) })
            .filter { isVisibleOnScreen(it.second, screenW, screenH) }
            .map { it.first.text?.toString()?.trim().orEmpty() }
            .filter { it.isNotEmpty() }
        return candidates.maxByOrNull { it.length } ?: ""
    }

    /**
     * 停手期间是否发生了「换卷」（v1.4）。
     *
     * 为什么需要：`finishStopped` 原本只在**前台包名变化**时解除，而"同一 App 内手动换卷"
     * 包名根本不变 —— 真机实测答完第七章后手动切到第八章，一直卡在
     * `⑩ 已停手：本应用已答完，等待前台切换`，新卷完全不处理。
     *
     * 判据（保守，宁可晚一轮也不误判）：
     *  - 读不到标题 → 不算换卷；
     *  - 与"停手时那份卷"的标题**不同** → 算换卷（初次进入尚未记录基线时只记基线，不解除）。
     */
    private fun paperChangedWhileStopped(): Boolean {
        val title = findPaperTitle(lastScreenW, lastScreenH)
        if (title.isEmpty()) return false
        currentPaperTitle = title
        if (finishedPaperTitle.isEmpty()) {
            finishedPaperTitle = title       // 还没记基线：先记下，这一轮不解除
            return false
        }
        return title != finishedPaperTitle
    }

    /**
     * 题卡校验结束后刷一下 WebView 无障碍树。
     *
     * 真机实测：关掉题卡面板后 WebView 的树会**陈旧**（题目/选项从树里消失，只剩标题与底部栏），
     * 手动点一次「上一题 → 下一题」即恢复。所以判定「尚未答完」要继续答题前，先做这个 nudge。
     */
    private fun nudgeTreeAfterCard(screenW: Int, screenH: Int) {
        val prev = findVisibleNodeBy(screenW, screenH) { it == "上一题" }
        val next = findVisibleNodeBy(screenW, screenH) { it == "下一题" }
        if (prev != null && next != null) {
            AppLog.i("⑩ 收尾：题卡后面板已关，点「上一题→下一题」刷新 WebView 无障碍树")
            clickAtPoint(prev.second.centerX(), prev.second.centerY(), "上一题")
            handler.postDelayed({
                clickAtPoint(next.second.centerX(), next.second.centerY(), "下一题")
            }, 800L)
        } else {
            AppLog.i("⑩ 收尾：没有「上一题/下一题」可用来刷新无障碍树（后续读不到题时请手动点一下页面）")
        }
    }

    /**
     * 数题号（契约 §7.1 步骤 2）：统计 `^\d{1,3}$` 的**可见**文本节点，跨所有分段累加、不去重。
     *
     * 注意（如实）：这里是**字面**实现 —— 题卡面板之外若也有纯数字节点（例如页面自己的题号），
     * 会被一并计入。这个偏差只会让 N 偏大（→ M<N → 不提交），属于安全方向，不会误交卷。
     */
    private fun cardQuestionNumbers(screenW: Int, screenH: Int): List<String> {
        val out = ArrayList<String>()
        for ((text, _) in visibleTexts(screenW, screenH)) {
            if (!CARD_QUESTION_NO.matches(text)) continue
            if (text.toIntOrNull() == null) continue
            out.add(text)
        }
        return out
    }

    /**
     * §7.1 步骤 4 + §7.2/§7.3：判定「整卷是否答完」，再按 `finishMode` 执行收尾。
     *
     * **v1.4 判据改版（用户口径）**：主判据从「App 自己点过几题（M >= N）」改成**题号**
     * —— 前提是**已经到最后一题**（`currentQuestionNo == n`），并且**最后一题确实处理过**
     * （`isRecentlyAnswered(最后一题)`）。
     *
     * 为什么还要"最后一题处理过"这一步：题号只说明**翻到了**最后一题，不代表**答了**它。
     * 平台读不到每题的作答标记（坑点 #18：题卡里的已答只有背景色），所以唯一可靠的
     * "这题处理过了"就是去重记录。缺了它，App 会在最后一题还没答时就交卷（交白卷）。
     * 注意这里**不再用 `answeredForFinish`（M）**：那个是"App 自己点过几题"，
     * 用户手动答过的卷子上恒为 0，正是它一直判"未答完"、导致自动提交走不到。
     */
    /**
     * **最后一题是否已经处理过**（v1.4 提交前的最后一道确认）。
     *
     * 为什么必须有它：题号只说明"翻到了最后一题"，不说明"答了它"。平台读不到每题的作答标记
     * （坑点 #18：智慧树题卡的已答状态只有背景色、无障碍节点里读不到），所以只能看**去重记录**
     * —— 那道题被处理过才会出现在 `recent` 里。
     *
     * 与旧 `M >= N` 的区别：这里**不看 App 自己点过几题**，只看"最后一题这道题"是否处理过，
     * 所以用户手动答过的卷子同样能判为答完（旧判据在那种卷上 M 恒为 0、永远不提交）。
     */
    private fun lastQuestionProcessed(res: OcrResult, n: Int): Boolean {
        if (n <= 0) return false
        // 已由 App 自己答满 n 题：直接算处理完（dryRun 下也计数，便于试运行核对）
        if (answeredForFinish >= n) return true
        val p = Prefs(this)
        val q = QuestionDetector.detect(res, p.minScore)
        if (!q.isCandidate) return false
        val key = questionKey(q)
        return isRecentlyAnswered(key, System.currentTimeMillis(), p.dedupSeconds * 1000L)
    }

    private fun onCardCounted(prefs: Prefs, res: OcrResult, n: Int, screenW: Int, screenH: Int) {
        if (finishStopped || !prefs.processingEnabled || prefs.finishMode == Prefs.FINISH_OFF) return
        val m = answeredForFinish
        val atLastQuestion = currentQuestionNo > 0 && currentQuestionNo >= n
        val lastQuestionDone = lastQuestionProcessed(res, n)
        if (!atLastQuestion || !lastQuestionDone) {
            AppLog.i("⑩ 收尾：题号 $currentQuestionNo/$n（最后一题已处理=$lastQuestionDone，App 自答 $m 题）→ 尚未答完")
            nudgeTreeAfterCard(screenW, screenH)
            stuckAtEndMs = 0L
            // v1.4：题卡校验期间（点开→数题号→关闭面板 ≈5s）推进的"无进展检测"会把暂停一路推到
            // 60s / 按钮失效 5 分钟，真机实测这会让"还没答完"之后**再也点不动「下一题」**。
            // 这里判定的是"要继续答题"，那么把暂停与连击都清掉，让推进立刻能重试。
            advanceBlockedUntilMs = 0L
            advanceStreak = 0
            AppLog.i("⑩ 收尾：清掉推进暂停与连击，允许继续答题")
            scheduleAdvance(prefs, res, "收尾：尚未答完")
            return
        }
        AppLog.i("⑩ 收尾：已到最后题且已处理（题号 $currentQuestionNo/$n）→ 执行收尾")
        when (prefs.finishMode) {
            Prefs.FINISH_IDLE -> {
                if (prefs.dryRun) {
                    AppLog.i("⑩ 试运行：本应停手（$m/$n），已跳过")
                    return
                }
                AppLog.i("⑩ 收尾：已答完（$m/$n）→ 按设置「继续答题」停手（不再操作本应用）")
                finishStopped = true
                finishedPaperTitle = currentPaperTitle.ifEmpty { findPaperTitle(screenW, screenH) }
                AppLog.i("⑩ 收尾：记下已答完的卷面标题「${finishedPaperTitle.ifEmpty { "(未识别到)" }}」，换卷时自动解除停手")
            }
            Prefs.FINISH_SUBMIT -> {
                val btn = findVisibleNodeBy(screenW, screenH) { SUBMIT_WORDS.contains(it) }
                if (btn == null) {
                    AppLog.i("⑩ 收尾：没找到「提交作业」按钮，放弃自动提交")
                    return
                }
                if (prefs.dryRun) {
                    AppLog.i("⑩ 试运行：本应提交（$m/$n，按钮「${btn.first}」(${btn.second.centerX()},${btn.second.centerY()})），已跳过")
                    return
                }
                doSubmit(prefs, btn, screenW, screenH, m, n)
            }
        }
    }

    /** §7.2 `submit`：点提交作业 → 读弹窗 → 只有「读到明确确认按钮且弹窗没有未作答提示」才点确认。 */
    private fun doSubmit(
        prefs: Prefs,
        btn: Pair<String, Rect>,
        screenW: Int,
        screenH: Int,
        m: Int,
        n: Int
    ) {
        val (label, box) = btn
        if (!clickAtPoint(box.centerX(), box.centerY(), "提交作业")) {
            AppLog.w("⑩ 收尾：点「$label」未生效（${box.centerX()},${box.centerY()}），放弃自动提交")
            return
        }
        AppLog.i("⑩ 收尾：已点「$label」（坐标 ${box.centerX()},${box.centerY()}）→ 等 ${FINISH_WAIT_MS}ms 读弹窗")
        handler.postDelayed({
            if (finishStopped || !prefs.processingEnabled) return@postDelayed
            val texts = visibleTexts(screenW, screenH).map { it.first }
            val hint = texts.firstOrNull { t -> UNANSWERED_HINTS.any { t.contains(it) } }
            if (hint != null) {
                AppLog.i("⑩ 收尾：提交前弹窗提示未作答，已取消提交（弹窗文本「${hint.take(30)}」）")
                val cancel = findVisibleNodeBy(screenW, screenH) { CANCEL_WORDS.contains(it) }
                if (cancel != null) {
                    clickAtPoint(cancel.second.centerX(), cancel.second.centerY(), "取消")
                    AppLog.i("⑩ 收尾：已点「${cancel.first}」关掉弹窗（坐标 ${cancel.second.centerX()},${cancel.second.centerY()}）")
                } else {
                    AppLog.i("⑩ 收尾：没找到「取消/关闭」按钮，停手不点")
                }
                return@postDelayed
            }
            val confirm = findVisibleNodeBy(screenW, screenH) { CONFIRM_WORDS.contains(it) }
            if (confirm == null) {
                // 安全底线：读不到明确确认按钮就绝不点（宁可不交卷）
                AppLog.i("⑩ 收尾：弹窗里没读到确认按钮，已放弃提交")
                return@postDelayed
            }
            AppLog.i("⑩ 收尾：弹窗确认按钮候选「${confirm.first}」(${confirm.second.centerX()},${confirm.second.centerY()})")
            if (!clickAtPoint(confirm.second.centerX(), confirm.second.centerY(), "确认提交")) {
                AppLog.w("⑩ 收尾：点「${confirm.first}」未生效，放弃自动提交")
                return@postDelayed
            }
            AppLog.i("⑩ 收尾：已点「确认提交」（坐标 ${confirm.second.centerX()},${confirm.second.centerY()}）")
            finishStopped = true
            finishedPaperTitle = currentPaperTitle.ifEmpty { findPaperTitle(screenW, screenH) }
            AppLog.i("⑩ 收尾：记下已提交的卷面标题「${finishedPaperTitle.ifEmpty { "(未识别到)" }}」，换卷时自动解除停手")
        }, FINISH_WAIT_MS)
    }

    /**
     * 「题目超出页面」时在**页内滚动一次**，把题面读全再作答（v1.4）。
     *
     * 返回 `true` = 本轮不需要滚动（可以继续走答/判流程）；`false` = 已经滚了，本轮就此结束，
     * 下一轮分析会读到新的一屏。
     *
     * 为什么要判「超出页面」：真机实测（智慧树 WebView）页面里**只有 WebView 一个可滚动容器**，
     * 题干或选项很长时题面会超出屏幕，端侧只能读到可见部分 —— 拿残缺题干问模型必然答错，
     * 而且底部选项根本看不到、点不到。
     *
     * 判据（保守）：
     *  - 选项框越过视口下沿（底栏上方）或跑到屏幕上半部之上；或
     *  - 题干行里存在**从屏幕下沿往上长**的长行（被截断），且当前屏题干比阈值长。
     * 次数上限 [READ_MAX_SCROLLS_PER_QUESTION]，且**按题号计数**，换题即清零，防止无限滚动。
     *
     * **不能用去重键判断"同一题"**（我第一版就这么写的，真机立刻踩到死循环）：
     * 滚动本身会改变可见文本 → 去重键每次都变（实测 keyLen 103→108）→ 计数永远被重置成 1 →
     * 无限滚动。题号（`5. 判断题` / `2. 单选题`）才是不随滚动改变的稳定标识。
     */
    private fun longQuestionScrollDone(prefs: Prefs, q: Question, res: OcrResult): Boolean {
        if (!prefs.processingEnabled || !q.isCandidate) return true
        val no = questionNumberTag(res, q)
        if (no != longQuestionScrollNo) {
            longQuestionScrollNo = no
            longQuestionScrollCount = 0
        }
        val need = hasOffScreenQuestionContent(res, q)
        if (!need) {
            longQuestionScrollCount = 0      // 已经能看全：清计数，题目发生变化也不用记着旧账
            return true
        }
        if (longQuestionScrollCount >= READ_MAX_SCROLLS_PER_QUESTION) {
            AppLog.i("⑨ 长题：已滚动 $longQuestionScrollCount 次仍有内容在屏幕外，不再滚（按当前可见内容继续）")
            return true
        }
        val kind = scrollForward(res.sourceWidth, res.sourceHeight)
        if (kind == ScrollKind.NONE) {
            AppLog.w("⑨ 长题：题目超出页面，但页内滚动未生效（不做推进，等下一轮）")
            return true
        }
        longQuestionScrollCount++
        AppLog.i("⑨ 长题：题目超出页面 → 页内滚动第 ${longQuestionScrollCount}/$READ_MAX_SCROLLS_PER_QUESTION 次" +
            "（${if (kind == ScrollKind.NODE) "节点滚动" else "上滑手势"}），读完再作答")
        handler.postDelayed({ schedule("长题滚动后重读", 300L) }, 500L)
        return false
    }

    /**
     * 当前题的稳定标识：优先用**题号标签**（`5. 判断题（2分）` 这种行），
     * 拿不到时退回题干前 40 字（也基本不随页内滚动变化）。**不用去重键**（它随滚动变化）。
     */
    private fun questionNumberTag(res: OcrResult, q: Question): String {
        val noLine = res.lines.firstOrNull { QUESTION_NO_LINE.containsMatchIn(it.text.trim()) }
        if (noLine != null) return noLine.text.trim().take(40)
        return q.stem.replace(WHITESPACE, "").take(40)
    }

    /**
     * 从识别结果里解析**当前题号**（`5. 判断题（2分）` → 5）。读不到返回 0。
     *
     * 为什么用题号而不是内容：题号是平台渲染的稳定身份，不随页内滚动、OCR 抖动变化，
     * 也不依赖"App 自己点过几题"的账本（那个在用户手动答过的卷子上永远是 0）。
     */
    private fun parseCurrentQuestionNo(res: OcrResult): Int {
        for (line in res.lines) {
            val t = line.text.trim()
            val m = QUESTION_NO_LINE.find(t) ?: continue
            val n = Regex("^\\d{1,3}").find(m.value)?.value?.toIntOrNull() ?: continue
            if (n > 0) return n
        }
        return 0
    }

    /**
     * 「还有下一题要答吗」：按题号判定（用户口径：**题号 < 题卡里的最大题号就继续作答**）。
     *
     * 为什么不能再用 `M >= N`（App 自己点过几题）：用户手动答过的卷子上 App 一次都没点过，
     * M 恒为 0，于是永远判"未答完/不提交"，而推进又被"按钮无反应"的暂停锁死 —— 真机整套卷停摆。
     * 改用题号后，这条路径**不依赖推进是否解得开**，卡死的那一环就被绕过了。
     *
     * 返回 true = 后面还有题（应继续作答并推进）；false = 已在最后一题。
     */
    private fun hasMoreQuestions(res: OcrResult): Boolean {
        currentQuestionNo = parseCurrentQuestionNo(res)
        if (paperTotalN <= 0 || currentQuestionNo <= 0) return true   // 读不到就保守地继续作答
        return currentQuestionNo < paperTotalN
    }

    /**
     * 已答过一道题之后决定去向（v1.4）。
     *
     * 关键区别：**只要后面还有题，就主动把推进解除暂停再点一次「下一题」**。
     * 原来这里直接走 `scheduleFinishOrAdvance` → `maybeAdvance`，而 `maybeAdvance` 一旦被
     * "按钮点了无反应/滚动无位移"的暂停挡住（题卡面板让 WebView 树陈旧造成的误判）就永远返回，
     * 真机表现为"停在一道已答过的题上无限自检"（`DEDUP-MATCH#` 每轮 age+2s 增长）。
     */
    private fun afterAnsweredQuestion(prefs: Prefs, res: OcrResult, reason: String) {
        if (paperTotalN > 0 && hasMoreQuestions(res)) {
            AppLog.i("⑩ 进度：第 $currentQuestionNo/$paperTotalN 题已答 → 继续下一题（$reason）")
            advanceBlockedUntilMs = 0L      // 解除"按钮无反应/滚动无位移"的误判暂停
            advanceStreak = 0
            stuckAtEndMs = 0L
            scheduleAdvance(prefs, res, "已答第 $currentQuestionNo/$paperTotalN 题，继续")
            return
        }
        // v1.4：**已在最后一题且这题处理过** → 直接进入整卷收尾校验（题卡 → 数题号 → 提交/停手）。
        // 不加这一步的话，会停在最后一题反复判"已答过"、又等不到推进（没有下一题可点），
        // 真机表现就是"答完了却不提交"。
        if (paperTotalN > 0 && prefs.finishMode != Prefs.FINISH_OFF) {
            AppLog.i("⑩ 进度：已在最后一题（$currentQuestionNo/$paperTotalN）且本题已处理 → 进入收尾校验")
            stuckAtEndMs = SystemClock.elapsedRealtime()
            advanceBlockedUntilMs = 0L
            advanceStreak = 0
            scheduleFinishOrAdvance(prefs, res, "已到最后一题", forceFinishCheck = true)
            return
        }
        scheduleFinishOrAdvance(prefs, res, reason)
    }

    /**
     * 当前屏是否有**题目内容被挡在屏幕外**。
     *
     * 底栏上沿经验值：真机底部「上一题/题卡/下一题」约占屏幕下沿 180px，题面可读区域在其上方。
     * 只用选项位置 + 题干行是否被下沿截断来判断，**不猜"下面还有内容"**：
     * 宁可漏判（多滚一次不会有副作用），也不要在题已看全时反复滚动。
     */
    private fun hasOffScreenQuestionContent(res: OcrResult, q: Question): Boolean {
        val w = res.sourceWidth
        val h = res.sourceHeight
        if (w <= 0 || h <= 0) return false
        val bottomCut = h - 180
        val topCut = h / 2

        for (o in q.options) {
            val b = o.box ?: continue
            if (b.bottom > bottomCut + 8) return true      // 选项被底栏/屏幕下沿挡住
            if (b.top < 0) return true
        }
        // 题干行：从屏幕下沿往上延伸、明显是一整行（宽度够大）的长文本 → 被截断
        val optionBoxes = q.options.mapNotNull { it.box }.toSet()
        for (line in res.lines) {
            val b = line.box ?: continue
            if (b in optionBoxes) continue
            if (b.width() < w / 2) continue
            if (line.text.trim().length < 12) continue
            val startsAboveBottom = b.top in 0 until bottomCut
            if (startsAboveBottom && b.bottom > bottomCut) return true
            if (b.top < topCut && b.bottom > topCut && b.height() > h / 4) return true   // 跨屏的半截长文本
        }
        return false
    }

    /** 可见节点文本列表（题号统计、弹窗读取共用）；只取屏幕内节点。 */
    private fun visibleTexts(screenW: Int, screenH: Int): List<Pair<String, Rect>> {
        val out = ArrayList<Pair<String, Rect>>()
        for ((node, box) in NodeReader.findTextNodesBy(allWindowRoots(), { true }, limit = 300)) {
            if (!isVisibleOnScreen(box, screenW, screenH)) continue
            val t = (node.text?.toString()?.takeIf { it.isNotBlank() }
                ?: node.contentDescription?.toString() ?: "").trim()
            if (t.isNotEmpty()) out.add(t to box)
        }
        return out
    }

    /** 找**可见**的节点（`text‖contentDescription` 过 [predicate]）；返回实际文本与框。 */
    private fun findVisibleNodeBy(
        screenW: Int,
        screenH: Int,
        predicate: (String) -> Boolean
    ): Pair<String, Rect>? {
        for ((node, box) in NodeReader.findTextNodesBy(allWindowRoots(), predicate)) {
            if (!isVisibleOnScreen(box, screenW, screenH)) continue
            val display = (node.text?.toString()?.takeIf { it.isNotBlank() }
                ?: node.contentDescription?.toString() ?: "").trim()
            if (display.isEmpty()) continue
            return display to box
        }
        return null
    }

    // ------------------------------------------------------------------ 答完自动推进（task-7）

    /**
     * 本轮「无事可做」（题目已全部完成 / 已答过不必重复作答）时，调度一次推进检查。
     *
     * 只延迟 [ADVANCE_DELAY_MS] 后跑：先让「提交作答 → 关闭浮层」走完，别和收尾抢同一个浮层。
     * 只带 [OcrResult] 的标量（签名与截图尺寸），避免把已 recycle 的 bitmap 带进来。
     */
    private fun scheduleAdvance(prefs: Prefs, res: OcrResult, reason: String) {
        if (!prefs.processingEnabled || !prefs.autoAdvanceEnabled) return
        if (finishStopped) return                       // §7.3 停手后不再推进
        val signature = res.signature
        val w = res.sourceWidth
        val h = res.sourceHeight
        AppLog.i("⑨ 推进：本轮无需作答（$reason）→ ${ADVANCE_DELAY_MS}ms 后检查是否推进")
        handler.postDelayed({ maybeAdvance(prefs, signature, w, h) }, ADVANCE_DELAY_MS)
    }

    /** 记录前台包名；变化时清空「本次会话答过题」计数（换个应用后就不该再替它翻页了）。 */
    private fun noteForegroundPkg(pkg: String?) {
        val p = pkg?.trim().orEmpty()
        if (p.isEmpty() || p == lastForegroundPkg) return
        val had = answeredThisSession > 0
        lastForegroundPkg = p
        answeredThisSession = 0
        answeredForFinish = 0          // 完成判定的 M 也随会话清零
        stuckAtEndMs = 0L              // 「推不动」标记随会话清零
        lastAnsweredAtMs = 0L          // 推进窗口也随会话清零
        advanceStreak = 0
        // 契约 §7.3：前台包名变化 → 解除停手（切到别的 App 再回来视为新一轮）
        if (finishStopped) {
            finishStopped = false
            AppLog.i("⑩ 收尾：前台应用变为 $p，解除停手（视为新一轮）")
        }
        lastFinishCheckAt = 0L         // 新一轮允许立刻做题卡校验
        // D-e：只清"按钮点了有没有反应"的检测状态；**不清 advanceBlockedUntilMs**
        // （5 分钟/60s 的阻塞到期自然解除即可，换应用不该把它提前解开）
        pendingButtonSignature = null
        pendingButtonClickedAt = 0L
        nodeScrollIneffective = false   // 换了应用，之前的"节点滚动无效"结论不再成立
        if (had) AppLog.i("⑨ 推进：前台应用变为 $p，本次会话答题计数清零（不再满足推进护栏）")
    }

    /**
     * 无进展检测（契约 §6.5 + F5 两段式 + F5 定稿「无效推进不计入连击」）：
     *  - 签名**变了**（页面确实动了）→ 若上次是节点滚动，这时才把那次推进计入连击；清「节点滚动无效」标记；
     *  - 签名**没变**且上次是**节点滚动** → 只记「节点滚动无效」，改用上滑手势重试，**连击不加、也不暂停**；
     *  - 签名**没变**且上次是**手势** → 判「已到页尾」，暂停推进 [ADVANCE_BLOCK_MS]（终点语义，不谈连击）。
     */
    private fun checkScrollProgress(prefs: Prefs, signature: String) {
        val before = pendingScrollSignature ?: return
        pendingScrollSignature = null
        val nodeScrollPending = pendingNodeScrollStreak
        pendingNodeScrollStreak = false

        if (signature != before) {
            nodeScrollIneffective = false
            if (nodeScrollPending) {
                // 节点滚动确实带动了画面 → 此刻才算一次有效推进（F5 定稿）
                advanceStreak++
                afterAdvanceAttempt(prefs)
            }
            return
        }
        if (lastScrollWasNode) {
            // 无效节点滚动 = 没发生的动作 → 不计入连击，也不暂停
            nodeScrollIneffective = true
            AppLog.i("⑨ 推进：节点滚动无位移，改用上滑手势重试")
        } else {
            advanceStreak = 0
            advanceBlockedUntilMs = SystemClock.elapsedRealtime() + ADVANCE_BLOCK_MS
            AppLog.i("⑨ 推进：滚动后画面未变化（已到页尾？），暂停推进 60s")
            stuckAtEndMs = SystemClock.elapsedRealtime()   // 推不动了 → 允许做题卡校验（§7.1）
        }
    }

    /**
     * 按钮推进的「点了有没有反应」检测（D-e）。
     *
     * 与滚动那套（[checkScrollProgress]）**并列且互不干扰**：按钮分支只写 `pendingButton*`，
     * 滚动分支只写 `pendingScrollSignature`/`lastScrollWasNode`；两边都不碰对方的字段。
     *
     *  - 签名**已变** → 按钮生效，清掉检测状态（连击已在点击时计过）；
     *  - 签名**未变**且距点击 ≥ 4s（给慢页面留反应时间）→ 判「点了没反应」：
     *    暂停推进 [ADVANCE_BUTTON_DEAD_MS]、清状态、**连击清零**（这是失败路径，不是一次有效推进）。
     *
     * 注意：只挡**推进**，不挡作答 —— 题目照常识别/作答（题目去重窗口那套完全不受影响）。
     */
    private fun checkButtonProgress(signature: String) {
        val before = pendingButtonSignature ?: return
        if (signature != before) {
            pendingButtonSignature = null
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - pendingButtonClickedAt < 4_000L) return      // 给页面最多 4s 反应时间，避免慢页面误判
        pendingButtonSignature = null
        advanceStreak = 0
        advanceBlockedUntilMs = now + ADVANCE_BUTTON_DEAD_MS
        AppLog.i("⑨ 推进：推进按钮点了无反应（画面未变化）→ 暂停推进 5 分钟")
        stuckAtEndMs = now                               // 推不动了 → 允许做题卡校验（§7.1）
    }

    /**
     * 真正决定并执行一次推进（只由 [scheduleAdvance] 在「本轮无事可做」后调度）。
     *
     * 优先级：**可见的推进按钮 → 向下滚动**；护栏：处理中/未答过题/暂停中/冷却中都不动作。
     */
    private fun maybeAdvance(prefs: Prefs, signature: String, screenWidth: Int, screenHeight: Int) {
        if (!prefs.processingEnabled || !prefs.autoAdvanceEnabled) return
        if (finishStopped) return                       // §7.3 停手后不再推进（挡住停手前排队的任务）
        if (busy.get()) {
            AppLog.i("⑨ 推进：上一次分析还没结束，本轮不推进")
            return
        }
        if (answeredThisSession <= 0) {
            AppLog.i("⑨ 推进：本应用本次会话还没答过题，不推进")
            return
        }
        val now = SystemClock.elapsedRealtime()
        // 推进窗口（契约 v1.2.2 §6.5）：以「最近一次成功作答」为基准，超出就不再推进；
        // **成功推进不刷新**这个时刻，所以答完一题后最多推进 ADVANCE_WINDOW_MS。
        val sinceAnsweredMs = now - lastAnsweredAtMs
        if (lastAnsweredAtMs <= 0L || sinceAnsweredMs > ADVANCE_WINDOW_MS) {
            AppLog.i("⑨ 推进：距上次作答已 ${sinceAnsweredMs / 1000}s（> ${ADVANCE_WINDOW_MS / 1000}s 窗口），不推进")
            return
        }
        if (now < advanceBlockedUntilMs) {
            AppLog.i("⑨ 推进：暂停中（还有 ${(advanceBlockedUntilMs - now + 999) / 1000}s），不推进")
            return
        }
        val since = now - lastAdvanceAt
        if (since < ADVANCE_COOLDOWN_MS) {
            AppLog.i("⑨ 推进：冷却中（距上次 ${"%.1f".format(since / 1000.0)}s < ${"%.1f".format(ADVANCE_COOLDOWN_MS / 1000.0)}s）")
            return
        }

        // ① 先点**可见**的推进按钮（折叠线以下的节点也在树里，但点中心会落到别处）
        val target = findVisibleAdvanceTarget(screenWidth, screenHeight)
        if (target != null) {
            val (label, box) = target
            if (prefs.dryRun) {
                AppLog.i("⑨ 试运行：本应推进（点「$label」(${box.centerX()},${box.centerY()})），已跳过")
                return
            }
            val ok = clickAtPoint(box.centerX(), box.centerY(), "推进")
            if (ok) {
                lastAdvanceAt = SystemClock.elapsedRealtime()
                advanceStreak++
                AppLog.i("⑨ 推进：已点「$label」（坐标 ${box.centerX()},${box.centerY()}）")
                // D-e：按钮路径此前**没有**无效果检测（滚动有），这里记下点击前的签名，
                // 下一轮由 checkButtonProgress 判定"点了有没有反应"；4s 没变就压 5 分钟。
                pendingButtonSignature = signature
                pendingButtonClickedAt = lastAdvanceAt
                afterAdvanceAttempt(prefs)
            } else {
                AppLog.w("⑨ 推进：点「$label」（${box.centerX()},${box.centerY()}）未生效，本轮不计数")
            }
            return
        }

        // ② 没有可见按钮 → 向下滚动
        AppLog.i("⑨ 推进：当前屏没有可见的推进按钮，改为向下滚动")
        if (prefs.dryRun) {
            AppLog.i("⑨ 试运行：本应推进（向下滚动），已跳过")
            return
        }
        lastAdvanceAt = SystemClock.elapsedRealtime()
        val kind = scrollForward(screenWidth, screenHeight)
        if (kind != ScrollKind.NONE) {
            // 记下滚动前的签名与"用了哪种滚动"，下一轮分析据此判断"滚没滚动 / 要不要改手势"
            pendingScrollSignature = signature
            lastScrollWasNode = (kind == ScrollKind.NODE)
            if (kind == ScrollKind.NODE) {
                // 节点滚动"返回成功"不等于真滚了：连击推迟到下一轮确认有位移才 +1（F5 定稿）
                pendingNodeScrollStreak = true
            } else {
                advanceStreak++
                afterAdvanceAttempt(prefs)
            }
        } else {
            AppLog.w("⑨ 推进：向下滚动未生效，本轮不计数")
        }
    }

    /** 推进动作执行完的统一收尾：连击达上限就暂停 [ADVANCE_BLOCK_MS] 并清零。 */
    private fun afterAdvanceAttempt(prefs: Prefs) {
        val maxStreak = prefs.autoAdvanceMaxStreak.coerceIn(1, 10)
        if (advanceStreak >= maxStreak) {
            advanceStreak = 0
            advanceBlockedUntilMs = SystemClock.elapsedRealtime() + ADVANCE_BLOCK_MS
            AppLog.i("⑨ 推进：连续 $maxStreak 次推进后仍未见新题，暂停推进 60s")
        }
    }

    /**
     * 找**可见**的推进按钮；没有返回 null（返回的是节点上显示的实际文本，供日志"按实"）。
     *
     * 长页面里折叠线以下的按钮仍然在无障碍节点树里，bounds 在屏幕外 —— 点它的中心会落到别处，
     * 所以这里必须先过「屏幕内」这道关（契约 §6.2）。
     */
    private fun findVisibleAdvanceTarget(screenWidth: Int, screenHeight: Int): Pair<String, Rect>? {
        val hits = NodeReader.findTextNodesBy(allWindowRoots(), { advanceLabelOf(it) != null })
        for ((node, box) in hits) {
            if (!isVisibleOnScreen(box, screenWidth, screenHeight)) continue
            // 与 NodeReader.walkBy 同一口径：text 为空/全空白才用 contentDescription（纯图标按钮）
            val display = (node.text?.toString()?.takeIf { it.isNotBlank() }
                ?: node.contentDescription?.toString() ?: "").trim()
            if (display.isEmpty()) continue
            return display to box
        }
        return null
    }

    /**
     * 推进按钮判定（契约 §6.4 v1.2.1）：`trim` → **先删掉成对括号包裹的整段** → 必须以某关键词开头
     * （多命中取最长）→ 余下每个字符都在装饰白名单内。
     *
     * 命中返回关键词，否则 null。例：`下一题`/`下一题。`/`下一题 >`/`下一题(1/10)`/`下一题《2》`/
     * `下一关【第2关】`/`下一题—`/`下一步：`/` 下一题 ` 命中；`下一题练习`（余下是汉字）、
     * `点击下一题`（前缀不符）、`上一题`、`继续`、`继续观看`、`Next`、空串都不命中。
     */
    private fun advanceLabelOf(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val plain = BRACKET_GROUP.replace(t, "").trim()
        if (plain.isEmpty()) return null
        for (w in ADVANCE_WORDS) {
            if (!plain.startsWith(w)) continue
            if (advanceSuffixOk(plain.substring(w.length))) return w
        }
        return null
    }

    /** 框是否与屏幕 `Rect(0,0,截图宽,截图高)` 相交且面积为正（task-7 ② 的可见性判定）。 */
    private fun isVisibleOnScreen(box: Rect, screenWidth: Int, screenHeight: Int): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0) return false
        val left = maxOf(box.left, 0)
        val top = maxOf(box.top, 0)
        val right = minOf(box.right, screenWidth)
        val bottom = minOf(box.bottom, screenHeight)
        return right > left && bottom > top
    }

    /**
     * 向下滚动：① 先试**可滚动节点的 ACTION_SCROLL_FORWARD**（不走输入子系统，最稳）；
     * ② 失败（或已被判定为"返回成功但无位移"）再上滑手势（落点复用既有 ±10px 抖动）。
     */
    private fun scrollForward(screenWidth: Int, screenHeight: Int): ScrollKind =
        scrollByAction(forward = true, screenWidth = screenWidth, screenHeight = screenHeight)

    /** 与 [scrollForward] 相反方向的滚动（把屏幕**上方**的目标带回来时用）。 */
    private fun scrollBackward(screenWidth: Int, screenHeight: Int): ScrollKind =
        scrollByAction(forward = false, screenWidth = screenWidth, screenHeight = screenHeight)

    /** 这次滚动实际用了哪条路（后续「无进展检测」要靠它区分节点滚动与手势，见 F5） */
    private enum class ScrollKind { NONE, NODE, GESTURE }

    private fun scrollByAction(forward: Boolean, screenWidth: Int, screenHeight: Int): ScrollKind {
        if (screenWidth <= 0 || screenHeight <= 0) return ScrollKind.NONE
        // 已知这条路上"返回成功却没位移"，就直接跳过去用手势，不再白试一轮（F5 约定 3）
        if (!nodeScrollIneffective) {
            val node = findScrollableNode(screenWidth, screenHeight)
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (node != null && runCatching { node.performAction(action) }.getOrDefault(false)) {
                // 日志按方向各写一条**字面串**：验收方可以直接 grep 原文（契约 §6.6 要求逐字）
                if (forward) AppLog.i("⑨ 推进：可滚动节点 ACTION_SCROLL_FORWARD 成功")
                else AppLog.i("⑨ 推进：可滚动节点 ACTION_SCROLL_BACKWARD 成功")
                return ScrollKind.NODE
            }
        } else if (forward) {
            AppLog.i("⑨ 推进：节点滚动此前无位移，直接改用上滑手势")
        }
        // 手势滚动：forward = (w/2, 0.85h) → (w/2, 0.15h)，反向则对调；300ms；落点 ±10px 抖动（防检测）
        val y1Base = if (forward) 0.85f else 0.15f
        val y2Base = if (forward) 0.15f else 0.85f
        val x1 = screenWidth / 2 + jitter(JITTER_MAX_PX)
        val y1 = (screenHeight * y1Base).toInt() + jitter(JITTER_MAX_PX)
        val x2 = x1 + jitter(JITTER_MAX_PX)
        val y2 = (screenHeight * y2Base).toInt() + jitter(JITTER_MAX_PX)
        if (forward) AppLog.i("⑨ 推进：改用上滑手势（$x1,$y1 → $x2,$y2）")
        else AppLog.i("⑨ 推进：改用下滑手势（$x1,$y1 → $x2,$y2）")
        return if (swipe(x1, y1, x2, y2)) ScrollKind.GESTURE else ScrollKind.NONE
    }

    /** ±[max] 的随机偏移（与 tapAt 的抖动同源同量） */
    private fun jitter(max: Int): Int = jitterRandom.nextInt(max * 2 + 1) - max

    private fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): Boolean = runCatching {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 300L)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }.getOrElse {
        AppLog.e("滚动手势异常：${it.javaClass.simpleName}: ${it.message}")
        false
    }

    /**
     * 取**可见且可滚动**的节点：优先**面积最大**的那个（长页面里主内容滚动容器几乎总是最大的），
     * 面积相同再取更深的。屏幕外的滚动容器不参与。
     */
    private fun findScrollableNode(screenWidth: Int, screenHeight: Int): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        var bestDepth = -1
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > SCROLL_MAX_DEPTH) return
            val r = Rect()
            runCatching { n.getBoundsInScreen(r) }
            val scrollable = runCatching { n.isScrollable }.getOrDefault(false)
            if (scrollable && isVisibleOnScreen(r, screenWidth, screenHeight)) {
                val area = r.width() * r.height()
                if (area > bestArea || (area == bestArea && d > bestDepth)) {
                    bestArea = area
                    bestDepth = d
                    best = n
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        for (root in allWindowRoots()) walk(root, 0)
        return best
    }

    /** 计划的目标框：节点计划取节点 bounds，坐标计划用 (x,y) 的单点框。 */
    private fun planTargetBox(plan: ClickPlan): Rect? {
        val node = plan.node
        if (node != null) {
            val b = Rect()
            runCatching { node.getBoundsInScreen(b) }
            return if (b.width() > 0 && b.height() > 0) b else null
        }
        return Rect(plan.x, plan.y, plan.x + 1, plan.y + 1)
    }

    /** 把屏幕外的目标滚进来：目标在下方就向前滚，在上方就向后滚。 */
    private fun scrollTargetIntoView(targetCenterY: Int, screenWidth: Int, screenHeight: Int): ScrollKind =
        if (targetCenterY < 0) scrollBackward(screenWidth, screenHeight)
        else scrollForward(screenWidth, screenHeight)

    /**
     * 用 root 执行 `input tap`。
     *
     * MuMu 的 root 默认放行（本机已开），所以 `su` 不会弹窗；带 3 秒超时兜住"万一弹窗"的情况，
     * 免得阻塞分析线程。
     */
    private fun rootTap(x: Int, y: Int): Boolean = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "input tap $x $y"))
        p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        true
    }.getOrDefault(false)

    private fun tapAt(x: Int, y: Int): Boolean {
        // 防检测：落点加 ±JITTER_MAX_PX 随机偏移，避免每次都是同一个精确坐标
        val jx = x + jitterRandom.nextInt(JITTER_MAX_PX * 2 + 1) - JITTER_MAX_PX
        val jy = y + jitterRandom.nextInt(JITTER_MAX_PX * 2 + 1) - JITTER_MAX_PX
        if (jx != x || jy != y) {
            AppLog.i("   落点抖动：($x,$y) → ($jx,$jy)（防检测 ±$JITTER_MAX_PX px）")
        }
        return runCatching {
            val path = Path().apply { moveTo(jx.toFloat(), jy.toFloat()) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        }.getOrElse {
            AppLog.e("手势点击异常：${it.javaClass.simpleName}: ${it.message}")
            false
        }
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 把 OCR 位图坐标系里的框换算回**屏幕坐标**。
     *
     * 截图与 `dispatchGesture` 同属整屏坐标系，所以只要 OCR 没有降采样，比例就是 1:1。
     * 降采样时用 `原始截图 / OCR 输入位图` 的比例换算 —— 这个比例由我们自己控制，是可靠的。
     *
     * 千万别用 `resources.displayMetrics`：它是「应用可用窗口」（本机 2400 高 vs 整屏 2800），
     * 拿它缩放会让点击整体偏移。这个坑踩过，别再改回去。
     */
    private fun scaleToScreen(res: OcrResult, box: Rect): Rect {
        if (!res.downscaled) return Rect(box)
        val sx = res.scaleX
        val sy = res.scaleY
        return Rect(
            (box.left * sx).toInt(),
            (box.top * sy).toInt(),
            (box.right * sx).toInt(),
            (box.bottom * sy).toInt()
        )
    }

    /** 只用于诊断：确认「截图尺寸」与「应用可用窗口」的差异（那是导航栏造成的，属正常） */
    private fun logIfSizeMismatch(res: OcrResult) {
        val dm = resources.displayMetrics
        if (res.sourceWidth != dm.widthPixels || res.sourceHeight != dm.heightPixels) {
            if (SystemClock.elapsedRealtime() - lastScaleWarnAt > 20_000) {
                lastScaleWarnAt = SystemClock.elapsedRealtime()
                AppLog.i("提示：截图 ${res.sourceWidth}x${res.sourceHeight} ≠ 应用窗口 ${dm.widthPixels}x${dm.heightPixels}" +
                    "（差值通常是导航栏）。截图与手势同属屏幕坐标系，故坐标用截图比例换算，不用窗口尺寸。")
            }
        }
    }

    private fun describeAnswer(a: LlmClient.Answer): String {
        val label = a.answerLabel.trim()
        val text = a.answerText.trim()
        val parts = ArrayList<String>(2)
        if (label.isNotEmpty()) parts.add("选项 $label")
        // 纯文本协议下 answerText 常常就等于标签本身，别重复打印
        if (text.isNotEmpty() && !text.equals(label, ignoreCase = true)) parts.add("「${text.take(60)}」")
        return if (parts.isEmpty()) "（模型未给出具体答案）" else parts.joinToString(" ")
    }

    /**
     * 反矛盾保护（契约 v1.1 §2.4）：模型点名的元素标签若**能明确归属到另一个选项标记**，就不用它。
     *
     * 例：模型答 `B`，但 `E7` 的标签是 `A. 对` —— 两个信号互相矛盾，多半是模型抄错了编号，
     * 此时退回 ClickPlanner（按字母找选项）比信编号更安全。
     *
     * 判据（v1.1 写实，修掉 v1.0 的两个反例）：
     *  - 归属标记只从标签**开头**的显式选项标记里抽（[TextMatch.optionTokenOf]），**长标签同样参与判定**
     *    （v1.0 用 normalizeLabel 的 12 字截断，`B. 水是由氢元素和氧元素组成的` 会漏判）；
     *  - 中文单字（`对`/`错`）与自由文本（`magnifying glass`）抽不出标记 → 不算矛盾，照用编号
     *    （v1.0 把汉字喂给 `isLetter()`，`对` 会被误判成选项字母）；
     *  - **同族才比较**：字母对字母、数字对数字；两族不同（例：模型答 `B` 而标记是 `1`）→ 不算矛盾。
     */
    private fun conflictsWithAnswerLetter(el: SomElement, a: LlmClient.Answer): Boolean {
        val token = TextMatch.optionTokenOf(el.label) ?: return false
        val ansLetters = Regex("[A-H]").findAll(a.answerLabel.uppercase()).map { it.value }.toSet()
        val ansDigits = Regex("[1-8]").findAll(a.answerLabel).map { it.value }.toSet()
        return when {
            token.length == 1 && token[0].isLetter() -> ansLetters.isNotEmpty() && token !in ansLetters
            else -> ansDigits.isNotEmpty() && token !in ansDigits
        }
    }

    // 截屏 → JPEG → Base64 的编码逻辑已抽到 SomAnnotator.encodeJpegBase64（SoM 标注图要与它共用同一套缩放公式），
    // 此处不再保留一份 —— 两份实现迟早会漂移。

    private fun eventName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "窗口变化"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "内容变化"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "窗口集合变化"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "滚动"
        else -> "事件$type"
    }

    private fun screenshotError(code: Int): String = when (code) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "内部错误($code)"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "无障碍服务无权限($code)"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "距上次截图太近($code)"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "无效显示($code)"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "无效窗口($code)"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "当前窗口禁止截屏($code)"
        else -> "未知错误($code)"
    }

    companion object {
        @Volatile
        var instance: ScreenOcrAccessibilityService? = null

        fun isConnected(): Boolean = instance != null
    }
}
