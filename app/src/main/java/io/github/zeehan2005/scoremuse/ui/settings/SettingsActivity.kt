package io.github.zeehan2005.scoremuse.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import io.github.zeehan2005.scoremuse.service.LyricNotificationManager
import io.github.zeehan2005.scoremuse.global.AppSettings
import io.github.zeehan2005.scoremuse.global.AppVersion
import io.github.zeehan2005.scoremuse.ui.settings.ApiTestActivity
import io.github.zeehan2005.scoremuse.global.CardClickAction
import io.github.zeehan2005.scoremuse.global.UpdateChannel
import io.github.zeehan2005.scoremuse.ui.components.SwitchWithIcon
import dev.amll.droidmate.ui.settings.ComponentSettings
import io.github.zeehan2005.scoremuse.global.theme.DynamicThemeManager
import io.github.zeehan2005.scoremuse.global.theme.ScoreMuseTheme
import io.github.zeehan2005.scoremuse.components.GitHubUpdateChecker
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.jvm.java

/**
 * 应用主设置界面
 *
 * 这个 Activity 提供了应用的所有主要设置选项，包括：
 * - 歌词通知设置（启用/禁用、打开系统通知设置）
 * - 卡片点击行为配置（直接打开/询问/禁用）
 * - 自动更新检查（启用/禁用、更新渠道）
 * - 跳过上一首回退功能
 * - 元数据处理开关
 * - Agent 识别器开关
 * - 权限管理（通知监听权限）
 *
 * **设计理念**：
 * - 分组展示：相关设置放在同一卡片中
 * - 即时生效：修改后立即应用到 AppSettings
 * - 状态同步：UI 状态与持久化存储保持一致
 * - 权限引导：未授权时显示说明对话框
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 启用边缘到边缘显示
        enableEdgeToEdge()
        // 设置系统栏不遮挡内容
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val isDarkTheme = androidx.compose.foundation.isSystemInDarkTheme()
            val dynamicColorScheme by DynamicThemeManager.observeColorScheme()
            
            ScoreMuseTheme(
                darkTheme = isDarkTheme,
                dynamicColorScheme = dynamicColorScheme
            ) {
                SettingsPage(
                    onBack = { finish() },
                    onOpenNotificationSettings = {
                        startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                )
            }
        }
    }
}

@Composable
private fun SettingsPage(
    onBack: () -> Unit,
    onOpenNotificationSettings: () -> Unit
) {
    val context = LocalContext.current

    /** use the global dynamic color scheme as the source of truth for accent */
    val dynamicColorScheme by DynamicThemeManager.observeColorScheme()
    val rippleColor = dynamicColorScheme?.primary ?: MaterialTheme.colorScheme.primary

    /** keep switch thumb/track consistent with the dynamic accent color */
    val switchColors = SwitchDefaults.colors(
        checkedThumbColor = rippleColor,
        checkedTrackColor = rippleColor.copy(alpha = 0.5f),
        checkedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
        uncheckedTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.24f)
    )

    /**
     * we no longer need MainViewModel or album art extraction here; that work
     * is already performed in MainActivity and pushed to DynamicThemeManager
     */

    var selectedAction by remember { mutableStateOf(AppSettings.getCardClickAction(context)) }
    var lyricNotificationEnabled by remember {
        mutableStateOf(
            AppSettings.isLyricNotificationEnabled(
                context
            )
        )
    }
    var autoCheckEnabled by remember { mutableStateOf(AppSettings.isAutoUpdateCheckEnabled(context)) }
    var updateChannel by remember { mutableStateOf(AppSettings.getUpdateChannel(context)) }
    var skipPreviousRewinds by remember {
        mutableStateOf(
            AppSettings.isSkipPreviousRewindsEnabled(
                context
            )
        )
    }
    var songStructureBarEnabled by remember {
        mutableStateOf(
            AppSettings.isSongStructureBarEnabled(
                context
            )
        )
    }
    var updateDialogTitle by remember { mutableStateOf("") }
    var updateDialogMessage by remember { mutableStateOf("") }
    var updateDialogUrl by remember { mutableStateOf<String?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var checkingUpdate by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        lyricNotificationEnabled = granted
        AppSettings.setLyricNotificationEnabled(context, granted)
        if (!granted) {
            LyricNotificationManager(context).cancel()
        }
    }

    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topAppBarState)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    FilledIconButton(
                        onClick = onBack,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                ),
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingsHeader("常驻通知实时歌词")

            SettingsToggleItem(
                title = "常驻通知实时歌词",
                description = "需要通知权限。获得锁屏权限后可锁屏显示。",
                checked = lyricNotificationEnabled,
                onCheckedChange = { enabled ->
                    if (!enabled) {
                        lyricNotificationEnabled = false
                        AppSettings.setLyricNotificationEnabled(context, false)
                        LyricNotificationManager(context).cancel()
                    } else {
                        if (needsNotificationPermission() && !hasNotificationPermission(context)) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            lyricNotificationEnabled = true
                            AppSettings.setLyricNotificationEnabled(context, true)
                        }
                    }
                },
                switchColors = switchColors
            )

            SettingsHeader("歌曲结构显示条")

            SettingsToggleItem(
                title = "歌曲结构显示条",
                checked = songStructureBarEnabled,
                onCheckedChange = { enabled ->
                    songStructureBarEnabled = enabled
                    AppSettings.setSongStructureBarEnabled(context, enabled)
                },
                switchColors = switchColors
            )

            SettingsHeader("歌词组件设置")

            SettingsNavigateItem(
                title = "歌词组件设置",
                onClick = {
                    context.startActivity(Intent(context, ComponentSettings::class.java))
                }
            )

            SettingsHeader("\"正在播放\"卡片点击行为")

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CardClickActionOption(
                        title = "直接打开播放源应用",
                        selected = selectedAction == CardClickAction.DIRECT_OPEN,
                        onClick = {
                            selectedAction = CardClickAction.DIRECT_OPEN
                            AppSettings.setCardClickAction(context, CardClickAction.DIRECT_OPEN)
                        }
                    )
                    CardClickActionOption(
                        title = "询问",
                        selected = selectedAction == CardClickAction.ASK,
                        onClick = {
                            selectedAction = CardClickAction.ASK
                            AppSettings.setCardClickAction(context, CardClickAction.ASK)
                        }
                    )
                    CardClickActionOption(
                        title = "不操作",
                        selected = selectedAction == CardClickAction.NONE,
                        onClick = {
                            selectedAction = CardClickAction.NONE
                            AppSettings.setCardClickAction(context, CardClickAction.NONE)
                        }
                    )
                }
            }

            SettingsHeader("辅助功能")

            SettingsToggleItem(
                title = "点击上一首回到 0:00",
                description = "点击上一首按钮会先回到歌曲开头，而不是直接跳转到上一首。",
                checked = skipPreviousRewinds,
                onCheckedChange = { enabled ->
                    skipPreviousRewinds = enabled
                    AppSettings.setSkipPreviousRewindsEnabled(context, enabled)
                },
                switchColors = switchColors
            )



