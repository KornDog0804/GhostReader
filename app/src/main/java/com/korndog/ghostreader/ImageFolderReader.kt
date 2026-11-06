package com.korndog.ghostreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
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
private val pageListCache=object:LinkedHashMap<String,List<DocumentFile>>(12,0.75f,true) {
 override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,List<DocumentFile>>?):Boolean=size>12
}
private val pageBitmapCache=object:LruCache<String,Bitmap>(24*1024) {
 override fun sizeOf(key:String,value:Bitmap):Int=(value.byteCount/1024).coerceAtLeast(1)
}
internal fun folderPages(context:Context, uri:Uri):List<DocumentFile> {
 val key=uri.toString()
 synchronized(pageListCache) { pageListCache[key]?.let { return it } }
 val found=DocumentFile.fromTreeUri(context,uri)?.listFiles()
  ?.filter { it.isFile && it.name?.substringAfterLast('.', "")?.lowercase() in imageTypes }
  ?.sortedBy { naturalKey(it.name.orEmpty()) } ?: emptyList()
 if(found.isNotEmpty()) synchronized(pageListCache) { pageListCache[key]=found }
 return found
}

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
 if(subfolders.isNotEmpty()) context.getSharedPreferences("image_folder_books",0).edit().remove(uri.toString()).apply()
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
 val key=uri.toString()
 synchronized(pageBitmapCache) { pageBitmapCache.get(key)?.let { return it } }
 val resolver=context.contentResolver
 val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
 resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,bounds) }
 var sample=1
 while(bounds.outWidth/sample>1600 || bounds.outHeight/sample>2400) sample*=2
 val opts=BitmapFactory.Options().apply { inSampleSize=sample;inPreferredConfig=Bitmap.Config.RGB_565 }
 val decoded=resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,opts) }
 if(decoded!=null) synchronized(pageBitmapCache) { pageBitmapCache.put(key,decoded) }
 return decoded
}
@Composable
internal fun ImageFolderReader(book:ImageFolderBook,onClose:()->Unit) {
 val context=LocalContext.current
 val prefs=remember { context.getSharedPreferences("image_folder_progress",0) }
 val key=book.uri.toString()
 var pages by remember(key) { mutableStateOf<List<DocumentFile>>(synchronized(pageListCache) { pageListCache[key] ?: emptyList() }) }
 var index by remember(key) { mutableIntStateOf(prefs.getInt(key,0)) }
 var bitmap by remember(key) { mutableStateOf<Bitmap?>(null) }
 var error by remember(key) { mutableStateOf<String?>(null) }
 var loading by remember(key) { mutableStateOf(true) }
 LaunchedEffect(key) {
  try {
   val found=withContext(Dispatchers.IO) { folderPages(context,book.uri) }
   if(found.isEmpty()) error="No image pages found. Re-select the book folder in Downloads."
   else { pages=found;index=index.coerceIn(0,found.lastIndex);error=null }
  }
  catch(e:Exception) { error=e.message ?: "Cannot read folder" }
 }
 LaunchedEffect(key,index,pages) {
  if(pages.isNotEmpty()) try {
   loading=true
   bitmap=synchronized(pageBitmapCache) { pageBitmapCache.get(pages[index].uri.toString()) }
   if(bitmap==null) bitmap=withContext(Dispatchers.IO) { decodePage(context,pages[index].uri) }
   if(bitmap==null) error="Could not read page"
   else {
    error=null
    prefs.edit().putInt(key,index).apply()
    val nearby=listOf(index+1,index-1).filter { it in pages.indices }
    withContext(Dispatchers.IO) { nearby.forEach { next -> try { decodePage(context,pages[next].uri) } catch(_:Exception) {} } }
   }
   loading=false
  } catch(e:Exception) { error=e.message ?: "Could not load page";loading=false }
 }
 var showControls by remember { mutableStateOf(true) }
 BackHandler { onClose() }
 Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
  if(showControls) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
   TextButton(onClick=onClose) { Text("← Library") }
   Text("${if(pages.isEmpty()) 0 else index+1} / ${pages.size}",modifier=Modifier.padding(14.dp))
  }
  GesturePage(pageKey=index,canPrev=index>0,canNext=index<pages.lastIndex,onPrev={index--},onNext={index++},onCenterTap={showControls=!showControls},modifier=Modifier.fillMaxWidth().weight(1f)) {
   bitmap?.let { Image(it.asImageBitmap(),contentDescription="Page ${index+1}",modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Fit) }
   if(loading && bitmap==null) CircularProgressIndicator(modifier=Modifier.align(Alignment.Center))
   error?.let { Text(it,color=Color.Red,modifier=Modifier.align(Alignment.Center).padding(16.dp)) }
  }
  if(showControls) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
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
