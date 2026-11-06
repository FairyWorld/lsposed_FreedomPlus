package io.github.fplus.core.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.xpler.core.XplerLog
import io.github.xpler.core.entity.EmptyHook
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 自动连播(自动播放下一条)
 *
 * 逻辑移植自 douyim(抖仙人) 的自动播放功能:
 * 1. 跟踪播放进度/完成回调, 得到当前视频的 进度/时长;
 * 2. 判定本条视频播放结束(播放完成回调, 或循环回到片头, 或停在片尾);
 * 3. 给信息流 ViewPager 合成一次上滑手势, 翻到下一条视频。
 *
 * 与 douyim 原实现 / 上一版的差异:
 * - 不再依赖注解精确匹配方法签名(参数个数/类型不符时 xpler 会静默跳过), 改为运行时枚举
 *   目标类的方法, 按方法名关键字(progress/complet/finish)动态挂钩, 实参按类型启发式解析;
 * - 播放完成回调为主路径, 进度推断为兜底;
 * - 每个环节都打日志(挂钩了哪些方法、采样值、翻页失败原因), 便于在 LSPosed 日志里定位;
 * - 翻页目标按"类名含 ViewPager + 竖向 + 面积最大"挑选, 找不到时退回 decorView。
 */
class HAutoPlay : BaseHook() {
    override fun setTargetClass(): Class<*> {
        return runCatching {
            findClass("com.ss.android.ugc.aweme.feed.controller.PlayerController")
        }.getOrDefault(EmptyHook::class.java)
    }

    override fun onInit() {
        XplerLog.i(
            "AutoPlay: 初始化 target=${targetClass.name} 开关=${ConfigV1.get().isAutoPlay}"
        )
        runCatching { hookPlayerCallbacks() }.onFailure { XplerLog.e(it) }
    }

    /**
     * 动态挂钩: 枚举候选类里所有"名字含 progress / complet / finish / playend"的方法,
     * 命中即挂, 这样不依赖某个固定版本的签名。
     */
    private fun hookPlayerCallbacks() {
        var total = 0
        for (className in CANDIDATES) {
            val clazz = runCatching {
                Class.forName(className, false, param.classLoader)
            }.getOrNull()
            if (clazz == null) {
                XplerLog.i("AutoPlay: 类不存在 $className")
                continue
            }
            var hooked = 0
            for (method in clazz.declaredMethods) {
                if (Modifier.isAbstract(method.modifiers) || Modifier.isStatic(method.modifiers)) continue
                val kind = classify(method.name) ?: continue
                runCatching {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(p: MethodHookParam) {
                            runCatching {
                                AutoPlayEngine.onPlayerCallback(kind, method, p.args)
                            }.onFailure { XplerLog.e(it) }
                        }
                    })
                    hooked++
                    XplerLog.i(
                        "AutoPlay: 挂钩 ${clazz.simpleName}#${method.name}(" +
                            method.parameterTypes.joinToString(",") { it.simpleName } +
                            ") 类型=$kind"
                    )
                }.onFailure { XplerLog.e(it) }
            }
            total += hooked
            XplerLog.i("AutoPlay: ${clazz.simpleName} 挂钩 $hooked 个方法")
            if (hooked == 0) {
                // 没挂上时把方法名打出来, 方便按真实签名补齐
                XplerLog.i(
                    "AutoPlay: ${clazz.simpleName} 方法清单 " +
                        clazz.declaredMethods.take(60).joinToString { it.name }
                )
            }
        }
        XplerLog.i("AutoPlay: 共挂钩 $total 个播放回调")
    }

    private fun classify(name: String): String? {
        val lower = name.lowercase()
        return when {
            lower.contains("progress") -> KIND_PROGRESS
            lower.contains("complet") || lower.contains("finish") ||
                lower.contains("onplayend") || lower.contains("playover") -> KIND_COMPLETE
            else -> null
        }
    }

    companion object {
        const val KIND_PROGRESS = "progress"
        const val KIND_COMPLETE = "complete"

        val CANDIDATES = listOf(
            "com.ss.android.ugc.aweme.feed.controller.PlayerController",
            "com.ss.android.ugc.aweme.player.sdk.impl.SimplifyPlayerImpl",
            "com.ss.android.ugc.aweme.player.sdk.impl.VideoEnginePlayerImpl",
            "com.ss.ttvideoengine.TTVideoEngine",
        )
    }
}

object AutoPlayEngine {
    private const val TAG = "AutoPlay"

    /** 两次翻页之间的最小间隔, 避免回调抖动导致连续翻页 */
    private const val ADVANCE_INTERVAL_MS = 1_500L

    private val handler = Handler(Looper.getMainLooper())

    private var activityRef: WeakReference<Activity>? = null

