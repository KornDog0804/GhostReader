package com.korndog.ghostreader

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
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

internal fun scanPdfs(folder:File):List<File> = folder.listFiles()?.filter { it.isFile && it.extension.equals("pdf",true) }?.sortedByDescending { it.lastModified() } ?: emptyList()
internal fun validatePdf(file:File) {
 require(file.length() in 1..750_000_000L) { "PDF empty or too large" }
 ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
  PdfRenderer(fd).use { renderer -> require(renderer.pageCount>0) { "PDF has no pages" } }
 }
}
@Composable
internal fun PdfReader(file:File,onClose:()->Unit) {
 val ctx=LocalContext.current
 val prefs=remember { ctx.getSharedPreferences("pdf_progress",0) }
 var index by remember(file) { mutableIntStateOf(prefs.getInt(file.name,0)) }
 var count by remember(file) { mutableIntStateOf(0) }
 var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
 var message by remember(file) { mutableStateOf<String?>(null) }
 LaunchedEffect(file,index) {
  try {
   val result=withContext(Dispatchers.IO) {
    ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
     PdfRenderer(fd).use { renderer ->
      val pageIndex=index.coerceIn(0,renderer.pageCount-1)
      renderer.openPage(pageIndex).use { page ->
       val scale=(1600f/page.width).coerceAtMost(2f)
       val width=(page.width*scale).toInt().coerceAtLeast(1)
       val height=(page.height*scale).toInt().coerceAtLeast(1)
       val b=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
       b.eraseColor(android.graphics.Color.WHITE)
       page.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
       renderer.pageCount to b
      }
     }
    }
   }
   count=result.first;bitmap=result.second
   prefs.edit().putInt(file.name,index).apply()
  } catch(e:Exception){message=e.message?:"Cannot render PDF"}
 }
 BackHandler { onClose() }
 Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
  Row(Modifier.fillMaxWidth().padding(8.dp),horizontalArrangement=Arrangement.SpaceBetween) {
   TextButton(onClick=onClose){Text("← Library")}
   Text("${index+1} / $count",modifier=Modifier.padding(12.dp))
  }
  Box(Modifier.weight(1f).fillMaxWidth().pointerInput(count,index) {
   var delta=0f
   detectHorizontalDragGestures(onDragEnd={
    if(delta < -65 && index < count-1) index++
    if(delta > 65 && index > 0) index--
    delta=0f
   },onHorizontalDrag={change,amount->change.consume();delta+=amount})
  }) {
   bitmap?.let { Image(bitmap=it.asImageBitmap(),contentDescription="PDF page ${index+1}",modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Fit) }
   message?.let { Text(it) }
  }
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
   OutlinedButton(onClick={index--},enabled=index>0){Text("← Previous")}
   OutlinedButton(onClick={index++},enabled=index<count-1){Text("Next →")}
  }
 }
}

/** Generate the actual first PDF page as its bookshelf cover. */
internal fun pdfCover(file:File):Bitmap =
 ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
  PdfRenderer(fd).use { renderer ->
   renderer.openPage(0).use { page ->
    val width=360
    val height=(width.toFloat()*page.height/page.width).toInt().coerceAtLeast(1).coerceAtMost(1200)
    Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also { bmp ->
     bmp.eraseColor(android.graphics.Color.WHITE)
     page.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    }
   }
  }
 }
