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
import java.util.Collections
import java.util.WeakHashMap

/**
 * 自动连播(自动播放下一条)
 *
 * 逻辑移植自 douyim(抖仙人):
 * 1. 挂钩播放器引擎(TTVideoEngine / SimplifyPlayerImpl ...)的 play/pause/setListener/completion,
 *    抓到当前活跃的引擎实例与完成回调;
 * 2. 心跳轮询引擎的 getDuration()/getCurrentPlaybackTime() 得到 进度/时长;
 * 3. 播完(完成回调, 或循环回到片头, 或停在片尾)后, 给信息流 ViewPager 合成上滑手势切下一条。
 *
 * 为什么不再用 PlayerController:
 * 实测(2026-11 版本) PlayerController 里已经没有 onPlaying/onResumePlay/onPausePlay/onPlayStop/
 * onPlayProgressChange, 按名挂钩会全部落空; 引擎侧方法才稳定存在。
 */
class HAutoPlay : BaseHook() {
    override fun setTargetClass(): Class<*> {
        return runCatching {
            findClass("com.ss.android.ugc.aweme.feed.controller.PlayerController")
        }.getOrDefault(EmptyHook::class.java)
    }

    override fun onInit() {
        XplerLog.i("AutoPlay: 初始化 v3 开关=${ConfigV1.get().isAutoPlay}")
        runCatching { EngineHooks.install(param.classLoader) }.onFailure { XplerLog.e(it) }
        runCatching { FeedPanelTracker.install(param.classLoader) }.onFailure { XplerLog.e(it) }
        AutoPlayEngine.startHeartbeat()
    }
}

/** 引擎侧挂钩: 抓引擎实例、抓完成回调 */
object EngineHooks {
    private const val TAG = "AutoPlay"

    private val ENGINE_CLASSES = listOf(
        "com.ss.ttvideoengine.TTVideoEngine",
        "com.ss.ttvideoengine.TTVideoEngineImpl",
        "com.ss.ttvideoengine.TTVideoEngineImplV2",
        "com.ss.android.ugc.aweme.player.sdk.impl.SimplifyPlayerImpl",
    )

    private val hookedListenerClasses =
        Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())

    fun install(classLoader: ClassLoader) {
        var total = 0
        for (className in ENGINE_CLASSES) {
            val clazz = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
            if (clazz == null) {
                XplerLog.i("$TAG: 引擎类不存在 $className")
                continue
            }
            var hooked = 0
            for (method in clazz.declaredMethods) {
                if (Modifier.isAbstract(method.modifiers) ||
                    Modifier.isStatic(method.modifiers) ||
                    Modifier.isNative(method.modifiers)
                ) continue
                val lower = method.name.lowercase()
                val kind = when {
                    isPlay(lower, method) -> KIND_PLAY
                    isPause(lower, method) -> KIND_PAUSE
                    lower.contains("completion") || lower.contains("completed") -> KIND_COMPLETE
                    lower.startsWith("set") && lower.contains("listener") -> KIND_LISTENER
                    else -> null
                } ?: continue

                runCatching {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(p: MethodHookParam) {
                            runCatching {
                                val self = p.thisObject
                                when (kind) {
                                    KIND_PLAY -> if (self != null) EngineTracker.onPlayed(self)
                                    KIND_PAUSE -> if (self != null) EngineTracker.remember(self)
                                    KIND_COMPLETE -> AutoPlayEngine.onCompletionCallback(
                                        "${clazz.simpleName}#${method.name}"
                                    )
                                    KIND_LISTENER -> {
                                        if (self != null) EngineTracker.remember(self)
                                        for (arg in p.args) {
                                            if (arg != null) hookListener(arg, method.name)
                                        }
                                    }
                                    else -> Unit
                                }
                            }.onFailure { XplerLog.e(it) }
                        }
                    })
                    hooked++
                    XplerLog.i(
                        "$TAG: 引擎挂钩 ${clazz.simpleName}#${method.name}(" +
                            method.parameterTypes.joinToString(",") { it.simpleName } +
                            ") 类型=$kind"
                    )
                }.onFailure { XplerLog.e(it) }
            }
            total += hooked
            XplerLog.i("$TAG: ${clazz.simpleName} 引擎挂钩 $hooked 个方法")
            if (hooked == 0) {
                XplerLog.i(
                    "$TAG: ${clazz.simpleName} 方法清单 " +
                        clazz.declaredMethods.take(80).joinToString { it.name }
                )
            }
        }
        XplerLog.i("$TAG: 引擎挂钩合计 $total 个方法")
    }

    private fun isPlay(name: String, method: Method): Boolean =
        method.parameterCount == 0 &&
            (name == "play" || name == "start" || name == "resume" || name == "startplay")

    private fun isPause(name: String, method: Method): Boolean =
        method.parameterCount == 0 &&
            (name.startsWith("pause") || name == "stop" || name == "release" ||
                name == "releaseasync")

    /** 引擎设置 listener 的时候, 顺手把 listener 的 onCompletion 挂上 */
    private fun hookListener(listener: Any, from: String) {
        val type = listener.javaClass
        synchronized(hookedListenerClasses) {
            if (!hookedListenerClasses.add(type)) return
        }
        var hooked = 0
        for (method in type.methods) {
            if (Modifier.isAbstract(method.modifiers) || Modifier.isNative(method.modifiers)) continue
            val lower = method.name.lowercase()
            if (lower != "oncompletion" && lower != "oncompleted") continue
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        runCatching {
                            AutoPlayEngine.onCompletionCallback("listener#${method.name}")
                        }.onFailure { XplerLog.e(it) }
                    }
                })
                hooked++
                XplerLog.i("$TAG: listener 挂钩 ${type.name}#${method.name} (来自 $from)")
            }.onFailure { XplerLog.e(it) }
        }
        XplerLog.i("$TAG: listener ${type.name} 挂钩 $hooked 个完成回调")
    }

    const val KIND_PLAY = "play"
    const val KIND_PAUSE = "pause"
    const val KIND_COMPLETE = "complete"
    const val KIND_LISTENER = "listener"
}

