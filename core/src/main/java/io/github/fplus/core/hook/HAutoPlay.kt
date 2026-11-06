package io.github.fplus.core.hook

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

/**
 * 自动连播（可开关，逻辑移植自 douyim/抖仙人）
 *
 * 实现分三步：
 * 1. 挂钩播放器引擎（TTVideoEngineImpl / SimplifyPlayerImpl ...）的 play/pause，
 *    抓到当前活跃的引擎实例；
 * 2. 主线程心跳轮询引擎的 getDuration() / getCurrentPlaybackTime() 得到进度与时长；
 * 3. 判定本条播完后，给信息流的竖版 ViewPager 合成一次上滑手势切到下一条。
 *
 * 为什么不用 PlayerController 的 onPlayProgressChange：
 * 实测当前抖音版本已移除该类里的 onPlaying/onResumePlay/onPausePlay/onPlayStop/
 * onPlayProgressChange，按名挂钩会全部落空；引擎侧方法才稳定存在。
 *
 * 为什么判定按引擎实例隔离：
 * 信息流会同时存在播放中与预加载的引擎，轮询会在两者之间来回跳，
 * 共用一份基线会被反复重置，导致第一条视频循环时判不出来。
 */
class HAutoPlay : BaseHook() {
    override fun setTargetClass(): Class<*> {
        return runCatching {
            findClass("com.ss.android.ugc.aweme.feed.controller.PlayerController")
        }.getOrDefault(EmptyHook::class.java)
    }

    override fun onInit() {
        runCatching { EngineHooks.install(param.classLoader) }.onFailure { XplerLog.e(it) }
        runCatching { FeedPanelTracker.install(param.classLoader) }.onFailure { XplerLog.e(it) }
        AutoPlayEngine.startHeartbeat()
    }
}

/** 引擎侧挂钩：只用 play/pause 追踪活跃引擎实例 */
private object EngineHooks {
    private const val KIND_PLAY = 0
    private const val KIND_PAUSE = 1

    private val ENGINE_CLASSES = listOf(
        "com.ss.ttvideoengine.TTVideoEngine",
        "com.ss.ttvideoengine.TTVideoEngineImpl",
        "com.ss.ttvideoengine.TTVideoEngineImplV2",
        "com.ss.android.ugc.aweme.player.sdk.impl.SimplifyPlayerImpl",
    )

    fun install(classLoader: ClassLoader) {
        for (className in ENGINE_CLASSES) {
            val clazz = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
                ?: continue
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
                    else -> continue
                }
                val ok = runCatching {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(p: MethodHookParam) {
                            val engine = p.thisObject ?: return
                            if (kind == KIND_PLAY) {
                                EngineTracker.onPlayed(engine)
                            } else {
                                EngineTracker.remember(engine)
                            }
                        }
                    })
                }.isSuccess
                if (ok) hooked++
            }
            if (hooked > 0) {
                XplerLog.i("AutoPlay: ${clazz.simpleName} 挂钩 $hooked 个方法")
            }
        }
    }

    private fun isPlay(name: String, method: Method): Boolean =
        method.parameterCount == 0 &&
            (name == "play" || name == "start" || name == "resume" || name == "startplay")

    private fun isPause(name: String, method: Method): Boolean =
        method.parameterCount == 0 &&
            (name.startsWith("pause") || name == "stop" || name == "release" ||
                name == "releaseasync")
}

/** 记录见过的引擎实例，把"最近在播"的排在前面供轮询 */
private object EngineTracker {
    private val engines = Collections.synchronizedList(ArrayList<WeakReference<Any>>(4))

    @Volatile
    private var activeRef: WeakReference<Any>? = null

    fun onPlayed(engine: Any) {
        activeRef = WeakReference(engine)
        remember(engine)
    }

    fun remember(engine: Any) {
        synchronized(engines) {
            if (engines.none { it.get() === engine }) {
                engines.add(WeakReference(engine))
                // 预加载引擎会不断登记, 淘汰时跳过正在播放的那个
                while (engines.size > 8) {
                    val index = engines.indexOfFirst {
                        val candidate = it.get()
                        candidate == null || candidate !== activeRef?.get()
                    }
                    if (index < 0) break
                    engines.removeAt(index)
                }
            }
        }
    }

    /** 候选引擎：最近在播的优先，其次按记录顺序倒序 */
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
}

/**
 * 信息流面板追踪：从 BaseListFragmentPanel 实例的 public 字段 f 取竖版 ViewPager，
 * 与 douyim 的 FeedContentTracker.currentPager 同一路径。
 * 字段不存在（版本混淆改名）时不做任何挂钩，交给 ViewPager 类名回退。
 */
private object FeedPanelTracker {
    private const val PANEL_CLASS = "com.ss.android.ugc.aweme.feed.panel.BaseListFragmentPanel"

