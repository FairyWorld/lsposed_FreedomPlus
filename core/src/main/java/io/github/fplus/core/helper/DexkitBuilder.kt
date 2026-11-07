package io.github.fplus.core.helper

import android.app.Application
import com.freegang.extension.appLastUpdateTime
import com.freegang.extension.appVersionCode
import com.freegang.extension.appVersionName
import com.freegang.extension.getIntOrDefault
import com.freegang.extension.getLongOrDefault
import com.freegang.extension.getStringOrDefault
import com.freegang.ktutils.log.KLogCat
import com.freegang.ktutils.text.KTextUtils
import io.github.fplus.core.config.ConfigV1
import io.github.xpler.core.findClass
import io.github.xpler.core.lparam
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.ClassDataList
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.result.MethodDataList
import java.lang.reflect.Method

object DexkitBuilder {
    const val TAG = "DexkitBuilder"
    private var app: Application? = null

    private var cacheVersion: Int = 0
    private var cacheJson: JSONObject = JSONObject()
    private var classCacheJson: JSONObject = JSONObject()
    private var methodsCacheJson: JSONObject = JSONObject()

    // class
    var sideBarNestedScrollViewClazz: Class<*>? = null
    var mainBottomTabViewClazz: Class<*>? = null
    var detailPageFragmentClazz: Class<*>? = null
    var recommendFeedFetchPresenterClazz: Class<*>? = null
    var fullFeedFollowFetchPresenterClazz: Class<*>? = null
    var restartUtilsClazz: Class<*>? = null
    var longPressEventClazz: Class<*>? = null
    var doubleClickEventClazz: Class<*>? = null
    var videoViewHolderClazz: Class<*>? = null
    var livePhotoClazz: Class<*>? = null
    var tabLandingClazz: Class<*>? = null

    /**
     * 只是为了解决出现的各种稀奇古怪的情况。
     * 部分未作混淆的类在 Dexkit 搜索期间, 会跳过构造方法的勾子(勾不住构造方法), 但是它的普通方法却能勾住。
     * 还是没太想通, 而按照作者`韵の祈`的说法, 应该是搜索期间导致错过了构造方法勾子的时机。
     *
     * @param app 被搜索app的Application
     * @param version 类缓存版本号, 更改会触发更新
     * @param searchBefore 在Dexkit搜索之前做些什么, 建议在这里勾住`未混淆`的类
     * @param searchAfter 在Dexkit搜索之前做些什么, 建议在这里勾住`混淆`的类
     */
    fun running(
        app: Application,
        version: Int,
        searchBefore: Runnable,
        searchAfter: Runnable,
    ) {
        searchBefore.run()
        readCacheOrStartSearch(app, version)
        searchAfter.run()
    }

    /**
     * 如果缓存存在则读取缓存, 否则开启搜索并保存
     * @param app 被搜索app的Application
     * @param version 类缓存版本号, 更改会触发更新
     */
    private fun readCacheOrStartSearch(app: Application, version: Int) {
        this.app = app
        this.cacheVersion = version

        KLogCat.tagI(TAG, "当前进程: ${lparam.processName}")
        if (readCache()) {
            KLogCat.tagI(TAG, "缓存读取成功!")
            return
        }
        startSearch()
        saveCache()
    }