    private var lastAid: String? = null
    private var lastPosition = 0L
    private var lastDuration = 0L
    private var armed = false
    private var endStallSince = 0L
    private var lastAdvanceAt = 0L
    private var lastSwipeAt = 0L
    private var swiping = false

    private var lastSampleLogAt = 0L
    private var lastFailLogAt = 0L

    fun onActivityResumed(activity: Activity) {
        activityRef = WeakReference(activity)
        XplerLog.i("$TAG: 前台 Activity ${activity.javaClass.name}")
    }

    // ------------------------------------------------------------------ 回调入口

    fun onPlayerCallback(kind: String, method: Method, args: Array<Any?>) {
        if (!ConfigV1.get().isAutoPlay) return

        if (kind == HAutoPlay.KIND_COMPLETE) {
            logThrottled(
                1_000L,
                "$TAG: 播放完成回调 ${method.name} args=${args.joinToString()}"
            )
            onCompleted("完成回调 ${method.name}")
            return
        }

        val parsed = parseProgress(args) ?: return
        onProgress(parsed.first, parsed.second, parsed.third)
    }

    /**
     * 进度实参解析: 抖音不同版本可能传 (aid, current, duration) / (current, duration) /
     * (progressRatio, current, duration), 统一归一成 (aid, currentMs, durationMs)。
     */
    private fun parseProgress(args: Array<Any?>): Triple<String?, Long, Long>? {
        val numbers = args.mapNotNull { (it as? Number)?.toDouble() }
        if (numbers.size < 2) return null
        val aid = args.firstOrNull { it is String } as? String

        var current: Double
        var duration: Double
        if (numbers.size >= 3 && numbers[0] in 0.0..1.5 && numbers[1] > 1_000.0) {
            // (ratio, current, duration)
            current = numbers[1]
            duration = numbers[2]
        } else {
            current = numbers[0]
            duration = numbers[1]
        }
        if (current > duration) {
            val swap = current
            current = duration
            duration = swap
        }
        if (duration < 1_000.0) return null
        return Triple(aid, current.toLong(), duration.toLong())
    }

    // ------------------------------------------------------------------ 判定

    private fun onCompleted(reason: String) {
        if (advance(reason)) {
            armed = false
            lastPosition = 0L
            endStallSince = 0L
        }
    }

    @SuppressLint("DefaultLocale")
    @Synchronized
    fun onProgress(rawAid: String?, rawCurrent: Long, rawDuration: Long) {
        var current = rawCurrent
        var duration = rawDuration
        if (current > duration) {
            val swap = current
            current = duration
            duration = swap
        }
        if (current < 0L || duration < 1_000L) {
            reset()
            return
        }

        val now = SystemClock.uptimeMillis()
        val startWindow = (duration / 20).coerceIn(100L, 500L)
        val endWindow = (duration / 20).coerceIn(250L, 750L)

        // 换到下一条视频(或时长变化)时重置基线
        if (rawAid != lastAid || duration != lastDuration) {
            lastAid = rawAid
            lastDuration = duration
            lastPosition = current
            endStallSince = 0L
            armed = current <= startWindow
            logThrottled(
                1_000L,
                "$TAG: 切换视频 aid=$rawAid $current/$duration armed=$armed"
            )
            return
        }

        logThrottled(2_000L, "$TAG: 采样 $current/$duration armed=$armed")

        // 1. 循环播放: 上一步在片尾, 这一步回到片头 = 本条播放结束
        if (armed && lastPosition >= duration - endWindow && current <= startWindow) {
            onCompleted("循环回到片头 $current/$duration")
            return
        }

        // 2. 非循环播放: 进度长时间停在片尾
        if (current >= duration - 500L) {
            if (endStallSince == 0L) endStallSince = now
            if (armed && now - endStallSince >= 800L) {
                onCompleted("停在片尾 $current/$duration")
                return
            }
        } else {
            endStallSince = 0L
        }

        // 播放回到片头(新一轮循环)或进度突然回退时重新布防
        if (current <= startWindow || current + 1_000L < lastPosition) {
            armed = true
        }
        lastPosition = current
    }

    private fun reset() {
        lastAid = null
        lastPosition = 0L
        lastDuration = 0L
        armed = false
        endStallSince = 0L
    }

    // ------------------------------------------------------------------ 翻页

