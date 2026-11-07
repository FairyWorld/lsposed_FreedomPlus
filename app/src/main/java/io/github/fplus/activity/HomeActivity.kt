package io.github.fplus.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.Icon
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.freegang.ktutils.app.KAppUtils
import com.freegang.ktutils.app.KToastUtils
import com.freegang.ktutils.log.KLogCat
import io.github.fplus.FreedomTheme
import io.github.fplus.HookStatus
import io.github.fplus.Themes
import io.github.fplus.core.ui.component.FCard
import io.github.fplus.resource.StringRes
import io.github.fplus.viewmodel.HomeVM

class HomeActivity : ComponentActivity() {
    private val moduleState = mutableStateOf("模块未加载!")

    private val model by viewModels<HomeVM>()

    @Composable
    fun TopBarView() {
        TopAppBar(
            modifier = Modifier.padding(vertical = 24.dp),
            elevation = 0.dp,
            backgroundColor = Themes.nowColors.colors.background,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = StringRes.moduleTitle,
                    style = Themes.nowTypography.subtitle1,
                )
                Spacer(modifier = Modifier.padding(vertical = 2.dp))
                Text(
                    text = StringRes.moduleSubtitle,
                    style = Themes.nowTypography.subtitle2,
                )
            }
        }
    }

    @Composable
    fun BodyView() {
        LazyColumn {
            // 模块状态
            item {
                FCard(
                    modifier = Modifier.padding(bottom = 24.dp, top = 12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                    ) {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val packageInfo = KAppUtils.getPackageInfo(application, "com.ss.android.ugc.aweme")
                            if (HookStatus.isEnabled) {
                                moduleState.value = if (HookStatus.framework == "Unknown") {
                                    "未知框架, 加载成功!"
                                } else {
                                    "${HookStatus.framework}加载成功!"
                                }
                                Text(
                                    text = moduleState.value,
                                    style = Themes.nowTypography.body1,
                                )
                            } else {
                                Text(
                                    text = StringRes.moduleHintFailed,
                                    style = Themes.nowTypography.body1,
                                )
                            }

                            if (packageInfo != null) {
                                var hint by remember { mutableStateOf("自行测试功能") }
                                LaunchedEffect("Versions") {
                                    hint = model.isSupportVersions(packageInfo.versionName)
                                }

                                Spacer(modifier = Modifier.padding(vertical = 2.dp))
                                Text(
                                    text = "抖音: ${packageInfo.versionName}，$hint",
                                    style = Themes.nowTypography.body2,
                                )
                            }
                        }
                    }
                }
            }

            // 模块设置
            item {
                FCard(
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = {
                                toModuleSetting()
                            }
                        ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "设置",
                            tint = Themes.nowColors.icon,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                        Column {
                            Text(
                                text = "模块设置",
                                style = Themes.nowTypography.body1,
                            )
                            Text(
                                text = "抖音内部左上角侧滑栏/加号按钮唤起模块设置",
                                style = Themes.nowTypography.overline,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FreedomTheme(
                window = window,
                isImmersive = true,
                isDark = false,
                followSystem = false,
            ) {
                Scaffold(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    topBar = { TopBarView() },
                ) {
                    Box(
                        modifier = Modifier.padding(it)
                    ) {
                        BodyView()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.updateVersions()
    }

    private fun toModuleSetting() {
        runCatching {
            if (!moduleState.value.contains("加载成功")) {
                KToastUtils.show(application, "模块未加载")
                return
            }

            val intent = Intent()
            intent.setClassName(
                "com.ss.android.ugc.aweme",
                "com.ss.android.ugc.aweme.main.MainActivity"
            )
            intent.putExtra("startModuleSetting", true)
            startActivity(intent)
        }.onFailure {
            KLogCat.e(it)
        }
    }
}
