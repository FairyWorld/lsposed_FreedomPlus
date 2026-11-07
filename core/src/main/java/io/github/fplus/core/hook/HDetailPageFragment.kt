package io.github.fplus.core.hook

import android.os.Bundle
import android.view.View
import com.freegang.extension.findMethodInvoke
import com.freegang.extension.postRunning
import com.ss.android.ugc.aweme.feed.model.Aweme
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.DexkitBuilder
import io.github.xpler.core.XplerLog
import io.github.xpler.core.entity.NoneHook
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.proxy.MethodParam

class HDetailPageFragment : BaseHook() {
    companion object {
        @get:Synchronized
        @set:Synchronized
        var isComment = false
    }

    private val config get() = ConfigV1.get()

    override fun setTargetClass(): Class<*> {
        return DexkitBuilder.detailPageFragmentClazz ?: NoneHook::class.java
    }

    @OnAfter("onViewCreated")
    fun onViewCreatedAfter(param: MethodParam, view: View, bundle: Bundle?) {
        hookBlockRunning(param) {
            HDetailPageFragment.isComment = false
            view.postRunning {
                val aweme = thisObject?.findMethodInvoke<Aweme> { returnType(Aweme::class.java) } ?: return@postRunning

                // awemeType 【134:评论区图片, 133|136:评论区视频】 by 25.1.0 至今
                if (aweme.awemeType == 134 || aweme.awemeType == 133 || aweme.awemeType == 136) {
                    HDetailPageFragment.isComment = true
                }
            }
        }.onFailure {
            XplerLog.e(it)
        }
    }

    @OnAfter("onStop")
    fun onStopAfter(param: MethodParam) {
        HDetailPageFragment.isComment = false
    }
}
