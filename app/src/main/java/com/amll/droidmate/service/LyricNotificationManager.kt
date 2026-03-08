package com.amll.droidmate.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.amll.droidmate.MainActivity
import com.amll.droidmate.R
import com.amll.droidmate.domain.model.LyricLine

class LyricNotificationManager(private val context: Context) {

    fun showOrUpdate(currentLine: LyricLine?) {
        if (!hasNotificationPermission()) return

        createChannelIfNeeded()
        val safeLine = buildNotificationText(currentLine)
        val contentIntent = createOpenAppIntent()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentText(safeLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText(safeLine))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)        // 提升优先级以确保锁屏显示
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)     // 在锁屏上显示完整内容
            .setShowWhen(false)                                      // 不显示时间戳
            .setCategory(NotificationCompat.CATEGORY_STATUS)         // 分类为状态通知
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotificationText(currentLine: LyricLine?): String {
        if (currentLine == null) return ""

        val lines = listOf(
            currentLine.text,
            currentLine.translation,
            currentLine.transliteration
        )
            .mapNotNull { it?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        return lines.joinToString(separator = "\n")
    }

    fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun createOpenAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, REQUEST_CODE_OPEN_APP, intent, flags)
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "实时歌词",
            NotificationManager.IMPORTANCE_DEFAULT           // 提升重要性以支持锁屏显示
        ).apply {
            description = "显示当前播放歌词行"
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC  // 锁屏可见
            setSound(null, null)                             // 静音
            enableVibration(false)                           // 禁用震动
        }

        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "lyric_live_channel"
        private const val NOTIFICATION_ID = 20021
        private const val REQUEST_CODE_OPEN_APP = 9001
    }
}
