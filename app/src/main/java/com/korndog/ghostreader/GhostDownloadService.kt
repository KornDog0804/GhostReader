package com.korndog.ghostreader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Keeps the existing in-process torrent worker eligible to run when the UI is
 * no longer visible. Does not yet provide crash recovery or a persisted queue.
 */
class GhostDownloadService : Service() {
 companion object {
  const val ACTION_START = "com.korndog.ghostreader.DOWNLOAD_START"
  const val ACTION_PROGRESS = "com.korndog.ghostreader.DOWNLOAD_PROGRESS"
  const val ACTION_STOP = "com.korndog.ghostreader.DOWNLOAD_STOP"
  const val EXTRA_STATUS = "status"
  private const val CHANNEL = "ghostreader_downloads"
  private const val NOTIFICATION_ID = 2401
 }
 override fun onBind(intent: Intent?): IBinder? = null

 override fun onCreate() {
  super.onCreate()
  val manager = getSystemService(NotificationManager::class.java)
  manager.createNotificationChannel(NotificationChannel(CHANNEL, "GhostReader downloads", NotificationManager.IMPORTANCE_LOW))
 }

 override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
  when (intent?.action) {
   ACTION_STOP -> { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
   ACTION_START -> {
    val notification = notification("Preparing download...")
    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
   }
   ACTION_PROGRESS -> {
    val status = intent.getStringExtra(EXTRA_STATUS) ?: "Downloading..."
    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
   }
  }
  return START_NOT_STICKY
 }

 private fun notification(status: String): Notification {
  val launch = PendingIntent.getActivity(
   this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
   PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
  )
  val percent = Regex("(\\d{1,3})%").find(status)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
  return NotificationCompat.Builder(this, CHANNEL)
   .setSmallIcon(android.R.drawable.stat_sys_download)
   .setContentTitle("GhostReader download")
   .setContentText(status.take(110))
   .setContentIntent(launch)
   .setOnlyAlertOnce(true)
   .setOngoing(true)
   .setProgress(100, percent ?: 0, percent == null)
   .build()
 }
}
