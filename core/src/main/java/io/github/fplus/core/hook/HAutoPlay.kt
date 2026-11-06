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
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.xpler.core.XplerLog
import io.github.xpler.core.entity.EmptyHook
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.proxy.MethodParam
import java.lang.ref.WeakReference

/**
 * 自动连播(自动播放下一条)
 *
 * 逻辑移植自 douyim(抖仙人) 的自动播放功能:
 * 1. 通过 PlayerController 的播放进度回调跟踪当前视频的 进度/时长;
 * 2. 进度到达片尾后循环回到片头(或非循环播放时停在片尾), 判定本条视频播放完毕;
 * 3. 给信息流的 VerticalViewPager 合成一次上滑手势, 翻到下一条视频。
 *
 * 与 douyim 原实现的差异:
 * - 进度来源改用抖音 PlayerController 回调, 不再反射 TTVideoEngine;
 * - 增加开关(ConfigV1.isAutoPlay), 关闭时不产生任何手势;
 * - 翻页目标优先取当前界面的 VerticalViewPager, 找不到时退回 decorView。
 */
class HAutoPlay : BaseHook() {
    override fun setTargetClass(): Class<*> {
        return runCatching {
            findClass("com.ss.android.ugc.aweme.feed.controller.PlayerController")
        }.getOrDefault(EmptyHook::class.java)
    }

    override fun onInit() {
        XplerLog.i("AutoPlay: 自动连播引擎已加载(hook=${targetClass.simpleName})")
    }

    @OnAfter("onPlayProgressChange")
    fun onPlayProgressChangeAfter(params: MethodParam) {
        hookBlockRunning(params) {
            val args = params.args
            if (args.size < 3) return@hookBlockRunning
            AutoPlayEngine.onProgress(args[0] as? String, args[1], args[2])
        }.onFailure {
            XplerLog.e(it)
        }
    }
}

object AutoPlayEngine {
    private const val TAG = "AutoPlay"
    private const val PAGER_CLASS = "com.ss.android.ugc.aweme.common.widget.VerticalViewPager"

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

    fun onActivityResumed(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    @SuppressLint("DefaultLocale")
    @Synchronized
    fun onProgress(rawAid: Any?, rawCurrent: Any?, rawDuration: Any?) {
        if (!ConfigV1.get().isAutoPlay) {
            reset()
            return
        }

        var current = (rawCurrent as? Number)?.toLong() ?: return
        var duration = (rawDuration as? Number)?.toLong() ?: return

        // 少数版本把 current/duration 顺序传反, 按大小关系纠正
        if (current > duration) {
            val swap = current
            current = duration
            duration = swap
        }
        if (current < 0L || duration < 1_000L) {
            reset()
            return
        }

        val aid = rawAid as? String
        val now = SystemClock.uptimeMillis()
        val startWindow = (duration / 20).coerceIn(100L, 500L)
        val endWindow = (duration / 20).coerceIn(250L, 750L)

        // 换到下一条视频(或时长变化)时重置基线
        if (aid != lastAid || duration != lastDuration) {
            lastAid = aid
            lastDuration = duration
            lastPosition = current
            endStallSince = 0L
            // 只有首帧确实在片头才布防, 避免回收的播放器残留上一条的片尾进度
            armed = current <= startWindow
            return
        }

        // 1. 循环播放: 上一步在片尾, 这一步回到片头 = 本条播放结束
        if (armed &&
            lastPosition >= duration - endWindow &&
            current <= startWindow &&
            now - lastAdvanceAt >= ADVANCE_INTERVAL_MS
        ) {
            if (advance("循环回到片头 ${current}/${duration}")) {
                armed = false
                lastPosition = current
                return
            }
        }

        // 2. 非循环播放: 进度长时间停在片尾
        if (current >= duration - 500L) {
            if (endStallSince == 0L) endStallSince = now
            if (armed &&
                now - endStallSince >= 800L &&
                now - lastAdvanceAt >= ADVANCE_INTERVAL_MS
            ) {
                if (advance("停在片尾 ${current}/${duration}")) {
                    armed = false
                    endStallSince = 0L
                    lastPosition = current
                    return
                }
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

    @SuppressLint("DefaultLocale")
    private fun advance(reason: String): Boolean {
        val now = SystemClock.uptimeMillis()
        if (swiping || now - lastSwipeAt < ADVANCE_INTERVAL_MS) {
            return false
        }

        val activity = activityRef?.get() ?: return false
        if (activity.isFinishing || activity.isDestroyed) {
            return false
        }
        val decor = runCatching { activity.window?.decorView }.getOrNull() ?: return false
        val target = findPager(decor) ?: decor
        if (!target.isAttachedToWindow || target.width <= 0 || target.height <= 0) {
            return false
        }

        swiping = true
        lastSwipeAt = now
        lastAdvanceAt = now
        endStallSince = 0L
        XplerLog.i(
            "$TAG: 切换到下一条($reason) target=${target.javaClass.simpleName} " +
                "${target.width}x${target.height}"
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
                }.onFailure { XplerLog.e(it) }
            }, step * 22L)
        }

        // 手势发出后留一点冷却时间, 期间不再判定播放结束
        handler.postDelayed({ swiping = false }, steps * 22L + 600L)
        return true
    }

    /** 在当前界面里找信息流的 VerticalViewPager, 广告页的 WebView 会吞手势, 直接投给 pager 更稳 */
    private fun findPager(root: View): View? {
        if (root.javaClass.name == PAGER_CLASS) {
            return root
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index) ?: continue
                findPager(child)?.let { return it }
            }
        }
        return null
    }
}
