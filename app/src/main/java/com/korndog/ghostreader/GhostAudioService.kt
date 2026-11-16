package com.korndog.ghostreader

import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.io.File
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Owns audio playback independently of the Compose screen and keeps media controls available. */
class GhostAudioService : MediaSessionService() {
 private var mediaSession: MediaSession? = null
 private var player: ExoPlayer? = null
 private val handler = Handler(Looper.getMainLooper())
 private var ticks = 0
 private fun pushToCloud(filename: String, durationMs: Long) {
  val ctx = applicationContext
  Thread { runCatching { CloudSync.syncAudioProgress(ctx, pull = false, durationMs = if (durationMs > 0) mapOf(filename to durationMs) else emptyMap()) } }.start()
 }
 private val saveProgress = object : Runnable {
  override fun run() {
   player?.let { p ->
    val uri = p.currentMediaItem?.localConfiguration?.uri
    if (uri != null && uri.scheme == "file" && p.currentPosition >= 0) {
     val filename = File(uri.path.orEmpty()).name
     if (filename.isNotBlank()) {
      AudioProgress.record(this@GhostAudioService, filename, p.currentPosition)
      // While playing, send the spot to the cloud about every 30 seconds so the TV can pick it up.
      if (p.isPlaying && ++ticks % 6 == 0) pushToCloud(filename, p.duration)
     }
    }
   }
   handler.postDelayed(this, 5000)
  }
 }

 override fun onCreate() {
  super.onCreate()
  val exo = ExoPlayer.Builder(this).build().apply {
   setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
  }
  player = exo
  mediaSession = MediaSession.Builder(this, exo).build()
  handler.post(saveProgress)
 }

 override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

 override fun onTaskRemoved(rootIntent: Intent?) {
  // Keep playback alive when the user leaves the UI, but stop idle services.
  if (player?.isPlaying != true) stopSelf()
 }

 override fun onDestroy() {
  handler.removeCallbacks(saveProgress)
  player?.let { p ->
   val uri = p.currentMediaItem?.localConfiguration?.uri
   if (uri?.scheme == "file") {
    val filename = File(uri.path.orEmpty()).name
    AudioProgress.record(this, filename, p.currentPosition.coerceAtLeast(0))
    pushToCloud(filename, p.duration)
   }
  }
  mediaSession?.release()
  mediaSession = null
  player?.release()
  player = null
  super.onDestroy()
 }
}
