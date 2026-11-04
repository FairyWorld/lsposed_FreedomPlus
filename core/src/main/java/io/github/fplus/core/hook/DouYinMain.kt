package io.github.fplus.core.hook

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import com.freegang.extension.child
import com.freegang.extension.need
import com.freegang.ktutils.app.KActivityUtils
import com.freegang.ktutils.app.KAppCrashUtils
import com.freegang.ktutils.app.KAppUtils
import com.freegang.ktutils.app.KToastUtils
import com.freegang.ktutils.log.KLogCat
import io.github.fplus.Constant
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.DexkitBuilder
import io.github.fplus.core.helper.SelfDisciplineHelper
import io.github.fplus.plugin.injectRes
import io.github.fplus.plugin.proxy.v1.PluginBridge
import io.github.xpler.core.XplerLog
import io.github.xpler.core.XplerModule
import java.util.zip.ZipFile

class DouYinMain(private val app: Application) {

    init {
        runCatching {
            exportNative(app)

            // 插件化注入
            PluginBridge.init(app, "com.ss.android.ugc.aweme.setting.ui.AboutActivity")
            injectRes(app.resources)

            // 全局Application
            KAppUtils.setApplication(app)
            KActivityUtils.register(app)

            // 日志工具
            XplerLog.setTag("Freedom+")
            KLogCat.init(app)
            KLogCat.setTag("Freedom+")

            // 全局异常捕获工具
            val intent = Intent()
            val className = "${Constant.modulePackage}.activity.ErrorActivity"
            intent.setClassName(Constant.modulePackage, className)
            KAppCrashUtils.init(app, "抖音异常退出!", intent)

            // 自律功能
            initSelfDiscipline(app)

            // search and hook
            DexkitBuilder.running(
                app = app,
                version = 32,
                searchBefore = {
                    HPhoneWindow()
                    HActivity()
                    HMainActivity()
                    HDetailActivity()
                    HDisallowInterceptRelativeLayout()
                    HPlayerController()
                    HPenetrateTouchRelativeLayout()
                    HDialog()
                },
                searchAfter = {
                    HCrashTolerance()
                    HSideBarNestedScrollView()
                    HMainBottomTabView()
                    HLongPressLayout()
                    HVideoViewHolder()
                    HFeedPlayerView()
                    HVerticalViewPager()
                    HDetailPageFragment()
                }
            )

        }.onFailure {
            XplerLog.e(it)
            KToastUtils.show(app, "Freedom+ Error: ${it.message}")
        }
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun exportNative(app: Application) {
        val abi = if (KAppUtils.is64BitDalvik()) "arm64-v8a" else "armeabi-v7a"
        val libDir = ConfigV1.getConfigDir(app).child("lib").need()
        val libDexkit = libDir.child("libdexkit.so")
        val libMmkv = libDir.child("libmmkv.so")

        if (!libDexkit.exists() || !libMmkv.exists()) {
            val dexkitSo = "lib/${abi}/libdexkit.so"
            val mmkbSo = "lib/${abi}/libmmkv.so"

            val zipFile = ZipFile(XplerModule.modulePath)
            zipFile.getInputStream(zipFile.getEntry(dexkitSo)).use { input ->
                libDexkit.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            zipFile.getInputStream(zipFile.getEntry(mmkbSo)).use { input ->
                libMmkv.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        System.load(libDexkit.absolutePath)
        ConfigV1.initialize(app) { _ ->
            System.load(libMmkv.absolutePath)
        }
    }

    /** 自律功能: 开启时启动前台计时 */
    private fun initSelfDiscipline(app: Application) {
        if (!ConfigV1.get().isSelfDiscipline) return
        selfDisciplineHelper = SelfDisciplineHelper(app)
        selfDisciplineHelper?.start()
    }

    companion object {
        @Volatile
        var selfDisciplineHelper: SelfDisciplineHelper? = null
    }
}
