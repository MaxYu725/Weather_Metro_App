package com.weather.metro.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.weather.metro.R
import com.weather.metro.domain.AlertSeverity

object NotificationChannels {
    const val URGENT = NotificationContract.URGENT
    const val GENERAL = NotificationContract.GENERAL
    const val TIPS = NotificationContract.TIPS
    const val STATUS = NotificationContract.STATUS
    const val TOPIC_PRODUCTION = "hko_alerts"

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channels = listOf(
            NotificationChannel(
                URGENT,
                context.getString(R.string.notification_channel_urgent),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "黑雨、紅雨、八號或以上熱帶氣旋及其他緊急警告"
                enableVibration(true)
            },
            NotificationChannel(
                GENERAL,
                context.getString(R.string.notification_channel_general),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "一般天氣警告及狀態更新" },
            NotificationChannel(
                TIPS,
                context.getString(R.string.notification_channel_tips),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "香港天文台特別天氣提示" },
            NotificationChannel(
                STATUS,
                context.getString(R.string.notification_channel_status),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "通知服務測試和同步狀態" },
        )
        manager.createNotificationChannels(channels)
    }

    fun forSeverity(severity: AlertSeverity, isTip: Boolean): String = when {
        severity == AlertSeverity.URGENT -> URGENT
        isTip -> TIPS
        else -> GENERAL
    }
}
