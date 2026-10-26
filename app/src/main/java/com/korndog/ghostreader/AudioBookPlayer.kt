package com.korndog.ghostreader

import android.media.MediaMetadataRetriever
import android.content.ComponentName
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.BitmapFactory
import java.io.File

private val purple = Color(0xFFB784FF)
private data class AudioMetadata(val title: String, val author: String, val cover: android.graphics.Bitmap?)
private fun audioMetadata(file: File): AudioMetadata {
 val reader = MediaMetadataRetriever()
 return try {
  reader.setDataSource(file.absolutePath)
  val name = file.nameWithoutExtension.replace(Regex("^\\d+_"), "").replace('_', ' ')
  val title = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: name
  val author = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
   ?: reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
   ?: "YOUR AUDIOBOOK"
  val cover = reader.embeddedPicture?.takeIf { it.size < 8_000_000 }?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
  AudioMetadata(title, author, cover)
 } catch (_: Exception) {
  AudioMetadata(file.nameWithoutExtension.replace(Regex("^\\d+_"), "").replace('_', ' '), "YOUR AUDIOBOOK", null)
 } finally { runCatching { reader.release() } }
}

/** GhostReader listening room. Local playback with persistent position. */
@Composable
internal fun AudioBookPlayer(file: File, savedPosition: Int, onProgress: (Int) -> Unit, onClose: () -> Unit) {
 val meta = remember(file.absolutePath) { audioMetadata(file) }
 val context = LocalContext.current
 var player by remember(file.absolutePath) { mutableStateOf<MediaController?>(null) }
 var playing by remember(file.absolutePath) { mutableStateOf(false) }
 var position by remember(file.absolutePath) { mutableIntStateOf(savedPosition) }
 var duration by remember(file.absolutePath) { mutableIntStateOf(0) }
 var failure by remember(file.absolutePath) { mutableStateOf<String?>(null) }
 var speed by remember(file.absolutePath) { mutableFloatStateOf(1f) }
 var sleepMinutes by remember(file.absolutePath) { mutableIntStateOf(0) }
 var sleepUntil by remember(file.absolutePath) { mutableLongStateOf(0L) }
 DisposableEffect(file.absolutePath) {
  val token = SessionToken(context, ComponentName(context, GhostAudioService::class.java))
  val future = MediaController.Builder(context, token).buildAsync()
  var disposed = false
  future.addListener({
   if (!disposed) {
    runCatching {
     val controller = future.get()
     val uri = android.net.Uri.fromFile(file)
     val sameFile = controller.currentMediaItem?.localConfiguration?.uri == uri
     if (!sameFile) {
      controller.setMediaItem(MediaItem.Builder().setUri(uri).setMediaMetadata(
       MediaMetadata.Builder().setTitle(meta.title).setArtist(meta.author).build()
      ).build())
      controller.prepare()
      controller.seekTo(savedPosition.toLong().coerceAtLeast(0))
     }
     player = controller
     duration = controller.duration.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
     position = controller.currentPosition.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
     playing = controller.isPlaying
    }.onFailure { failure = it.message ?: "Cannot connect to audio service" }
   }
  }, ContextCompat.getMainExecutor(context))
  val handler = Handler(Looper.getMainLooper())
  val tick = object : Runnable {
   override fun run() {
    player?.let { p ->
     runCatching {
      position = p.currentPosition.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
      duration = p.duration.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
      playing = p.isPlaying
      if (p.isPlaying) onProgress(position)
      if (sleepUntil > 0 && System.currentTimeMillis() >= sleepUntil) {
       p.pause()
       playing = false
       sleepUntil = 0L
       sleepMinutes = 0
       onProgress(position)
      }
     }
    }
    handler.postDelayed(this, 1000)
   }
  }
  handler.post(tick)
  onDispose {
   disposed = true
   handler.removeCallbacks(tick)
   runCatching { player?.let { onProgress(it.currentPosition.toInt()) } }
   MediaController.releaseFuture(future)
   player = null
  }
 }
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
   TextButton(onClick = onClose) { Text("‹ LIBRARY", color = purple) }
   Text("NOW LISTENING", color = Color(0xFFA999C1), style = MaterialTheme.typography.labelMedium, letterSpacing = 2.sp)
  }
  Spacer(Modifier.height(22.dp))
  Box(
   Modifier.fillMaxWidth(0.79f).aspectRatio(1f).clip(RoundedCornerShape(22.dp)).background(Color(0xFF251933)),
   contentAlignment = Alignment.Center
  ) {
   if (meta.cover != null) Image(meta.cover.asImageBitmap(), contentDescription = "Audiobook cover", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
   else Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text("GHOST", color = purple, fontSize = 34.sp, fontWeight = FontWeight.Black)
    Text("READER", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
    Spacer(Modifier.height(22.dp))
    Text("◈", color = purple, fontSize = 58.sp)
    Text("AUDIOBOOK", color = Color(0xFFBCA9D5), letterSpacing = 3.sp)
   }
  }
  Spacer(Modifier.height(25.dp))
  Text(meta.title, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
  Spacer(Modifier.height(5.dp))
  Text(meta.author, color = purple, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 2)
  Spacer(Modifier.height(25.dp))
  if (failure != null) Text("Playback error: $failure", color = Color(0xFFFF9999))
  Slider(
   value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
   onValueChange = { position = it.toInt() },
   onValueChangeFinished = { player?.seekTo(position.toLong()); onProgress(position) },
   valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
   enabled = player != null && duration > 0,
   modifier = Modifier.fillMaxWidth()
  )
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
   Text(formatAudioTime(position), color = Color.LightGray)
   Text("-" + formatAudioTime((duration - position).coerceAtLeast(0)), color = Color.LightGray)
  }
  Spacer(Modifier.height(18.dp))
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
   OutlinedButton(enabled = player != null, onClick = { player?.let { position = (it.currentPosition - 15000L).coerceAtLeast(0).toInt(); it.seekTo(position.toLong()); onProgress(position) } }) { Text("↶ 15") }
   Button(
    enabled = player != null,
    modifier = Modifier.size(width = 112.dp, height = 72.dp),
    onClick = { player?.let { if (it.isPlaying) { it.pause(); playing = false; onProgress(it.currentPosition.toInt()) } else { it.play(); playing = true } } },
    colors = ButtonDefaults.buttonColors(containerColor = purple)
   ) { Text(if (playing) "Ⅱ" else "▶", color = Color(0xFF170D20), fontSize = 28.sp) }
   OutlinedButton(enabled = player != null, onClick = { player?.let { position = (it.currentPosition + 30000L).coerceAtMost(duration.toLong()).toInt(); it.seekTo(position.toLong()); onProgress(position) } }) { Text("30 ↷") }
  }
  Spacer(Modifier.height(24.dp))
  HorizontalDivider(color = Color(0xFF3D304B))
  Spacer(Modifier.height(12.dp))
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
   TextButton(onClick = {
    speed = when (speed) { 0.75f -> 1f; 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; else -> 0.75f }
    runCatching { player?.setPlaybackSpeed(speed) }
   }) { Text("${speed}× SPEED", color = purple) }
   TextButton(onClick = {
    sleepMinutes = when (sleepMinutes) { 0 -> 15; 15 -> 30; 30 -> 60; else -> 0 }
    sleepUntil = if (sleepMinutes == 0) 0L else System.currentTimeMillis() + sleepMinutes * 60_000L
   }) { Text(if (sleepMinutes == 0) "☾ SLEEP TIMER" else "☾ ${sleepMinutes} MIN", color = purple) }
  }
  Spacer(Modifier.height(14.dp))
  Text("Background playback and lock-screen controls enabled", color = Color(0xFF9588A6), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
  Spacer(Modifier.height(30.dp))
 }
}

private fun formatAudioTime(ms: Int): String {
 val seconds = ms.coerceAtLeast(0) / 1000
 return "%d:%02d:%02d".format(seconds / 3600, (seconds / 60) % 60, seconds % 60)
}
