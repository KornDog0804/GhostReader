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
 private val saveProgress = object : Runnable {
  override fun run() {
   player?.let { p ->
    val uri = p.currentMediaItem?.localConfiguration?.uri
    if (uri != null && uri.scheme == "file" && p.currentPosition >= 0) {
     val filename = File(uri.path.orEmpty()).name
     if (filename.isNotBlank()) getSharedPreferences("reading", MODE_PRIVATE).edit().putInt("audio_" + filename, p.currentPosition.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).apply()
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
   if (uri?.scheme == "file") getSharedPreferences("reading", MODE_PRIVATE).edit().putInt("audio_" + File(uri.path.orEmpty()).name, p.currentPosition.coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).apply()
  }
  mediaSession?.release()
  mediaSession = null
  player?.release()
  player = null
  super.onDestroy()
 }
}
