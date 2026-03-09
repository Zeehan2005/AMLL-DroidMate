package com.amll.droidmate.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ContextWrapper
import android.net.Uri
import android.util.Log
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.amll.droidmate.R
import com.amll.droidmate.domain.model.LyricLine
import com.amll.droidmate.domain.model.NowPlayingMusic
import com.amll.droidmate.domain.model.TTMLLyrics
import com.amll.droidmate.ui.AppSettings
import com.amll.droidmate.ui.CardClickAction
import com.amll.droidmate.ui.CustomLyricsActivity
import com.amll.droidmate.ui.LyricsCacheActivity
import com.amll.droidmate.ui.viewmodel.MainViewModel
import com.amll.droidmate.update.GitHubUpdateChecker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

private const val MAIN_SCREEN_LOG_TAG = "MainScreen"

/**
 * 获取应用名称
 */
fun getAppNameFromPackage(context: Context, packageName: String?): String? {
    if (packageName.isNullOrBlank()) return null
    return try {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(packageName, 0)
        pm.getApplicationLabel(appInfo).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val viewModel: MainViewModel = viewModel()
    val nowPlaying by viewModel.nowPlayingMusic.collectAsState()
    val lyrics by viewModel.lyrics.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val currentTime = nowPlaying?.currentPosition ?: 0L
    var notificationAccessGranted by remember { mutableStateOf(isNotificationAccessGranted(context)) }
    var isLyricsFullscreen by remember { mutableStateOf(false) }
    val fullscreenOverlayAlpha by animateFloatAsState(
        targetValue = if (isLyricsFullscreen) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "fullscreenOverlayAlpha"
    )
    var webViewReloadKey by remember { mutableStateOf(0) }
    var showMenu by remember { mutableStateOf(false) }
    var showOpenAppDialog by remember { mutableStateOf(false) }
    var showAutoUpdateDialog by remember { mutableStateOf(false) }
    var autoUpdateDialogTitle by remember { mutableStateOf("") }
    var autoUpdateDialogMessage by remember { mutableStateOf("") }
    var autoUpdateDialogUrl by remember { mutableStateOf<String?>(null) }
    val useDarkSystemIcons = !isLyricsFullscreen &&
        MaterialTheme.colorScheme.background.luminance() > 0.5f

    // Follow content brightness: dark content -> white icons, light content -> dark icons.
    AdaptiveStatusBarStyle(useDarkIcons = useDarkSystemIcons)

    val customLyricsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val lyricsText = data?.getStringExtra(CustomLyricsActivity.EXTRA_LYRICS_TEXT).orEmpty()
            val title = data?.getStringExtra(CustomLyricsActivity.EXTRA_TITLE).orEmpty()
            val artist = data?.getStringExtra(CustomLyricsActivity.EXTRA_ARTIST).orEmpty()

            if (lyricsText.isNotBlank()) {
                val fallbackTitle = nowPlaying?.title ?: "自选歌词"
                val fallbackArtist = nowPlaying?.artist ?: "Unknown"
                viewModel.applyCustomLyricsInput(
                    content = lyricsText,
                    title = if (title.isNotBlank()) title else fallbackTitle,
                    artist = if (artist.isNotBlank()) artist else fallbackArtist
                )
            }
        }
    }

    BackHandler(enabled = isLyricsFullscreen) {
        isLyricsFullscreen = false
    }

    LaunchedEffect(Unit) {
        while (true) {
            notificationAccessGranted = isNotificationAccessGranted(context)
            kotlinx.coroutines.delay(1000)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshLyricNotification()
    }

    LaunchedEffect(Unit) {
        if (!AppSettings.isAutoUpdateCheckEnabled(context)) return@LaunchedEffect

        val now = System.currentTimeMillis()
        val lastCheck = AppSettings.getLastUpdateCheckAt(context)
        val oneDayMillis = 24L * 60 * 60 * 1000
        if (now - lastCheck < oneDayMillis) return@LaunchedEffect

        val updateChannel = AppSettings.getUpdateChannel(context)
        val result = GitHubUpdateChecker.check(context, updateChannel)
        AppSettings.setLastUpdateCheckAt(context, now)

        if (result.hasUpdate) {
            autoUpdateDialogTitle = "发现新版本: ${result.resolvedReleaseTag ?: "未知版本"}"
            autoUpdateDialogMessage = buildString {
                append("当前版本: ${result.currentVersionName}\n")
                append("通道: ${if (updateChannel == com.amll.droidmate.ui.UpdateChannel.PREVIEW) "预览版" else "正式版"}\n\n")
                if (!result.resolvedReleaseNotes.isNullOrBlank()) {
                    append(result.resolvedReleaseNotes)
                } else {
                    append("暂无更新说明")
                }
            }
            autoUpdateDialogUrl = result.resolvedReleaseUrl
            showAutoUpdateDialog = true
        }
    }

    LaunchedEffect(webViewReloadKey) {
        Timber.i("[reload] webViewReloadKey changed -> $webViewReloadKey")
        Log.i(MAIN_SCREEN_LOG_TAG, "[reload] webViewReloadKey changed -> $webViewReloadKey")
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            if (!isLyricsFullscreen) {
                // 标题栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "菜单")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(Icons.Default.TextSnippet, contentDescription = null)
                                },
                                text = { Text("自选歌词") },
                                onClick = {
                                    val intent = Intent(context, CustomLyricsActivity::class.java).apply {
                                        putExtra(CustomLyricsActivity.EXTRA_TITLE, nowPlaying?.title ?: "")
                                        putExtra(CustomLyricsActivity.EXTRA_ARTIST, nowPlaying?.artist ?: "")
                                    }
                                    customLyricsLauncher.launch(intent)
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                },
                                text = { Text("刷新") },
                                onClick = {
                                    Timber.i("[reload] Refresh menu clicked, oldKey=$webViewReloadKey")
                                    Log.i(MAIN_SCREEN_LOG_TAG, "[reload] Refresh menu clicked, oldKey=$webViewReloadKey")
                                    webViewReloadKey += 1
                                    Timber.i("[reload] Refresh handled, newKey=$webViewReloadKey")
                                    Log.i(MAIN_SCREEN_LOG_TAG, "[reload] Refresh handled, newKey=$webViewReloadKey")
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(Icons.Default.Settings, contentDescription = null)
                                },
                                text = { Text("设置") },
                                onClick = {
                                    context.startActivity(Intent(context, com.amll.droidmate.ui.SettingsActivity::class.java))
                                    showMenu = false
                                }
                            )
                        }
                    }
                }
            }
        
            // 仅在未授权时显示权限卡片
            if (!notificationAccessGranted && !isLyricsFullscreen) {
                PermissionStatusCard(
                    notificationAccessGranted = notificationAccessGranted,
                    onOpenNotificationAccessSettings = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (showOpenAppDialog) {
                val sourceAppName =
                    getAppNameFromPackage(context, nowPlaying?.packageName)
                        ?: "播放源应用"
                AlertDialog(
                    onDismissRequest = { showOpenAppDialog = false },
                    title = { Text("打开 $sourceAppName？") },
                    text = { Text("您可进入设置调整点击卡片的默认行为。") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val opened = openSourceApp(context, nowPlaying?.packageName)
                                if (!opened) {
                                    Toast.makeText(context, "无法打开播放源应用", Toast.LENGTH_SHORT).show()
                                }
                                showOpenAppDialog = false
                            }
                        ) {
                            Text("打开")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showOpenAppDialog = false }) {
                            Text("不操作")
                        }
                    }
                )
            }

            if (showAutoUpdateDialog) {
                AlertDialog(
                    onDismissRequest = { showAutoUpdateDialog = false },
                    title = { Text(autoUpdateDialogTitle) },
                    text = { Text(autoUpdateDialogMessage) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (!autoUpdateDialogUrl.isNullOrBlank()) {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(autoUpdateDialogUrl)))
                                }
                                showAutoUpdateDialog = false
                            }
                        ) {
                            Text(if (!autoUpdateDialogUrl.isNullOrBlank()) "去更新" else "知道了")
                        }
                    },
                    dismissButton = {
                        if (!autoUpdateDialogUrl.isNullOrBlank()) {
                            TextButton(onClick = { showAutoUpdateDialog = false }) {
                                Text("稍后")
                            }
                        }
                    }
                )
            }

            if (!errorMessage.isNullOrEmpty() && !isLyricsFullscreen) {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (!isLyricsFullscreen) {
                val currentLyrics = lyrics
                if (currentLyrics != null) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        LyricsVisualLayer(
                            nowPlaying = nowPlaying,
                            lyrics = currentLyrics,
                            currentTime = currentTime,
                            webViewReloadKey = webViewReloadKey,
                            onLineSeek = { seekTime ->
                                Timber.i("[embedded] onLineSeek($seekTime)")
                                Log.i(MAIN_SCREEN_LOG_TAG, "[embedded] onLineSeek($seekTime)")
                                viewModel.seekTo(seekTime)
                            },
                            onFullscreenTap = { isLyricsFullscreen = true },
                            amllDebugSource = "embedded",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "等待歌曲信息...",
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }

            AnimatedVisibility(
                visible = !isLyricsFullscreen,
                enter = fadeIn(animationSpec = tween(220)) + expandVertically(animationSpec = tween(220)),
                exit = fadeOut(animationSpec = tween(180)) + shrinkVertically(animationSpec = tween(180))
            ) {
                NowPlayingCard(
                nowPlaying = nowPlaying,
                context = context,
                onPlayPauseClick = {
                    if (nowPlaying?.isPlaying == true) {
                        viewModel.pause()
                    } else {
                        viewModel.play()
                    }
                },
                onSkipPreviousClick = { viewModel.skipToPrevious() },
                onSkipNextClick = { viewModel.skipToNext() },
                onRewind = { viewModel.rewind() },
                onFastForward = { viewModel.fastForward() },
                onSeek = { position -> viewModel.seekTo(position) },
                onCardClick = {
                    when (AppSettings.getCardClickAction(context)) {
                        CardClickAction.DIRECT_OPEN -> {
                            val opened = openSourceApp(context, nowPlaying?.packageName)
                            if (!opened) {
                                Toast.makeText(context, "无法打开播放源应用", Toast.LENGTH_SHORT).show()
                            }
                        }
                        CardClickAction.ASK -> {
                            showOpenAppDialog = true
                        }
                        CardClickAction.NONE -> {
                            // 用户选择不操作
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                )
            }
        }

        val fullscreenLyrics = lyrics
        AnimatedVisibility(
            visible = isLyricsFullscreen && lyrics != null,
            enter = fadeIn(animationSpec = tween(260)) + scaleIn(
                initialScale = 0.96f,
                animationSpec = tween(260)
            ),
            exit = fadeOut(animationSpec = tween(220)) + scaleOut(
                targetScale = 0.96f,
                animationSpec = tween(220)
            ),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f * fullscreenOverlayAlpha))
            ) {
                if (fullscreenLyrics != null) {
                    Card(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(0.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Black)
                    ) {
                        LyricsVisualLayer(
                            nowPlaying = nowPlaying,
                            lyrics = fullscreenLyrics,
                            currentTime = currentTime,
                            webViewReloadKey = webViewReloadKey,
                            onLineSeek = { seekTime ->
                                Timber.i("[fullscreen] onLineSeek($seekTime)")
                                Log.i(MAIN_SCREEN_LOG_TAG, "[fullscreen] onLineSeek($seekTime)")
                                viewModel.seekTo(seekTime)
                            },
                            amllDebugSource = "fullscreen",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                IconButton(
                    onClick = { isLyricsFullscreen = false },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(8.dp)
                ) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "退出全屏",
                        tint = if (useDarkSystemIcons) Color.Black else Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun AdaptiveStatusBarStyle(useDarkIcons: Boolean) {
    val view = LocalView.current

    SideEffect {
        val activity = view.context.findActivity() ?: return@SideEffect
        val insetsController = WindowCompat.getInsetsController(activity.window, view)
        insetsController.isAppearanceLightStatusBars = useDarkIcons
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun LyricsVisualLayer(
    nowPlaying: NowPlayingMusic?,
    lyrics: TTMLLyrics,
    currentTime: Long,
    webViewReloadKey: Int,
    onLineSeek: (Long) -> Unit,
    amllDebugSource: String,
    onFullscreenTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(webViewReloadKey, amllDebugSource) {
        Timber.i("[$amllDebugSource] LyricsVisualLayer reload signal key=$webViewReloadKey")
        Log.i(MAIN_SCREEN_LOG_TAG, "[$amllDebugSource] LyricsVisualLayer reload signal key=$webViewReloadKey")
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
    ) {
        if (nowPlaying?.albumArtUri != null) {
            AsyncImage(
                model = nowPlaying.albumArtUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(28.dp)
                    .alpha(0.55f)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.28f),
                            Color.Black.copy(alpha = 0.55f),
                            Color.Black.copy(alpha = 0.68f)
                        )
                    )
                )
        )

        androidx.compose.runtime.key(webViewReloadKey, amllDebugSource) {
            AMLLLyricsView(
                lyrics = lyrics,
                currentTime = currentTime,
                albumArtUri = nowPlaying?.albumArtUri,
                renderMode = AMLLRenderMode.DOM,
                debugSource = amllDebugSource,
                onLineSeek = onLineSeek,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Transparent overlay to reliably capture tap-to-fullscreen over WebView.
        if (onFullscreenTap != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { onFullscreenTap() }
            )
        }
    }
}

@Composable
fun PermissionStatusCard(
    notificationAccessGranted: Boolean,
    onOpenNotificationAccessSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (notificationAccessGranted) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (notificationAccessGranted) {
                    "通知访问已授权"
                } else {
                    "需要通知访问权限才能读取播放信息"
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            if (!notificationAccessGranted) {
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onOpenNotificationAccessSettings) {
                    Text("去授权")
                }
            }
        }
    }
}

private fun isNotificationAccessGranted(context: Context): Boolean {
    val enabledListeners = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    ) ?: return false
    return enabledListeners.contains(context.packageName)
}

private fun openSourceApp(context: Context, packageName: String?): Boolean {
    if (packageName.isNullOrBlank()) return false

    val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    return try {
        if (launchIntent == null) return false
        context.startActivity(launchIntent)
        true
    } catch (e: Exception) {
        false
    }
}

@Composable
fun NowPlayingCard(
    nowPlaying: NowPlayingMusic?,
    context: Context,
    onPlayPauseClick: () -> Unit,
    onSkipPreviousClick: () -> Unit,
    onSkipNextClick: () -> Unit,
    onRewind: () -> Unit,
    onFastForward: () -> Unit,
    onSeek: (Long) -> Unit,
    onCardClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    
    Card(
        modifier = modifier.clickable(onClick = onCardClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        if (nowPlaying != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 上部：歌曲信息 + 专辑封面
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 歌曲信息
                    Column(
                        modifier = Modifier
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // 播放源应用名
                        val appName = nowPlaying.packageName?.let { getAppNameFromPackage(context, it) }
                        Text(
                            text = appName ?: "播放源应用",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )

                        Text(
                            nowPlaying.title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Text(
                            nowPlaying.artist,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    
                    // 专辑封面（右侧）
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        if (nowPlaying.albumArtUri != null) {
                            AsyncImage(
                                model = nowPlaying.albumArtUri,
                                contentDescription = "专辑封面",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }

                // 可拖动进度条（全宽）
                var sliderValue by remember(nowPlaying.currentPosition) {
                    mutableStateOf(nowPlaying.currentPosition.toFloat())
                }
                
                Column(modifier = Modifier.fillMaxWidth()) {
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = {
                            onSeek(sliderValue.toLong())
                        },
                        valueRange = 0f..nowPlaying.duration.toFloat().coerceAtLeast(1f),
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            formatTime(sliderValue.toLong()),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            formatTime(nowPlaying.duration),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                // 播放控制按钮（3:5:3 比例，全部可触摸）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    var rewindJob by remember { mutableStateOf<Job?>(null) }
                    val leftInteractionSource = remember { MutableInteractionSource() }
                    
                    // 左侧区域 (3份) - 上一首/快退
                    Box(
                        modifier = Modifier
                            .weight(3f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(50))
                            .indication(leftInteractionSource, rememberRipple())
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { onSkipPreviousClick() },
                                    onPress = { offset ->
                                        val press = PressInteraction.Press(offset)
                                        leftInteractionSource.tryEmit(press)
                                        
                                        val longPressTimeout = 500L
                                        val repeatInterval = 200L
                                        
                                        try {
                                            awaitPointerEventScope {
                                                val down = awaitFirstDown(requireUnconsumed = false)
                                                val longPressJob = scope.launch {
                                                    delay(longPressTimeout)
                                                    // 长按开始，持续触发
                                                    while (true) {
                                                        onRewind()
                                                        delay(repeatInterval)
                                                    }
                                                }
                                                
                                                // 等待手指抬起
                                                val up = waitForUpOrCancellation()
                                                longPressJob.cancel()
                                                
                                                if (up == null) {
                                                    leftInteractionSource.tryEmit(PressInteraction.Cancel(press))
                                                } else {
                                                    leftInteractionSource.tryEmit(PressInteraction.Release(press))
                                                }
                                            }
                                        } catch (e: Exception) {
                                            leftInteractionSource.tryEmit(PressInteraction.Cancel(press))
                                            rewindJob?.cancel()
                                        }
                                    }
                                )
                            }
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.FastRewind,
                            contentDescription = "上一首（长按快退）",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    
                    // 中间区域 (5份) - 播放/暂停
                    Box(
                        modifier = Modifier
                            .weight(5f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(50))
                            .clickable { onPlayPauseClick() }
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (nowPlaying.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (nowPlaying.isPlaying) "暂停" else "播放",
                            modifier = Modifier.size(42.dp)
                        )
                    }
                    
                    var fastForwardJob by remember { mutableStateOf<Job?>(null) }
                    val rightInteractionSource = remember { MutableInteractionSource() }
                    
                    // 右侧区域 (3份) - 下一首/快进
                    Box(
                        modifier = Modifier
                            .weight(3f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(50))
                            .indication(rightInteractionSource, rememberRipple())
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { onSkipNextClick() },
                                    onPress = { offset ->
                                        val press = PressInteraction.Press(offset)
                                        rightInteractionSource.tryEmit(press)
                                        
                                        val longPressTimeout = 500L
                                        val repeatInterval = 200L
                                        
                                        try {
                                            awaitPointerEventScope {
                                                val down = awaitFirstDown(requireUnconsumed = false)
                                                val longPressJob = scope.launch {
                                                    delay(longPressTimeout)
                                                    // 长按开始，持续触发
                                                    while (true) {
                                                        onFastForward()
                                                        delay(repeatInterval)
                                                    }
                                                }
                                                
                                                // 等待手指抬起
                                                val up = waitForUpOrCancellation()
                                                longPressJob.cancel()
                                                
                                                if (up == null) {
                                                    rightInteractionSource.tryEmit(PressInteraction.Cancel(press))
                                                } else {
                                                    rightInteractionSource.tryEmit(PressInteraction.Release(press))
                                                }
                                            }
                                        } catch (e: Exception) {
                                            rightInteractionSource.tryEmit(PressInteraction.Cancel(press))
                                            fastForwardJob?.cancel()
                                        }
                                    }
                                )
                            }
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.FastForward,
                            contentDescription = "下一首（长按快进）",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "未检测到播放信息",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
fun LyricsDisplayArea(
    lyrics: TTMLLyrics,
    currentTime: Long,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(lyrics.lines) { line ->
            val isCurrentLine = currentTime in line.startTime..line.endTime
            LyricLineItem(
                line = line,
                isCurrent = isCurrentLine,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun LyricLineItem(
    line: LyricLine,
    isCurrent: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.background(
            color = if (isCurrent) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                Color.Transparent
            }
        )
    ) {
        Text(
            text = line.text,
            fontSize = if (isCurrent) 20.sp else 16.sp,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            color = if (isCurrent) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            },
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

fun formatTime(millis: Long): String {
    val totalSeconds = (millis / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}
