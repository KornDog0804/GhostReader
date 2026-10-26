package com.korndog.ghostreader

import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Owns audio playback independently of the Compose screen and keeps media controls available. */
class GhostAudioService : MediaSessionService() {
 private var mediaSession: MediaSession? = null
 private var player: ExoPlayer? = null

 override fun onCreate() {
  super.onCreate()
  val exo = ExoPlayer.Builder(this).build().apply {
   setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
  }
  player = exo
  mediaSession = MediaSession.Builder(this, exo).build()
 }

 override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

 override fun onTaskRemoved(rootIntent: Intent?) {
  // Keep playback alive when the user leaves the UI, but stop idle services.
  if (player?.isPlaying != true) stopSelf()
 }

 override fun onDestroy() {
  mediaSession?.release()
  mediaSession = null
  player?.release()
  player = null
  super.onDestroy()
 }
}
