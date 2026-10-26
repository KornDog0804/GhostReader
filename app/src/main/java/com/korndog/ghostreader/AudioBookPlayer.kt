package com.korndog.ghostreader

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File

/** First local-audio milestone. Player persists position per file. */
@Composable
internal fun AudioBookPlayer(file: File, savedPosition: Int, onProgress: (Int) -> Unit, onClose: () -> Unit) {
 var player by remember(file.absolutePath) { mutableStateOf<MediaPlayer?>(null) }
 var playing by remember(file.absolutePath) { mutableStateOf(false) }
 var position by remember(file.absolutePath) { mutableIntStateOf(savedPosition) }
 var duration by remember(file.absolutePath) { mutableIntStateOf(0) }
 var failure by remember(file.absolutePath) { mutableStateOf<String?>(null) }
 DisposableEffect(file.absolutePath) {
  val mp = MediaPlayer()
  try {
   mp.setDataSource(file.absolutePath)
   mp.prepare()
   duration = mp.duration.coerceAtLeast(0)
   mp.seekTo(savedPosition.coerceIn(0, duration))
   mp.setOnCompletionListener { playing = false; position = 0; onProgress(0) }
   player = mp
  } catch (e: Exception) { failure = e.message ?: "Cannot play this audio file"; mp.release() }
  val handler = Handler(Looper.getMainLooper())
  val tick = object : Runnable {
   override fun run() {
    player?.let { p ->
     runCatching { position = p.currentPosition; if (p.isPlaying) onProgress(position) }
    }
    handler.postDelayed(this, 1200)
   }
  }
  handler.post(tick)
  onDispose {
   handler.removeCallbacks(tick)
   runCatching { player?.let { onProgress(it.currentPosition); it.release() } }
   player = null
  }
 }
 Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
  TextButton(onClick = onClose) { Text("‹ LIBRARY") }
  Text(file.name.substringBeforeLast('.').replace('_', ' '), color = Color.White, style = MaterialTheme.typography.headlineSmall)
  Text("AUDIOBOOK", color = Color(0xFFB784FF))
  if (failure != null) Text("Playback error: $failure", color = Color(0xFFFF9999))
  Text("${formatAudioTime(position)} / ${formatAudioTime(duration)}", color = Color.LightGray)
  Slider(value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()), onValueChange = {
   position = it.toInt()
  }, onValueChangeFinished = { player?.seekTo(position); onProgress(position) }, valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = player != null && duration > 0)
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
   OutlinedButton(enabled = player != null, onClick = { player?.let { position = (it.currentPosition - 15000).coerceAtLeast(0); it.seekTo(position); onProgress(position) } }) { Text("−15s") }
   Button(enabled = player != null, onClick = {
    player?.let { if (it.isPlaying) { it.pause(); playing = false } else { it.start(); playing = true } }
   }) { Text(if (playing) "Pause" else "Play") }
   OutlinedButton(enabled = player != null, onClick = { player?.let { position = (it.currentPosition + 30000).coerceAtMost(duration); it.seekTo(position); onProgress(position) } }) { Text("+30s") }
  }
  Text("Playback position is saved automatically. Keep GhostReader open while listening in this first version.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
 }
}

private fun formatAudioTime(ms: Int): String {
 val seconds = ms.coerceAtLeast(0) / 1000
 return "%d:%02d:%02d".format(seconds / 3600, (seconds / 60) % 60, seconds % 60)
}
