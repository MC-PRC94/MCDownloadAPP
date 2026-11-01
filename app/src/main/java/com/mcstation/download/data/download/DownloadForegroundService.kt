package com.mcstation.download.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mcstation.download.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 下载保活服务：把下载进程挂到前台，避免用户切后台/锁屏时被系统回收；
 * 同时用一条常驻通知实时显示进度、速度。
 *
 * 设计取舍：下载逻辑仍留在 ViewModel（分段下载器重度依赖协程与状态），
 * 本服务只负责"抬优先级 + 画通知"，通过 [DownloadLiveHub] 单向读取状态，
 * 因此不会引入第二份任务状态，也不会在服务重建时重启下载。
 */
class DownloadForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastNotifyAt = 0L
    private var lastPercent = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 必须在 onCreate/onStartCommand 里尽快 startForeground，否则 ANR
        startForegroundCompat(buildNotification(DownloadLiveHub.state.value))
        scope.launch {
            DownloadLiveHub.state.collectLatest { state ->
                if (state == null) {
                    stopSelfSafely()
                } else {
                    maybeNotify(state)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = DownloadLiveHub.state.value ?: return START_NOT_STICKY
        maybeNotify(state, force = true)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------------- 通知 ----------------

    private fun maybeNotify(state: DownloadLiveState, force: Boolean = false) {
        val now = System.currentTimeMillis()
        // 进度回调很密（每读一块就回调一次），节流到 ~2 次/秒，百分比变化立即刷新
        if (!force && now - lastNotifyAt < 450 && state.percent == lastPercent) return
        lastNotifyAt = now
        lastPercent = state.percent
        val manager = getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.notify(NOTIFICATION_ID, buildNotification(state))
        } catch (_: Exception) {
            // 通知失败（权限被关等）不影响下载
        }
    }

    private fun buildNotification(state: DownloadLiveState?): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(state?.title ?: "正在下载")
            .setContentText(
                listOfNotNull(
                    state?.sizeText,
                    state?.speedText.takeIf { !it.isNullOrEmpty() },
                    state?.let { if (it.activeCount > 1) "共 ${it.activeCount} 个任务" else null },
                ).joinToString(" · "),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (state != null && state.total > 0) {
            builder.setProgress(100, state.percent, false)
        } else if (state != null) {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "下载进度",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示正在下载的版本进度与速度"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            // Android 12+ 后台启动限制等极端情况：服务启动失败不影响下载本身
        }
    }

    private fun stopSelfSafely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
        stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "download_progress"
        private const val NOTIFICATION_ID = 0x1D0A

        /** 有下载任务时启动（幂等：服务已在跑时重复调用无副作用） */
        fun start(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                // 后台启动被拒绝：下载照常进行，只是没有常驻通知
            }
        }

        /** 任务全部结束/取消时停止 */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, DownloadForegroundService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
