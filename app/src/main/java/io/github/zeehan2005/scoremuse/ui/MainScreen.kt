package io.github.zeehan2005.scoremuse.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Trace
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import io.github.zeehan2005.scoremuse.global.viewmodel.MainViewModel
import dev.amll.droidmate.R
import io.github.zeehan2005.scoremuse.global.AppSettings
import io.github.zeehan2005.scoremuse.global.CardClickAction
import io.github.zeehan2005.scoremuse.global.NowPlayingMusic
import io.github.zeehan2005.scoremuse.global.SongStructure
import io.github.zeehan2005.scoremuse.global.UnifiedLyrics
import dev.amll.droidmate.global.AMLLSettings
import io.github.zeehan2005.scoremuse.ui.components.WavySlider
import io.github.zeehan2005.scoremuse.ui.components.WavySliderDefaults
import io.github.zeehan2005.scoremuse.ui.settings.SettingsActivity
import io.github.zeehan2005.scoremuse.global.theme.AlbumColorExtractor
import io.github.zeehan2005.scoremuse.components.GitHubUpdateChecker
import io.github.zeehan2005.scoremuse.ui.components.SongStructureBar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale
import dev.amll.droidmate.components.AMLLLyricsView
import kotlin.collections.isNotEmpty
import kotlin.collections.map
import kotlin.jvm.java

/**
 * 主界面屏幕 - 应用的核心 UI
 *
 * 这是用户看到的主要界面，包含以下功能模块：
 * - 当前播放信息卡片（歌名、艺术家、专辑）
 * - 歌词显示区域
 * - 设置入口
 * - 权限管理（通知、媒体访问）
 * - 动画效果（平滑过渡和交互反馈）
 *
 * 主要特性：
 * - Compose 声明式 UI
 * - 响应式布局（适配不同屏幕尺寸）
 * - 动态主题（根据专辑封面变色）
 * - 手势支持（滑动、点击）
 * - 权限请求处理
 */