    @SuppressLint("DefaultLocale")
    private fun advance(reason: String): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { performAdvance(reason) }
            return true
        }
        return performAdvance(reason)
    }

    @SuppressLint("DefaultLocale")
    private fun performAdvance(reason: String): Boolean {
        val now = SystemClock.uptimeMillis()
        if (swiping || now - lastSwipeAt < ADVANCE_INTERVAL_MS) {
            logFail("翻页冷却中")
            return false
        }

        val activity = currentActivity()
        if (activity == null) {
            logFail("拿不到前台 Activity")
            return false
        }
        val decor = runCatching { activity.window?.decorView }.getOrNull()
        if (decor == null) {
            logFail("拿不到 decorView")
            return false
        }

        var target: View = findPager(decor) ?: decor
        if (!target.isAttachedToWindow || target.width <= 0 || target.height <= 0) {
            target = decor
        }
        if (!target.isAttachedToWindow || target.width <= 0 || target.height <= 0) {
            logFail("目标视图不可用 ${target.javaClass.simpleName} ${target.width}x${target.height}")
            return false
        }

        swiping = true
        lastSwipeAt = now
        lastAdvanceAt = now
        endStallSince = 0L
        XplerLog.i(
            "$TAG: 切换到下一条($reason) target=${target.javaClass.simpleName} " +
                "${target.width}x${target.height} activity=${activity.javaClass.simpleName}"
        )

        val width = target.width.toFloat()
        val height = target.height.toFloat()
        val x = width * 0.5f
        val startY = height * 0.72f
        val endY = height * 0.24f
        val downTime = SystemClock.uptimeMillis()

        fun dispatch(action: Int, eventTime: Long, y: Float) {
            if (!ConfigV1.get().isAutoPlay) return
            val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                target.dispatchTouchEvent(event)
            } catch (t: Throwable) {
                XplerLog.e(t)
            } finally {
                event.recycle()
            }
        }

        runCatching {
            dispatch(MotionEvent.ACTION_DOWN, downTime, startY)
        }.onFailure {
            XplerLog.e(it)
            swiping = false
            return false
        }

        val steps = 8
        for (step in 1..steps) {
            handler.postDelayed({
                if (!ConfigV1.get().isAutoPlay) {
                    swiping = false
                    return@postDelayed
                }
                runCatching {
                    val y = startY + (endY - startY) * step / steps
                    val action =
                        if (step == steps) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
                    dispatch(action, SystemClock.uptimeMillis(), y)
                    if (step == steps) {
                        XplerLog.i("$TAG: 手势已发出(上滑)")
                    }
                }.onFailure { XplerLog.e(it) }
            }, step * 22L)
        }

        // 手势发出后留一点冷却时间, 期间不再判定播放结束
        handler.postDelayed({ swiping = false }, steps * 22L + 600L)
        // 看门狗: 万一回调丢了也不要把 swiping 永久卡住
        handler.postDelayed({ swiping = false }, 3_000L)
        return true
    }

    // ------------------------------------------------------------------ 查找

    /** 前台 Activity: 优先用 onResume 记录的, 拿不到时反射 ActivityThread */
    private fun currentActivity(): Activity? {
        activityRef?.get()?.let { activity ->
            if (!activity.isFinishing && !activity.isDestroyed) return activity
        }
        return runCatching {
            val clazz = Class.forName("android.app.ActivityThread")
            val thread = clazz.getMethod("currentActivityThread").invoke(null) ?: return null
            val field = clazz.getDeclaredField("mActivities").apply { isAccessible = true }
            val records = field.get(thread) as? Map<*, *> ?: return null
            records.values.mapNotNull { record ->
                val activityField = record?.javaClass?.getDeclaredField("activity")
                    ?.apply { isAccessible = true }
                (activityField?.get(record) as? Activity)
                    ?.takeIf { !it.isFinishing && !it.isDestroyed }
            }.lastOrNull()
        }.getOrNull()
    }

    /** 在当前界面里找信息流的 ViewPager, 广告页的 WebView 会吞手势, 直接投给 pager 更稳 */
    @SuppressLint("DefaultLocale")
    private fun findPager(root: View): View? {
        val candidates = ArrayList<View>()
        collectPagers(root, candidates)
        if (candidates.isEmpty()) {
            logFail("未找到 ViewPager, 使用 decorView")
            return null
        }
        val vertical = candidates.filter { it.height > it.width }
        val pick = (if (vertical.isNotEmpty()) vertical else candidates)
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
        logThrottled(
            2_000L,
            "$TAG: pager 候选=${candidates.joinToString { it.javaClass.simpleName }} " +
                "选用=${pick?.javaClass?.simpleName}"
        )
        return pick
    }

    private fun collectPagers(root: View, out: MutableList<View>) {
        if (root.javaClass.name.contains("ViewPager", ignoreCase = true)) {
            out.add(root)
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index) ?: continue
                collectPagers(child, out)
            }
        }
    }

    // ------------------------------------------------------------------ 日志

    private fun logThrottled(intervalMs: Long, message: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastSampleLogAt < intervalMs) return
        lastSampleLogAt = now
        XplerLog.i(message)
    }

    private fun logFail(reason: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFailLogAt < 1_000L) return
        lastFailLogAt = now
        XplerLog.i("$TAG: 跳过翻页($reason)")
    }
}
