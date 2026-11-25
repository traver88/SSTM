package com.kaoyan.planner

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class PlannerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_TIMER,
                "计时提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "专注计时结束时提醒你" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            val reminderChannels = listOf(
                NotificationChannel(
                    CHANNEL_PHASE,
                    "阶段提醒",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "考研阶段预热和切换提醒" },
                NotificationChannel(
                    CHANNEL_DAILY,
                    "日常学习提醒",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "计划任务开始和单科时长提醒" },
                NotificationChannel(
                    CHANNEL_MONTHLY,
                    "月计划续期提醒",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "月度计划到期续期提醒" },
            )
            val syncChannel = NotificationChannel(
                CHANNEL_SYNC,
                "同步状态",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "云端同步失败时提醒你打开应用重试" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(syncChannel)
            getSystemService(NotificationManager::class.java).createNotificationChannels(reminderChannels)
        }
    }

    companion object {
        const val CHANNEL_TIMER = "timer_finished"
        const val CHANNEL_PHASE = "channel_phase_milestone"
        const val CHANNEL_DAILY = "channel_daily_execution"
        const val CHANNEL_MONTHLY = "channel_monthly_renew"
        const val CHANNEL_SYNC = "channel_sync_status"
    }
}