fun getAppNameFromPackage(context: Context, packageName: String?): String? {
    if (packageName.isNullOrBlank()) return null
    return try {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(packageName, 0)
        pm.getApplicationLabel(appInfo).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}

/** helpers moved here so they are visible to MainScreen early in the file */
private fun isNotificationAccessGranted(context: Context): Boolean =
    Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")?.contains(context.packageName) == true

@Composable
private fun AdaptiveStatusBarStyle(useDarkIcons: Boolean) {
    val view = LocalView.current
    SideEffect {
        val activity = view.context.findActivity() ?: return@SideEffect
        WindowCompat.getInsetsController(activity.window, view).isAppearanceLightStatusBars =
            useDarkIcons
    }
}

/** used by AdaptiveStatusBarStyle */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val viewModel: MainViewModel = viewModel()
    val nowPlaying by viewModel.nowPlayingMusic.collectAsState()
    val isDarkTheme = isSystemInDarkTheme()
    /** rippleColor tracks album-art-based primary color; falls back to theme primary if extraction fails */
    val initialPrimary = MaterialTheme.colorScheme.primary
    val rippleColor = remember { mutableStateOf(initialPrimary) }
    /**
     * derived background color used by both now playing card and dropdown menu
     * always use the darker alpha so light mode matches dark mode
     */
    val cardBg = rippleColor.value
/**
 * // 菜单背景：在容器表面色基础上叠加 15% 的主题色，保持不透明的同时跟随主题色变化
 * val menuBg = rippleColor.value.copy(alpha = 0.15f).compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh)
 */
    val lyrics by viewModel.lyrics.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val songStructures by viewModel.songStructures.collectAsState()
    val isSongStructureBarEnabled by viewModel.isSongStructureBarEnabled.collectAsState()
    /** 使用 derivedStateOf 优化状态计算，减少不必要的 recomposition */
    val currentTime by remember {
        derivedStateOf {
            nowPlaying?.currentPosition ?: 0L
        }
    }
    /** 歌曲结构当前索引，仅在跨越段落边界时变化，避免 SongStructureBarSection 频繁重组 */
    val currentStructureIndex by remember(songStructures) {
        derivedStateOf {
            songStructures.indexOfFirst { currentTime in it.startTime..it.endTime }
        }
    }
    /** Apply user-configured lyric timing offsets when updating the lyric view */
    val lyricTime by remember {
        derivedStateOf {
            viewModel.getLyricTimeWithDeviceOffset(nowPlaying)
        }
    }
    var notificationAccessGranted by remember { mutableStateOf(isNotificationAccessGranted(context)) }

    // update ripple color whenever album art changes or theme toggles
    // use initialPrimary rather than recomputing MaterialTheme inside the coroutine
    LaunchedEffect(nowPlaying?.albumArtUri, isDarkTheme) {
        val uri = nowPlaying?.albumArtUri
        if (!uri.isNullOrBlank()) {
            try {
                /** 优化：将颜色提取移���后台线程，避免阻塞UI线程 */
                val colors = withContext(Dispatchers.Default) {
                    AlbumColorExtractor.extractColorsFromAlbumArt(context, uri, isDarkTheme)
                }
                rippleColor.value = colors?.primary ?: initialPrimary
            } catch (_: Exception) {
                rippleColor.value = initialPrimary
            }

        } else {
            rippleColor.value = initialPrimary
        }
    }
    var isLyricsFullscreen by remember { mutableStateOf(false) }

    var webViewReloadKey by remember { mutableIntStateOf(0) }
    var showMenu by remember { mutableStateOf(false) }
    var showOpenAppDialog by remember { mutableStateOf(false) }
    var showAutoUpdateDialog by remember { mutableStateOf(false) }
    var autoUpdateDialogTitle by remember { mutableStateOf("") }
    var autoUpdateDialogMessage by remember { mutableStateOf("") }
    var autoUpdateDialogUrl by remember { mutableStateOf<String?>(null) }

    /** 全屏控制状态 */
    var controlsVisible by remember { mutableStateOf(true) }
    var hideControlsJob by remember { mutableStateOf<Job?>(null) }
    /**
     * fadeOut 动画时长。作为唯一时序源，控制 controlsAlpha 动画与
     * controlsInLayout 的判断逻辑。
     */
    val controlsTransitionDuration = 250
    val controlsAlpha by animateFloatAsState(
        targetValue = if (controlsVisible && isLyricsFullscreen) 1f else 0f,
        animationSpec = tween(durationMillis = controlsTransitionDuration),
        label = "controlsAlpha"
    )
    /**
     * 控件是否在布局中：完全以 controlsAlpha 动画的 ground truth 为依据。
     * - controlsVisible=true → 立即在布局中（alpha 正在淡入）
     * - controlsAlpha>0.01 → 仍在 fadeOut 动画中，保留在布局中让动画走完
     * - controlsAlpha≤0.01 → 动画已完成，从布局中彻底移除（不再拦截点击）
     * 这样避免依赖于额外状态，确保动画与布局完全同步，不闪烁。
     */
    val controlsInLayout = controlsVisible || controlsAlpha > 0.01f

    /** 在 Composable 上下文中创建协程作用域，供回调函数使用 */
    val scope = rememberCoroutineScope()

    fun resetHideTimer() {
        hideControlsJob?.cancel()
        if (isLyricsFullscreen) {
            controlsVisible = true
            hideControlsJob = scope.launch { delay(3000L); controlsVisible = false }
        }
    }

    AdaptiveStatusBarStyle(useDarkIcons = !isLyricsFullscreen && MaterialTheme.colorScheme.background.luminance() > 0.5f)

    val customLyricsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val lyricsText = data?.getStringExtra(CustomLyricsActivity.EXTRA_LYRICS_TEXT).orEmpty()
            if (lyricsText.isNotBlank()) {
                viewModel.applyCustomLyricsInput(
                    content = lyricsText,
                    title = data?.getStringExtra(CustomLyricsActivity.EXTRA_TITLE)
                        ?: nowPlaying?.title ?: "自选歌词",
                    artist = data?.getStringExtra(CustomLyricsActivity.EXTRA_ARTIST)
                        ?: nowPlaying?.artist ?: "Unknown",
                    source = data?.getStringExtra(CustomLyricsActivity.EXTRA_SOURCE) ?: "manual"
                )
            }
        }
    }



    var showMatchBubble by remember { mutableStateOf(false) }
    LaunchedEffect(isLoading) {
        if (isLoading) {
            delay(5000L)
            showMatchBubble = true
        } else {
            showMatchBubble = false
            // 注意：不要在这里调用 viewModel.cancelFetchLyrics()。
            // isLoading 变 false 有两种情况：
            //   1. fetchLyrics 协程自然完成 ——此时 fetchLyricsAuto 已经返回，重复取消是空操作但无问题。
            //   2. 切歌时 fetchLyrics() 取消了旧协程、启动了新协程。
            //      旧协程的 finally 会设置 isLoading=false；如果这里再调用
            //      cancelFetchLyrics()，会**误伤刚启动的新协程**，导致新歌曲的
            //      fetchLyricsAuto 被错误取消（出现“切歌后新歌曲不触发 fetchLyrics”
            //      的 bug）。
            // 因此取消动作应该只在 ViewModel.onCleared()、或者用户主动取消的场景下进行。
        }
    }

    // 智能退出逻辑：非加载期且无歌词时，延迟退回
    LaunchedEffect(lyrics, isLoading) {
        if (!isLoading && lyrics == null && isLyricsFullscreen) {
            delay(1500)
            if (isLyricsFullscreen) {
                isLyricsFullscreen = false
            }
        }
    }

    BackHandler(enabled = isLyricsFullscreen) { isLyricsFullscreen = false }

    // 减少轮询频率以降低主线程负担：从每秒一次改为每 5 秒一次。
    LaunchedEffect(Unit) {
        while (true) {
            notificationAccessGranted = isNotificationAccessGranted(context)
            delay(5000)
        }
    }

    LaunchedEffect(Unit) {
        if (AppSettings.isAutoUpdateCheckEnabled(context)) {
            val now = System.currentTimeMillis()
            if (now - AppSettings.getLastUpdateLaterAt(context) >= 24 * 60 * 60 * 1000) {
                val updateChannel = AppSettings.getUpdateChannel(context)
                val result = GitHubUpdateChecker.check(context, updateChannel)
                if (result.hasUpdate) {
                    autoUpdateDialogTitle = "发现新版本: ${result.resolvedReleaseTag ?: "未知版本"}"
                    autoUpdateDialogMessage =
                        "当前版本: ${result.currentVersionName}\n\n${result.resolvedReleaseNotes ?: "暂无更新说明"}"
                    autoUpdateDialogUrl = result.resolvedReleaseUrl
                    showAutoUpdateDialog = true
                }
            }
        }
    }

    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topAppBarState)

    /** 动画数值：共用 Card 实例所需的过渡动画 */
    val cardPaddingH by animateDpAsState(if (isLyricsFullscreen) 0.dp else 16.dp, label = "cardPaddingH")
    val cardPaddingV by animateDpAsState(if (isLyricsFullscreen) 0.dp else 8.dp, label = "cardPaddingV")
    val cardCorner by animateDpAsState(if (isLyricsFullscreen) 0.dp else 24.dp, label = "cardCorner")

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        topBar = {
            AnimatedVisibility(
                visible = !isLyricsFullscreen && !isLandscape,
                enter = fadeIn(tween(300)) + slideInVertically(initialOffsetY = { -it }),
                exit = fadeOut(tween(250)) + slideOutVertically(targetOffsetY = { -it })
            ) {
                LargeTopAppBar(
                    title = { Text(text = stringResource(R.string.app_name)) },
                    actions = {
                        // AppBar action with anchored M3 DropdownMenu
                        Box {
                            val menuInteractionSource = remember { MutableInteractionSource() }
                            FilledIconButton(
                                onClick = { showMenu = true },
                                modifier = Modifier.indication(
                                    menuInteractionSource,
                                    ripple(color = rippleColor.value)
                                ),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                )
                            ) {
                                Icon(Icons.Default.MoreVert, contentDescription = "菜单")
                            }

                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(24.dp)
                            ) {
                                MainMenuDropdownContent(
                                    nowPlaying = nowPlaying,
                                    context = context,
                                    scope = scope,
                                    viewModel = viewModel,
                                    isDarkTheme = isDarkTheme,
                                    initialPrimary = initialPrimary,
                                    rippleColor = rippleColor,
                                    customLyricsLauncher = customLyricsLauncher,
                                    webViewReloadKey = { webViewReloadKey++ },
                                    onDismiss = { showMenu = false }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.background
                    ),
                    scrollBehavior = scrollBehavior,
                    modifier = Modifier.statusBarsPadding()
                )
            }
        }
    ) { innerPadding ->
        if (isLandscape) {
            val topPadding = if (isLyricsFullscreen) 0.dp else innerPadding.calculateTopPadding()
            val bottomPadding = if (isLyricsFullscreen) 0.dp else innerPadding.calculateBottomPadding()

            if (isLyricsFullscreen) {
                // 横屏全屏模式：歌词铺满整个屏幕
                val currentLyrics = lyrics
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    LyricsVisualLayer(
                        nowPlaying = nowPlaying,
                        lyrics = currentLyrics,
                        currentTime = lyricTime,
                        webViewReloadKey = webViewReloadKey,
                        onLineSeek = {
                            viewModel.seekTo(it)
                            resetHideTimer()
                        },
                        onFullscreenTap = { resetHideTimer() },
                        isInteractive = true,
                        modifier = Modifier.fillMaxSize()
                    )

                    if (currentLyrics == null && !isLoading) {
                        LyricsEmptyState()
                    }

                    if (controlsInLayout) {
                        IconButton(
                            onClick = { isLyricsFullscreen = false },
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(top = 40.dp, start = 8.dp)
                                .alpha(controlsAlpha)
                        ) {
                            Icon(Icons.Default.FullscreenExit, contentDescription = "退出全屏", tint = Color.White.copy(alpha = 0.9f))
                        }

                        nowPlaying?.let { _ ->
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .alpha(controlsAlpha)
                            ) {
                                SongStructureBarSection(
                                    isLoading = isLoading,
                                    isSongStructureBarEnabled = isSongStructureBarEnabled,
                                    songStructures = songStructures,
                                    currentStructureIndex = currentStructureIndex,
                                    onSeekTo = { viewModel.seekTo(it); resetHideTimer() },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                )
                                NowPlayingCard(
                                    nowPlaying = nowPlaying, context = context, songStructures = songStructures,
                                    onPlayPauseClick = { if (nowPlaying?.isPlaying == true) viewModel.pause() else viewModel.play(); resetHideTimer() },
                                    onSkipPreviousClick = {
                                        val p = nowPlaying?.currentPosition ?: 0L
                                        if (AppSettings.isSkipPreviousRewindsEnabled(context) && p > 3000) viewModel.seekTo(0) else viewModel.skipToPrevious()
                                        resetHideTimer()
                                    },
                                    onSkipNextClick = { viewModel.skipToNext(); resetHideTimer() },
                                    onRewind = { viewModel.rewind(); resetHideTimer() },
                                    onFastForward = { viewModel.fastForward(); resetHideTimer() },
                                    onSeek = { viewModel.seekTo(it); resetHideTimer() },
                                    onCardClick = {
                                        when (AppSettings.getCardClickAction(context)) {
                                            CardClickAction.DIRECT_OPEN -> openSourceApp(context, nowPlaying?.packageName)
                                            CardClickAction.ASK -> showOpenAppDialog = true
                                            else -> {}
                                        }
                                        resetHideTimer()
                                    },
                                    cardBg = cardBg,
                                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                                )
                                Spacer(Modifier.height(32.dp))
                            }
                        }
                    }

                    if (isLoading) {
                        LoadingOverlay()
                    }
                }
            } else {
                // 横屏普通模式：左右分栏
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = topPadding, bottom = bottomPadding)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        // 左侧面板：标题栏 + 播放卡片 + 歌曲结构
                        Column(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(380.dp)
                                .background(MaterialTheme.colorScheme.background)
                        ) {
                            // 标题栏（借鉴 LargeTopAppBar 样式）
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .statusBarsPadding()
                                    .heightIn(min = 64.dp)
                                    .padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.app_name),
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 12.dp)
                                )
                                Box(modifier = Modifier.padding(end = 4.dp)) {
                                    val menuInteractionSource = remember { MutableInteractionSource() }
                                    IconButton(
                                        onClick = { showMenu = true },
                                        modifier = Modifier.indication(
                                            menuInteractionSource,
                                            ripple(color = rippleColor.value)
                                        )
                                    ) {
                                        Icon(
                                            Icons.Default.MoreVert,
                                            contentDescription = "菜单",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false },
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(24.dp)
                                    ) {
                                        MainMenuDropdownContent(
                                            nowPlaying = nowPlaying,
                                            context = context,
                                            scope = scope,
                                            viewModel = viewModel,
                                            isDarkTheme = isDarkTheme,
                                            initialPrimary = initialPrimary,
                                            rippleColor = rippleColor,
                                            customLyricsLauncher = customLyricsLauncher,
                                            webViewReloadKey = { webViewReloadKey++ },
                                            onDismiss = { showMenu = false }
                                        )
                                    }
                                }
                            }

                            // 播放卡片 + 歌曲结构（居中）
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                            NowPlayingCard(
                                nowPlaying = nowPlaying,
                                context = context,
                                songStructures = songStructures,
                                onPlayPauseClick = { if (nowPlaying?.isPlaying == true) viewModel.pause() else viewModel.play() },
                                onSkipPreviousClick = {
                                    val currentPos = nowPlaying?.currentPosition ?: 0L
                                    if (AppSettings.isSkipPreviousRewindsEnabled(context) && currentPos > 3000) viewModel.seekTo(0) else viewModel.skipToPrevious()
                                },
                                onSkipNextClick = { viewModel.skipToNext() },
                                onRewind = { viewModel.rewind() },
                                onFastForward = { viewModel.fastForward() },
                                onSeek = { viewModel.seekTo(it) },
                                onCardClick = {
                                    when (AppSettings.getCardClickAction(context)) {
                                        CardClickAction.DIRECT_OPEN -> openSourceApp(context, nowPlaying?.packageName)
                                        CardClickAction.ASK -> showOpenAppDialog = true
                                        else -> {}
                                    }
                                },
                                cardBg = cardBg,
                                landscapeMode = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )

                            SongStructureBarSection(
                                isLoading = isLoading,
                                isSongStructureBarEnabled = isSongStructureBarEnabled,
                                songStructures = songStructures,
                                currentStructureIndex = currentStructureIndex,
                                onSeekTo = { viewModel.seekTo(it) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        } // 内层 Column（播放卡片 + 歌曲结构居中）
                            } // 左侧面板 Column

                        // 右侧面板：歌词
                        val currentLyrics = lyrics

                        Card(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(1f)
                                .padding(12.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(color = rippleColor.value)
                                ) {
                                    isLyricsFullscreen = true
                                },
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.Black)
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                LyricsVisualLayer(
                                    nowPlaying = nowPlaying,
                                    lyrics = currentLyrics,
                                    currentTime = lyricTime,
                                    webViewReloadKey = webViewReloadKey,
                                    onLineSeek = { viewModel.seekTo(it) },
                                    onFullscreenTap = {
                                        if (currentLyrics != null) isLyricsFullscreen = true
                                    },
                                    isInteractive = false,
                                    modifier = Modifier.fillMaxSize()
                                )

                                if (currentLyrics == null && !isLoading) {
                                    LyricsEmptyState()
                                }

                                if (isLoading) {
                                    LoadingOverlay()
                                }
                            }
                        }
                    }
                }
            }
        } else {
            /** 竖屏布局（原有逻辑保持不变）*/
            Column(modifier = Modifier.fillMaxSize()) {
                val topPadding = if (isLyricsFullscreen) 0.dp else innerPadding.calculateTopPadding()
                Spacer(Modifier.height(topPadding))

                if (!notificationAccessGranted && !isLyricsFullscreen) {
                    PermissionStatusCard(
                        onOpenNotificationAccessSettings = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                val currentLyrics = lyrics
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = cardPaddingH, vertical = cardPaddingV)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = rippleColor.value)
                        ) {
                            if (!isLyricsFullscreen) {
                                isLyricsFullscreen = true
                            }
                        },
                    shape = RoundedCornerShape(cardCorner),
                    colors = CardDefaults.cardColors(containerColor = Color.Black)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        LyricsVisualLayer(
                            nowPlaying = nowPlaying,
                            lyrics = currentLyrics,
                            currentTime = lyricTime,
                            webViewReloadKey = webViewReloadKey,
                            onLineSeek = {
                                viewModel.seekTo(it)
                                if (isLyricsFullscreen) resetHideTimer()
                            },
                            onFullscreenTap = {
                                if (isLyricsFullscreen) {
                                    resetHideTimer()
                                } else if (currentLyrics != null) {
                                    isLyricsFullscreen = true
                                }
                            },
                            isInteractive = isLyricsFullscreen,
                            modifier = Modifier.fillMaxSize()
                        )

                        // 占位提示
                        if (currentLyrics == null && !isLoading) {
                            LyricsEmptyState()
                        }

                        // 匹配气泡（仅在非全屏显示）
                        if (!isLyricsFullscreen && showMatchBubble) {
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f))
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "正在匹配更优歌词",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontSize = 14.sp
                                )
                                Button(
                                    onClick = {
                                        val intent = Intent(context, CustomLyricsActivity::class.java).apply {
                                            putExtra(CustomLyricsActivity.EXTRA_TITLE, nowPlaying?.title ?: "")
                                            putExtra(CustomLyricsActivity.EXTRA_ARTIST, nowPlaying?.artist ?: "")
                                            putExtra(CustomLyricsActivity.EXTRA_PLAYBACK_SOURCE, getAppNameFromPackage(context, nowPlaying?.packageName) ?: "")
                                        }
                                        customLyricsLauncher.launch(intent)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ) { Text("自选歌词", fontSize = 14.sp) }
                            }
                        }

                        // 全屏控制按钮
                        if (isLyricsFullscreen && controlsInLayout) {
                            IconButton(
                                onClick = { isLyricsFullscreen = false },
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(top = 40.dp, start = 8.dp)
                                    .alpha(controlsAlpha)
                            ) {
                                Icon(
                                    Icons.Default.FullscreenExit,
                                    contentDescription = "退出全屏",
                                    tint = Color.White.copy(alpha = 0.9f)
                                )
                            }

                            nowPlaying?.let { _ ->
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .alpha(controlsAlpha)
                                ) {
                                    SongStructureBarSection(
                                        isLoading = isLoading,
                                        isSongStructureBarEnabled = isSongStructureBarEnabled,
                                        songStructures = songStructures,
                                        currentStructureIndex = currentStructureIndex,
                                        onSeekTo = { viewModel.seekTo(it); resetHideTimer() },
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                    )

                                    // 在全屏模式下复用播放卡片作为控制中心
                                    NowPlayingCard(
                                        nowPlaying = nowPlaying,
                                        context = context,
                                        songStructures = songStructures,
                                        onPlayPauseClick = {
                                            if (nowPlaying?.isPlaying == true) viewModel.pause() else viewModel.play()
                                            resetHideTimer()
                                        },
                                        onSkipPreviousClick = {
                                            val currentPos = nowPlaying?.currentPosition ?: 0L
                                            if (AppSettings.isSkipPreviousRewindsEnabled(context) && currentPos > 3000) viewModel.seekTo(0) else viewModel.skipToPrevious()
                                            resetHideTimer()
                                        },
                                        onSkipNextClick = { viewModel.skipToNext(); resetHideTimer() },
                                        onRewind = { viewModel.rewind(); resetHideTimer() },
                                        onFastForward = { viewModel.fastForward(); resetHideTimer() },
                                        onSeek = { viewModel.seekTo(it); resetHideTimer() },
                                        onCardClick = {
                                            when (AppSettings.getCardClickAction(context)) {
                                                CardClickAction.DIRECT_OPEN -> openSourceApp(context, nowPlaying?.packageName)
                                                CardClickAction.ASK -> showOpenAppDialog = true
                                                else -> {}
                                            }
                                            resetHideTimer()
                                        },
                                        cardBg = cardBg,
                                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                                    )
                                    Spacer(Modifier.height(32.dp))
                                }
                            }
                        }

                        if (isLoading) {
                            LoadingOverlay()
                        }
                    }
                }

                // 歌曲结构显示条
                AnimatedVisibility(visible = !isLyricsFullscreen) {
                    SongStructureBarSection(
                        isLoading = isLoading,
                        isSongStructureBarEnabled = isSongStructureBarEnabled,
                        songStructures = songStructures,
                        currentStructureIndex = currentStructureIndex,
                        onSeekTo = { viewModel.seekTo(it) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 底部播放卡片
                AnimatedVisibility(visible = !isLyricsFullscreen) {
                    NowPlayingCard(
                        nowPlaying = nowPlaying,
                        context = context,
                        songStructures = songStructures,
                        onPlayPauseClick = { if (nowPlaying?.isPlaying == true) viewModel.pause() else viewModel.play() },
                        onSkipPreviousClick = {
                            val currentPos = nowPlaying?.currentPosition ?: 0L
                            if (AppSettings.isSkipPreviousRewindsEnabled(context) && currentPos > 3000) viewModel.seekTo(0) else viewModel.skipToPrevious()
                        },
                        onSkipNextClick = { viewModel.skipToNext() },
                        onRewind = { viewModel.rewind() },
                        onFastForward = { viewModel.fastForward() },
                        onSeek = { viewModel.seekTo(it) },
                        onCardClick = {
                            when (AppSettings.getCardClickAction(context)) {
                                CardClickAction.DIRECT_OPEN -> openSourceApp(context, nowPlaying?.packageName)
                                CardClickAction.ASK -> showOpenAppDialog = true
                                else -> {}
                            }
                        },
                        cardBg = cardBg,
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    )
                }

                val bottomPadding = if (isLyricsFullscreen) 0.dp else innerPadding.calculateBottomPadding()
                Spacer(Modifier.height(bottomPadding))
            }
        }

        // 全屏模式下的窗口管理逻辑
        if (isLyricsFullscreen) {
            val localView = LocalView.current
            val activity = remember(localView) { localView.context.findActivity() }
            val window = remember(activity) { activity?.window }
            val insetsController = remember(window, localView) {
                window?.let { WindowCompat.getInsetsController(it, localView) }
            }

            SideEffect {
                if (activity == null || window == null || insetsController == null) return@SideEffect
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode = if (controlsVisible)
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                        else
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
                if (controlsVisible) {
                    insetsController.show(WindowInsetsCompat.Type.systemBars())
                } else {
                    insetsController.hide(WindowInsetsCompat.Type.systemBars())
                    insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }

            DisposableEffect(Unit) {
                onDispose {
                    if (activity == null || window == null || insetsController == null) return@onDispose
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT }
                    }
                    insetsController.show(WindowInsetsCompat.Type.systemBars())
                }
            }
            LaunchedEffect(Unit) { resetHideTimer() }
        }

        // 对话框逻辑
        if (showOpenAppDialog) {
            val sourceAppName = getAppNameFromPackage(context, nowPlaying?.packageName) ?: "播放源应用"
            AlertDialog(
                onDismissRequest = { showOpenAppDialog = false },
                title = { Text("打开 $sourceAppName？") },
                text = { Text("您可进入设置调整点击卡片的默认行为。") },
                containerColor = MaterialTheme.colorScheme.background,
                confirmButton = {
                    TextButton(onClick = { openSourceApp(context, nowPlaying?.packageName); showOpenAppDialog = false }) { Text("打开") }
                },
                dismissButton = {
                    TextButton(onClick = { showOpenAppDialog = false }) { Text("忽略") }
                }
            )
        }

        if (showAutoUpdateDialog) {
            AlertDialog(
                onDismissRequest = { showAutoUpdateDialog = false },
                containerColor = MaterialTheme.colorScheme.background,
                title = { Text(autoUpdateDialogTitle) },
                text = { Text(autoUpdateDialogMessage) },
                confirmButton = {
                    TextButton(onClick = {
                        autoUpdateDialogUrl?.let { context.startActivity(Intent(Intent.ACTION_VIEW, it.toUri())) }
                        showAutoUpdateDialog = false
                    }) { Text("去更新") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        AppSettings.setLastUpdateLaterAt(context, System.currentTimeMillis())
                        showAutoUpdateDialog = false
                    }) { Text("稍后") }
                }
            )
        }
    }
    Trace.endSection()
}


