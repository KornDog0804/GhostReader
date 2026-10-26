package com.korndog.ghostreader

import android.content.Intent
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
    Spacer(Modifier.height(18.dp))
    Text("GHOSTREADER", style = MaterialTheme.typography.headlineLarge, color = violet, fontWeight = FontWeight.Black)
    Text("Your library. Your rules.", color = Color.LightGray)
    Spacer(Modifier.height(24.dp))
    Button(onClick = { picker.launch(arrayOf("application/epub+zip", "application/octet-stream", "*/*")) }) { Text("＋ Import EPUB") }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { showMagnet = !showMagnet }) { Text("Magnet links (external torrent app)") }
    if (showMagnet) {
     Spacer(Modifier.height(8.dp))
     OutlinedTextField(value = magnet, onValueChange = { magnet = it }, label = { Text("Paste a legal book magnet URI") }, modifier = Modifier.fillMaxWidth())
     Text("Requires a separate torrent app. Download the EPUB there, then import it here.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
     Button(onClick = {
      if (!magnet.startsWith("magnet:?xt=urn:btih:", true)) { error = "Enter a valid BitTorrent magnet link" }
      else try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(magnet))) }
      catch (e: Exception) { error = "No torrent app installed to open magnet links" }
     }) { Text("Open magnet") }
    }
    Spacer(Modifier.height(16.dp))
    Text("MY SHELF  •  ${books.size} BOOKS", color = violet, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    if (books.isEmpty()) Text("Your shelf is empty. Import a DRM-free EPUB to start reading.", color = Color.LightGray)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
     items(books) { book ->
      Card(onClick = { openBook(book) }, colors = CardDefaults.cardColors(containerColor = Color(0xFF21192D))) {
       Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("📖  ${book.title}", color = Color.White, fontWeight = FontWeight.Bold)
        Text("Tap to read", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
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
  zip.getInputStream(entry).use { stream -> DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); setFeature("http://xml.org/sax/features/external-general-entities", false); setFeature("http://xml.org/sax/features/external-parameter-entities", false) }.newDocumentBuilder().parse(stream) }
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