    /**
     * Dexkit开始搜索
     */
    private fun startSearch() {
        KLogCat.tagI(TAG, "Dexkit开始搜索: ${lparam.appInfo.sourceDir}")
        DexKitBridge.create(lparam.appInfo.sourceDir).use { bridge ->
            val sideBarNestedScrollView = bridge.findClass {
                matcher {
                    className = "com.ss.android.ugc.aweme.sidebar.SideBarNestedScrollView"
                }
            }
            sideBarNestedScrollViewClazz = sideBarNestedScrollView.instance("sideBarNestedScrollView")

            val mainBottomTabView = bridge.findClass {
                matcher {
                    superClass = "android.widget.FrameLayout"

                    fields {
                        add {
                            type = "com.bytedance.dux.image.DuxImageView"
                        }
                    }

                    methods {
                        add {
                            name = "getBottomColor"
                        }
                        add {
                            name = "setBackgroundDrawable"
                            paramTypes = listOf("android.graphics.drawable.Drawable")
                        }
                        add {
                            name = "setBackgroundResource"
                        }
                        add {
                            name = "setBackgroundColor"
                        }
                        add {
                            name = "setVisibility"
                        }
                        add {
                            name = "setAlpha"
                        }
                    }

                    usingStrings {
                        add("alpha", StringMatchType.Equals)
                        add("translationY", StringMatchType.Equals)
                        add("MainBottomTabView", StringMatchType.Equals)
                    }
                }
            }
            mainBottomTabViewClazz = mainBottomTabView.instance("mainBottomTabView")

            // 31.7.2+ 版本底部导航栏类变为 MainBottomTabViewFallback
            if (mainBottomTabViewClazz == null) {
                val mainBottomTabViewFallback = bridge.findClass {
                    matcher {
                        superClass = "android.widget.FrameLayout"
                        usingStrings {
                            add("MainBottomTabViewFallback", StringMatchType.Equals)
                        }
                    }
                }
                mainBottomTabViewClazz = mainBottomTabViewFallback.instance("mainBottomTabViewFallback")
            }

            val restartUtils = bridge.findClass {
                searchPackages("X")
                matcher {
                    methods {
                        add {
                            paramTypes = listOf("android.content.Context")
                            usingNumbers = listOf(0x10008000)
                        }
                    }
                    usingStrings {
                        add("System.exit returned normally, while it was supposed to halt JVM.")
                    }
                }
            }
            restartUtilsClazz = restartUtils.instance("restartUtils")

            val longPressEvent = bridge.findClass {
                matcher {
                    interfaces {
                        add {
                            addAnnotation {
                                addElement {
                                    name = "value"
                                    value {
                                        classValue {
                                            this.className = "com.ss.android.ugc.aweme.feed.ui.LongPressLayout"
                                        }
                                    }
                                }
                            }
                        }
                    }

                    addField {
                        type = "com.ss.android.ugc.aweme.feed.model.Aweme"
                    }
                    addField {
                        type = "android.content.Context"
                    }
                }
            }
            longPressEventClazz = longPressEvent.instance("longPressEvent")

            val doubleClickEvent = bridge.findClass {
                matcher {
                    fieldCount(1)
                    methods {
                        add {
                            paramTypes = listOf("boolean")
                        }
                        add {
                            paramTypes = listOf(
                                "android.view.View",
                                "android.view.MotionEvent",
                                "android.view.MotionEvent",
                                "android.view.MotionEvent",
                            )
                        }
                    }
                }
            }
            doubleClickEventClazz = doubleClickEvent.instance("doubleClickEvent")

            val videoViewHolder = bridge.findClass {
                matcher {
                    className = "com.ss.android.ugc.aweme.feed.adapter.VideoViewHolder"
                }
            }
            videoViewHolderClazz = videoViewHolder.instance("videoViewHolder")

            val livePhoto = bridge.findClass {
                matcher {
                    fields {
                        add {
                            type = "com.ss.android.ugc.aweme.feed.model.VideoItemParams"
                        }
                        add {
                            type = "com.bytedance.ies.dmt.ui.widget.DmtTextView"
                        }
                        add {
                            type = "android.widget.ImageView"
                        }
                    }
                    methods {
                        add {
                            paramTypes = listOf("com.ss.android.ugc.aweme.kiwi.model.QModel")
                        }
                        add {
                            paramTypes = listOf("com.ss.android.ugc.aweme.feed.model.Aweme")
                        }
                    }
                }
            }
            livePhotoClazz = livePhoto.instance("livePhoto")

            val tabLanding = bridge.findClass {
                matcher {
                    fields {
                        add {
                            type =
                                "com.ss.android.ugc.aweme.feed.plato.business.mainarchitecture.tablandguide.TabLandGuideTriggerEventType"
                        }
                        add {
                            type = "com.bytedance.dux.image.DuxImageView"
                        }

                        add {
                            type = "com.ss.android.ugc.aweme.feed.model.VideoItemParams"
                        }
                    }
                    methods {
                        add {
                            paramTypes = listOf("com.ss.android.ugc.aweme.feed.model.VideoItemParams")
                        }
                    }
                    usingStrings = listOf("TabLandGuidePresenter", "tabLandingActionBtn")
                }
            }
            tabLandingClazz = tabLanding.instance("tabLanding")

            val recommendFeedFetchPresenter = bridge.findClass {
                matcher {
                    methods {
                        add {
                            name = "onSuccess"
                        }
                    }
                    addUsingString("com.ss.android.ugc.aweme.feed.presenter.RecommendFeedFetchPresenter")
                    addUsingString("enter_from")
                    addUsingString("homepage_hot")
                }
            }
            recommendFeedFetchPresenterClazz =
                recommendFeedFetchPresenter.instance("recommendFeedFetchPresenter")

            val fullFeedFollowFetchPresenter = bridge.findClass {
                matcher {
                    methods {
                        add {
                            name = "onSuccess"
                        }
                    }
                    addUsingString("com.ss.android.ugc.aweme.feed.presenter.FullFeedFollowFetchPresenter")
                    addUsingString("enter_from")
                    addUsingString("homepage_follow")
                }
            }
            fullFeedFollowFetchPresenterClazz =
                fullFeedFollowFetchPresenter.instance("fullFeedFollowFetchPresenter")


            //
            // by using string
            val findMaps = bridge.batchFindClassUsingStrings {
                addSearchGroup {
                    groupName = "detailPageFragment"
                    usingStrings = listOf(
                        "a1128.b7947",
                        "com/ss/android/ugc/aweme/detail/ui/DetailPageFragment",
                        "DetailActOtherNitaView",
                    )
                }
            }

            val detailPageFragment = findMaps["detailPageFragment"]
            detailPageFragmentClazz = detailPageFragment.instance("detailPageFragment")
        }
    }