// ──────────────────────────────────────────────
// Shared helper composables used across modes
// ──────────────────────────────────────────────

/** 下拉菜单内容：歌词管理、刷新、设置 */
@Composable
private fun MainMenuDropdownContent(
    nowPlaying: NowPlayingMusic?,
    context: Context,
    scope: CoroutineScope,
    viewModel: MainViewModel,
    isDarkTheme: Boolean,
    initialPrimary: Color,
    rippleColor: MutableState<Color>,
    customLyricsLauncher: ActivityResultLauncher<Intent>,
    webViewReloadKey: () -> Unit,
    onDismiss: () -> Unit
) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        DropdownMenuItem(
            text = { Text("歌词管理", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface) },
            trailingIcon = {
                Icon(Icons.AutoMirrored.Filled.TextSnippet, contentDescription = null, modifier = Modifier.size(20.dp))
            },
            onClick = {
                val intent = Intent(context, CustomLyricsActivity::class.java).apply {
                    putExtra(CustomLyricsActivity.EXTRA_TITLE, nowPlaying?.title ?: "")
                    putExtra(CustomLyricsActivity.EXTRA_ARTIST, nowPlaying?.artist ?: "")
                    putExtra(CustomLyricsActivity.EXTRA_PLAYBACK_SOURCE, getAppNameFromPackage(context, nowPlaying?.packageName) ?: "")
                }
                customLyricsLauncher.launch(intent)
                onDismiss()
            },
            colors = MenuDefaults.itemColors(
                textColor = MaterialTheme.colorScheme.onSurface,
                trailingIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        )
        DropdownMenuItem(
            text = { Text("刷新", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface) },
            trailingIcon = {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
            },
            onClick = {
                scope.launch {
                    Timber.d("[UI] 刷新按钮被点击：开始重新拉取歌词 + 重新识别/推断 SongStructure")
                    viewModel.fetchLyrics()
                    webViewReloadKey()
                    viewModel.refreshAlbumArt()
                    val currentUri = nowPlaying?.albumArtUri
                    if (!currentUri.isNullOrBlank()) {
                        try {
                            val colors = AlbumColorExtractor.extractColorsFromAlbumArt(context, currentUri, isDarkTheme)
                            rippleColor.value = colors?.primary ?: initialPrimary
                        } catch (_: Exception) {
                            rippleColor.value = initialPrimary
                        }
                    } else {
                        rippleColor.value = initialPrimary
                    }
                    onDismiss()
                    Timber.d("[UI] 刷新按钮处理完成")
                }
            },
            colors = MenuDefaults.itemColors(
                textColor = MaterialTheme.colorScheme.onSurface,
                trailingIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        )
        DropdownMenuItem(
            text = { Text("设置", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface) },
            trailingIcon = {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(20.dp))
            },
            onClick = {
                context.startActivity(Intent(context, SettingsActivity::class.java))
                onDismiss()
            },
            colors = MenuDefaults.itemColors(
                textColor = MaterialTheme.colorScheme.onSurface,
                trailingIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        )
    }
}

