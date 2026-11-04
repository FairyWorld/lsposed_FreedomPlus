package io.github.fplus.core.hook

import android.app.Activity
import androidx.core.view.updatePadding
import com.freegang.extension.contentView
import com.freegang.extension.navBarInteractionMode
import com.freegang.extension.navigationBarHeight
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.ImmersiveHelper
import io.github.fplus.core.ui.activity.FreedomSettingActivity
import io.github.xpler.core.XplerLog
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.proxy.MethodParam

class HActivity : BaseHook() {
    private val config get() = ConfigV1.get()

    override fun setTargetClass(): Class<*> {
        return Activity::class.java
    }

    @OnBefore("onResume")
    fun onResumeBefore(params: MethodParam) {
        hookBlockRunning(params) {
            singleLaunchMain {
                val activity = thisObject as Activity
                DouYinMain.selfDisciplineHelper?.onAppForeground(activity)
            }
        }.onFailure {
            XplerLog.e(it)
        }
    }

    @OnAfter("onWindowFocusChanged")
    fun onWindowFocusChangedAfter(params: MethodParam, boolean: Boolean) {
        hookBlockRunning(params) {
            singleLaunchMain {
                val activity = thisObject as Activity

                if (activity is FreedomSettingActivity)
                    return@singleLaunchMain

                immersive(activity)
            }
        }.onFailure {
            XplerLog.e(it)
        }
    }

    private fun immersive(activity: Activity) {
        if (config.isImmersive) {
            ImmersiveHelper.immersive(
                activity = activity,
                hideStatusBar = config.systemControllerValue[0],
                hideNavigationBars = config.systemControllerValue[1],
            )
            ImmersiveHelper.systemBarColor(activity)

            // 底部三键导航
            if (activity.navBarInteractionMode == 0 && !config.systemControllerValue[1]) {
                activity.contentView.apply {
                    updatePadding(bottom = context.navigationBarHeight)
                }
            }
        }
    }
}