    private var panelRef: WeakReference<Any>? = null
    private var pagerField: java.lang.reflect.Field? = null

    fun install(classLoader: ClassLoader) {
        val clazz = runCatching {
            Class.forName(PANEL_CLASS, false, classLoader)
        }.getOrNull() ?: return
        val field = runCatching { clazz.getField("f") }.getOrNull() ?: return
        pagerField = field

        runCatching {
            XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    p.thisObject?.let { panelRef = WeakReference(it) }
                }
            })
        }.onFailure { XplerLog.e(it) }

        // 页面切换回调（混淆名 y0(int)），顺带刷新 panel 引用
        runCatching { clazz.getDeclaredMethod("y0", Int::class.javaPrimitiveType) }.getOrNull()
            ?.let { method ->
                runCatching {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(p: MethodHookParam) {
                            p.thisObject?.let { panelRef = WeakReference(it) }
                        }
                    })
                }.onFailure { XplerLog.e(it) }
            }
    }

    /** 当前信息流的 pager，取不到返回 null */
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

/** 播放进度轮询 + 播完判定 + 翻页 */
object AutoPlayEngine {
    private const val TAG = "AutoPlay"

    /** 两次翻页之间的最小间隔，避免回调抖动导致连续翻页 */
    private const val ADVANCE_INTERVAL_MS = 1_500L

    /** 轮询播放进度的心跳间隔 */
    private const val HEARTBEAT_MS = 500L

    /** 引擎状态多久没更新就丢弃 */
    private const val STATE_TTL_MS = 30_000L

    /** 手势：8 步、每步 22ms，x 取中线，y 从 72% 到 24% */
    private const val SWIPE_STEPS = 8
    private const val SWIPE_STEP_MS = 22L
    private const val SWIPE_START_RATIO = 0.72f
    private const val SWIPE_END_RATIO = 0.24f

    private val handler = Handler(Looper.getMainLooper())

    private var activityRef: WeakReference<Activity>? = null

    private var lastSwipeAt = 0L
    private var swiping = false

    private var sampling = false
    private var failLogAt = 0L
    private var retryCount = 0
    private var retryToken = 0
    private var lastCompletedAt = 0L

    /** 反射方法缓存 */
    private val methodCache = HashMap<String, Method?>()

    /**
     * 每条视频的判定状态，按引擎实例隔离。
     * 抖音会同时存在多个引擎（预加载/复用），共用一份状态会被来回重置。
     */
    private data class EngineState(
        var duration: Long = 0L,
        var lastPosition: Long = -1L,
        var maxPosition: Long = 0L,
        var endStallSince: Long = 0L,
        var lastSeen: Long = 0L,
    )