/** 歌曲结构条 + 加载中占位 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SongStructureBarSection(
    isLoading: Boolean,
    isSongStructureBarEnabled: Boolean,
    songStructures: List<SongStructure>,
    currentStructureIndex: Int,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isSongStructureBarEnabled && !isLoading) return
    if (!isLoading && songStructures.isEmpty()) return

    // 稳定 onSeekTo lambda，避免因 lambda 引用变化导致不必要的重组
    val currentOnSeekTo by rememberUpdatedState(onSeekTo)
    val stableOnSeekTo = remember { { time: Long -> currentOnSeekTo(time) } }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
            LoadingIndicator()
        }
    } else {
        SongStructureBar(
            structures = songStructures,
            currentStructureIndex = currentStructureIndex,
            onSeekTo = stableOnSeekTo,
            modifier = modifier
        )
    }
}

/** 歌词为空时的占位提示 */
@Composable
private fun LyricsEmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "选择歌词来显示", color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp, textAlign = TextAlign.Center)
    }
}

/** 加载中覆盖层 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoadingOverlay() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
}

@Composable
fun LyricsVisualLayer(
    modifier: Modifier = Modifier,
    nowPlaying: NowPlayingMusic?,
    lyrics: UnifiedLyrics?,
    currentTime: Long,
    webViewReloadKey: Int,
    onLineSeek: (Long) -> Unit,
    onFullscreenTap: (() -> Unit)? = null,
    debugSource: String = "MainScreen",
    useAndroidBlurOverride: Boolean? = null,
    isInteractive: Boolean = true,
) {
    val context = LocalContext.current
    val useAndroidBlur = useAndroidBlurOverride
        ?: !(AMLLSettings.isAmllBackgroundRendererEnabled(context) ?: true)
    val fallbackAlbumArtUri = remember(context) {
        "android.resource://${context.packageName}/drawable/background_blue_black_light_1591226"
    }
    val effectiveAlbumArtUri = if (nowPlaying == null) {
        null
    } else {
        nowPlaying.albumArtUri ?: fallbackAlbumArtUri
    }

    /** 优化：使用更高效的状态管理，减少不必要的内存分配 */
    val boxHeight = remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val sizeChangeJob = remember { mutableStateOf<Job?>(null) }

    // 优化：移除不必要的savedBoxHeight，因为boxHeight已经足够

    // 优化：简化布局结构，减少Box嵌套
    Box(
        modifier = modifier
            .onSizeChanged { size ->
                // 防抖处理，避免频繁的尺寸变化触发重组
                sizeChangeJob.value?.cancel()
                sizeChangeJob.value = scope.launch {
                    delay(500) // 增加防抖延迟，进一步减少触发频率
                    if (size.height != boxHeight.intValue && size.height > 0) {
                        boxHeight.intValue = size.height
                    }
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(pass = PointerEventPass.Initial)
                    val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                    if (up != null) {
                        Timber.d("[LyricsVisualLayer] Tap detected in Initial pass, triggering onFullscreenTap")
                        onFullscreenTap?.invoke()
                    }
                }
            }
    ) {
        // 背景图和叠加效果
        if (useAndroidBlur && effectiveAlbumArtUri != null) {
            // 减小模糊半径以降低渲染成本
            AsyncImage(
                model = effectiveAlbumArtUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(12.dp).alpha(0.75f)
            )
            // 添加主题色叠加效果
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
            )
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.2f))
            )
        }

        // 使用 AMLLLyricsView 组件显示歌词和背景
        key(webViewReloadKey) {
            AMLLLyricsView(
                lyrics = lyrics,
                currentTime = currentTime,
                albumArtUri = effectiveAlbumArtUri,
                debugSource = debugSource,
                onLyricsClick = onFullscreenTap,
                onLineSeek = onLineSeek,
                isPlaying = nowPlaying?.isPlaying ?: false,
                isInteractive = isInteractive,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}


@Composable
fun NowPlayingCard(
    modifier: Modifier = Modifier,
    nowPlaying: NowPlayingMusic?,
    context: Context,
    songStructures: List<SongStructure> = emptyList(),
    onPlayPauseClick: () -> Unit,
    onSkipPreviousClick: () -> Unit,
    onSkipNextClick: () -> Unit,
    onRewind: () -> Unit,
    onFastForward: () -> Unit,
    onSeek: (Long) -> Unit,
    onCardClick: () -> Unit,
    cardBg: Color = MaterialTheme.colorScheme.primaryContainer,
    landscapeMode: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var isSeeking by remember { mutableStateOf(false) }

    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardBg,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (nowPlaying?.albumArtUri != null) {
                // 减少背景模糊强度以避免每帧高成本的模糊计算。
                AsyncImage(
                    model = nowPlaying.albumArtUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .blur(16.dp)
                        .alpha(0.2f)
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(
                                    cardBg.copy(alpha = 0.6f),
                                    cardBg.copy(alpha = 0.95f)
                                )
                            )
                        )
                )
            }

            if (nowPlaying != null) {
                if (landscapeMode) {
                    // 横屏布局：专辑图占满宽度在上方，专辑/应用名在底部
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
                    ) {
                        // 专辑图 - 占满卡片宽度
                        AsyncImage(
                            model = nowPlaying.albumArtUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )

                        Spacer(Modifier.height(12.dp))

                        // 歌曲信息 + 播放按钮（可点击打开应用）
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple()
                                ) { onCardClick() },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    nowPlaying.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    lineHeight = 24.sp
                                )
                                Text(
                                    nowPlaying.artist,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                                )
                            }

                            IconButton(
                                onClick = { onPlayPauseClick() },
                                modifier = Modifier.size(48.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary)
                            ) {
                                Icon(
                                    if (nowPlaying.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // 进度控制区域
                        var draggedSliderValue by remember { androidx.compose.runtime.mutableFloatStateOf(nowPlaying.currentPosition.toFloat()) }
                        val sliderValue by remember(nowPlaying, isSeeking) {
                            derivedStateOf { if (!isSeeking) nowPlaying.currentPosition.toFloat() else draggedSliderValue }
                        }
                        var isRemainingTimeMode by remember { mutableStateOf(AppSettings.isRemainingTimeMode(context)) }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            MediaControlButton(
                                icon = Icons.Default.FastRewind,
                                onClick = { onSkipPreviousClick() },
                                onLongPress = { onRewind() },
                                scope = scope,
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                            val timeAlpha = if (isSeeking) 1.0f else 0.8f
                            Text(formatTime(sliderValue.toLong()), fontSize = 11.sp, modifier = Modifier.alpha(timeAlpha), fontWeight = if (isSeeking) FontWeight.Bold else FontWeight.Normal, color = MaterialTheme.colorScheme.onPrimary, maxLines = 1)
                            Spacer(Modifier.width(6.dp))
                            WavySlider(
                                value = sliderValue / nowPlaying.duration.toFloat().coerceAtLeast(1f),
                                onValueChange = { normalizedValue ->
                                    draggedSliderValue = normalizedValue * nowPlaying.duration.toFloat().coerceAtLeast(1f)
                                    isSeeking = true
                                },
                                onValueChangeFinished = {
                                    onSeek(draggedSliderValue.toLong())
                                    isSeeking = false
                                },
                                customSteps = songStructures
                                    .map { it.startTime }
                                    .filter { it > 0 && it < nowPlaying.duration }
                                    .map { (it.toFloat() / nowPlaying.duration.toFloat().coerceIn(0f, 1f)) }
                                    .distinct(),
                                modifier = Modifier.weight(1f),
                                colors = WavySliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.onPrimary,
                                    stepColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f),
                                    activeTrackColor = MaterialTheme.colorScheme.onPrimary,
                                    inactiveTrackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f),
                                ),
                                waveSpeed = if (nowPlaying.isPlaying) WavySliderDefaults.WaveSpeed else 0.dp,
                            )
                            val rightTimeText = if (isRemainingTimeMode) {
                                "-${formatTime((nowPlaying.duration - sliderValue.toLong()).coerceAtLeast(0L))}"
                            } else {
                                formatTime(nowPlaying.duration)
                            }
                            Text(
                                rightTimeText, fontSize = 11.sp,
                                modifier = Modifier.alpha(timeAlpha)
                                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                        isRemainingTimeMode = !isRemainingTimeMode
                                        AppSettings.setRemainingTimeMode(context, isRemainingTimeMode)
                                    },
                                color = MaterialTheme.colorScheme.onPrimary, maxLines = 1
                            )
                            MediaControlButton(
                                icon = Icons.Default.FastForward,
                                onClick = { onSkipNextClick() },
                                onLongPress = { onFastForward() },
                                scope = scope,
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        // 专辑名 + 应用名（底部）
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                nowPlaying.album ?: "",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            val appName by remember(nowPlaying.packageName) {
                                derivedStateOf { nowPlaying.packageName?.let { getAppNameFromPackage(context, it) } }
                            }
                            Text(
                                appName ?: "",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else {
                // 竖屏布局（原有逻辑）
                Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 20.dp)) {
                    // 上半部分：歌曲信息区域（可点击打开应用）
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple()
                            ) { onCardClick() }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                nowPlaying.album ?: "",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            val appName by remember(nowPlaying.packageName) {
                                derivedStateOf { nowPlaying.packageName?.let { getAppNameFromPackage(context, it) } }
                            }
                            Text(
                                appName ?: "",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = nowPlaying.albumArtUri,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(76.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    nowPlaying.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    lineHeight = 24.sp
                                )
                                Text(
                                    nowPlaying.artist,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                                )
                            }

                            IconButton(
                                onClick = { onPlayPauseClick() },
                                modifier = Modifier.size(56.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary)
                            ) {
                                Icon(
                                    if (nowPlaying.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    var draggedSliderValue by remember { androidx.compose.runtime.mutableFloatStateOf(nowPlaying.currentPosition.toFloat()) }
                    val sliderValue by remember(nowPlaying, isSeeking) {
                        derivedStateOf { if (!isSeeking) nowPlaying.currentPosition.toFloat() else draggedSliderValue }
                    }

                    var isRemainingTimeMode by remember { mutableStateOf(AppSettings.isRemainingTimeMode(context)) }

                    // 下半部分：进度控制区域（拦截卡片点击）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { /* 拦截点击以防止触发打开应用对话框 */ },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // 最外侧：倒退
                        MediaControlButton(
                            icon = Icons.Default.FastRewind,
                            onClick = { onSkipPreviousClick() },
                            onLongPress = { onRewind() },
                            scope = scope,
                            tint = MaterialTheme.colorScheme.onPrimary
                        )

                        /** 其次：当前时间 */
                        val timeAlpha = if (isSeeking) 1.0f else 0.8f
                        Text(
                            formatTime(sliderValue.toLong()),
                            fontSize = 11.sp,
                            modifier = Modifier.alpha(timeAlpha),
                            fontWeight = if (isSeeking) FontWeight.Bold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onPrimary,
                            maxLines = 1
                        )

                        // 增加左侧间距，避免较粗的 Active 进度条贴得太近
                        Spacer(Modifier.width(6.dp))

                        // 中间：进度条
                        WavySlider(
                            value = sliderValue / nowPlaying.duration.toFloat().coerceAtLeast(1f),
                            onValueChange = { normalizedValue ->
                                draggedSliderValue = normalizedValue * nowPlaying.duration.toFloat().coerceAtLeast(1f)
                                isSeeking = true
                            },
                            onValueChangeFinished = {
                                onSeek(draggedSliderValue.toLong())
                                isSeeking = false
                            },
                            customSteps = songStructures
                                .map { it.startTime }
                                .filter { it > 0 && it < nowPlaying.duration }
                                .map { (it.toFloat() / nowPlaying.duration.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f) }
                                .distinct(),
                            modifier = Modifier.weight(1f),
                            colors = WavySliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.onPrimary,
                                stepColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f),
                                activeTrackColor = MaterialTheme.colorScheme.onPrimary,
                                inactiveTrackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f),
                            ),
                            waveSpeed = if (nowPlaying.isPlaying) WavySliderDefaults.WaveSpeed else 0.dp,
                        )

                        /** 其次：时间显示模式（总时长 vs 剩���时长） */
                        val rightTimeText = if (isRemainingTimeMode) {
                            "-${formatTime((nowPlaying.duration - sliderValue.toLong()).coerceAtLeast(0L))}"
                        } else {
                            formatTime(nowPlaying.duration)
                        }

                        Text(
                            rightTimeText,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .alpha(timeAlpha)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    isRemainingTimeMode = !isRemainingTimeMode
                                    AppSettings.setRemainingTimeMode(context, isRemainingTimeMode)
                                },
                            color = MaterialTheme.colorScheme.onPrimary,
                            maxLines = 1
                        )

                        // 最外侧：快进
                        MediaControlButton(
                            icon = Icons.Default.FastForward,
                            onClick = { onSkipNextClick() },
                            onLongPress = { onFastForward() },
                            scope = scope,
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
                } // else 竖屏布局
            } // if (nowPlaying != null)
        }
    }
    Trace.endSection()
}

