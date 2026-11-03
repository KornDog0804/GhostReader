package com.korndog.ghostreader

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

internal fun scanComics(folder:File):List<File> = folder.listFiles()?.filter { it.isFile && it.extension.equals("cbz",true) }?.sortedByDescending { it.lastModified() } ?: emptyList()
private fun pages(file:File):List<String> = ZipFile(file).use { z ->
 z.entries().asSequence().filter { !it.isDirectory && !it.name.startsWith("__MACOSX/") && !it.name.split('/').any { part -> part.startsWith(".") } && it.name.substringAfterLast('.').lowercase() in setOf("jpg","jpeg","png","webp") }.map { it.name }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.replace(Regex("\\d+")) { m -> m.value.padStart(12,'0') } }).toList()
}
internal fun validateComic(file:File) {
 require(file.length() in 1..1_500_000_000L) { "Comic archive is empty or exceeds 1.5 GB" }
 require(pages(file).isNotEmpty()) { "No comic pages found in CBZ" }
}
@Composable
internal fun ComicReader(file:File,onClose:()->Unit) {
 val ctx=LocalContext.current
 val prefs=remember { ctx.getSharedPreferences("comic_progress",0) }
 val key=remember(file) { file.name }
 var index by remember(file) { mutableIntStateOf(prefs.getInt(key,0)) }
 var names by remember(file) { mutableStateOf<List<String>>(emptyList()) }
 var image by remember(file) { mutableStateOf<android.graphics.Bitmap?>(null) }
 var message by remember(file) { mutableStateOf<String?>(null) }
 LaunchedEffect(file) { try { names=withContext(Dispatchers.IO) { pages(file) };index=index.coerceIn(0,(names.size-1).coerceAtLeast(0)) } catch(e:Exception){message=e.message} }
 LaunchedEffect(file,index,names) {
  if(names.isNotEmpty()) try {
   message=null
   image=withContext(Dispatchers.IO) {
    ZipFile(file).use { zip ->
     val entry=zip.getEntry(names[index]) ?: error("Missing page")
     require(entry.size in 1..35_000_000L) { "Page exceeds 35 MB" }
     val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
     zip.getInputStream(entry).use { stream -> BitmapFactory.decodeStream(stream, null, bounds) }
     var sample = 1
     while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 2400) sample *= 2
     val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 }
     zip.getInputStream(entry).use { stream -> BitmapFactory.decodeStream(stream, null, options) ?: error("Unreadable page") }
    }
   }
   prefs.edit().putInt(key,index).apply()
  } catch(e:Exception) { message=e.message }
 }
 BackHandler { onClose() }
 Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
  Row(Modifier.fillMaxWidth().padding(8.dp),horizontalArrangement=Arrangement.SpaceBetween) {
   TextButton(onClick=onClose){Text("← Library")}
   Text("${index+1} / ${names.size}",modifier=Modifier.padding(12.dp))
  }
  Box(Modifier.weight(1f).fillMaxWidth().pointerInput(names,index) {
   var delta=0f
   detectHorizontalDragGestures(onDragEnd={
    if(delta < -65 && index < names.lastIndex) index++
    if(delta > 65 && index > 0) index--
    delta=0f
   },onHorizontalDrag={change,amount->change.consume();delta+=amount})
  }) {
   image?.let { Image(bitmap=it.asImageBitmap(),contentDescription="Comic page ${index+1}",modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Fit) }
   message?.let { Text(it) }
  }
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
   OutlinedButton(onClick={index--},enabled=index>0){Text("← Previous")}
   OutlinedButton(onClick={index++},enabled=index<names.lastIndex){Text("Next →")}
  }
 }
}
