package win.wintertools.videocatcher.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import win.wintertools.videocatcher.MainActivity
import win.wintertools.videocatcher.R

object Notify {
    private const val CH_PROGRESS = "progress"
    private const val CH_DONE = "done"
    const val ID_FOREGROUND = 1

    fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Laufende Downloads", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Fertige Downloads", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java)
            .setAction(MainActivity.ACTION_SHOW_DOWNLOADS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun progress(ctx: Context, title: String, text: String, percent: Int?): Notification {
        val b = NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
        if (percent != null) b.setProgress(100, percent.coerceIn(0, 100), false)
        else b.setProgress(0, 0, true)
        return b.build()
    }

    fun finished(ctx: Context, id: Long, ok: Boolean, title: String, text: String) {
        val n = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(if (ok) "Download fertig" else "Download fehlgeschlagen")
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$title\n$text"))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java)
                .notify((1000 + (id % 100000)).toInt(), n)
        } catch (_: SecurityException) {
            // Benachrichtigungen nicht erlaubt
        }
    }
}