@Composable
private fun MediaControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
    tint: Color
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .indication(interactionSource, ripple())
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onPress = { offset ->
                        val press = PressInteraction.Press(offset)
                        interactionSource.tryEmit(press)
                        val job = scope.launch {
                            delay(500)
                            while (true) {
                                onLongPress()
                                delay(200)
                            }
                        }
                        try {
                            awaitPointerEventScope {
                                waitForUpOrCancellation()
                                job.cancel()
                                interactionSource.tryEmit(PressInteraction.Release(press))
                            }
                        } catch (_: Exception) {
                            job.cancel()
                            interactionSource.tryEmit(PressInteraction.Cancel(press))
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, modifier = Modifier.size(28.dp), tint = tint)
    }
}


@Composable
fun PermissionStatusCard(onOpenNotificationAccessSettings: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "需要通知访问权限才能正常使用此应用。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                "滥用通知使用权危及安全，因此系统可能会弹窗阻止。本应用是开源软件，您可以查看本应用的执行逻辑，因此在应用来源可靠的情况下无需感到担忧。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
            )
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onOpenNotificationAccessSettings,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("去授权", color = MaterialTheme.colorScheme.onError)
                }
            }
        }
    }
}

private fun openSourceApp(context: Context, packageName: String?): Boolean {
    if (packageName.isNullOrBlank()) return false
    val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
    return try { context.startActivity(launchIntent); true } catch (_: Exception) { false }
}

fun formatTime(millis: Long): String = String.format(Locale.ROOT, "%d:%02d", millis / 60000, (millis % 60000) / 1000)
