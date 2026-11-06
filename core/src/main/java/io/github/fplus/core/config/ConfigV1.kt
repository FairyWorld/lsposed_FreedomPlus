package io.github.fplus.core.config

import android.content.Context
import android.os.Environment
import com.freegang.extension.child
import com.freegang.extension.getStringOrDefault
import com.freegang.extension.parseJSON
import com.freegang.extension.storageRootFile
import com.tencent.mmkv.MMKV
import com.tencent.mmkv.MMKV.LibLoader
import org.json.JSONObject
import java.io.File

class ConfigV1 private constructor() {
    data class Version(
        var versionName: String = "", // 版本名称
        var versionCode: Long = 0L, // 版本代码
        var dyVersionName: String = "", // 抖音版本名称
        var dyVersionCode: Long = 0L, // 抖音版本代码
    )

    companion object {
        private val mmkv by lazy { MMKV.defaultMMKV() }

        private val config by lazy { ConfigV1() }

        fun getFreedomDir(context: Context): File {
            return context.applicationContext.storageRootFile
                .child(Environment.DIRECTORY_DOWNLOADS)
                .child("Freedom")
        }

        fun getConfigDir(context: Context): File {
            return context.filesDir.child("fplus")
        }

        fun initialize(context: Context, libLoader: LibLoader? = null) {
            MMKV.initialize(context, getConfigDir(context).absolutePath, libLoader)
        }

        fun clear(context: Context) {
            mmkv.clearAll()
            getFreedomDir(context).deleteRecursively()
        }

        fun get() = config
    }

    /// 视频/图文/音乐下载
    var isDownload: Boolean = true
        get() {
            field = mmkv.getBoolean("isDownload", true)
            return field
        }
        set(value) {
            mmkv.putBoolean("isDownload", value)
            field = value
        }

    /// 按视频创作者单独创建文件夹
    var ownerDir: Boolean = false
        get() {
            field = mmkv.getBoolean("ownerDir", false)
            return field
        }
        set(value) {
            mmkv.putBoolean("ownerDir", value)
            field = value
        }

    /// 通知栏下载
    var notificationDownload: Boolean = false
        get() {
            field = mmkv.getBoolean("notificationDownload", false)
            return field
        }
        set(value) {
            mmkv.putBoolean("notificationDownload", value)
            field = value
        }

    /// 复制链接时弹出下载
    var copyLinkDownload: Boolean = false
        get() {
            field = mmkv.getBoolean("copyLinkDownload", false)
            return field
        }
        set(value) {
            mmkv.putBoolean("copyLinkDownload", value)
            field = value
        }

    /// 视频编码 [Auto, H265, H264]
    var videoCoding: String = "H265"
        get() {
            field = mmkv.getString("videoCoding", "H265")!!
            return field
        }
        set(value) {
            mmkv.putString("videoCoding", value)
            field = value
        }

    /// 震动反馈
    var vibrate: Boolean = false
        get() {
            field = mmkv.getBoolean("vibrate", false)
            return field
        }
        set(value) {
            mmkv.putBoolean("vibrate", value)
            field = value
        }

    /// 双击屏幕响应类型
    var isDoubleClickType: Boolean = true
        get() {
            field = mmkv.getBoolean("isDoubleClickType", true)
            return field
        }
        set(value) {
            mmkv.putBoolean("isDoubleClickType", value)
            field = value
        }

    /// 双击响应类型: 1=打开评论, 2=点赞视频
    var doubleClickType: Int = 2
        get() {
            field = mmkv.getInt("doubleClickType", 2)
            return field
        }
        set(value) {
            mmkv.putInt("doubleClickType", value)
            field = value
        }

    /// 弹窗过滤
    var isDialogFilter: Boolean = true
        get() {
            field = mmkv.getBoolean("isDialogFilter", true)
            return field
        }
        set(value) {
            mmkv.putBoolean("isDialogFilter", value)
            field = value
        }

    /// 弹窗关闭提示
    var dialogDismissTips: Boolean = false
        get() {
            field = mmkv.getBoolean("dialogDismissTips", false)
            return field
        }
        set(value) {
            mmkv.putBoolean("dialogDismissTips", value)
            field = value
        }

    /// 弹窗过滤关键字
    var dialogFilterKeywords: String = "现在安装, 立即升级"
        get() {
            field = mmkv.getString("dialogFilterKeywords", "现在安装, 立即升级")!!
            return field
        }
        set(value) {
            mmkv.putString("dialogFilterKeywords", value)
            field = value
        }

