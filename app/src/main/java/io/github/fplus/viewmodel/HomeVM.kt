package io.github.fplus.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.freegang.extension.appVersionName
import com.freegang.extension.child
import com.freegang.ktutils.app.KAppUtils
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.config.Version
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeVM(application: Application) : AndroidViewModel(application) {
    private val app: Application get() = getApplication()

    // module config
    private val config: ConfigV1 get() = ConfigV1.get()

    // 获取远程版本适配列表
    fun updateVersions() {
        if (KAppUtils.isAppInDebug(app)) return
        if (app.appVersionName.contains(Regex("beta|alpha"))) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val versions = Version.getVersions() ?: return@withContext
                val file = ConfigV1.getConfigDir(app).child("versions.json")
                file.writeText(versions)
            }
        }
    }

    // 版本适配提示
    suspend fun isSupportVersions(versionName: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val versions = ConfigV1.getConfigDir(app).child("versions.json")
                val text = versions.readText()
                if (text.contains(versionName)) "版本功能正常" else "自行测试功能"
            } catch (e: Exception) {
                "自行测试功能"
            }
        }
    }
}
