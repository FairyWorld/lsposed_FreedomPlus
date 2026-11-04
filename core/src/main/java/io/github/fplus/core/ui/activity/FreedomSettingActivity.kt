package io.github.fplus.core.ui.activity

import android.content.Context
import android.os.Bundle
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Checkbox
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.ExposedDropdownMenuBox
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.RadioButton
import androidx.compose.material.Scaffold
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.material.TextButton
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.freegang.extension.findMethodInvoke
import com.freegang.ktutils.app.KToastUtils
import io.github.fplus.core.helper.DexkitBuilder
import io.github.fplus.core.ui.ModuleTheme
import io.github.fplus.core.ui.asDp
import io.github.fplus.core.ui.component.FCard
import io.github.fplus.core.ui.component.FMessageDialog
import io.github.fplus.core.ui.viewmodel.FreedomSettingVM
import io.github.fplus.plugin.activity.XplerActivity
import io.github.fplus.resource.IconRes
import io.github.fplus.resource.icons.Manage
import io.github.fplus.resource.icons.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.system.exitProcess

class FreedomSettingActivity : XplerActivity() {
    private val model by lazy {
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(application))
            .get(FreedomSettingVM::class.java)
    }

    private var isModuleStart = false
    private var isDark = false

    private var showRestartAppDialog = mutableStateOf(false)

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun TopBarView() {
        var showLogDialog by remember { mutableStateOf(false) }

        var rotate by remember { mutableFloatStateOf(0f) }
        val rotateAnimate by animateFloatAsState(
            targetValue = rotate,
            animationSpec = tween(durationMillis = Random.nextInt(500, 1500)),
        )

        var showUpdateLogDialog by remember { mutableStateOf(false) }
        var updateLog by remember { mutableStateOf("") }

        TopAppBar(
            elevation = 0.dp,
            backgroundColor = MaterialTheme.colors.background,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    modifier = Modifier
                        .size(20.dp)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = {
                                onBackPressedDispatcher.onBackPressed()
                            },
                        ),
                )
                Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Freedom+ Setting",
                        style = MaterialTheme.typography.body1.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colors.onSurface,
                        ),
                    )
                    Text(
                        text = "No one is always happy.",
                        style = MaterialTheme.typography.body1.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colors.onSurface.copy(0.5f),
                        ),
                    )
                }
                Icon(
                    imageVector = IconRes.Manage,
                    contentDescription = "Log",
                    modifier = Modifier
                        .size(24.dp)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = {
                                if (!model.hasDexkitCache) {
                                    KToastUtils.show(application, "没有类日志")
                                    return@clickable
                                }
                                showLogDialog = true
                            },
                        ),
                )
                Spacer(modifier = Modifier.padding(horizontal = 12.dp))
                Icon(
                    imageVector = IconRes.Motion,
                    contentDescription = "更新日志",
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(rotateAnimate)
                        .combinedClickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onLongClick = {
                                lifecycleScope.launch {
                                    withContext(Dispatchers.IO) {
                                        runCatching {
                                            // pluginAssets
                                            assets
                                                .open("update.txt")
                                                .use {
                                                    updateLog = it
                                                        .readBytes()
                                                        .decodeToString()
                                                }
                                        }.onFailure {
                                            withContext(Dispatchers.Main) {
                                                KToastUtils.show(application, "更新日志获取失败")
                                            }
                                        }
                                    }
                                    showUpdateLogDialog = updateLog.isNotBlank()
                                }
                            },
                            onClick = {
                                rotate = if (rotate == 0f) 360f else 0f
                            },
                        ),
                )
            }

            // 类日志弹窗
            if (showLogDialog) {
                FMessageDialog(
                    title = "类日志",
                    cancel = "取消",
                    confirm = "清除",
                    onCancel = {
                        showLogDialog = false
                    },
                    onConfirm = {
                        showLogDialog = false
                        model.clearDexkitCache()
                        KToastUtils.show(application, "类日志清除")
                    }
                ) {
                    Text(
                        text = """
                                    类日志是对混淆类名的本地存储操作, 设计该功能的主要目的是为了减少启动时间。
                                    清除类日志并不会造成功能丢失，相反来说若某功能无法正常使用, 也可尝试手动清除类日志, 模块在下次启动时会对功能类重新搜索。
                                """.trimIndent(),
                    )
                }
            }

            // 更新日志弹窗
            if (showUpdateLogDialog) {
                FMessageDialog(
                    title = "更新日志",
                    onlyConfirm = true,
                    confirm = "确定",
                    onConfirm = { showUpdateLogDialog = false },
                ) {
                    LazyColumn(
                        modifier = Modifier,
                    ) {
                        item {
                            SelectionContainer {
                                Text(
                                    modifier = Modifier.fillMaxWidth(),
                                    text = updateLog,
                                    style = MaterialTheme.typography.body1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun BodyView(
        modifier: Modifier,
    ) {
        RestartDialog()

        LazyColumn(
            modifier = modifier,
        ) {
            item { DownloadItem() }
            item { DoubleClickTypeItem() }
            item { NeatModeItem() }
            item { DialogFilterItem() }
            item { VideoFilterItem() }
            item { ImmersiveItem() }
            item { SelfDisciplineItem() }
        }
    }

    @Composable
    private fun RestartDialog() {
        if (showRestartAppDialog.value) {
            FMessageDialog(
                title = "提示",
                cancel = "取消",
                confirm = "重启",
                onCancel = {
                    showRestartAppDialog.value = false
                },
                onConfirm = {
                    showRestartAppDialog.value = false
                    runCatching {
                        // model.setVersionConfig(pluginAssets)
                        model.setVersionConfig(assets)
                    }.onFailure {
                        model.setVersionConfig(null)
                    }
                    // KAppUtils.restartApplication(application)
                    DexkitBuilder.restartUtilsClazz?.findMethodInvoke<Any>(this) {
                        parameterTypes(listOf(Context::class.java))
                    }
                },
            ) {
                Text(
                    text = "需要重启应用生效, 若未重启请手动重启",
                    style = MaterialTheme.typography.body1,
                )
            }
        }
    }

    @Composable
    private fun DownloadItem() {
        var showTipsDialog by remember { mutableStateOf(false) }
        var showSettingDialog by remember { mutableStateOf(false) }

        SwitchItem(
            text = "视频/图文/音乐下载",
            subtext = "点击调整相关设置",
            checked = model.isDownload.observeAsState(false),
            onClick = {
                showSettingDialog = true
            },
            onCheckedChange = {
                model.changeIsDownload(it)
                if (it) {
                    showTipsDialog = true
                }
            }
        )

        if (showTipsDialog) {
            FMessageDialog(
                title = "提示",
                confirm = "确定",
                onlyConfirm = true,
                onConfirm = {
                    showTipsDialog = false
                }
            ) {
                Text(
                    text = buildAnnotatedString {
                        append("开启后请在清爽模式弹窗中下载。")
                    },
                )
            }
        }

        if (showSettingDialog) {
            FMessageDialog(
                title = "相关设置",
                confirm = "确定",
                onlyConfirm = true,
                onConfirm = {
                    showSettingDialog = false
                }
            ) {
                Column {
                    CheckBoxItem(
                        text = "视频创作者单独创建文件夹",
                        checked = model.isOwnerDir.observeAsState(false),
                        onCheckedChange = {
                            model.setOwnerDir(it)
                        }
                    )
                    CheckBoxItem(
                        text = "通知栏显示下载进度",
                        checked = model.isNotification.observeAsState(false),
                        onCheckedChange = {
                            model.setNotificationDownload(it)
                        }
                    )
                    CheckBoxItem(
                        text = "“分享->复制链接”弹出下载",
                        checked = model.isCopyDownload.observeAsState(false),
                        onCheckedChange = {
                            model.setCopyLinkDownload(it)
                        }
                    )

                    Divider()

                    Box {
                        val menus by remember { mutableStateOf(listOf("Auto", "H264", "H265")) }
                        val videoCoding by model.videoCoding.observeAsState("Auto")

                        ExposedDropdownItem(
                            text = "视频编码类型",
                            value = videoCoding,
                            menus = menus,
                            onSelected = {
                                model.setVideoCoding(it)
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun DoubleClickTypeItem() {
        var showDoubleClickModeDialog by remember { mutableStateOf(false) }

        SwitchItem(
            text = "双击视频响应类型",
            subtext = "点击调整双击视频响应方式",
            checked = model.isDoubleClickType.observeAsState(false),
            onClick = {
                showDoubleClickModeDialog = true
            },
            onCheckedChange = {
                model.changeIsDoubleClickType(it)
            }
        )

        if (showDoubleClickModeDialog) {
            var radioIndex by remember { mutableStateOf(model.doubleClickType.value ?: 2) }
            FMessageDialog(
                title = "请选择双击响应模式",
                confirm = "更改",
                onlyConfirm = true,
                onConfirm = { showDoubleClickModeDialog = false },
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = radioIndex == 1,
                            onClick = {
                                radioIndex = 1
                                model.setDoubleClickType(radioIndex)
                            },
                        )
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        Text(
                            text = "打开评论",
                            style = MaterialTheme.typography.body1,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = radioIndex == 2,
                            onClick = {
                                radioIndex = 2
                                model.setDoubleClickType(radioIndex)
                            },
                        )
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        Text(
                            text = "点赞视频",
                            style = MaterialTheme.typography.body1,
                        )
                    }
                }
            }
        }
    }

    @Composable
    @OptIn(ExperimentalFoundationApi::class)
    private fun NeatModeItem() {
        var showLongPressModeDialog by remember { mutableStateOf(false) }

        SwitchItem(
            text = "清爽模式",
            subtext = "长按视频进入清爽模式, 点击更改响应模式",
            checked = model.isNeatMode.observeAsState(false),
            onClick = {
                showLongPressModeDialog = true
            },
            onCheckedChange = {
                model.changeIsNeatMode(it)
            }
        )

        if (showLongPressModeDialog) {
            val longPressMode by model.longPressMode.observeAsState(false)

            FMessageDialog(
                title = "请选择响应模式",
                confirm = "更改",
                onlyConfirm = true,
                onConfirm = { showLongPressModeDialog = false },
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = longPressMode,
                            onClick = {
                                model.setLongPressMode(true)
                            },
                        )
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        Text(
                            text = "长按视频上半",
                            style = MaterialTheme.typography.body1,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = !longPressMode,
                            onClick = {
                                model.setLongPressMode(false)
                            },
                        )
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        Text(
                            text = "长按视频下半",
                            style = MaterialTheme.typography.body1,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun DialogFilterItem() {
        var showSettingDialog by remember { mutableStateOf(false) }

        SwitchItem(
            text = "弹窗过滤",
            subtext = "自动关闭包含关键字的弹窗, 点击设置关键字",
            checked = model.isDialogFilter.observeAsState(false),
            onClick = {
                showSettingDialog = true
            },
            onCheckedChange = {
                model.changeIsDialogFilter(it)
            }
        )

        if (showSettingDialog) {
            val dialogDismissTips by model.dialogDismissTips.observeAsState(false)
            val keywords by model.dialogFilterKeywords.observeAsState("")
            val textState = remember { mutableStateOf(keywords) }

            FMessageDialog(
                title = "弹窗过滤设置",
                cancel = "取消",
                confirm = "保存",
                onCancel = {
                    showSettingDialog = false
                },
                onConfirm = {
                    model.setDialogFilterKeywords(textState.value)
                    showSettingDialog = false
                },
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = dialogDismissTips,
                            onCheckedChange = { model.setDialogDismissTips(it) },
                        )
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        Text(
                            text = "关闭弹窗时提示",
                            style = MaterialTheme.typography.body1,
                        )
                    }
                    Spacer(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        text = "过滤关键字(逗号分隔)",
                        style = MaterialTheme.typography.body2,
                    )
                    Spacer(modifier = Modifier.padding(vertical = 4.dp))
                    TextField(
                        value = textState.value,
                        onValueChange = { textState.value = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 3,
                        placeholder = { Text("现在安装, 立即升级") },
                    )
                }
            }
        }
    }

    @Composable
    private fun VideoFilterItem() {
        var showSettingDialog by remember { mutableStateOf(false) }

        SwitchItem(
            text = "视频过滤",
            subtext = "自动过滤直播/广告/图文等视频, 点击设置关键字",
            checked = model.isVideoFilter.observeAsState(false),
            onClick = {
                showSettingDialog = true
            },
            onCheckedChange = {
                model.changeIsVideoFilter(it)
            }
        )

        if (showSettingDialog) {
            val keywords by model.videoFilterKeywords.observeAsState("")
            val textState = remember { mutableStateOf(keywords) }

            FMessageDialog(
                title = "视频过滤设置",
                cancel = "取消",
                confirm = "保存",
                onCancel = {
                    showSettingDialog = false
                },
                onConfirm = {
                    model.setVideoFilterKeywords(textState.value)
                    showSettingDialog = false
                },
            ) {
                Column {
                    Text(
                        text = "内置过滤类型: 直播, 广告, 图文, 长视频, 推荐卡片, 推荐商家, 空文案",
                        style = MaterialTheme.typography.body2,
                    )
                    Spacer(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        text = "自定义过滤关键字(逗号分隔, 匹配视频文案)",
                        style = MaterialTheme.typography.body2,
                    )
                    Spacer(modifier = Modifier.padding(vertical = 4.dp))
                    TextField(
                        value = textState.value,
                        onValueChange = { textState.value = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 3,
                        placeholder = { Text("直播, 广告") },
                    )
                }
            }
        }
    }

    @Composable
    private fun ImmersiveItem() {
        var showSettingDialog by remember { mutableStateOf(false) }

        SwitchItem(
            text = "全屏沉浸",
            subtext = "全屏沉浸式播放, 会造成视频剪辑拉伸, 点击调整设置",
            checked = model.isImmersive.observeAsState(false),
            onClick = {
                showSettingDialog = true
            },
            onCheckedChange = {
                model.changeIsImmersive(it)
                showRestartAppDialog.value = true
            }
        )

        if (showSettingDialog) {
            val systemControllerValue = model.systemControllerValue.value ?: listOf(false, false)
            val isHideStatusBar = remember { mutableStateOf(systemControllerValue[0]) }
            val isHideNavigateBar = remember { mutableStateOf(systemControllerValue[1]) }
            FMessageDialog(
                title = "请选择系统隐藏项",
                confirm = "更改",
                onlyConfirm = true,
                onConfirm = {
                    showSettingDialog = false
                    model.setSystemControllerValue(
                        listOf(
                            isHideStatusBar.value,
                            isHideNavigateBar.value,
                        )
                    )
                    showRestartAppDialog.value = true
                },
            ) {
                Column {
                    CheckBoxItem(
                        text = "隐藏状态栏",
                        checked = isHideStatusBar,
                        onCheckedChange = {
                            isHideStatusBar.value = it
                        },
                    )

                    CheckBoxItem(
                        text = "隐藏导航栏",
                        checked = isHideNavigateBar,
                        onCheckedChange = {
                            isHideNavigateBar.value = it
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun SelfDisciplineItem() {
        var showSettingDialog by remember { mutableStateOf(false) }

        SwitchItem(
            text = "自律",
            subtext = "限制抖音每日/每小时使用时长, 点击设置",
            checked = model.isSelfDiscipline.observeAsState(false),
            onClick = {
                showSettingDialog = true
            },
            onCheckedChange = {
                model.changeIsSelfDiscipline(it)
                showRestartAppDialog.value = true
            },
        )

        if (showSettingDialog) {
            val value = model.selfDisciplineValue.observeAsState(listOf(120, 30)).value
            val blockType by model.selfDisciplineBlockType.observeAsState(0)
            var dayLimit by remember { mutableStateOf("${value.getOrElse(0) { 120 }}") }
            var hourLimit by remember { mutableStateOf("${value.getOrElse(1) { 30 }}") }
            var directExit by remember { mutableStateOf((blockType ?: 0) == 1) }

            FMessageDialog(
                title = "自律设置",
                cancel = "取消",
                confirm = "确定",
                onCancel = {
                    showSettingDialog = false
                },
                onConfirm = {
                    val intDay = dayLimit.toIntOrNull() ?: -1
                    val intHour = hourLimit.toIntOrNull() ?: -1
                    if (intDay < 0 || intHour < 0) {
                        KToastUtils.show(application, "请输入正确的分钟数")
                        return@FMessageDialog
                    }
                    if (intDay == 0 && intHour == 0) {
                        KToastUtils.show(application, "每日和每小时限制不能同时为0")
                        return@FMessageDialog
                    }
                    if (intDay > 1440) {
                        KToastUtils.show(application, "每日限制不能超过1440分钟")
                        return@FMessageDialog
                    }
                    if (intHour > 60) {
                        KToastUtils.show(application, "每小时限制不能超过60分钟")
                        return@FMessageDialog
                    }
                    showSettingDialog = false
                    model.setSelfDisciplineValue(listOf(intDay, intHour))
                    model.setSelfDisciplineBlockType(if (directExit) 1 else 0)
                    KToastUtils.show(application, "下次启动生效!")
                },
            ) {
                Column {
                    Text(
                        text = "每日限制(分钟, 0=不限)",
                        style = MaterialTheme.typography.body2,
                    )
                    Spacer(modifier = Modifier.padding(vertical = 4.dp))
                    TextField(
                        value = dayLimit,
                        onValueChange = { dayLimit = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                    )
                    Spacer(modifier = Modifier.padding(vertical = 12.dp))
                    Text(
                        text = "每小时限制(分钟, 0=不限)",
                        style = MaterialTheme.typography.body2,
                    )
                    Spacer(modifier = Modifier.padding(vertical = 4.dp))
                    TextField(
                        value = hourLimit,
                        onValueChange = { hourLimit = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                    )
                    Spacer(modifier = Modifier.padding(vertical = 12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = directExit,
                            onCheckedChange = { directExit = it },
                        )
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        Text(
                            text = "到达限制后直接退出应用",
                            style = MaterialTheme.typography.body1,
                        )
                    }
                }
            }
        }
    }

    @Composable
    @OptIn(ExperimentalMaterialApi::class, ExperimentalFoundationApi::class)
    private fun SwitchItem(
        text: String,
        subtext: String = "",
        isWaiting: Boolean = false,
        checked: State<Boolean>,
        onCheckedChange: (checked: Boolean) -> Unit,
        onClick: () -> Unit = {},
        onLongClick: () -> Unit = {},
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .combinedClickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {
                        onClick.invoke()
                    },
                    onLongClick = {
                        onLongClick.invoke()
                    }
                )
                .then(if (subtext.isNotBlank()) Modifier.padding(vertical = 4.dp) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.body1,
                )
                if (subtext.isNotBlank()) {
                    Text(
                        modifier = Modifier.padding(vertical = 2.dp),
                        text = subtext,
                        style = MaterialTheme.typography.body2,
                    )
                }
            }
            if (isWaiting) {
                Box(
                    modifier = Modifier
                        .wrapContentSize(Alignment.Center)
                        .padding(17.dp), // switch: width = 34.dp
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(MaterialTheme.typography.body1.fontSize.asDp),
                    )
                }
            } else {
                Switch(
                    checked = checked.value,
                    onCheckedChange = {
                        onCheckedChange.invoke(it)
                    },
                )
            }
        }
    }

    @Composable
    private fun CheckBoxItem(
        text: String,
        checked: State<Boolean>,
        onCheckedChange: ((Boolean) -> Unit)?,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = checked.value,
                onCheckedChange = onCheckedChange,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.body1,
            )
        }
    }

    @OptIn(ExperimentalMaterialApi::class)
    @Composable
    private fun ExposedDropdownItem(
        text: String,
        value: String,
        menus: List<String>,
        onSelected: (String) -> Unit,
    ) {
        var expanded by remember { mutableStateOf(false) }

        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.body1,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = {
                    expanded = it
                },
            ) {
                TextButton(
                    onClick = {
                        expanded = true
                    },
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.body1,
                    )
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        "Trailing icon for exposed dropdown menu",
                        Modifier.rotate(
                            if (expanded)
                                180f
                            else
                                360f
                        )
                    )
                }

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = {
                        expanded = false
                    },
                ) {
                    for (menu in menus) {
                        Text(
                            text = menu,
                            style = MaterialTheme.typography.body1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .clickable {
                                    expanded = false
                                    onSelected.invoke(menu)
                                }
                        )
                    }
                }
            }
        }
    }

    private fun initExtra() {
        isModuleStart = intent.getBooleanExtra("isModuleStart", false)
        isDark = intent.getBooleanExtra("isDark", false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initExtra()
        setContent {
            ModuleTheme(
                isDark = isDark,
                followSystem = false,
            ) {
                Scaffold(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    topBar = {
                        TopBarView()
                    }
                ) {
                    BodyView(
                        modifier = Modifier.padding(it),
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.loadConfig()
    }

    override fun onPause() {
        super.onPause()
        runCatching {
            // model.setVersionConfig(pluginAssets)
            model.setVersionConfig(assets)
        }.onFailure {
            model.setVersionConfig(null)
        }
    }

    override fun finish() {
        super.finish()
        if (isModuleStart) exitProcess(0)
    }
}