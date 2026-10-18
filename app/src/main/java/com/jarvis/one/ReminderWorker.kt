package com.jarvis.one

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val text = inputData.getString("text").orEmpty()
        if (text.isBlank()) return Result.failure()
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel("jarvis_reminders", "Jarvis reminders", NotificationManager.IMPORTANCE_DEFAULT))
        }
        manager.notify(System.currentTimeMillis().toInt(), NotificationCompat.Builder(applicationContext, "jarvis_reminders")
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("Jarvis reminder")
            .setContentText(text)
            .setAutoCancel(true)
            .build())
        return Result.success()
    }
}

class ReminderReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent?) = Unit
}
