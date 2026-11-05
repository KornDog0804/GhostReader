package com.korndog.ghostreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ImageFolderBook(val uri: Uri, val title: String)
private val imageTypes = setOf("jpg","jpeg","png","webp")
private fun naturalKey(s:String)=s.lowercase().replace(Regex("\\d+")) { it.value.padStart(12,'0') }
internal fun folderPages(context:Context, uri:Uri):List<DocumentFile> =
 DocumentFile.fromTreeUri(context,uri)?.listFiles()?.filter { it.isFile && it.name?.substringAfterLast('.', "")?.lowercase() in imageTypes }
  ?.sortedBy { naturalKey(it.name.orEmpty()) } ?: emptyList()

internal fun savedImageFolders(context:Context):List<ImageFolderBook> {
 val prefs=context.getSharedPreferences("image_folder_books",0)
 return prefs.all.keys.sorted().mapNotNull { key ->
  val title=prefs.getString(key,null) ?: return@mapNotNull null
  val uri=Uri.parse(key)
  if(context.contentResolver.persistedUriPermissions.none { it.isReadPermission && (it.uri==uri || uri.toString().startsWith(it.uri.toString().substringBeforeLast("%2F") + "%2F")) }) null
  else ImageFolderBook(uri,title)
 }
}
internal fun addImageFolder(context:Context,uri:Uri) {
 val root=DocumentFile.fromTreeUri(context,uri) ?: error("Cannot open folder")
 val children=root.listFiles()
 val subfolders=children.filter { it.isDirectory && it.listFiles().any { page -> page.isFile && page.name?.substringAfterLast('.', "")?.lowercase() in imageTypes } }
 val targets=if(subfolders.isNotEmpty()) subfolders else listOf(root)
 val edit=context.getSharedPreferences("image_folder_books",0).edit()
 var added=0
 targets.forEach { folder ->
  if(folder.listFiles().any { it.isFile && it.name?.substringAfterLast('.', "")?.lowercase() in imageTypes }) {
   edit.putString(folder.uri.toString(),folder.name ?: "Image Book")
   added++
  }
 }
 require(added>0) { "No image book folders found" }
 edit.apply()
}
internal fun removeImageFolder(context:Context,uri:Uri) {
 context.getSharedPreferences("image_folder_books",0).edit().remove(uri.toString()).apply()
}
private fun decodePage(context:Context,uri:Uri):Bitmap? {
 val resolver=context.contentResolver
 val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
 resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,bounds) }
 var sample=1
 while(bounds.outWidth/sample>1600 || bounds.outHeight/sample>2400) sample*=2
 val opts=BitmapFactory.Options().apply { inSampleSize=sample;inPreferredConfig=Bitmap.Config.RGB_565 }
 return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,opts) }
}
@Composable
internal fun ImageFolderReader(book:ImageFolderBook,onClose:()->Unit) {
 val context=LocalContext.current
 val prefs=remember { context.getSharedPreferences("image_folder_progress",0) }
 val key=book.uri.toString()
 var pages by remember(key) { mutableStateOf<List<DocumentFile>>(emptyList()) }
 var index by remember(key) { mutableIntStateOf(prefs.getInt(key,0)) }
 var bitmap by remember(key) { mutableStateOf<Bitmap?>(null) }
 var error by remember(key) { mutableStateOf<String?>(null) }
 var loading by remember(key) { mutableStateOf(true) }
 LaunchedEffect(key) {
  try { pages=withContext(Dispatchers.IO) { folderPages(context,book.uri) };index=index.coerceIn(0,(pages.size-1).coerceAtLeast(0)) }
  catch(e:Exception) { error=e.message ?: "Cannot read folder" }
 }
 LaunchedEffect(key,index,pages) {
  if(pages.isNotEmpty()) try {
   loading=true
   bitmap=null
   bitmap=withContext(Dispatchers.IO) { decodePage(context,pages[index].uri) }
   if(bitmap==null) error="Could not read page"
   else { error=null;prefs.edit().putInt(key,index).apply() }
   loading=false
  } catch(e:Exception) { error=e.message ?: "Could not load page";loading=false }
 }
 BackHandler { onClose() }
 Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
   TextButton(onClick=onClose) { Text("← Library") }
   Text("${index+1} / ${pages.size}",modifier=Modifier.padding(14.dp))
  }
  Box(Modifier.fillMaxWidth().weight(1f).pointerInput(pages,index) {
   var drag=0f
   detectHorizontalDragGestures(onDragEnd={
    if(drag < -65 && index<pages.lastIndex) index++
    if(drag > 65 && index>0) index--
    drag=0f
   },onHorizontalDrag={change,amount->change.consume();drag+=amount})
  }) {
   bitmap?.let { Image(it.asImageBitmap(),contentDescription="Page ${index+1}",modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Fit) }
   if(loading) CircularProgressIndicator(modifier=Modifier.align(Alignment.Center))
   error?.let { Text(it,color=Color.Red,modifier=Modifier.align(Alignment.Center).padding(16.dp)) }
  }
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
   OutlinedButton(onClick={index--},enabled=index>0) { Text("← Previous") }
   OutlinedButton(onClick={index++},enabled=index<pages.lastIndex) { Text("Next →") }
  }
 }
}
@Composable
internal fun ImageFolderShelf(books:List<ImageFolderBook>,onOpen:(ImageFolderBook)->Unit,onRemove:(ImageFolderBook)->Unit) {
 val ctx=LocalContext.current
 var expanded by remember { mutableStateOf(false) }
 val series=books.filter { it.title.matches(Regex("^[0-9]{2} - .*")) }
 val others=books.filterNot { it in series }
 if(series.isNotEmpty()) {
  Row(Modifier.fillMaxWidth().clickable { expanded=!expanded }.padding(20.dp),verticalAlignment=Alignment.CenterVertically) {
   Column(Modifier.weight(1f)) {
    Text("Dog Man",style=MaterialTheme.typography.titleLarge)
    Text("${series.size} books · ${if(expanded) "Hide books" else "Browse series"}")
   }
   Text(if(expanded) "▲" else "▼")
  }
  if(expanded) series.sortedBy { naturalKey(it.title) }.forEach { ImageFolderBookRow(it,onOpen,onRemove) }
 }
 others.forEach { ImageFolderBookRow(it,onOpen,onRemove) }
}
@Composable
private fun ImageFolderBookRow(book:ImageFolderBook,onOpen:(ImageFolderBook)->Unit,onRemove:(ImageFolderBook)->Unit) {
 val ctx=LocalContext.current
 var cover by remember(book.uri) { mutableStateOf<Bitmap?>(null) }
 var count by remember(book.uri) { mutableIntStateOf(0) }
 LaunchedEffect(book.uri) {
  val pages=withContext(Dispatchers.IO) { folderPages(ctx,book.uri) }
  count=pages.size
  cover=pages.firstOrNull()?.let { withContext(Dispatchers.IO) { decodePage(ctx,it.uri) } }
 }
 Row(Modifier.fillMaxWidth().clickable { onOpen(book) }.padding(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
  cover?.let { Image(it.asImageBitmap(),null,modifier=Modifier.size(width=65.dp,height=90.dp),contentScale=ContentScale.Fit) }
  Column(Modifier.weight(1f)) {
   Text(book.title,style=MaterialTheme.typography.titleMedium)
   val progress=ctx.getSharedPreferences("image_folder_progress",0).getInt(book.uri.toString(),0)
   Text("$count pages · Page ${if(count==0) 0 else progress.coerceIn(0,count-1)+1}")
   Text("Read book",color=MaterialTheme.colorScheme.primary)
  }
  TextButton(onClick={onRemove(book)}) { Text("Remove") }
 }
}
