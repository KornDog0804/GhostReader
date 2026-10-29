package com.korndog.ghostreader

import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.net.URL
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Html
import android.util.Xml
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

private val violet = Color(0xFFB784FF)
private val dark = Color(0xFF0B0911)
internal data class Book(val file: File, val title: String, val sectionPrefix: String = "") {
 val progressKey: String get() = file.name + if (sectionPrefix.isBlank()) "" else "::" + sectionPrefix
}
internal data class Chapter(val title: String, val text: String, val spans: List<TextSpan> = emptyList())

class MainActivity : ComponentActivity() {
 private val incomingMagnet = mutableStateOf<String?>(null)
 override fun onNewIntent(intent: Intent) {
  super.onNewIntent(intent)
  setIntent(intent)
  acceptMagnet(intent)
 }
 private fun acceptMagnet(intent: Intent?) {
  val uri = intent?.data
  if (intent?.action == Intent.ACTION_VIEW && uri?.scheme.equals("magnet", true) && uri.toString().startsWith("magnet:?xt=urn:btih:", true)) incomingMagnet.value = uri.toString()
 }
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  acceptMagnet(intent)
  setContent { MaterialTheme(colorScheme = darkColorScheme(primary = violet, background = dark, surface = Color(0xFF191321))) { GhostApp(incomingMagnet.value, onMagnetConsumed = { incomingMagnet.value = null }) } }
 }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GhostApp(incomingMagnet: String?, onMagnetConsumed: () -> Unit) {
 val ctx = LocalContext.current
 val shelf = remember { File(ctx.filesDir, "books").apply { mkdirs() } }
 val prefs = remember { ctx.getSharedPreferences("reading", 0) }
 var books by remember { mutableStateOf(scanBooks(shelf)) }
 var selected by remember { mutableStateOf<Book?>(null) }
 var selectedAudio by remember { mutableStateOf<File?>(null) }
 var audioFiles by remember { mutableStateOf(scanAudioFiles(File(ctx.filesDir, "audio").apply { mkdirs() })) }
 var deleteCandidate by remember { mutableStateOf<Book?>(null) }
 var deleteAudioCandidate by remember { mutableStateOf<File?>(null) }
 var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
 var chapterIndex by remember { mutableIntStateOf(0) }
 var pageIndex by remember { mutableIntStateOf(0) }
 var fontSize by remember { mutableFloatStateOf(prefs.getFloat("fontSize", 18f)) }
 var error by remember { mutableStateOf<String?>(null) }
 var magnet by remember { mutableStateOf("") }
 var showMagnet by remember { mutableStateOf(false) }
 var showHome by remember { mutableStateOf(true) }
 var tab by remember { mutableIntStateOf(0) }
 var magnetMode by remember { mutableStateOf("epub") }
 LaunchedEffect(incomingMagnet) {
  if (!incomingMagnet.isNullOrBlank()) {
   magnet = incomingMagnet
   magnetMode = "audio"
   showMagnet = true
   showHome = false
   tab = 1
   selectedAudio = null
   selected = null
   onMagnetConsumed()
  }
 }
 var audioChoices by remember { mutableStateOf<List<BookTorrentEngine.AudioOption>>(emptyList()) }
 var audioSelection by remember { mutableStateOf<java.util.concurrent.CompletableFuture<Int>?>(null) }
 var showTools by remember { mutableStateOf(false) }
 var backupBusy by remember { mutableStateOf(false) }
 var backupStatus by remember { mutableStateOf("") }
 var backupPercent by remember { mutableIntStateOf(0) }
 var backupPhase by remember { mutableStateOf("") }
 var torrentBusy by remember { mutableStateOf(false) }
 var torrentStatus by remember { mutableStateOf("") }
 val torrentWorker = remember { java.util.concurrent.Executors.newSingleThreadExecutor() }
 val backupWorker = remember { java.util.concurrent.Executors.newSingleThreadExecutor() }
 val uiHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
 var torrentEngine by remember { mutableStateOf<BookTorrentEngine?>(null) }
 fun downloadMagnet() {
  if (torrentBusy) return
  if (!magnet.trim().startsWith("magnet:?xt=urn:btih:", true)) {
   error = "Paste a complete magnet link, not an EPUB filename"
   return
  }
  torrentBusy = true
  val engine = BookTorrentEngine(ctx.applicationContext)
  torrentEngine = engine
  torrentWorker.execute {
   var downloaded: File? = null
   try {
    if (magnetMode == "audio") {
     val audioDir = File(ctx.filesDir, "audio").apply { mkdirs() }
     downloaded = engine.downloadAudio(magnet.trim(), audioDir, { status -> uiHandler.post { torrentStatus = status } }, { options ->
      val selection = java.util.concurrent.CompletableFuture<Int>()
      uiHandler.post { audioChoices = options; audioSelection = selection }
      try { selection.get(10, java.util.concurrent.TimeUnit.MINUTES) } finally { uiHandler.post { audioSelection = null; audioChoices = emptyList() } }
     })
    } else {
     downloaded = engine.download(magnet.trim(), shelf) { status -> uiHandler.post { torrentStatus = status } }
     parseEpub(downloaded!!)
    }
    uiHandler.post {
     books = scanBooks(shelf)
     audioFiles = scanAudioFiles(File(ctx.filesDir, "audio"))
     torrentStatus = if (magnetMode == "audio") "Audiobook downloaded and added to your shelf" else "EPUB downloaded and added to your shelf"
     torrentBusy = false
     torrentEngine = null
    }
   } catch (e: Exception) {
    downloaded?.delete()
    uiHandler.post {
     error = e.message ?: "Torrent download failed"
     torrentBusy = false
     torrentEngine = null
    }
   }
  }
 }
 val backupExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
  if (uri != null) {
   backupBusy = true
   backupPercent = 0
   backupPhase = "Saving"
   backupStatus = "Preparing backup..."
   backupWorker.execute {
    val result = runCatching { LibraryBackup.export(ctx.applicationContext, uri) { percent, detail -> uiHandler.post { backupPercent = percent; backupStatus = detail } } }
    uiHandler.post { backupBusy = false; result.onSuccess { backupStatus = "Library archive saved. Check the ZIP in your selected location." }.onFailure { backupStatus = "Backup failed: ${it.message}"; error = backupStatus } }
   }
  }
 }
 val backupImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
  if (uri != null) {
   backupBusy = true
   backupPercent = 0
   backupPhase = "Loading"
   backupStatus = "Reading backup ZIP..."
   backupWorker.execute {
    val result = runCatching { LibraryBackup.restore(ctx.applicationContext, uri) }
    uiHandler.post { backupBusy = false; result.onSuccess { books = scanBooks(shelf); backupStatus = "Restored $it ebooks, available audiobooks and saved progress"; audioFiles = scanAudioFiles(File(ctx.filesDir, "audio")) }.onFailure { backupStatus = "Restore failed: ${it.message}"; error = backupStatus } }
   }
  }
 }
 val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
  if (uri != null) {
   try {
    val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
     val index = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
     if (c.moveToFirst() && index >= 0) c.getString(index) else null
    } ?: "audiobook.m4b"
    val safe = name.replace(Regex("[^a-zA-Z0-9._ -]"), "_")
    if (!safe.endsWith(".m4b", true) && !safe.endsWith(".mp3", true) && !safe.endsWith(".m4a", true)) throw IllegalArgumentException("Choose an M4B, MP3, or M4A file")
    val folder = File(ctx.filesDir, "audio").apply { mkdirs() }
    val target = File(folder, "${System.currentTimeMillis()}_$safe")
    try { ctx.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: throw IllegalStateException("Could not open audio file") }
    catch (e: Exception) { target.delete(); throw e }
    audioFiles = scanAudioFiles(folder)
   } catch (e: Exception) { error = e.message ?: "Could not import audiobook" }
  }
 }
 val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
  if (uri != null) {
   try {
    val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
     val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
     if (c.moveToFirst() && i >= 0) c.getString(i) else null
    } ?: "book.epub"
    val safe = name.replace(Regex("[^a-zA-Z0-9._ -]"), "_")
    if (!safe.endsWith(".epub", true)) throw IllegalArgumentException("Choose an EPUB file")
    val target = File(shelf, "${System.currentTimeMillis()}_$safe")
    try { ctx.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: throw IllegalStateException("Cannot open file") }
    catch (e: Exception) { target.delete(); throw e }
    // Validate before adding to the shelf.
    parseEpub(target)
    books = scanBooks(shelf)
   } catch (e: Exception) { error = e.message ?: "Could not import EPUB" }
  }
 }
 fun openBook(book: Book) {
  try {
   chapters = parseEpub(book.file, book.sectionPrefix)
   selected = book
   chapterIndex = prefs.getInt(book.progressKey, 0).coerceIn(0, chapters.lastIndex)
   pageIndex = prefs.getInt(book.progressKey + "_page", 0)
   error = null
  } catch (e: Exception) { error = e.message ?: "Could not open book" }
 }
 Surface(color = dark, modifier = Modifier.fillMaxSize()) {
  Column(if (selectedAudio == null) Modifier.fillMaxSize() else Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
   if (selectedAudio != null) {
    val file = selectedAudio!!
    AudioBookPlayer(file, prefs.getInt("audio_" + file.name, 0), onProgress = { prefs.edit().putInt("audio_" + file.name, it).apply() }, onClose = { selectedAudio = null })
   } else if (selected == null && showHome) {
    HomeSplash(onLibrary = { showHome = false; tab = 0 }, onAddBooks = { showHome = false; tab = 1 })
    BackHandler(enabled = false) {}
   } else if (selected == null) {
    BackHandler { showHome = true }
    Box(Modifier.weight(1f).fillMaxWidth()) {
     when (tab) {
      0 -> LibraryScreen(
       books = books, audio = audioFiles,
       onOpenBook = { openBook(it) }, onDeleteBook = { deleteCandidate = it },
       onOpenAudio = { selectedAudio = it }, onDeleteAudio = { deleteAudioCandidate = it }
      )
      1 -> Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
       Text("Downloads", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
       Text("Add books and audiobooks you own.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
       Spacer(Modifier.height(16.dp))
       Button(onClick = { picker.launch(arrayOf("application/epub+zip", "application/octet-stream", "*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("＋ Import EPUB") }
       Spacer(Modifier.height(8.dp))
       OutlinedButton(onClick = { audioPicker.launch(arrayOf("audio/*", "application/octet-stream", "*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("＋ Import audiobook") }
       Spacer(Modifier.height(8.dp))
       OutlinedButton(onClick = { showMagnet = !showMagnet }, modifier = Modifier.fillMaxWidth()) { Text("Download from magnet") }
       if (showMagnet) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
         FilterChip(selected = magnetMode == "epub", onClick = { magnetMode = "epub" }, label = { Text("EPUB") })
         FilterChip(selected = magnetMode == "audio", onClick = { magnetMode = "audio" }, label = { Text("Audiobook") })
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = magnet, onValueChange = { magnet = it }, label = { Text("Paste a legal book magnet URI") }, modifier = Modifier.fillMaxWidth().height(104.dp), maxLines = 3, singleLine = false)
        Text("Long magnet links scroll inside the box. Download only files you are authorized to obtain.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
         Button(enabled = !torrentBusy, onClick = { downloadMagnet() }) { Text(if (torrentBusy) "Downloading..." else if (magnetMode == "audio") "Find audiobooks" else "Download EPUB") }
         if (torrentBusy) OutlinedButton(onClick = { audioSelection?.complete(-1); torrentEngine?.cancel(); torrentStatus = "Cancelling..." }) { Text("Cancel") }
        }
        if (torrentStatus.isNotBlank()) Text(torrentStatus, color = Color.LightGray)
       }
      }
      else -> Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
       Text("Settings", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
       Spacer(Modifier.height(16.dp))
       Text("SHARED LIBRARY  •  GOOGLE DRIVE", color = violet, fontWeight = FontWeight.Bold)
       Spacer(Modifier.height(8.dp))
       Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !backupBusy, onClick = { backupExport.launch("GhostReader-Library.zip") }) { Text("Save library") }
        OutlinedButton(enabled = !backupBusy, onClick = { backupImport.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) { Text("Load library") }
       }
       Text("Choose Google Drive in the Android file picker to save or load a portable ZIP of ebooks, audiobooks and progress. Manual transfer for now; automatic sync is not enabled.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
       if (backupBusy) {
         Spacer(Modifier.height(12.dp))
         Text(backupPhase + if (backupPhase == "Saving") " • $backupPercent%" else " • Working...", color = violet, fontWeight = FontWeight.Bold)
         if (backupPhase == "Saving") {
          LinearProgressIndicator(progress = { backupPercent / 100f }, modifier = Modifier.fillMaxWidth())
         } else {
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
         }
        }
        if (backupStatus.isNotBlank()) Text(backupStatus, color = violet, style = MaterialTheme.typography.bodySmall)
      }
     }
    }
    ShellNav(selected = tab, onSelect = { tab = it })
   } else {
    val book = selected!!
    ReaderScreen(title = book.title, author = runCatching { readVolumeMetadata(book).author }.getOrDefault(""), chapters = chapters, prefs = prefs, bookKey = book.progressKey, onClose = { selected = null })
   }
  }
 }
 if (audioSelection != null && audioChoices.isNotEmpty()) {
  AlertDialog(onDismissRequest = { audioSelection?.complete(-1) }, title = { Text("Choose audiobook") }, text = {
   Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
    Text("Choose one track or download the complete audiobook. Full downloads need temporary storage.")
    TextButton(onClick = { audioSelection?.complete(-2) }, modifier = Modifier.fillMaxWidth()) { Text("↓ Download all ${audioChoices.size} tracks • ${audioChoices.sumOf { it.bytes } / 1_000_000} MB", color = violet) }
    audioChoices.forEach { option ->
     TextButton(onClick = { audioSelection?.complete(option.index) }, modifier = Modifier.fillMaxWidth()) {
      Text("${option.name.substringAfterLast('/')}  •  ${option.bytes / 1_000_000} MB", maxLines = 2)
     }
    }
   }
  }, confirmButton = { TextButton(onClick = { audioSelection?.complete(-1) }) { Text("Cancel") } })
 }
 if (deleteAudioCandidate != null) {
  val file = deleteAudioCandidate!!
  AlertDialog(
   onDismissRequest = { deleteAudioCandidate = null },
   title = { Text("Delete audiobook?") },
   text = { Text("Remove ${file.name.substringAfter('_').substringBeforeLast('.').replace('_', ' ')} from this device? This also clears its saved listening position. Google Drive backups are not affected.") },
   confirmButton = {
    TextButton(onClick = {
     if (file.delete()) {
      prefs.edit().remove("audio_" + file.name).apply()
      audioFiles = scanAudioFiles(File(ctx.filesDir, "audio"))
     } else {
      error = "Could not delete audiobook"
     }
     deleteAudioCandidate = null
    }) { Text("Delete", color = Color(0xFFFF8080)) }
   },
   dismissButton = { TextButton(onClick = { deleteAudioCandidate = null }) { Text("Cancel") } }
  )
 }
 if (deleteCandidate != null) {
  val candidate = deleteCandidate!!
  AlertDialog(
   onDismissRequest = { deleteCandidate = null },
   title = { Text("Delete book?") },
   text = { Text(if (candidate.sectionPrefix.isNotBlank()) "This title is part of a collection. Deleting it removes the entire EPUB and all ${books.count { it.file == candidate.file }} books in that collection from this device. Your external backups are not affected." else "Remove ${candidate.title} from this device? This also clears its saved reading position. Your external backup files are not affected.") },
   confirmButton = {
    TextButton(onClick = {
     val file = candidate.file
     if (file.delete()) {
      prefs.edit().apply {
       books.filter { it.file == file }.forEach { remove(it.progressKey); remove(it.progressKey + "_page") }
       remove(file.name); remove(file.name + "_page")
      }.apply()
      books = scanBooks(shelf)
      if (selected?.file == file) selected = null
     } else error = "Couldn't delete this book"
     deleteCandidate = null
    }) { Text("Delete", color = Color(0xFFFF7777)) }
   },
   dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") } }
  )
 }
 if (error != null) AlertDialog(onDismissRequest = { error = null }, title = { Text("GhostReader") }, text = { Text(error!!) }, confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } })
}

internal data class ShelfAudioMetadata(val title: String, val author: String, val cover: android.graphics.Bitmap?, val durationMs: Long)

internal fun formatShelfDuration(ms: Long): String {
 val minutes = ms.coerceAtLeast(0) / 60000
 return "${minutes / 60} hr ${minutes % 60} min"
}

internal fun readShelfAudioMetadata(file: File): ShelfAudioMetadata {
 val fallback = file.nameWithoutExtension
  .replace(Regex("(?i)^gr_\\d+_\\d+_[^_]+_"), "")
  .replace(Regex("(?i)^gr_\\d+_"), "")
  .replace(Regex("^\\d+_"), "")
  .replace('_', ' ')
  .replace(Regex("\\s+"), " ")
  .trim()
  .ifBlank { "Unknown audiobook" }
 val retriever = android.media.MediaMetadataRetriever()
 return try {
  retriever.setDataSource(file.absolutePath)
  fun field(key: Int) = retriever.extractMetadata(key)?.trim().orEmpty()
  val title = field(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE).ifBlank { fallback }
  val author = field(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
   .ifBlank { field(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) }
   .ifBlank { field(android.media.MediaMetadataRetriever.METADATA_KEY_AUTHOR) }
  val duration = field(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0L
  val cover = retriever.embeddedPicture?.takeIf { it.size in 1..5_000_000 }?.let { bytes ->
   val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
   android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
   val sample = (maxOf(bounds.outWidth, bounds.outHeight) / 800).coerceAtLeast(1)
   android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
  }
  ShelfAudioMetadata(title, author, cover, duration)
 } catch (_: Exception) {
  ShelfAudioMetadata(fallback, "", null, 0)
 } finally { runCatching { retriever.release() } }
}

internal data class BookMetadata(val title: String, val author: String, val cover: android.graphics.Bitmap?)

internal fun readBookMetadata(file: File): BookMetadata = runCatching {
 ZipFile(file).use { zip ->
  fun doc(path: String): org.w3c.dom.Document {
   val entry = zip.getEntry(path) ?: error("Missing metadata")
   val factory = DocumentBuilderFactory.newInstance().apply {
    isNamespaceAware = true
    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
    runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
   }
   val builder = factory.newDocumentBuilder()
   builder.setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
   return zip.getInputStream(entry).use { builder.parse(it) }
  }
  val container = doc("META-INF/container.xml")
  val opfPath = (container.getElementsByTagNameNS("*", "rootfile").item(0) as Element).getAttribute("full-path")
  val opf = doc(opfPath)
  fun meta(name: String) = opf.getElementsByTagNameNS("*", name).item(0)?.textContent?.trim().orEmpty()
  val title = meta("title").ifBlank { file.name.substringAfter('_').removeSuffix(".epub") }
  val author = meta("creator").ifBlank { "Unknown author" }
  val items = opf.getElementsByTagNameNS("*", "item")
  val coverId = (0 until opf.getElementsByTagNameNS("*", "meta").length).map { opf.getElementsByTagNameNS("*", "meta").item(it) as Element }
   .firstOrNull { it.getAttribute("name") == "cover" }?.getAttribute("content")
  val image = (0 until items.length).map { items.item(it) as Element }.firstOrNull {
   it.getAttribute("id") == coverId || it.getAttribute("properties").split(' ').contains("cover-image")
  }
  val cover = image?.let {
   val base = opfPath.substringBeforeLast('/', "")
   val path = (if (base.isBlank()) it.getAttribute("href") else "$base/${it.getAttribute("href")}")
   val normalized = java.net.URI(null, null, "/$path", null).normalize().path.removePrefix("/")
   zip.getEntry(normalized)?.takeIf { entry -> entry.size in 1..5_000_000L }?.let { entry ->
    zip.getInputStream(entry).use { stream -> android.graphics.BitmapFactory.decodeStream(stream) }
   }
  }
  BookMetadata(title, author, cover)
 }
}.getOrElse { BookMetadata(file.name.substringAfter('_').removeSuffix(".epub").replace('_', ' '), "Unknown author", null) }

/** Resolve a volume's own embedded cover without duplicating the collection EPUB. */
internal fun readVolumeMetadata(book: Book): BookMetadata {
 val base = readBookMetadata(book.file)
 if (book.sectionPrefix.isBlank()) return base
 if (book.sectionPrefix.startsWith("#s")) return BookMetadata(book.title, base.author, volumeCover(book.file, book.sectionPrefix))
 val cover = runCatching {
  ZipFile(book.file).use { zip ->
   val candidates = zip.entries().asSequence().filter { entry ->
    !entry.isDirectory && entry.name.startsWith(book.sectionPrefix + "/") &&
     entry.name.substringAfterLast('/').matches(Regex("(?i)cover(?:[0-9_\\-]*)?\\.(?:jpe?g|png|webp)")) &&
     entry.size in 1..5_000_000L
   }.sortedBy { it.name.length }.toList()
   candidates.firstNotNullOfOrNull { entry ->
    runCatching { zip.getInputStream(entry).use { stream ->
     val bytes = stream.readBytes()
     val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
     android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
     val scale = (maxOf(bounds.outWidth, bounds.outHeight) / 900).coerceAtLeast(1)
     android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
      android.graphics.BitmapFactory.Options().apply { inSampleSize = scale })
    } }.getOrNull()
   }
  }
 }.getOrNull()
 return BookMetadata(book.title, base.author, cover ?: base.cover)
}

private fun scanBooks(dir: File): List<Book> = dir.listFiles()
 ?.filter { it.isFile && it.name.endsWith(".epub", true) }
 ?.sortedByDescending { it.lastModified() }
 ?.flatMap { file ->
  val parts = runCatching {
   ZipFile(file).use { zip ->
    val entry = zip.getEntry("toc.ncx") ?: return@use emptyList<Book>()
    val factory = DocumentBuilderFactory.newInstance().apply {
     isNamespaceAware = true
     runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
     runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }
    val doc = zip.getInputStream(entry).use { factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) } }.parse(it) }
    val navs = doc.getElementsByTagNameNS("*", "navPoint")
    val found = linkedMapOf<String, String>()
    for (i in 0 until navs.length) {
     val nav = navs.item(i) as? Element ?: continue
     val parent = nav.parentNode as? Element ?: continue
     if (parent.localName != "navPoint") continue
     val content = nav.getElementsByTagNameNS("*", "content").item(0) as? Element ?: continue
     val src = content.getAttribute("src").substringBefore('#')
     val prefix = src.split('/').take(2).joinToString("/")
     if (!Regex("^\\d+/\\d+$").matches(prefix)) continue
     val label = nav.getElementsByTagNameNS("*", "text").item(0)?.textContent?.trim().orEmpty()
     if (label.isNotBlank() && !found.containsKey(prefix)) found[prefix] = label
    }
    if (found.size in 2..40) found.map { (prefix, title) -> Book(file, title.replace(Regex("(?i)\\s+anthology$"), "").trim(), prefix) } else emptyList()
   }
  }.getOrDefault(emptyList())
  parts.ifEmpty { detectVolumes(file).map { (title, range) -> Book(file, title, range) } }
   .ifEmpty { listOf(Book(file, readBookMetadata(file).title)) }
 } ?: emptyList()

private fun parseEpub(file: File, sectionPrefix: String = ""): List<Chapter> = ZipFile(file).use { zip ->
 fun xml(path: String) = zip.getEntry(path)?.let { entry ->
  zip.getInputStream(entry).use { stream -> DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }; runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }; runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) } }.newDocumentBuilder().apply { setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) } }.parse(stream) }
 } ?: error("Missing EPUB file: $path")
 val container = xml("META-INF/container.xml")
 val rootFiles = container.getElementsByTagNameNS("*", "rootfile")
 if (rootFiles.length == 0) error("Invalid EPUB: no package document")
 val opfPath = (rootFiles.item(0) as Element).getAttribute("full-path")
 val opf = xml(opfPath)
 val base = opfPath.substringBeforeLast('/', "")
 val manifest = mutableMapOf<String, String>()
 val items = opf.getElementsByTagNameNS("*", "item")
 for (i in 0 until items.length) {
  val e = items.item(i) as Element
  manifest[e.getAttribute("id")] = e.getAttribute("href")
 }
 val spine = opf.getElementsByTagNameNS("*", "itemref")
 val result = mutableListOf<Chapter>()
 for (i in 0 until spine.length) {
  val id = (spine.item(i) as Element).getAttribute("idref")
  val href = manifest[id] ?: continue
  val path = (if (base.isEmpty()) href else "$base/$href").substringBefore('#')
  val normalized = java.net.URI(null, null, "/$path", null).normalize().path.removePrefix("/")
  if (sectionPrefix.startsWith("#s")) {
   val (from, to) = volumeRange(sectionPrefix)
   if (i < from || i > to) continue
  } else if (sectionPrefix.isNotBlank() && !normalized.startsWith("$sectionPrefix/")) continue
  val entry = zip.getEntry(normalized) ?: zip.getEntry(resolvePath(base, href)) ?: continue
  val html = zip.getInputStream(entry).bufferedReader().use { it.readText() }
  val body = Regex("<body\\b[^>]*>([\\s\\S]*?)</body>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1) ?: html
  val clean = body.replace(Regex("<(script|style)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), "")
  val cleaned = cleanSpanned(Html.fromHtml(clean, Html.FROM_HTML_MODE_LEGACY))
  // Use the section's own heading as its title when it has one, instead of "Section N".
  val heading = Regex("<h[1-3]\\b[^>]*>([\\s\\S]*?)</h[1-3]>", RegexOption.IGNORE_CASE).find(clean)
   ?.groupValues?.get(1)?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().replace("\uFFFC", "").replace(Regex("\\s+"), " ").trim() }
   ?.takeIf { it.isNotBlank() && it.length <= 60 }
  if (cleaned.text.isNotBlank()) result.add(Chapter(heading ?: "Section ${result.size + 1}", cleaned.text, cleaned.spans))
 }
 if (result.isEmpty()) error("No readable chapters found in this EPUB")
 result
}

private fun scanAudioFiles(dir: File): List<File> = dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("m4b", "mp3", "m4a") }?.sortedByDescending { it.lastModified() } ?: emptyList()
