package io.github.fplus.core.helper

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.graphics.Color
import android.os.CountDownTimer
import android.os.Process
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.freegang.ktutils.app.KActivityUtils
import com.freegang.ktutils.app.KAppUtils
import com.freegang.ktutils.log.KLogCat
import com.tencent.mmkv.MMKV
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.ui.activity.FreedomSettingActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * 自律功能: 限制抖音每日/每小时使用时长, 超限拦截
 * - 每日/每小时可分别设置分钟数, 0=不限
 * - 拦截方式: 0=全屏遮罩(显示解锁倒计时), 1=直接退出
 * - 使用时长按日/小时分段累计并持久化到MMKV, 进程重启不丢
 */
class SelfDisciplineHelper(private val app: Application) {

    private val config: ConfigV1
        get() = ConfigV1.get()

    private val mmkv: MMKV
        get() = MMKV.defaultMMKV()

    private var running = false
    private var tickTimer: CountDownTimer? = null
    private var activeActivity: Activity? = null

    // 全屏遮罩
    private var blockDialog: AlertDialog? = null
    private var blockCountdownText: TextView? = null
    private var blockTimer: CountDownTimer? = null

    /** 启动前台计时(仅在功能开启时调用) */
    fun start() {
        if (running) return
        running = true
        tickTimer?.cancel()
        tickTimer = object : CountDownTimer(Long.MAX_VALUE, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                runCatching { onSecondTick() }.onFailure { KLogCat.e(TAG, it.toString()) }
            }

            override fun onFinish() {}
        }.start()
        KLogCat.i(TAG, "自律计时已启动")
    }

    fun stop() {
        running = false
        tickTimer?.cancel()
        tickTimer = null
        dismissBlockDialog()
    }

    /** Activity 进入前台立即检查(防绕过) */
    fun onAppForeground(activity: Activity) {
        if (!config.isSelfDiscipline) return
        if (activity is FreedomSettingActivity) return
        activeActivity = activity
        runCatching { checkAndBlock() }.onFailure { KLogCat.e(TAG, it.toString()) }
    }

    private fun onSecondTick() {
        if (!config.isSelfDiscipline) return
        if (!KAppUtils.isAppInForeground(app)) return
        if (isOverLimit()) return
        accumulate()
        checkAndBlock()
    }

    /** 前台每秒累计1秒, 按日/小时分段存储 */
    private fun accumulate() {
        mmkv.putLong(dayKey(), mmkv.getLong(dayKey(), 0L) + 1L)
        mmkv.putLong(hourKey(), mmkv.getLong(hourKey(), 0L) + 1L)
    }

    /** 当前是否超限 */
    private fun isOverLimit(): Boolean {
        val values = config.selfDisciplineValue
        val dayLimit = values.getOrElse(0) { 0 }
        val hourLimit = values.getOrElse(1) { 0 }
        val dayUsed = mmkv.getLong(dayKey(), 0L)
        val hourUsed = mmkv.getLong(hourKey(), 0L)
        if (dayLimit > 0 && dayUsed >= dayLimit * 60L) return true
        if (hourLimit > 0 && hourUsed >= hourLimit * 60L) return true
        return false
    }

    private fun checkAndBlock() {
        if (!isOverLimit()) {
            dismissBlockDialog()
            return
        }
        when (config.selfDisciplineBlockType) {
            1 -> killApp()
            else -> showBlockDialog()
        }
    }

    // ---- 全屏遮罩 ----

    private fun showBlockDialog() {
        val activity = activeActivity ?: KActivityUtils.getActiveActivity() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        if (blockDialog?.isShowing == true) {
            updateBlockDialog()
            return
        }
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            buildBlockDialog(activity).apply {
                blockDialog = this
                show()
            }
        }
    }

    private fun buildBlockDialog(activity: Activity): AlertDialog {
        val dp = activity.resources.displayMetrics.density

        val title = TextView(activity).apply {
            text = "自律模式"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val countdown = TextView(activity).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, (24 * dp).toInt(), 0, (24 * dp).toInt())
        }
        blockCountdownText = countdown

        val exitBtn = TextView(activity).apply {
            text = "退出抖音"
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#E53935"))
            setPadding(0, (26 * dp).toInt(), 0, (26 * dp).toInt())
            setOnClickListener { killApp() }
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E6121212"))
            setPadding((24 * dp).toInt(), (48 * dp).toInt(), (24 * dp).toInt(), (48 * dp).toInt())
            addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(countdown, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(exitBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (12 * dp).toInt()
            })
        }

        val root = FrameLayout(activity).apply {
            setBackgroundColor(Color.parseColor("#CC000000"))
            addView(
                card,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                ).apply {
                    setMargins((32 * dp).toInt(), 0, (32 * dp).toInt(), 0)
                }
            )
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(root)
            .setCancelable(false)
            .create()
        dialog.setOnKeyListener { _, keyCode, event ->
            keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN
        }
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            decorView.setBackgroundColor(Color.TRANSPARENT)
        }
        dialog.setOnDismissListener {
            blockTimer?.cancel()
            blockTimer = null
        }
        updateBlockDialog()
        return dialog
    }

    /** 刷新遮罩剩余时间(距下个整点/次日解锁) */
    private fun updateBlockDialog() {
        val tv = blockCountdownText ?: return
        tv.text = "已达今日/小时使用上限\n${nextUnlockText()}"
        blockTimer?.cancel()
        blockTimer = object : CountDownTimer(Long.MAX_VALUE, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                tv.text = "已达今日/小时使用上限\n${nextUnlockText()}"
            }

            override fun onFinish() {}
        }.start()
    }

    /** 计算解锁时间文案 */
    private fun nextUnlockText(): String {
        val values = config.selfDisciplineValue
        val dayLimit = values.getOrElse(0) { 0 }
        val hourLimit = values.getOrElse(1) { 0 }
        val dayOver = dayLimit > 0 && mmkv.getLong(dayKey(), 0L) >= dayLimit * 60L
        val hourOver = hourLimit > 0 && mmkv.getLong(hourKey(), 0L) >= hourLimit * 60L
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())

        if (hourOver && !dayOver) {
            // 仅小时超限: 下个整点恢复
            val next = Calendar.getInstance().apply {
                add(Calendar.HOUR_OF_DAY, 1)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return "下个整点 ${fmt.format(next.time)} 恢复使用"
        }
        // 每日超限(或两者都超): 次日恢复
        val next = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return "次日 ${fmt.format(next.time)} 恢复使用"
    }

    private fun dayKey(): String =
        "sd_day_" + SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())

    private fun hourKey(): String =
        "sd_hour_" + SimpleDateFormat("yyyyMMddHH", Locale.getDefault()).format(Date())

    private fun dismissBlockDialog() {
        blockTimer?.cancel()
        blockTimer = null
        runCatching { blockDialog?.dismiss() }
        blockDialog = null
    }

    /** 直接退出: 结束全部Activity并杀进程 */
    private fun killApp() {
        runCatching {
            dismissBlockDialog()
            KActivityUtils.getActivities().forEach {
                runCatching { it.finishAndRemoveTask() }
            }
        }.onFailure {
            KLogCat.e(TAG, it.toString())
        }
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }

    companion object {
        private const val TAG = "SelfDiscipline"
    }
}
