package com.korndog.ghostreader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val plum = Color(0xFFB784FF)
private val lime = Color(0xFFB9F54C)
private val wood = Color(0xFF302018)

private data class RoomItem(
 val title: String, val kind: String, val series: String,
 val open: () -> Unit,
 val book: Book? = null, val file: File? = null
)

@Composable
internal fun ReadingRoom(
 books: List<Book>, audio: List<File>, pdfs: List<File>, comics: List<File>,
 imageBooks: List<ImageFolderBook>,
 onClassic: () -> Unit, onBook: (Book) -> Unit, onAudio: (File) -> Unit,
 onPdf: (File) -> Unit, onComic: (File) -> Unit, onImage: (ImageFolderBook) -> Unit
) {
 var selectedSeries by remember { mutableStateOf<String?>(null) }
 var selectedKind by remember { mutableStateOf("All") }
 var roomMode by remember { mutableStateOf("Shelves") }
 var staged by remember { mutableStateOf<RoomItem?>(null) }
 BackHandler { if (staged != null) staged = null else if (selectedSeries != null) selectedSeries = null else onClassic() }
 val items = remember(books, audio, pdfs, comics, imageBooks) {
  buildList {
   books.forEach { book -> add(RoomItem(book.title, "Read", seriesOf(book.title), { onBook(book) }, book = book)) }
   audio.forEach { file ->
    val title = cleanRoomTitle(file.nameWithoutExtension)
    val carl = listOf("Dungeon Crawler Carl", "Carl's Doomsday Scenario", "The Dungeon Anarchist's Cookbook", "The Gate of the Feral Gods", "The Butcher's Masquerade", "The Eye of the Bedlam Bride", "This Inevitable Ruin", "A Parade of Horribles").any { title.contains(it, ignoreCase = true) }
    add(RoomItem(title, "Audio", if(carl) "Dungeon Crawler Carl" else seriesOf(title), { onAudio(file) }, file = file))
   }
   pdfs.forEach { file -> add(RoomItem(cleanRoomTitle(file.nameWithoutExtension), "PDF", seriesOf(cleanRoomTitle(file.nameWithoutExtension)), { onPdf(file) }, file = file)) }
   comics.forEach { file -> add(RoomItem(cleanRoomTitle(file.nameWithoutExtension), "Comics", seriesOf(cleanRoomTitle(file.nameWithoutExtension)), { onComic(file) }, file = file)) }
   imageBooks.forEach { book ->
    val series = if (book.title.matches(Regex("^\\d+\\s*[-.]\\s*.*"))) "Image Book Collection" else seriesOf(book.title)
    add(RoomItem(book.title, "Image", series) { onImage(book) })
   }
  }
 }
 val visible = items.filter { (selectedKind == "All" || it.kind == selectedKind) && (roomMode != "Jukebox" || it.kind == "Audio") && (roomMode != "Desk" || it.kind != "Audio") }
 val grouped = visible.groupBy { it.series.ifBlank { it.title } }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
 Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF110C1B),Color(0xFF281821),Color(0xFF0B0911)))).statusBarsPadding()) {
  Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
   Column(Modifier.weight(1f)) {
    Text("GhostReader", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
    Text(when(roomMode) { "Desk" -> "THE READING DESK"; "Jukebox" -> "THE AUDIO JUKEBOX"; else -> "THE READING ROOM" }, color = plum, fontSize = 11.sp, letterSpacing = 2.sp)
   }
   TextButton(onClick = onClassic) { Text("Classic Library", color = Color.White) }
  }
  if (staged != null) {
   val chosen = staged!!
   var landed by remember(chosen.title) { mutableStateOf(false) }
   LaunchedEffect(chosen.title) { landed = true }
   val scale by animateFloatAsState(if (landed) 1f else 0.52f, animationSpec = tween(520), label = "bookToDesk")
   val rise by animateDpAsState(if (landed) 0.dp else (-90).dp, animationSpec = tween(520), label = "shelfToDesk")
   Column(Modifier.weight(1f).fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
    Text(if(chosen.kind=="Audio") "♫  THE JUKEBOX" else "▤  THE READING DESK", color=plum, fontSize=18.sp, fontWeight=FontWeight.Bold)
    Spacer(Modifier.height(28.dp))
    Box(Modifier.fillMaxWidth().height(260.dp).background(Brush.verticalGradient(listOf(Color(0xFF4B3024),Color(0xFF1B1219))),RoundedCornerShape(20.dp)), contentAlignment=Alignment.Center) {
     Box(modifier=Modifier.offset(y=rise).graphicsLayer(scaleX=scale,scaleY=scale).width(180.dp).height(240.dp)) {
      RoomCover(chosen, Modifier.fillMaxSize())
     }
    }
    Spacer(Modifier.height(22.dp))
    Button(onClick={ chosen.open() }, colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF6D3FD0))) { Text(if(chosen.kind=="Audio") "▶ Play / Resume audiobook" else "Open full-screen / Resume reading") }
    TextButton(onClick={staged=null}) { Text("‹ Return to shelves",color=plum) }
   }
  } else if (selectedSeries != null) {
   TextButton(onClick = { selectedSeries = null }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("‹ Back to shelves", color = plum) }
  } else {
   Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
    listOf("All","Read","Audio","Comics","Image").forEach { kind ->
     FilterChip(selected = selectedKind == kind, onClick = { selectedKind = kind }, label = { Text(kind, fontSize = 11.sp) })
    }
   }
  }
  if (staged == null) LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
   if (selectedSeries == null) {
    item {
     Text(if(roomMode=="Jukebox") "CHOOSE AN AUDIOBOOK" else if(roomMode=="Desk") "CHOOSE A BOOK" else "YOUR SHELVES", color = Color(0xFFE9C99E), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
     Spacer(Modifier.height(8.dp))
    }
    items(grouped.entries.toList().chunked(3)) { rowEntries ->
     Column(Modifier.fillMaxWidth()) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
       rowEntries.forEach { entry ->
        Column(Modifier.weight(1f).clickable {
         if (entry.value.size == 1) staged = entry.value.first() else selectedSeries = entry.key
        }, horizontalAlignment = Alignment.CenterHorizontally) {
         RoomCover(entry.value.first(), Modifier.fillMaxWidth().height(142.dp).shadow(5.dp, RoundedCornerShape(5.dp)))
         Spacer(Modifier.height(7.dp))
         Text(if(entry.value.size>1) "${entry.value.size} volumes" else "Tap to open",color=plum,fontSize=10.sp,maxLines=1)
        }
       }
       repeat(3-rowEntries.size) { Spacer(Modifier.weight(1f)) }
      }
      Spacer(Modifier.height(4.dp))
      Box(Modifier.fillMaxWidth().height(15.dp).background(Brush.verticalGradient(listOf(Color(0xFF80583A),Color(0xFF29170F))),RoundedCornerShape(3.dp)))
     }
    }
   } else {
    val chosen = grouped[selectedSeries].orEmpty()
    item { Text(selectedSeries.orEmpty(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp) }
    items(chosen.chunked(3)) { rowEntries ->
     Column {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
       rowEntries.forEach { entry ->
        Box(Modifier.weight(1f).height(154.dp).clickable { staged=entry }) {
         RoomCover(entry, Modifier.fillMaxSize())
        }
       }
       repeat(3-rowEntries.size) { Spacer(Modifier.weight(1f)) }
      }
      Spacer(Modifier.height(5.dp))
      Box(Modifier.fillMaxWidth().height(12.dp).background(Color(0xFF70462F)))
     }
    }
   }
  }
  Row(Modifier.fillMaxWidth().background(Color(0xFF1B1222)).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
   OutlinedButton(onClick = { roomMode=if(roomMode=="Desk") "Shelves" else "Desk"; selectedKind="All"; selectedSeries=null; staged=null }, modifier=Modifier.weight(1f)) { Text("▤ Reading Desk", fontSize=12.sp) }
   OutlinedButton(onClick = { roomMode=if(roomMode=="Jukebox") "Shelves" else "Jukebox"; selectedKind="All"; selectedSeries=null; staged=null }, modifier=Modifier.weight(1f)) { Text("♫ Jukebox", fontSize=12.sp) }
  }
 }
}


@Composable
private fun RoomCover(item: RoomItem, modifier: Modifier = Modifier) {
 val context = LocalContext.current
 val cover by produceState<Bitmap?>(null, item.book, item.file) {
  value = withContext(Dispatchers.IO) {
   runCatching { roomCoverBitmap(context, item.book, item.file, item.kind, item.title) }.getOrNull()
  }
 }
 if (cover != null) {
  Image(bitmap = cover!!.asImageBitmap(), contentDescription = item.title,
   modifier = modifier.clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop)
 } else {
  Box(modifier.background(Brush.verticalGradient(listOf(Color(0xFF594030), Color(0xFF241727))), RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
   Text(item.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 4, overflow = TextOverflow.Ellipsis)
  }
 }
}

private fun cleanRoomTitle(raw: String): String = raw.replace(Regex("^\\d{10,}[_\\s-]*"), "").replace(Regex("(?i)\\b(?:oceanofpdf\\.com|digital)\\b"), "").replace('_', ' ').replace(Regex("\\s+"), " ").trim(' ', '-', '.').ifBlank { raw }
