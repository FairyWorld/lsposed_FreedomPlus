package io.github.fplus.core.hook

import com.freegang.extension.findFieldGetValue
import com.freegang.extension.findFieldSetValue
import com.freegang.ktutils.text.KTextUtils
import com.ss.android.ugc.aweme.common.widget.VerticalViewPager
import com.ss.android.ugc.aweme.feed.model.Aweme
import com.ss.android.ugc.aweme.follow.presenter.FollowFeed
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.DexkitBuilder
import io.github.xpler.core.XplerLog
import io.github.xpler.core.hookClass
import io.github.xpler.core.lparam

class HVerticalViewPager : BaseHook() {
    companion object {
        var isFilterLive = false
        var isFilterImage = false
        var isFilterAd = false
        var isFilterLongVideo = false
        var isFilterRecommendedCards = false
        var isFilterRecommendedMerchants = false
        var isFilterEmptyDesc = false
    }

    private val config get() = ConfigV1.get()

    private val filterKeywordsAndTypes: Set<String>
        get() = config.videoFilterKeywords
            .replace("，", ",")
            .replace("\\s".toRegex(), "")
            .removePrefix(",").removeSuffix(",")
            .split(",")
            .filter { it.isNotEmpty() }
            .toSet()

    private val keywordsRegex: Regex
        get() = filterKeywordsAndTypes
            .filter { !config.videoFilterTypes.contains(it) }
            .joinToString("|")
            .replace("\\|+".toRegex(), "|")
            .toRegex()

    override fun setTargetClass(): Class<*> {
        return VerticalViewPager::class.java
    }

    @Suppress("UNCHECKED_CAST")
    private fun filterAwemeList(items: List<Aweme>): List<Aweme> {
        resetFilter()
        val awemes = mutableListOf<Aweme>()
        for (item in items) {
            needAweme(item) ?: continue
            awemes.add(item)
        }
        XplerLog.d("VideoFilter: 推荐 ${items.size} -> ${awemes.size}, live=$isFilterLive ad=$isFilterAd image=$isFilterImage")
        return awemes
    }

    @Suppress("UNCHECKED_CAST")
    private fun filterFollowFeedList(items: List<FollowFeed>): List<FollowFeed> {
        resetFilter()
        val followFeeds = mutableListOf<FollowFeed>()
        for (item in items) {
            needAweme(item.aweme) ?: continue
            followFeeds.add(item)
        }
        XplerLog.d("VideoFilter: 关注 ${items.size} -> ${followFeeds.size}")
        return followFeeds
    }

    private fun resetFilter() {
        isFilterLive = false
        isFilterImage = false
        isFilterAd = false
        isFilterLongVideo = false
        isFilterRecommendedCards = false
        isFilterRecommendedMerchants = false
        isFilterEmptyDesc = false
        for (s in filterKeywordsAndTypes) {
            when (s) {
                "直播" -> isFilterLive = true
                "图文" -> isFilterImage = true
                "广告" -> isFilterAd = true
                "长视频" -> isFilterLongVideo = true
                "推荐卡片" -> isFilterRecommendedCards = true
                "推荐商家" -> isFilterRecommendedMerchants = true
                "空文案" -> isFilterEmptyDesc = true
            }
        }
    }

    private fun needAweme(aweme: Aweme): Aweme? {
        return runCatching {
            when {
                isFilterLive && aweme.isLive -> null
                isFilterImage && aweme.isMultiImage -> null
                isFilterAd && aweme.isAd -> null
                isFilterLongVideo && aweme.isCopyRightLongVideo -> null
                isFilterRecommendedCards && aweme.awemeType == 145 -> null
                isFilterRecommendedMerchants && aweme.awemeType == 140 -> null
                isFilterEmptyDesc && KTextUtils.isEmpty(aweme.desc) -> null
                keywordsRegex.pattern.trim().isNotEmpty()
                        && KTextUtils.get(aweme.desc).contains(keywordsRegex) -> null
                else -> aweme
            }
        }.getOrElse {
            XplerLog.e("VideoFilter: needAweme error: ${it.message}")
            aweme
        }
    }

    override fun onInit() {
        XplerLog.d("VideoFilter: onInit recommend=${DexkitBuilder.recommendFeedFetchPresenterClazz != null} follow=${DexkitBuilder.fullFeedFollowFetchPresenterClazz != null}")

        DexkitBuilder.recommendFeedFetchPresenterClazz?.runCatching {
            lparam.hookClass(this)
                .method("onSuccess") {
                    onBefore {
                        if (!config.isVideoFilter) return@onBefore

                        val mModel = thisObject?.findFieldGetValue<Any> { name("mModel") }
                        val mData = mModel?.findFieldGetValue<Any> { name("mData") }
                        XplerLog.d("VideoFilter: 推荐onSuccess mData=${mData?.javaClass?.simpleName}")

                        if (mData != null) {
                            val items = mData.findFieldGetValue<List<Aweme>> { name("items") }
                                ?: mData.findFieldGetValue<List<Aweme>> { name("mItems") }
                                ?: mData.findFieldGetValue<List<Aweme>> { name("list") }
                                ?: emptyList()

                            if (items.isNotEmpty()) {
                                mData.findFieldSetValue(filterAwemeList(items)) { name("items") }
                            } else {
                                XplerLog.d("VideoFilter: 推荐列表为空, 尝试字段: items/mItems/list")
                            }
                        }
                    }
                }
        }?.onFailure {
            XplerLog.e("VideoFilter: 推荐Hook失败: ${it.message}")
        }

        DexkitBuilder.fullFeedFollowFetchPresenterClazz?.runCatching {
            lparam.hookClass(this)
                .method("onSuccess") {
                    onBefore {
                        if (!config.isVideoFilter) return@onBefore

                        val mModel = thisObject?.findFieldGetValue<Any> { name("mModel") }
                        val mData = mModel?.findFieldGetValue<Any> { name("mData") }
                        XplerLog.d("VideoFilter: 关注onSuccess mData=${mData?.javaClass?.simpleName}")

                        if (mData != null) {
                            val mItems = mData.findFieldGetValue<List<FollowFeed>> { name("mItems") }
                                ?: mData.findFieldGetValue<List<FollowFeed>> { name("items") }
                                ?: mData.findFieldGetValue<List<FollowFeed>> { name("list") }
                                ?: emptyList()

                            if (mItems.isNotEmpty()) {
                                mData.findFieldSetValue(filterFollowFeedList(mItems)) { name("mItems") }
                            }
                        }
                    }
                }
        }?.onFailure {
            XplerLog.e("VideoFilter: 关注Hook失败: ${it.message}")
        }
    }
}
