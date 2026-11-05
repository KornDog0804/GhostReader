package com.korndog.ghostreader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

private val plum = Color(0xFFB784FF)
private val lime = Color(0xFFB9F54C)
private val wood = Color(0xFF302018)

private data class RoomItem(
 val title: String, val kind: String, val series: String,
 val open: () -> Unit
)

@Composable
internal fun ReadingRoom(
 books: List<Book>, audio: List<File>, pdfs: List<File>, comics: List<File>,
 imageBooks: List<ImageFolderBook>,
 onClassic: () -> Unit, onBook: (Book) -> Unit, onAudio: (File) -> Unit,
 onPdf: (File) -> Unit, onComic: (File) -> Unit, onImage: (ImageFolderBook) -> Unit
) {
 BackHandler { onClassic() }
 var selectedSeries by remember { mutableStateOf<String?>(null) }
 var selectedKind by remember { mutableStateOf("All") }
 val items = remember(books, audio, pdfs, comics, imageBooks) {
  buildList {
   books.forEach { book -> add(RoomItem(book.title, "Read", seriesOf(book.title)) { onBook(book) }) }
   audio.forEach { file ->
    val title = file.nameWithoutExtension.replace(Regex("^\\d+_"), "").replace('_',' ')
    val carl = listOf("Dungeon Crawler Carl", "Carl's Doomsday Scenario", "The Dungeon Anarchist's Cookbook", "The Gate of the Feral Gods", "The Butcher's Masquerade", "The Eye of the Bedlam Bride", "This Inevitable Ruin", "A Parade of Horribles").any { title.contains(it, ignoreCase = true) }
    add(RoomItem(title, "Audio", if(carl) "Dungeon Crawler Carl" else seriesOf(title)) { onAudio(file) })
   }
   pdfs.forEach { file -> add(RoomItem(file.nameWithoutExtension, "PDF", seriesOf(file.nameWithoutExtension)) { onPdf(file) }) }
   comics.forEach { file -> add(RoomItem(file.nameWithoutExtension, "Comics", seriesOf(file.nameWithoutExtension)) { onComic(file) }) }
   imageBooks.forEach { book ->
    val series = if (book.title.matches(Regex("^\\d+\\s*[-.]\\s*.*"))) "Image Book Collection" else seriesOf(book.title)
    add(RoomItem(book.title, "Image", series) { onImage(book) })
   }
  }
 }
 val visible = items.filter { selectedKind == "All" || it.kind == selectedKind }
 val grouped = visible.groupBy { it.series.ifBlank { it.title } }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
 Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF110C1B),Color(0xFF281821),Color(0xFF0B0911)))).statusBarsPadding()) {
  Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
   Column(Modifier.weight(1f)) {
    Text("GhostReader", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
    Text("THE READING ROOM", color = plum, fontSize = 11.sp, letterSpacing = 2.sp)
   }
   TextButton(onClick = onClassic) { Text("Classic Library", color = Color.White) }
  }
  if (selectedSeries != null) {
   TextButton(onClick = { selectedSeries = null }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("‹ Back to shelves", color = plum) }
  } else {
   Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
    listOf("All","Read","Audio","Comics","Image").forEach { kind ->
     FilterChip(selected = selectedKind == kind, onClick = { selectedKind = kind }, label = { Text(kind, fontSize = 11.sp) })
    }
   }
  }
  LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
   if (selectedSeries == null) {
    item {
     Text("YOUR COLLECTIONS", color = Color(0xFFE9C99E), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
     Spacer(Modifier.height(8.dp))
    }
    items(grouped.entries.toList()) { entry ->
     Row(
      Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(wood,Color(0xFF211827))), RoundedCornerShape(12.dp))
       .clickable { selectedSeries = entry.key }.padding(16.dp),
      verticalAlignment = Alignment.CenterVertically
     ) {
      Box(Modifier.width(55.dp).height(78.dp).background(if(entry.value.first().kind=="Audio") Color(0xFF482B67) else Color(0xFF5A382A), RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
       Text(if(entry.value.first().kind=="Audio") "♫" else "▤", color = lime, fontSize = 27.sp)
      }
      Spacer(Modifier.width(14.dp))
      Column(Modifier.weight(1f)) {
       Text(entry.key, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
       Text("${entry.value.size} ${if(entry.value.size==1) "title" else "titles"} • ${entry.value.map { it.kind }.distinct().joinToString()}", color = Color.LightGray, fontSize = 12.sp)
      }
      Text("›", color = plum, fontSize = 30.sp)
     }
    }
   } else {
    val chosen = grouped[selectedSeries].orEmpty()
    item { Text(selectedSeries.orEmpty(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp) }
    items(chosen) { entry ->
     Row(Modifier.fillMaxWidth().background(Color(0xFF211827), RoundedCornerShape(12.dp)).clickable { entry.open() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(if(entry.kind=="Audio") "♫" else "▤", color = lime, fontSize = 25.sp)
      Spacer(Modifier.width(16.dp))
      Column(Modifier.weight(1f)) {
       Text(entry.title, color = Color.White, fontWeight = FontWeight.SemiBold)
       Text(if(entry.kind=="Audio") "Play / Resume" else "Read / Resume", color = plum, fontSize = 12.sp)
      }
      Text("›", color = Color.White, fontSize = 24.sp)
     }
    }
   }
  }
  Row(Modifier.fillMaxWidth().background(Color(0xFF1B1222)).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
   OutlinedButton(onClick = { selectedKind="Read"; selectedSeries=null }, modifier=Modifier.weight(1f)) { Text("▤ Reading Desk", fontSize=12.sp) }
   OutlinedButton(onClick = { selectedKind="Audio"; selectedSeries=null }, modifier=Modifier.weight(1f)) { Text("♫ Jukebox", fontSize=12.sp) }
  }
 }
}