//            Card(
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .clickable {
//                        val newState = !processMetadataEnabled
//                        processMetadataEnabled = newState
//                        AppSettings.setMetadataProcessingEnabled(context, newState)
//                    },
//                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
//            ) {
//                Row(
//                    modifier = Modifier
//                        .fillMaxWidth()
//                        .padding(12.dp),
//                    horizontalArrangement = Arrangement.SpaceBetween,
//                    verticalAlignment = Alignment.CenterVertically
//                ) {
//                    Column(modifier = Modifier.fillMaxWidth(0.75f)) {
//                        Text("处理元数据（实验性）（当前版本请勿开启）", color = MaterialTheme.colorScheme.onSurface)
//                        Text(
//                            text = "尝试自动移除歌词中的\"词:\", \"曲:\", \"编曲:\"等行，可能会误删。\n由于当前的处理实现会破坏结构，请勿开启。",
//                            style = MaterialTheme.typography.bodySmall,
//                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
//                        )
//                    }
//                    SwitchWithIcon(
//                        checked = processMetadataEnabled,
//                        onCheckedChange = { enabled ->
//                            processMetadataEnabled = enabled
//                            AppSettings.setMetadataProcessingEnabled(context, enabled)
//                        },
//                        colors = switchColors
//                    )
//                }
//            )
//
//            Card(
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .clickable {
//                        val newState = !agentRecognizerEnabled
//                        agentRecognizerEnabled = newState
//                        AppSettings.setAgentRecognizerEnabled(context, newState)
//                    },
//                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
//            ) {
//                Row(
//                    modifier = Modifier
//                        .fillMaxWidth()
//                        .padding(12.dp),
//                    horizontalArrangement = Arrangement.SpaceBetween,
//                    verticalAlignment = Alignment.CenterVertically
//                ) {
//                    Column(modifier = Modifier.fillMaxWidth(0.75f)) {
//                        Text("角色识别（实验性）（当前版本请勿开启）", color = MaterialTheme.colorScheme.onSurface)
//                        Text(
//                            text = "尝试识别当前句由哪位歌手演唱并设置对唱歌词，可能存在错误。\n由于当前的处理实现会破坏结构，请勿开启。",
//                            style = MaterialTheme.typography.bodySmall,
//                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
//                        )
//                    }
//                    SwitchWithIcon(
//                        checked = agentRecognizerEnabled,
//                        onCheckedChange = { enabled ->
//                            agentRecognizerEnabled = enabled
//                            AppSettings.setAgentRecognizerEnabled(context, enabled)
//                        },
//                        colors = switchColors
//                    )
//                }
//            }


            SettingsHeader("歌词时间轴偏移")
            SettingsLabel("歌曲偏移 + 设备偏移")
            SettingsNavigateItem(
                title = "歌词时间轴偏移设置",
                onClick = {
                    context.startActivity(Intent(context, LyricOffsetSettingsActivity::class.java))
                }
            )




            SettingsHeader("版本更新")

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "当前版本: ${AppVersion.current(context)}",
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val newState = !autoCheckEnabled
                                autoCheckEnabled = newState
                                AppSettings.setAutoUpdateCheckEnabled(context, newState)
                            },
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.fillMaxWidth(0.75f)) {
                            Text("自动检查更新", color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                text = "自动检查 GitHub Release",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        SwitchWithIcon(
                            checked = autoCheckEnabled,
                            onCheckedChange = { enabled ->
                                autoCheckEnabled = enabled
                                AppSettings.setAutoUpdateCheckEnabled(context, enabled)
                            },
                            colors = switchColors
                        )
                    }

                    Text(
                        text = "更新通道",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    CardClickActionOption(
                        title = "正式版",
                        selected = updateChannel == UpdateChannel.STABLE,
                        onClick = {
                            updateChannel = UpdateChannel.STABLE
                            AppSettings.setUpdateChannel(context, UpdateChannel.STABLE)
                        }
                    )
                    CardClickActionOption(
                        title = "测试版",
                        selected = updateChannel == UpdateChannel.BETA,
                        onClick = {
                            updateChannel = UpdateChannel.BETA
                            AppSettings.setUpdateChannel(context, UpdateChannel.BETA)
                        }
                    )
                    CardClickActionOption(
                        title = "开发版",
                        selected = updateChannel == UpdateChannel.ALPHA,
                        onClick = {
                            updateChannel = UpdateChannel.ALPHA
                            AppSettings.setUpdateChannel(context, UpdateChannel.ALPHA)
                        }
                    )

                    Button(
                        onClick = {
                            if (checkingUpdate) return@Button
                            checkingUpdate = true
                            scope.launch {
                                val result = GitHubUpdateChecker.check(context, updateChannel)
                                AppSettings.setLastUpdateCheckAt(
                                    context,
                                    System.currentTimeMillis()
                                )
                                checkingUpdate = false

                                if (result.hasUpdate) {
                                    updateDialogTitle =
                                        "发现新版本: ${result.resolvedReleaseTag ?: "未知版本"}"
                                    updateDialogMessage = buildString {
                                        append("当前版本: ${result.currentVersionName}\n")
                                        if (result.resolvedPublishedAt != null) {
                                            append("发布时间: ${formatReleaseTime(result.resolvedPublishedAt)}\n\n")
                                        }
                                        if (!result.resolvedReleaseNotes.isNullOrBlank()) {
                                            append(result.resolvedReleaseNotes)
                                        } else {
                                            append("暂无更新说明")
                                        }
                                    }
                                    updateDialogUrl = result.resolvedReleaseUrl
                                } else {
                                    updateDialogTitle = "检查更新"
                                    updateDialogMessage = result.reason ?: "当前已是最新版本"
                                    updateDialogUrl = null
                                }
                                showUpdateDialog = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (checkingUpdate) "检查中..." else "检查更新")
                    }
                }
            }

            SettingsHeader("通知访问权限")
            SettingsDescription("必需权限。用于获取正在播放信息。")
            SettingsDescription("滥用通知使用权危及安全，因此系统可能会弹窗阻止。本应用是开源软件，您可以查看本应用的执行逻辑，因此在应用来源可靠的情况下无需感到担忧。")
            SettingsNavigateItem(
                title = "转至“读取、回复和控制通知”",
                onClick = onOpenNotificationSettings
            )

            SettingsHeader("开发者工具")

            SettingsNavigateItem(
                title = "查看日志",
                onClick = {
                    context.startActivity(Intent(context, LogDisplayActivity::class.java))
                }
            )

            SettingsNavigateItem(
                title = "API 检测",
                onClick = {
                    context.startActivity(Intent(context, ApiTestActivity::class.java))
                }
            )

            SettingsHeader("项目与贡献")

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    "https://github.com/Zeehan2005/AMLL-DroidMate".toUri()
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("项目仓库")
                    }

                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    "https://github.com/amll-dev/amll-ttml-db/blob/main/README.md".toUri()
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("在 AMLL TTML DB 协助改进歌词")
                    }
                }
            }
        }

        if (showUpdateDialog) {
            AlertDialog(
                onDismissRequest = { showUpdateDialog = false },
                containerColor = MaterialTheme.colorScheme.background,
                title = { Text(updateDialogTitle) },
                text = { Text(updateDialogMessage) },
                confirmButton = {
                    if (!updateDialogUrl.isNullOrBlank()) {
                        TextButton(
                            onClick = {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        updateDialogUrl!!.toUri()
                                    )
                                )
                                showUpdateDialog = false
                            }
                        ) {
                            Text("查看详情")
                        }
                    } else {
                        TextButton(onClick = { showUpdateDialog = false }) {
                            Text("知道了")
                        }
                    }
                },
                dismissButton = {
                    if (!updateDialogUrl.isNullOrBlank()) {
                        TextButton(onClick = {
                            AppSettings.setLastUpdateLaterAt(context, System.currentTimeMillis())
                            showUpdateDialog = false
                        }) {
                            Text("稍后")
                        }
                    }
                }
            )
        }
    }
}

