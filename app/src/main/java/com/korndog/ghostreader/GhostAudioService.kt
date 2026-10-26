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
 /** Writes the listening position for the current file. Never saves a not-yet-ready 0 over real progress. */
 private fun savePosition(force: Boolean = false) {
  val p = player ?: return
  val uri = p.currentMediaItem?.localConfiguration?.uri ?: return
  if (uri.scheme != "file") return
  val ready = p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_ENDED
  if (!ready && !force) return
  val position = p.currentPosition
  if (position < 0) return
  val filename = File(uri.path.orEmpty()).name
  if (filename.isBlank()) return
  getSharedPreferences("reading", MODE_PRIVATE).edit()
   .putInt("audio_" + filename, position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
   .commit()
 }

 private val saveProgress = object : Runnable {
  override fun run() {
   savePosition()
   handler.postDelayed(this, 2000)
  }
 }

 override fun onCreate() {
  super.onCreate()
  val exo = ExoPlayer.Builder(this).build().apply {
   setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
  }
  player = exo
  // Save the moment playback pauses, stops or jumps, not just on the timer.
  exo.addListener(object : Player.Listener {
   override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) savePosition() }
   override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) { savePosition() }
  })
  mediaSession = MediaSession.Builder(this, exo).build()
  handler.post(saveProgress)
 }

 override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

 override fun onTaskRemoved(rootIntent: Intent?) {
  // Keep playback alive when the user leaves the UI, but stop idle services.
  savePosition()
  if (player?.isPlaying != true) stopSelf()
 }

 override fun onDestroy() {
  handler.removeCallbacks(saveProgress)
  savePosition()
  mediaSession?.release()
  mediaSession = null
  player?.release()
  player = null
  super.onDestroy()
 }
}
