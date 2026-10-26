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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

private val violet = Color(0xFFB784FF)
private val dark = Color(0xFF0B0911)
private data class Book(val file: File, val title: String)
private data class Chapter(val title: String, val text: String)

class MainActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  setContent { MaterialTheme(colorScheme = darkColorScheme(primary = violet, background = dark, surface = Color(0xFF191321))) { GhostApp() } }
 }
}

@Composable
private fun GhostApp() {
 val ctx = LocalContext.current
 val shelf = remember { File(ctx.filesDir, "books").apply { mkdirs() } }
 val prefs = remember { ctx.getSharedPreferences("reading", 0) }
 var books by remember { mutableStateOf(scanBooks(shelf)) }
 var selected by remember { mutableStateOf<Book?>(null) }
 var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
 var chapterIndex by remember { mutableIntStateOf(0) }
 var error by remember { mutableStateOf<String?>(null) }
 var magnet by remember { mutableStateOf("") }
 var showMagnet by remember { mutableStateOf(false) }
 var torrentBusy by remember { mutableStateOf(false) }
 var torrentStatus by remember { mutableStateOf("") }
 val torrentWorker = remember { java.util.concurrent.Executors.newSingleThreadExecutor() }
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
    downloaded = engine.download(magnet.trim(), shelf) { status ->
     uiHandler.post { torrentStatus = status }
    }
    parseEpub(downloaded)
    uiHandler.post {
     books = scanBooks(shelf)
     torrentStatus = "EPUB downloaded and added to your shelf"
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
 var downloading by remember { mutableStateOf(false) }
 var downloadMessage by remember { mutableStateOf("") }
 val worker = remember { Executors.newSingleThreadExecutor() }
 val ui = remember { Handler(Looper.getMainLooper()) }
 fun downloadBook(id: Int, title: String) {
  if (downloading) return
  downloading = true
  downloadMessage = "Downloading $title..."
  worker.execute {
   var file: File? = null
   try {
    val url = URL("https://www.gutenberg.org/ebooks/$id.epub3.images")
    val connection = url.openConnection() as HttpURLConnection
    connection.connectTimeout = 15000
    connection.readTimeout = 30000
    connection.instanceFollowRedirects = true
    try {
     if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
     if (connection.url.protocol != "https") throw IllegalStateException("Insecure redirect")
     if (connection.contentLengthLong > 30_000_000L) throw IllegalStateException("Book too large")
     file = File(shelf, "${System.currentTimeMillis()}_${title.replace(Regex("[^a-zA-Z0-9]"), "_")}.epub")
     connection.inputStream.use { input ->
      file!!.outputStream().use { output ->
       val buf = ByteArray(8192)
       var total = 0L
       while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > 30_000_000L) throw IllegalStateException("Book too large")
        output.write(buf, 0, n)
       }
      }
     }
     parseEpub(file!!)
     ui.post { books = scanBooks(shelf); downloadMessage = "$title added to your shelf"; downloading = false }
    } finally { connection.disconnect() }
   } catch (e: Exception) {
    file?.delete()
    ui.post { downloading = false; downloadMessage = ""; error = e.message ?: "Download failed" }
   }
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
   chapters = parseEpub(book.file)
   selected = book
   chapterIndex = prefs.getInt(book.file.name, 0).coerceIn(0, chapters.lastIndex)
   error = null
  } catch (e: Exception) { error = e.message ?: "Could not open book" }
 }
 Surface(color = dark, modifier = Modifier.fillMaxSize()) {
  Column(Modifier.fillMaxSize().padding(20.dp)) {
   if (selected == null) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    Spacer(Modifier.height(18.dp))
    Text("GHOSTREADER", style = MaterialTheme.typography.headlineLarge, color = violet, fontWeight = FontWeight.Black)
    Text("Your library. Your rules.", color = Color.LightGray)
    Spacer(Modifier.height(24.dp))
    Button(onClick = { picker.launch(arrayOf("application/epub+zip", "application/octet-stream", "*/*")) }) { Text("＋ Import EPUB") }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { showMagnet = !showMagnet }) { Text("Magnet EPUB download (built in)") }
    if (showMagnet) {
     Spacer(Modifier.height(8.dp))
     OutlinedTextField(value = magnet, onValueChange = { magnet = it }, label = { Text("Paste a legal book magnet URI") }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 120.dp), maxLines = 3, singleLine = false)
     Text("Downloads an EPUB from a magnet into your shelf. Use authorized torrents.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
     Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(enabled = !torrentBusy, onClick = { downloadMagnet() }) { Text(if (torrentBusy) "Downloading..." else "Download EPUB") }
      if (torrentBusy) OutlinedButton(onClick = { torrentEngine?.cancel(); torrentStatus = "Cancelling..." }) { Text("Cancel") }
     }
     if (torrentStatus.isNotBlank()) Text(torrentStatus, color = Color.LightGray)
    }
    Spacer(Modifier.height(12.dp))
    Text("FREE PUBLIC-DOMAIN BOOKS • DOWNLOAD INSIDE APP", color = violet, fontWeight = FontWeight.Bold)
    TextButton(enabled = !downloading, onClick = { downloadBook(84, "Frankenstein") }) { Text("↓ Frankenstein • Mary Shelley") }
    TextButton(enabled = !downloading, onClick = { downloadBook(11, "Alice's Adventures in Wonderland") }) { Text("↓ Alice’s Adventures in Wonderland • Lewis Carroll") }
    TextButton(enabled = !downloading, onClick = { downloadBook(1342, "Pride and Prejudice") }) { Text("↓ Pride and Prejudice • Jane Austen") }
    if (downloadMessage.isNotBlank()) Text(downloadMessage, color = Color.LightGray)
    Spacer(Modifier.height(16.dp))
    Text("MY SHELF  •  ${books.size} BOOKS", color = violet, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    if (books.isEmpty()) Text("Your shelf is empty. Import a DRM-free EPUB to start reading.", color = Color.LightGray)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
     books.forEach { book ->
      Card(onClick = { openBook(book) }, colors = CardDefaults.cardColors(containerColor = Color(0xFF21192D))) {
       Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("📖  ${book.title}", color = Color.White, fontWeight = FontWeight.Bold)
        Text("Tap to read", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
       }
      }
     }
    }
    }
   } else {
    val book = selected!!
    Row(verticalAlignment = Alignment.CenterVertically) {
     TextButton(onClick = { selected = null }) { Text("‹ SHELF") }
     Text(book.title, color = violet, maxLines = 1, modifier = Modifier.weight(1f))
    }
    if (chapters.isNotEmpty()) {
     val current = chapters[chapterIndex]
     Text("${chapterIndex + 1} / ${chapters.size}  •  ${current.title}", color = violet, style = MaterialTheme.typography.titleMedium)
     Spacer(Modifier.height(12.dp))
     key(book.file.name, chapterIndex) {
      Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
       Text(current.text, color = Color(0xFFF3EDF9), style = MaterialTheme.typography.bodyLarge, lineHeight = androidx.compose.ui.unit.TextUnit(27f, androidx.compose.ui.unit.TextUnitType.Sp))
      }
     }
     Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      OutlinedButton(enabled = chapterIndex > 0, onClick = { chapterIndex--; prefs.edit().putInt(book.file.name, chapterIndex).apply() }) { Text("← Previous") }
      Button(enabled = chapterIndex < chapters.lastIndex, onClick = { chapterIndex++; prefs.edit().putInt(book.file.name, chapterIndex).apply() }) { Text("Next →") }
     }
    }
   }
  }
 }
 if (error != null) AlertDialog(onDismissRequest = { error = null }, title = { Text("GhostReader") }, text = { Text(error!!) }, confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } })
}

private fun scanBooks(dir: File): List<Book> = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".epub", true) }?.sortedByDescending { it.lastModified() }?.map {
 Book(it, it.name.substringAfter('_').removeSuffix(".epub").replace('_', ' '))
} ?: emptyList()

private fun parseEpub(file: File): List<Chapter> = ZipFile(file).use { zip ->
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
  val entry = zip.getEntry(normalized) ?: continue
  val html = zip.getInputStream(entry).bufferedReader().use { it.readText() }
  val body = Regex("<body\\b[^>]*>([\\s\\S]*?)</body>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1) ?: html
  val clean = body.replace(Regex("<(script|style)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), "")
  val plain = Html.fromHtml(clean, Html.FROM_HTML_MODE_LEGACY).toString().trim()
  if (plain.isNotBlank()) result.add(Chapter("Section ${result.size + 1}", plain))
 }
 if (result.isEmpty()) error("No readable chapters found in this EPUB")
 result
}