    private val states = java.util.IdentityHashMap<Any, EngineState>()

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
    }

    fun onActivityResumed(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    /** 轮询候选引擎的位置/时长，喂给判定逻辑 */
    private fun sampleEngine() {
        if (sampling) return
        val activity = activityRef?.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        sampling = true
        try {
            for (engine in EngineTracker.candidates()) {
                val duration = invokeLong(engine, "getDuration", "getVideoDuration")
                val position = invokeLong(
                    engine, "getCurrentPlaybackTime", "getCurrentPosition", "getPlaybackTime"
                )
                if (duration == null || position == null || duration <= 0L || position < 0L) {
                    continue
                }
                onEngineProgress(engine, position, duration)
            }
            cleanupStates()
        } finally {
            sampling = false
        }
    }

    private fun cleanupStates() {
        val now = SystemClock.uptimeMillis()
        val iterator = states.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key == null || now - entry.value.lastSeen > STATE_TTL_MS) {
                iterator.remove()
            }
        }
    }

    private fun invokeLong(target: Any, vararg names: String): Long? {
        val clazz = target.javaClass
        for (name in names) {
            val cacheKey = clazz.name + '#' + name
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

    // ------------------------------------------------------------------ 判定

    private fun onCompleted(reason: String) {
        if (advance(reason)) {
            states.clear()
            retryCount = 0
            return
        }
        // 同一段播完只补一次；距离上次完成超过 5 秒视为新的一次
        val now = SystemClock.uptimeMillis()
        if (now - lastCompletedAt > 5_000L) retryCount = 0
        lastCompletedAt = now
        retryAdvance(reason)
    }

    @Synchronized
    private fun onEngineProgress(engine: Any, rawCurrent: Long, rawDuration: Long) {
        var current = rawCurrent
        var duration = rawDuration
        if (current > duration) {
            val swap = current
            current = duration
            duration = swap
        }
        if (current < 0L || duration < 1_000L) return

        val now = SystemClock.uptimeMillis()
        val state = states[engine] ?: EngineState().also { states[engine] = it }
        state.lastSeen = now

        // 换了一条视频（时长变化）：重新记录基线
        if (duration != state.duration) {
            state.duration = duration
            state.lastPosition = current
            state.maxPosition = current
            state.endStallSince = 0L
            return
        }

        if (current > state.maxPosition) state.maxPosition = current

        // 1. 循环播放：上一次采样还在片尾附近，这一次已经回到片头 = 本条播完。
        //    不依赖"布防标记"，所以视频从哪一秒开始被采样都能在第一次循环命中。
        if (state.lastPosition >= duration - 1_500L && current <= 1_500L) {
            onCompleted("循环回到片头 $current/$duration max=${state.maxPosition}")
            return
        }

        // 2. 非循环播放：播到片尾后停住不动
        if (current >= duration - 500L && state.maxPosition >= duration - 1_500L) {
            if (state.endStallSince == 0L) state.endStallSince = now
            if (now - state.endStallSince >= 800L) {
                onCompleted("停在片尾 $current/$duration")
                return
            }
        } else {
            state.endStallSince = 0L
        }

        state.lastPosition = current
    }

    /** 翻页没成功时(冷却/目标未就绪)重试一次, 避免这一条就此丢掉 */
    private fun retryAdvance(reason: String) {
        if (retryCount >= 1) return
        retryCount++
        val token = ++retryToken
        handler.postDelayed({
            if (token == retryToken && advance(reason)) {
                states.clear()
                retryCount = 0
            }
        }, 1_200L)
    }

    // ------------------------------------------------------------------ 翻页

    private fun advance(reason: String): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { performAdvance(reason) }
            return true
        }
        return performAdvance(reason)
    }

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

        // 只认信息流的竖版 pager：根容器 MainScrollableViewPager 一旦被翻动，
        // 整页会从"朋友"切到"我"，表现出来就是莫名进入作者作品页。
        val target = FeedPanelTracker.pagerFor(decor) ?: findFeedPager(decor)
        if (target == null) {
            logFail("未找到信息流 ViewPager")
            return false
        }

        swiping = true
        lastSwipeAt = now
        XplerLog.i("$TAG: 切换到下一条($reason) target=${target.javaClass.simpleName}")

        val beforeItem = pagerItem(target)
        val x = target.width.toFloat() * 0.5f
        val startY = target.height.toFloat() * SWIPE_START_RATIO
        val endY = target.height.toFloat() * SWIPE_END_RATIO
        val downTime = SystemClock.uptimeMillis()

        fun dispatch(action: Int, eventTime: Long, y: Float) {
            val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                target.dispatchTouchEvent(event)
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

        for (step in 1..SWIPE_STEPS) {
            handler.postDelayed({
                if (!ConfigV1.get().isAutoPlay) {
                    swiping = false
                    return@postDelayed
                }
                runCatching {
                    val y = startY + (endY - startY) * step / SWIPE_STEPS
                    val action =
                        if (step == SWIPE_STEPS) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
                    dispatch(action, SystemClock.uptimeMillis(), y)
                }.onFailure { XplerLog.e(it) }
            }, step * SWIPE_STEP_MS)
        }

        // 手势没被 pager 接收时，用 setCurrentItem 兜底（只作用于信息流 pager）
        handler.postDelayed({
            val afterItem = pagerItem(target)
            if (beforeItem != null && afterItem != null && afterItem == beforeItem) {
                val next = beforeItem + 1
                val ok = pagerSetItem(target, next)
                XplerLog.i("$TAG: 手势未生效, setCurrentItem($beforeItem -> $next) 结果=$ok")
            }
        }, SWIPE_STEPS * SWIPE_STEP_MS + 600L)

        handler.postDelayed({ swiping = false }, SWIPE_STEPS * SWIPE_STEP_MS + 600L)
        return true
    }

    // ------------------------------------------------------------------ 查找

    /** 前台 Activity：优先用 onResume 记录的，拿不到时反射 ActivityThread */
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

    /** 信息流的竖版 ViewPager：优先 VerticalViewPager，其次竖向且接近满屏的非根 pager */
    private fun findFeedPager(root: View): View? {
        val candidates = ArrayList<View>(4)
        collectPagers(root, candidates)
        if (candidates.isEmpty()) return null

        candidates.firstOrNull {
            it.javaClass.name.contains("VerticalViewPager", ignoreCase = true)
        }?.let { return it }

        val rootHeight = if (root.height > 0) root.height else 1
        return candidates.firstOrNull { view ->
            val name = view.javaClass.simpleName
            !name.contains("MainScroll") &&
                !name.contains("MainFlippable") &&
                !name.contains("TabContainer") &&
                view.height > view.width &&
                view.height >= rootHeight * 3 / 4
        }
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
        if (now - failLogAt < 30_000L) {
            failLogAt = now
            XplerLog.i("$TAG: 跳过翻页($reason)")
        }
    }
}