/** 记录见过的引擎实例, 选出"最近在播"的那一个用于轮询 */
object EngineTracker {
    private const val TAG = "AutoPlay"

    private val engines = Collections.synchronizedList(ArrayList<WeakReference<Any>>())

    @Volatile
    private var activeRef: WeakReference<Any>? = null

    private var lastLogAt = 0L

    fun onPlayed(engine: Any) {
        activeRef = WeakReference(engine)
        remember(engine)
        val now = SystemClock.uptimeMillis()
        if (now - lastLogAt >= 2_000L) {
            lastLogAt = now
            XplerLog.i("$TAG: 引擎开始播放 ${engine.javaClass.name}")
        }
    }

    fun remember(engine: Any) {
        synchronized(engines) {
            if (engines.none { it.get() === engine }) {
                engines.add(WeakReference(engine))
                while (engines.size > 4) engines.removeAt(0)
            }
        }
    }

    fun active(): Any? = candidates().firstOrNull()

    /** 候选引擎: 最近在播的优先, 其次按记录顺序倒序 */
    fun candidates(): List<Any> {
        val result = ArrayList<Any>(4)
        activeRef?.get()?.let { result.add(it) }
        synchronized(engines) {
            for (index in engines.indices.reversed()) {
                val engine = engines[index].get() ?: continue
                if (result.none { it === engine }) result.add(engine)
            }
        }
        return result
    }

    fun forget(engine: Any) {
        synchronized(engines) {
            engines.removeAll { it.get() == null || it.get() === engine }
        }
        if (activeRef?.get() === engine) activeRef = null
    }
}

/**
 * 信息流面板追踪: 从 BaseListFragmentPanel 实例的字段 f 取竖版 ViewPager
 * (douyim 的 FeedContentTracker.currentPager 就是这么拿的)。
 * 直接扫 ViewPager 容易选到底部 Tab 之类错误的 pager, 触摸投过去会被当成点击。
 */
object FeedPanelTracker {
    private const val TAG = "AutoPlay"
    private const val PANEL_CLASS = "com.ss.android.ugc.aweme.feed.panel.BaseListFragmentPanel"

    private var panelRef: WeakReference<Any>? = null
    private var pagerField: java.lang.reflect.Field? = null
    private var logged = false

    fun install(classLoader: ClassLoader) {
        val clazz = runCatching {
            Class.forName(PANEL_CLASS, false, classLoader)
        }.getOrNull()
        if (clazz == null) {
            XplerLog.i("$TAG: 信息流 panel 类不存在 $PANEL_CLASS")
            return
        }
        pagerField = runCatching { clazz.getField("f") }.getOrNull()
        XplerLog.i(
            "$TAG: panel 类已找到, 字段 f=${pagerField != null} " +
                "(${pagerField?.type?.name})"
        )

        runCatching {
            XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    runCatching {
                        p.thisObject?.let {
                            panelRef = WeakReference(it)
                            if (!logged) {
                                logged = true
                                XplerLog.i("$TAG: 抓到信息流 panel ${it.javaClass.name}")
                            }
                        }
                    }.onFailure { XplerLog.e(it) }
                }
            })
        }.onFailure { XplerLog.e(it) }

        // 页面切换回调(混淆名 y0(int)), 顺带刷新 panel 引用
        for (name in listOf("y0", "y1")) {
            val method = runCatching {
                clazz.getDeclaredMethod(name, Int::class.javaPrimitiveType)
            }.getOrNull() ?: continue
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        p.thisObject?.let { panelRef = WeakReference(it) }
                    }
                })
                XplerLog.i("$TAG: panel 挂钩 ${method.name}(int) 页面切换回调")
            }.onFailure { XplerLog.e(it) }
        }
    }

    /** 当前信息流所在的 pager, 取不到返回 null */
    fun pagerFor(decor: View): View? {
        val panel = panelRef?.get() ?: return null
        val field = pagerField ?: return null
        val pager = runCatching { field.get(panel) }.getOrNull() as? View ?: return null
        if (!pager.isAttachedToWindow) return null
        if (pager.rootView !== decor) return null
        if (pager.width <= 0 || pager.height <= 0) return null
        return pager
    }
}

