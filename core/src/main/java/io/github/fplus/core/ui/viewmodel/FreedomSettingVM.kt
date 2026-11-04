package io.github.fplus.core.ui.viewmodel

import android.app.Application
import android.content.res.AssetManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.freegang.extension.appVersionCode
import com.freegang.extension.appVersionName
import com.freegang.extension.isEmpty
import com.freegang.extension.readAssetsAsText
import com.freegang.ktutils.app.KAppUtils
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.config.Version
import io.github.fplus.core.config.VersionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class FreedomSettingVM(application: Application) : AndroidViewModel(application) {
    val app: Application get() = getApplication()

    private var _versionConfig = MutableLiveData<VersionConfig>()
    val versionConfig: LiveData<VersionConfig> = _versionConfig

    // module config
    private lateinit var config: ConfigV1

    private var _isDownload = MutableLiveData(false)
    val isDownload: LiveData<Boolean> = _isDownload

    private var _ownerDir = MutableLiveData(false)
    val isOwnerDir: LiveData<Boolean> = _ownerDir

    private var _notificationDownload = MutableLiveData(false)
    val isNotification: LiveData<Boolean> = _notificationDownload

    private var _isCopyLinkDownload = MutableLiveData(false)
    val isCopyDownload: LiveData<Boolean> = _isCopyLinkDownload

    private var _videoCoding = MutableLiveData("")
    val videoCoding: LiveData<String> = _videoCoding

    private var _vibrate = MutableLiveData(false)
    val isVibrate: LiveData<Boolean> = _vibrate

    private var _isDoubleClickType = MutableLiveData(false)
    val isDoubleClickType: LiveData<Boolean> = _isDoubleClickType

    private var _doubleClickType = MutableLiveData(2)
    val doubleClickType: LiveData<Int> = _doubleClickType

    private var _isNeatMode = MutableLiveData(false)
    val isNeatMode: LiveData<Boolean> = _isNeatMode

    private var _isDialogFilter = MutableLiveData(false)
    val isDialogFilter: LiveData<Boolean> = _isDialogFilter

    private var _dialogDismissTips = MutableLiveData(false)
    val dialogDismissTips: LiveData<Boolean> = _dialogDismissTips

    private var _dialogFilterKeywords = MutableLiveData("")
    val dialogFilterKeywords: LiveData<String> = _dialogFilterKeywords

    private var _isVideoFilter = MutableLiveData(false)
    val isVideoFilter: LiveData<Boolean> = _isVideoFilter

    private var _videoFilterKeywords = MutableLiveData("")
    val videoFilterKeywords: LiveData<String> = _videoFilterKeywords

    private var _isImmersive = MutableLiveData(false)
    val isImmersive: LiveData<Boolean> = _isImmersive

    private var _systemControllerValue = MutableLiveData(listOf(false, false))
    val systemControllerValue: LiveData<List<Boolean>> = _systemControllerValue

    private var _longPressMode = MutableLiveData(false)
    val longPressMode: LiveData<Boolean> = _longPressMode

    private var _isSelfDiscipline = MutableLiveData(false)
    val isSelfDiscipline: LiveData<Boolean> = _isSelfDiscipline

    private var _selfDisciplineValue = MutableLiveData(listOf(120, 30))
    val selfDisciplineValue: LiveData<List<Int>> = _selfDisciplineValue

    private var _selfDisciplineBlockType = MutableLiveData(0)
    val selfDisciplineBlockType: LiveData<Int> = _selfDisciplineBlockType

    // 检查版本更新
    fun checkVersion() {
        if (KAppUtils.isAppInDebug(app)) return // 测试包不检查更新
        if (app.appVersionName.contains(Regex("beta|alpha"))) return // 非release包不检查更新
        viewModelScope.launch {
            val version = withContext(Dispatchers.IO) { Version.getRemoteReleasesLatest() }
            if (version != null) _versionConfig.value = version
        }
    }

    // 读取模块配置
    fun loadConfig() {
        viewModelScope.launch {
            config = withContext(Dispatchers.IO) { ConfigV1.get() }
            changeIsDownload(config.isDownload)
            setOwnerDir(config.ownerDir)
            setNotificationDownload(config.notificationDownload)
            setCopyLinkDownload(config.copyLinkDownload)
            setVideoCoding(config.videoCoding)
            setVibrate(config.vibrate)
            changeIsDoubleClickType(config.isDoubleClickType)
            setDoubleClickType(config.doubleClickType)
            changeIsNeatMode(config.isNeatMode)
            setLongPressMode(config.longPressMode)
            changeIsDialogFilter(config.isDialogFilter)
            setDialogDismissTips(config.dialogDismissTips)
            setDialogFilterKeywords(config.dialogFilterKeywords)
            changeIsVideoFilter(config.isVideoFilter)
            setVideoFilterKeywords(config.videoFilterKeywords)
            changeIsImmersive(config.isImmersive)
            setSystemControllerValue(config.systemControllerValue)
            changeIsSelfDiscipline(config.isSelfDiscipline)
            setSelfDisciplineValue(config.selfDisciplineValue)
            setSelfDisciplineBlockType(config.selfDisciplineBlockType)
        }
    }

    // 视频/图文/音乐下载
    fun changeIsDownload(value: Boolean) {
        _isDownload.value = value
        config.isDownload = value
    }

    // 视频创作者单独创建文件夹
    fun setOwnerDir(value: Boolean) {
        _ownerDir.value = value
        config.ownerDir = value
    }

    // 通知栏下载
    fun setNotificationDownload(value: Boolean) {
        _notificationDownload.value = value
        config.notificationDownload = value
    }

    // 复制链接时弹出下载
    fun setCopyLinkDownload(value: Boolean) {
        _isCopyLinkDownload.value = value
        config.copyLinkDownload = value
    }

    // 视频编码类型
    fun setVideoCoding(value: String) {
        _videoCoding.value = value
        config.videoCoding = value
    }

    // 震动反馈
    fun setVibrate(value: Boolean) {
        _vibrate.value = value
        config.vibrate = value
    }

    // 是否开启更改双击响应类型
    fun changeIsDoubleClickType(value: Boolean) {
        _isDoubleClickType.value = value
        config.isDoubleClickType = value
    }

    // 双击响应类型
    fun setDoubleClickType(value: Int) {
        _doubleClickType.value = value
        config.doubleClickType = value
    }

    // 清爽模式
    fun changeIsNeatMode(value: Boolean) {
        _isNeatMode.value = value
        config.isNeatMode = value
    }

    // 弹窗过滤
    fun changeIsDialogFilter(value: Boolean) {
        _isDialogFilter.value = value
        config.isDialogFilter = value
    }

    // 弹窗关闭提示
    fun setDialogDismissTips(value: Boolean) {
        _dialogDismissTips.value = value
        config.dialogDismissTips = value
    }

    // 弹窗过滤关键字
    fun setDialogFilterKeywords(value: String) {
        _dialogFilterKeywords.value = value
        config.dialogFilterKeywords = value
    }

    // 视频过滤
    fun changeIsVideoFilter(value: Boolean) {
        _isVideoFilter.value = value
        config.isVideoFilter = value
    }

    // 视频过滤关键字
    fun setVideoFilterKeywords(value: String) {
        _videoFilterKeywords.value = value
        config.videoFilterKeywords = value
    }

    // 全屏沉浸
    fun changeIsImmersive(value: Boolean) {
        _isImmersive.value = value
        config.isImmersive = value
    }

    // 系统隐藏项(状态栏、导航栏)
    fun setSystemControllerValue(value: List<Boolean>) {
        _systemControllerValue.value = value
        config.systemControllerValue = value
    }

    // 自律
    fun changeIsSelfDiscipline(value: Boolean) {
        _isSelfDiscipline.value = value
        config.isSelfDiscipline = value
    }

    // 自律时长限制(每日分钟, 每小时分钟)
    fun setSelfDisciplineValue(value: List<Int>) {
        _selfDisciplineValue.value = value
        config.selfDisciplineValue = value
    }

    // 自律拦截方式(0=遮罩, 1=直接退出)
    fun setSelfDisciplineBlockType(value: Int) {
        _selfDisciplineBlockType.value = value
        config.selfDisciplineBlockType = value
    }

    // 清爽模式弹窗响应模式
    fun setLongPressMode(value: Boolean) {
        _longPressMode.value = value
        config.longPressMode = value
    }

    // 保存版本信息
    fun setVersionConfig(asset: AssetManager?) {
        if (asset == null) {
            config.versionConfig = config.versionConfig.copy(
                dyVersionName = app.appVersionName,
                dyVersionCode = app.appVersionCode,
            )
            return
        }

        val version = asset.readAssetsAsText("version")
        val versionName = version.substringBeforeLast("-")
        val versionCode = version.substringAfterLast("-")
        config.versionConfig = config.versionConfig.copy(
            versionName = versionName,
            versionCode = versionCode.toLong(),
            dyVersionName = app.appVersionName,
            dyVersionCode = app.appVersionCode,
        )
    }

    val hasDexkitCache get() = !config.dexkitCache.isEmpty()

    // 清除类日志
    fun clearDexkitCache() {
        config.dexkitCache = JSONObject()
    }
}