// ── 设置页面通用组件 ─────────────────────────────────────────────────

/**
 * 设置分组节标题
 */
@Composable
private fun SettingsHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
}

/**
 * 设置项描述文字
 */
@Composable
private fun SettingsDescription(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    )
}

/**
 * 设置项小标签
 */
@Composable
private fun SettingsLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    )
}

/**
 * 开关切换设置项卡片。点击卡片或开关都会触发切换。
 *
 * @param title 主标题
 * @param description 可选描述文字（非 null 时显示）
 * @param checked 当前开关状态
 * @param onCheckedChange 切换回调（接收新状态）
 * @param switchColors 开关配色
 */
@Composable
private fun SettingsToggleItem(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    switchColors: SwitchColors,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.fillMaxWidth(if (description != null) 0.75f else 0.78f)) {
                Text(title, color = MaterialTheme.colorScheme.onSurface)
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
            SwitchWithIcon(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = switchColors
            )
        }
    }
}

/**
 * 导航型设置项卡片，右侧显示箭头。
 */
@Composable
private fun SettingsNavigateItem(
    title: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = MaterialTheme.colorScheme.onSurface)
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "进入",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun formatReleaseTime(instant: Instant): String {
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(instant)
}

private fun needsNotificationPermission(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}

private fun hasNotificationPermission(context: Context): Boolean {
    if (!needsNotificationPermission()) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun CardClickActionOption(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}