    /**
     * 读取缓存
     */
    private fun readCache(): Boolean {
        val cache = ConfigV1.get().dexkitCache

        // version
        val version = cache.getIntOrDefault("version")
        val appVersionName = cache.getStringOrDefault("appVersionName")
        val appVersionCode = cache.getLongOrDefault("appVersionCode", 0)
        val appLastUpdateTime = cache.getLongOrDefault("appLastUpdateTime")

        if (appVersionName != app!!.appVersionName) {
            return false
        }

        if (appVersionCode != app!!.appVersionCode) {
            return false
        }

        if (appLastUpdateTime != app!!.appLastUpdateTime) {
            return false
        }

        if (version < cacheVersion) {
            return false
        }

        readClassCache(cache)

        KLogCat.tagI(TAG, cache.toString(2))

        return true
    }

    /**
     * 从缓存中读取类
     */
    private fun readClassCache(cache: JSONObject) {
        val classCache = cache.getJSONObject("class")

        sideBarNestedScrollViewClazz = classCache.getStringOrDefault("sideBarNestedScrollView").loadOrFindClass()
        mainBottomTabViewClazz = classCache.getStringOrDefault("mainBottomTabView").loadOrFindClass()
            ?: classCache.getStringOrDefault("mainBottomTabViewFallback").loadOrFindClass()
        detailPageFragmentClazz = classCache.getStringOrDefault("detailPageFragment").loadOrFindClass()
        recommendFeedFetchPresenterClazz = classCache.getStringOrDefault("recommendFeedFetchPresenter").loadOrFindClass()
        fullFeedFollowFetchPresenterClazz = classCache.getStringOrDefault("fullFeedFollowFetchPresenter").loadOrFindClass()
        restartUtilsClazz = classCache.getStringOrDefault("restartUtils").loadOrFindClass()
        longPressEventClazz = classCache.getStringOrDefault("longPressEvent").loadOrFindClass()
        doubleClickEventClazz = classCache.getStringOrDefault("doubleClickEvent").loadOrFindClass()
        videoViewHolderClazz = classCache.getStringOrDefault("videoViewHolder").loadOrFindClass()
        livePhotoClazz = classCache.getStringOrDefault("livePhoto").loadOrFindClass()
        tabLandingClazz = classCache.getStringOrDefault("tabLanding").loadOrFindClass()
    }

    /**
     * 保存缓存
     */
    private fun saveCache() {
        // version
        cacheJson.put("version", "$cacheVersion")
        cacheJson.put("appVersionName", app!!.appVersionName)
        cacheJson.put("appVersionCode", app!!.appVersionCode)
        cacheJson.put("appLastUpdateTime", app!!.appLastUpdateTime)

        // cache
        cacheJson.put("class", classCacheJson)
        cacheJson.put("methods", methodsCacheJson)
        ConfigV1.get().dexkitCache = cacheJson
    }

    // 拓展方法
    private fun ClassDataList?.instance(label: String): Class<*>? {
        return this?.singleOrNull().instance(label)
    }

    private fun ClassData?.instance(label: String): Class<*>? {
        KLogCat.tagI(TAG, "found-class[$label]: ${this?.name}")
        classCacheJson.put(label, "${this?.name}")
        return this?.getInstance(lparam.classLoader)
    }

    private fun MethodDataList.instanceAll(label: String): List<Method> {
        val array = JSONArray()
        methodsCacheJson.put(label, array)
        return this.filter {
            it.isMethod
        }.map {
            KLogCat.tagI(TAG, "found-method[$label]: $it")
            array.put(it.toJson())
            it.getMethodInstance(lparam.classLoader)
        }
    }

    private fun MethodData.toJson(): JSONObject {
        val json = JSONObject()
        json.put("className", className)
        json.put("methodName", methodName)
        json.put("paramTypeNames", paramTypeNames.joinToString())
        return json
    }

    private fun String.loadOrFindClass(): Class<*>? {
        if (KTextUtils.isEmpty(this)) {
            return null
        }

        return try {
            app?.classLoader?.loadClass(this) ?: lparam.findClass(this)
        } catch (e: Throwable) {
            null
        }
    }
}
