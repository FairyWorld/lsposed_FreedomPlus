package io.github.fplus.core.hook

import io.github.fplus.core.base.BaseHook
import io.github.xpler.core.XplerLog
import io.github.xpler.core.entity.EmptyHook
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.proxy.MethodParam

class HPlayerController : BaseHook() {
    companion object {
        var playingAid: String? = ""

        @get:Synchronized
        @set:Synchronized
        var isPlaying = true
    }

    override fun setTargetClass(): Class<*> {
        return runCatching {
            findClass("com.ss.android.ugc.aweme.feed.controller.PlayerController")
        }.getOrDefault(EmptyHook::class.java)
    }

    @OnBefore("onPlaying")
    fun onPlayingAfter(params: MethodParam, aid: String?) {
        hookBlockRunning(params) {
            playingAid = aid
            isPlaying = true
        }.onFailure {
            XplerLog.e(it)
        }
    }

    @OnBefore("onResumePlay")
    fun onResumePlayBefore(params: MethodParam, aid: String?) {
        hookBlockRunning(params) {
            playingAid = aid
            isPlaying = true
        }.onFailure {
            XplerLog.e(it)
        }
    }

    @OnBefore("onPausePlay")
    fun onPausePlayBefore(params: MethodParam, aid: String?) {
        hookBlockRunning(params) {
            isPlaying = false
        }.onFailure {
            XplerLog.e(it)
        }
    }

    @OnBefore("onPlayStop")
    fun onPlayStopBefore(params: MethodParam, aid: String?) {
        hookBlockRunning(params) {
            isPlaying = false
        }.onFailure {
            XplerLog.e(it)
        }
    }

    @OnAfter("onPlayProgressChange")
    fun onPlayProgressChangeBefore(
        params: MethodParam, aid: String?,
        current: Long,
        duration: Long,
    ) {
        hookBlockRunning(params) {
            playingAid = aid
            isPlaying = true
        }.onFailure {
            XplerLog.e(it)
        }
    }
}