object AutoPlayEngine {
    private const val TAG = "AutoPlay"

    /** 两次翻页之间的最小间隔, 避免回调抖动导致连续翻页 */
    private const val ADVANCE_INTERVAL_MS = 1_500L

    /** 轮询播放进度的心跳间隔 */
    private const val HEARTBEAT_MS = 400L

    private val handler = Handler(Looper.getMainLooper())

    private var activityRef: WeakReference<Activity>? = null

    private var lastAid: String? = null
    private var lastPosition = 0L
    private var lastDuration = 0L
    private var maxPosition = 0L
    private var armed = false
    private var endStallSince = 0L
    private var lastAdvanceAt = 0L
    private var lastSwipeAt = 0L
    private var swiping = false

    private var sampling = false
    private var sampleLogAt = 0L
    private var callbackLogAt = 0L
    private var pagerLogAt = 0L
    private var failLogAt = 0L
    private var probeLogAt = 0L
    private var probeLogged = false

    /** 反射方法缓存 */
    private val methodCache = HashMap<String, Method?>()

    // ------------------------------------------------------------------ 心跳

    private val heartbeat = object : Runnable {
        override fun run() {
            if (ConfigV1.get().isAutoPlay) {
                runCatching { sampleEngine() }.onFailure { XplerLog.e(it) }
            }
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    fun startHeartbeat() {
        handler.removeCallbacks(heartbeat)
        handler.post(heartbeat)
        XplerLog.i("$TAG: 进度心跳已启动(${HEARTBEAT_MS}ms)")
    }

    fun onActivityResumed(activity: Activity) {
        activityRef = WeakReference(activity)
        XplerLog.i("$TAG: 前台 Activity ${activity.javaClass.name}")
    }

    /** 轮询当前引擎的 位置/时长, 喂给判定逻辑 */
    @SuppressLint("DefaultLocale")
    private fun sampleEngine() {
        if (sampling) return
        sampling = true
        try {
            val engines = EngineTracker.candidates()
            if (engines.isEmpty()) {
                val now = SystemClock.uptimeMillis()
                if (now - probeLogAt >= 3_000L) {
                    probeLogAt = now
                    XplerLog.i("$TAG: 还没有抓到引擎实例")
                }
                return
            }
            var lastDiagnosed: Any? = null
            for (engine in engines) {
                val duration = invokeLong(engine, "getDuration", "getVideoDuration")
                val position = invokeLong(
                    engine, "getCurrentPlaybackTime", "getCurrentPosition", "getPlaybackTime"
                )
                if (duration == null || position == null || duration <= 0L || position < 0L) {
                    lastDiagnosed = engine
                    continue
                }
                if (!probeLogged) {
                    probeLogged = true
                    XplerLog.i(
                        "$TAG: 引擎轮询正常 ${engine.javaClass.simpleName} $position/$duration"
                    )
                }
                onProgress(null, position, duration)
                return
            }

            // 一个都取不到: 打一次方法清单, 方便按真实 API 修正
            val now = SystemClock.uptimeMillis()
            if (now - probeLogAt >= 3_000L) {
                probeLogAt = now
                val engine = lastDiagnosed
                if (engine == null) {
                    XplerLog.i("$TAG: 候选引擎都取不到有效时长/位置 ${engines.size} 个")
                } else if (!probeLogged) {
                    probeLogged = true
                    val getters = engine.javaClass.declaredMethods
                        .filter {
                            val n = it.name.lowercase()
                            n.contains("position") || n.contains("duration") ||
                                n.contains("progress") || n.contains("time")
                        }
                        .take(40)
                        .joinToString { "${it.name}(${it.parameterTypes.joinToString(",") { p -> p.simpleName }})" }
                    XplerLog.i(
                        "$TAG: ${engine.javaClass.simpleName} 取不到位置/时长, 相关方法=[$getters]"
                    )
                }
            }
        } finally {
            sampling = false
        }
    }

    private fun invokeLong(target: Any, vararg names: String): Long? {
        val clazz = target.javaClass
        for (name in names) {
            val cacheKey = "${clazz.name}#$name"
            val method = if (methodCache.containsKey(cacheKey)) {
                methodCache[cacheKey]
            } else {
                val found = runCatching {
                    generateSequence<Class<*>>(clazz) { it.superclass }
                        .mapNotNull { c -> runCatching { c.getDeclaredMethod(name) }.getOrNull() }
                        .firstOrNull()
                }.getOrNull()
                methodCache[cacheKey] = found
                found
            } ?: continue
            val value = runCatching {
                method.isAccessible = true
                method.invoke(target)
            }.getOrNull() ?: continue
            return (value as? Number)?.toLong()
        }
        return null
    }

    // ------------------------------------------------------------------ 回调入口

    fun onCompletionCallback(source: String) {
        val now = SystemClock.uptimeMillis()
        if (now - callbackLogAt >= 1_000L) {
            callbackLogAt = now
            XplerLog.i("$TAG: 播放完成回调 $source")
        }
        if (!ConfigV1.get().isAutoPlay) return
        onCompleted("完成回调 $source")
    }

    private fun onCompleted(reason: String) {
        if (advance(reason)) {
            armed = false
            lastPosition = 0L
            endStallSince = 0L
        }
    }

    // ------------------------------------------------------------------ 判定

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
            maxPosition = current
            endStallSince = 0L
            armed = current <= startWindow
            return
        }

        val nowForLog = SystemClock.uptimeMillis()
        if (nowForLog - sampleLogAt >= 2_000L) {
            sampleLogAt = nowForLog
            XplerLog.i("$TAG: 采样 $current/$duration max=$maxPosition armed=$armed")
        }

        if (current > maxPosition) maxPosition = current

        // 1. 循环播放: 进度曾经到达片尾附近, 现在大幅回退(回到片头) = 本条播放结束
        //    轮询间隔 400ms 可能正好跨过片尾窗口, 所以用"历史最远位置 + 大幅回退"判定,
        //    而不是要求"上一次采样就在片尾"
        val reachedEnd = maxPosition >= duration - 1_500L ||
            lastPosition >= duration - endWindow
        if (armed && reachedEnd && current + 1_000L < lastPosition) {
            onCompleted("循环回到片头 $current/$duration max=$maxPosition")
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

    /** 翻页后重置基线, 等下一次"接近片头"的采样重新布防 */
    fun resetBaseline() {
        lastPosition = 0L
        lastDuration = 0L
        maxPosition = 0L
        armed = false
        endStallSince = 0L
    }

    private fun reset() {
        lastAid = null
        lastPosition = 0L
        lastDuration = 0L
        maxPosition = 0L
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

        val panelPager = FeedPanelTracker.pagerFor(decor)
        var target: View = panelPager ?: findPager(decor) ?: decor
        if (!target.isAttachedToWindow || target.width <= 0 || target.height <= 0) {
            target = decor
        }
        if (panelPager == null) {
            XplerLog.i("$TAG: 未取到信息流 panel 的 pager, 回退扫描结果")
        }
        if (!target.isAttachedToWindow || target.width <= 0 || target.height <= 0) {
            logFail("目标视图不可用 ${target.javaClass.simpleName} ${target.width}x${target.height}")
            return false
        }

        swiping = true
        lastSwipeAt = now
        lastAdvanceAt = now
        endStallSince = 0L
        val beforeItem = pagerItem(target)
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
                        resetBaseline()
                    }
                }.onFailure { XplerLog.e(it) }
            }, step * 22L)
        }

        // 手势没生效时, 直接 setCurrentItem 兜底
        handler.postDelayed({
            val afterItem = pagerItem(target)
            if (beforeItem != null && afterItem != null && afterItem == beforeItem) {
                val next = beforeItem + 1
                val ok = pagerSetItem(target, next)
                XplerLog.i(
                    "$TAG: 手势未生效, setCurrentItem($beforeItem -> $next) 结果=$ok"
                )
            }
        }, steps * 22L + 600L)

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
        val now = SystemClock.uptimeMillis()
        if (now - pagerLogAt >= 2_000L) {
            pagerLogAt = now
            XplerLog.i(
                "$TAG: pager 候选=${candidates.joinToString { it.javaClass.simpleName }} " +
                    "选用=${pick?.javaClass?.simpleName}"
            )
        }
        return pick
    }

    private fun pagerItem(view: View): Int? = runCatching {
        view.javaClass.getMethod("getCurrentItem").invoke(view) as? Int
    }.getOrNull()

    private fun pagerSetItem(view: View, index: Int): Boolean = runCatching {
        view.javaClass.getMethod(
            "setCurrentItem",
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        ).invoke(view, index, true)
        true
    }.getOrDefault(false)

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

    private fun logFail(reason: String) {
        val now = SystemClock.uptimeMillis()
        if (now - failLogAt < 1_000L) return
        failLogAt = now
        XplerLog.i("$TAG: 跳过翻页($reason)")
    }
}
