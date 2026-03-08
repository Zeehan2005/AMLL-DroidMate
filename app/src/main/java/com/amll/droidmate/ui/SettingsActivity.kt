package com.amll.droidmate.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.amll.droidmate.ui.theme.DroidMateTheme

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            DroidMateTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SettingsPage(
                        onBack = { finish() },
                        onOpenNotificationSettings = {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPage(
    onBack: () -> Unit,
    onOpenNotificationSettings: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedAction by remember { mutableStateOf(AppSettings.getCardClickAction(context)) }
    var lyricNotificationEnabled by remember { mutableStateOf(AppSettings.isLyricNotificationEnabled(context)) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        lyricNotificationEnabled = granted
        AppSettings.setLyricNotificationEnabled(context, granted)
        if (!granted) {
            com.amll.droidmate.service.LyricNotificationManager(context).cancel()
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        TopAppBar(
            title = { Text("设置") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "实时歌词通知",
                style = MaterialTheme.typography.titleMedium
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.fillMaxWidth(0.78f)) {
                        Text("常驻通知显示当前句歌词")
                        Text(
                            text = "默认关闭，可在通知权限允许后实时更新。获得锁屏权限后可锁屏显示。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    Switch(
                        checked = lyricNotificationEnabled,
                        onCheckedChange = { enabled ->
                            if (!enabled) {
                                lyricNotificationEnabled = false
                                AppSettings.setLyricNotificationEnabled(context, false)
                                com.amll.droidmate.service.LyricNotificationManager(context).cancel()
                            } else {
                                if (needsNotificationPermission() && !hasNotificationPermission(context)) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    lyricNotificationEnabled = true
                                    AppSettings.setLyricNotificationEnabled(context, true)
                                }
                            }
                        }
                    )
                }
            }

            Text(
                text = "“正在播放”卡片点击行为",
                style = MaterialTheme.typography.titleMedium
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                        title = "询问 (推荐)",
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

            Text(
                text = "通知访问权限（获取正在播放）",
                style = MaterialTheme.typography.titleMedium
            )
            Button(
                onClick = onOpenNotificationSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("打开通知访问设置")
            }
        }
    }
}

private fun needsNotificationPermission(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}

private fun hasNotificationPermission(context: android.content.Context): Boolean {
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
    androidx.compose.foundation.layout.Row(
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
        Text(text = title, modifier = Modifier.padding(start = 8.dp))
    }
}