    /// 视频过滤
    var isVideoFilter: Boolean = true
        get() {
            field = mmkv.getBoolean("isVideoFilter", true)
            return field
        }
        set(value) {
            mmkv.putBoolean("isVideoFilter", value)
            field = value
        }

    /// 视频过滤类型
    val videoFilterTypes = setOf("直播", "广告", "图文", "长视频", "推荐卡片", "推荐商家", "空文案")

    /// 视频过滤关键字
    var videoFilterKeywords: String = "直播, 广告"
        get() {
            field = mmkv.getString("videoFilterKeywords", "直播, 广告")!!
            return field
        }
        set(value) {
            mmkv.putString("videoFilterKeywords", value)
            field = value
        }

    /// 清爽模式
    var isNeatMode: Boolean = true
        get() {
            field = mmkv.getBoolean("isNeatMode", true)
            return field
        }
        set(value) {
            mmkv.putBoolean("isNeatMode", value)
            field = value
        }

    /// 当前是否处于清爽模式
    var neatModeState: Boolean = false
        get() {
            field = mmkv.getBoolean("neatModeState", false)
            return field
        }
        set(value) {
            mmkv.putBoolean("neatModeState", value)
            field = value
        }

    /// 清爽模式弹窗响应模式 true上半, false下半
    var longPressMode: Boolean = false
        get() {
            field = mmkv.getBoolean("longPressMode", false)
            return field
        }
        set(value) {
            mmkv.putBoolean("longPressMode", value)
            field = value
        }

    /// 全屏沉浸式
    var isImmersive: Boolean = true
        get() {
            return mmkv.getBoolean("immersive", true)
        }
        set(value) {
            mmkv.putBoolean("immersive", value)
            field = value
        }

    // 系统隐藏项(状态栏、导航栏)
    var systemControllerValue: List<Boolean> = listOf(false, false)
        get() {
            field = mmkv.getString("systemControllerValue", "false, false")!!
                .split(",")
                .map { it.trim().toBoolean() }
            return field
        }
        set(value) {
            mmkv.putString("systemControllerValue", value.joinToString())
            field = value
        }

    /// 版本信息
    var versionConfig: ConfigV1.Version = ConfigV1.Version()
        get() {
            return Version(
                mmkv.getString("versionName", "")!!,
                mmkv.getLong("versionCode", 0L),
                mmkv.getString("dyVersionName", "")!!,
                mmkv.getLong("dyVersionCode", 0L),
            )
        }
        set(value) {
            field = value
            mmkv.putString("versionName", value.versionName)
            mmkv.putLong("versionCode", value.versionCode)
            mmkv.putString("dyVersionName", value.dyVersionName)
            mmkv.putLong("dyVersionCode", value.dyVersionCode)
        }

    var is32BitTips: Boolean = true
        get() {
            field = mmkv.getBoolean("is32BitTips", true)
            return field
        }
        set(value) {
            field = value
            mmkv.putBoolean("is32BitTips", value)
        }

    /// 自动连播(视频播放完毕后自动切换到下一条)
    var isAutoPlay: Boolean = false
        get() {
            field = mmkv.getBoolean("isAutoPlay", false)
            return field
        }
        set(value) {
            field = value
            mmkv.putBoolean("isAutoPlay", value)
        }

    var isSelfDiscipline: Boolean = false
        get() {
            field = mmkv.getBoolean("isSelfDiscipline", false)
            return field
        }
        set(value) {
            field = value
            mmkv.putBoolean("isSelfDiscipline", value)
        }

    var selfDisciplineValue: List<Int> = listOf(120, 30)
        get() {
            val raw = mmkv.getString("selfDisciplineValue", "120, 30") ?: "120, 30"
            val parsed = raw.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it >= 0 }
            field = if (parsed.size == 2) parsed else listOf(120, 30)
            return field
        }
        set(value) {
            field = value
            mmkv.putString("selfDisciplineValue", value.joinToString(", "))
        }

    var selfDisciplineBlockType: Int = 0
        get() {
            field = mmkv.getInt("selfDisciplineBlockType", 0)
            return field
        }
        set(value) {
            field = value
            mmkv.putInt("selfDisciplineBlockType", value)
        }

    var dexkitCache: JSONObject = JSONObject()
        get() {
            return mmkv.getString("dexkitCache", "")!!.parseJSON()
        }
        set(value) {
            field = value
            mmkv.putString("dexkitCache", value.toString())
        }
}
