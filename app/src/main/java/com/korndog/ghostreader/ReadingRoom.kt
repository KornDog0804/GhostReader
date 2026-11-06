package com.korndog.ghostreader

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import java.io.File
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val plum = Color(0xFFB784FF)
private val lime = Color(0xFFB9F54C)
private val amber = Color(0xFFFFB347)
private val nightBg = Color(0xFF110A1C)
private val shelfBack = Color(0xFF1B0F2A)

private data class RoomItem(
 val title: String, val kind: String, val series: String,
 val open: () -> Unit,
 val book: Book? = null, val file: File? = null
)

/**
 * The bookcase art (room_bookcase_right.png, 310x460) is sliced into shelf bands that are stacked
 * to make a bookcase of any height. srcTop/srcBottom are rows of the PNG; inTop/inBottom are the
 * interior rows where books stand (inBottom is the top face of the plank they rest on).
 * The bands are contiguous (0-132, 132-208, 208-282) so the ivy in the top corner is never cut.
 */
private data class ShelfBand(val srcTop: Int, val srcBottom: Int, val inTop: Int, val inBottom: Int)
private val BAND_TOP = ShelfBand(0, 132, 62, 116)
private val BAND_SECOND = ShelfBand(132, 208, 132, 192)
private val BAND_MID = ShelfBand(208, 282, 208, 266)
private const val BC_W = 310
private const val BC_H = 460
private const val FOOT_TOP = 440
private const val IN_X0 = 42
private const val IN_X1 = 258
private const val V_STRETCH = 1.28f

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
 var stagedFrom by remember { mutableStateOf(Rect.Zero) }
 var rootOrigin by remember { mutableStateOf(Offset.Zero) }
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
    add(RoomItem(book.title, "Image", series, open = { onImage(book) }))
   }
  }
 }
 val visible = items.filter { (selectedKind == "All" || it.kind == selectedKind) && (roomMode != "Jukebox" || it.kind == "Audio") && (roomMode != "Desk" || it.kind != "Audio") }
 val grouped = visible.groupBy { it.series.ifBlank { it.title } }.toSortedMap(String.CASE_INSENSITIVE_ORDER)

 Box(Modifier.fillMaxSize().background(nightBg).onGloballyPositioned { rootOrigin = it.positionInRoot() }) {
  val chosen = staged
  if (chosen == null) {
   ShelvesScreen(
    grouped = grouped, selectedSeries = selectedSeries, selectedKind = selectedKind, roomMode = roomMode,
    onKind = { selectedKind = it },
    onMode = { mode ->
     roomMode = if (roomMode == mode) "Shelves" else mode
     selectedKind = "All"; selectedSeries = null; staged = null
    },
    onSeries = { selectedSeries = it },
    onPick = { item, rect -> stagedFrom = rect; staged = item },
    onClassic = onClassic
   )
  } else if (chosen.kind == "Audio") {
   JukeboxScene(chosen, stagedFrom, rootOrigin, onOpen = { chosen.open() }, onBack = { staged = null })
  } else {
   DeskScene(chosen, stagedFrom, rootOrigin, onOpen = { chosen.open() }, onBack = { staged = null })
  }
 }
}

@Composable
private fun ShelvesScreen(
 grouped: Map<String, List<RoomItem>>, selectedSeries: String?, selectedKind: String, roomMode: String,
 onKind: (String) -> Unit, onMode: (String) -> Unit, onSeries: (String?) -> Unit,
 onPick: (RoomItem, Rect) -> Unit, onClassic: () -> Unit
) {
 val bookcase = ImageBitmap.imageResource(R.drawable.room_bookcase_right)
 Column(Modifier.fillMaxSize()) {
  RoomHero(roomMode, onMode, onClassic)
  Row(
   Modifier.fillMaxWidth().background(Color(0xFF160E24)).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
   horizontalArrangement = Arrangement.spacedBy(6.dp)
  ) {
   listOf("All", "Read", "Audio", "Comics", "Image").forEach { kind ->
    FilterChip(selected = selectedKind == kind, onClick = { onKind(kind) }, label = { Text(kind, fontSize = 12.sp) })
   }
  }
  BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
   val sx = maxWidth / BC_W
   val interiorW = sx * (IN_X1 - IN_X0)
   val perRow = (interiorW.value / 70f).toInt().coerceIn(3, 8)
   val groups: List<List<RoomItem>> =
    if (selectedSeries == null) grouped.values.toList() else grouped[selectedSeries].orEmpty().map { listOf(it) }
   val rows = groups.chunked(perRow)
   val bandCount = maxOf(rows.size, 2)
   LazyColumn(Modifier.fillMaxSize()) {
    if (selectedSeries != null) {
     item {
      Row(Modifier.fillMaxWidth().background(Color(0xFF160E24)).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
       TextButton(onClick = { onSeries(null) }) { Text("‹ Back to shelves", color = plum) }
       Text(selectedSeries, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
     }
    }
    items(bandCount) { i ->
     val band = when (i) { 0 -> BAND_TOP; 1 -> BAND_SECOND; else -> BAND_MID }
     ShelfBandRow(band, bookcase) { iw, ih ->
      val rowGroups = rows.getOrNull(i)
      if (rowGroups != null) {
       ShelfBooks(rowGroups, perRow, iw, ih) { group, rect ->
        if (group.size == 1) onPick(group.first(), rect)
        else onSeries(group.first().series.ifBlank { group.first().title })
       }
      }
     }
    }
    item { ShelfFoot(bookcase) }
   }
   // warm light falling from the lamps above the shelves
   Box(Modifier.fillMaxWidth().height(110.dp).background(Brush.verticalGradient(listOf(amber.copy(alpha = 0.18f), Color.Transparent))))
  }
 }
}

@Composable
private fun ShelfBandRow(band: ShelfBand, bookcase: ImageBitmap, content: @Composable (Dp, Dp) -> Unit) {
 BoxWithConstraints(Modifier.fillMaxWidth()) {
  val sx = maxWidth / BC_W
  val sy = sx * V_STRETCH
  val rowH = sy * (band.srcBottom - band.srcTop)
  Box(Modifier.fillMaxWidth().height(rowH).background(shelfBack)) {
   Canvas(Modifier.matchParentSize()) {
    drawImage(
     bookcase,
     srcOffset = IntOffset(0, band.srcTop),
     srcSize = IntSize(BC_W, band.srcBottom - band.srcTop),
     dstOffset = IntOffset.Zero,
     dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
     filterQuality = FilterQuality.None
    )
   }
   val iw = sx * (IN_X1 - IN_X0)
   val ih = sy * (band.inBottom - band.inTop)
   Box(Modifier.padding(start = sx * IN_X0, top = sy * (band.inTop - band.srcTop)).width(iw).height(ih)) {
    content(iw, ih)
   }
  }
 }
}

@Composable
private fun ShelfFoot(bookcase: ImageBitmap) {
 BoxWithConstraints(Modifier.fillMaxWidth()) {
  val sx = maxWidth / BC_W
  val sy = sx * V_STRETCH
  Canvas(Modifier.fillMaxWidth().height(sy * (BC_H - FOOT_TOP)).background(shelfBack)) {
   drawImage(
    bookcase,
    srcOffset = IntOffset(0, FOOT_TOP),
    srcSize = IntSize(BC_W, BC_H - FOOT_TOP),
    dstOffset = IntOffset.Zero,
    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
    filterQuality = FilterQuality.None
   )
  }
 }
}

@Composable
private fun ShelfBooks(entries: List<List<RoomItem>>, perRow: Int, iw: Dp, ih: Dp, onTap: (List<RoomItem>, Rect) -> Unit) {
 val gap = 6.dp
 val cw = (iw - gap * (perRow + 1)) / perRow
 val ch = minOf(ih * 0.94f, cw * 1.5f)
 Row(
  Modifier.fillMaxSize().padding(start = gap, bottom = 1.dp),
  horizontalArrangement = Arrangement.spacedBy(gap),
  verticalAlignment = Alignment.Bottom
 ) {
  entries.forEach { group -> ShelfBook(group, cw, ch, onTap) }
 }
}

@Composable
private fun ShelfBook(group: List<RoomItem>, w: Dp, h: Dp, onTap: (List<RoomItem>, Rect) -> Unit) {
 var bounds by remember { mutableStateOf(Rect.Zero) }
 val first = group.first()
 val label = if (group.size > 1) "${first.series.ifBlank { first.title }}, ${group.size} volumes" else "${first.title}, ${first.kind}"
 Box(
  Modifier.size(w, h)
   .onGloballyPositioned { bounds = it.boundsInRoot() }
   .semantics(mergeDescendants = true) { contentDescription = label }
   .clickable(onClickLabel = if (group.size > 1) "Open series" else "Open book", role = Role.Button) { onTap(group, bounds) }
 ) {
  RoomCover(first, Modifier.fillMaxSize(), describe = false)
  if (group.size > 1) {
   Text(
    "×${group.size}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).background(Color(0xCC24103F), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp)
   )
  }
 }
}

@Composable
private fun rememberFlicker(): Float {
 val t = rememberInfiniteTransition(label = "lamp")
 val v by t.animateFloat(0.78f, 1f, infiniteRepeatable(tween(1700, easing = LinearEasing), RepeatMode.Reverse), label = "flicker")
 return v
}

private fun Modifier.vignette(strength: Float = 0.6f): Modifier = drawBehind {
 drawRect(
  Brush.radialGradient(
   colors = listOf(Color.Transparent, Color(0xFF07040D).copy(alpha = strength)),
   center = center, radius = size.maxDimension * 0.75f
  )
 )
}

@Composable
private fun LampGlow(modifier: Modifier, color: Color, alpha: Float) {
 Canvas(modifier) {
  val r = size.minDimension / 2f
  drawCircle(
   brush = Brush.radialGradient(listOf(color.copy(alpha = alpha.coerceIn(0f, 1f)), Color.Transparent), center = center, radius = r),
   radius = r, center = center
  )
 }
}

@Composable
private fun RoomHero(roomMode: String, onMode: (String) -> Unit, onClassic: () -> Unit) {
 val wall = ImageBitmap.imageResource(R.drawable.room_wall_bg)
 val jukebox = ImageBitmap.imageResource(R.drawable.room_jukebox)
 val lamp = ImageBitmap.imageResource(R.drawable.room_lamp_table)
 val stack = ImageBitmap.imageResource(R.drawable.room_book_stack)
 val pendant = ImageBitmap.imageResource(R.drawable.room_lamp_pendant)
 val ivy = ImageBitmap.imageResource(R.drawable.room_ivy)
 val flicker = rememberFlicker()
 val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
 val modeLabel = when (roomMode) { "Desk" -> "THE READING DESK"; "Jukebox" -> "THE AUDIO JUKEBOX"; else -> "THE READING ROOM" }
 BoxWithConstraints(Modifier.fillMaxWidth().height(196.dp + top).clipToBounds()) {
  val w = maxWidth
  Image(wall, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.None)
  Box(Modifier.matchParentSize().vignette(0.55f))
  Box(Modifier.fillMaxWidth().height(96.dp + top).background(Brush.verticalGradient(listOf(Color(0xDD0B0614), Color.Transparent))))
  Image(ivy, null, Modifier.align(Alignment.TopEnd).offset(y = top).height(104.dp), filterQuality = FilterQuality.None)
  // hanging lamps
  LampGlow(Modifier.size(150.dp).align(Alignment.TopStart).offset(x = w * 0.40f - 75.dp, y = top + 14.dp), amber, 0.50f * flicker)
  LampGlow(Modifier.size(150.dp).align(Alignment.TopStart).offset(x = w * 0.60f - 75.dp, y = top + 14.dp), amber, 0.50f * flicker)
  Image(pendant, null, Modifier.align(Alignment.TopStart).offset(x = w * 0.40f - 33.dp, y = top).width(66.dp), filterQuality = FilterQuality.None)
  Image(pendant, null, Modifier.align(Alignment.TopStart).offset(x = w * 0.60f - 33.dp, y = top).width(66.dp), filterQuality = FilterQuality.None)
  // reading desk corner (left)
  LampGlow(Modifier.size(150.dp).align(Alignment.BottomStart).offset(x = 6.dp, y = 30.dp), if (roomMode == "Desk") lime else amber, (if (roomMode == "Desk") 0.30f else 0.34f) * flicker)
  Row(
   Modifier.align(Alignment.BottomStart).padding(start = 6.dp)
    .semantics(mergeDescendants = true) { contentDescription = "Reading desk. Shows books and comics." }
    .clickable(onClickLabel = "Show books and comics", role = Role.Button) { onMode("Desk") },
   verticalAlignment = Alignment.Bottom
  ) {
   Image(stack, null, Modifier.height(64.dp), filterQuality = FilterQuality.None)
   Image(lamp, null, Modifier.height(96.dp), filterQuality = FilterQuality.None)
  }
  // jukebox (right)
  LampGlow(Modifier.size(200.dp).align(Alignment.BottomEnd).offset(x = 22.dp, y = 20.dp), if (roomMode == "Jukebox") lime else Color(0xFF8A3FFC), 0.34f)
  Image(
   jukebox, null,
   Modifier.align(Alignment.BottomEnd).padding(end = 14.dp).height(150.dp)
    .semantics(mergeDescendants = true) { contentDescription = "Audiobook jukebox. Shows audiobooks." }
    .clickable(onClickLabel = "Show audiobooks", role = Role.Button) { onMode("Jukebox") },
   filterQuality = FilterQuality.None
  )
  Column(Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = top + 6.dp)) {
   Text("GhostReader", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
   Text(modeLabel, color = lime, fontSize = 11.sp, letterSpacing = 2.sp)
   TextButton(onClick = onClassic, contentPadding = PaddingValues(horizontal = 0.dp)) {
    Text("Classic Library", color = Color(0xFFE9C99E), fontSize = 12.sp)
   }
  }
 }
}

@Composable
private fun FlyingCover(item: RoomItem, from: Rect, to: Rect, origin: Offset, p: Float, fadeOut: Boolean) {
 val d = LocalDensity.current
 val l = lerp(from.left, to.left, p)
 val t = lerp(from.top, to.top, p)
 val w = lerp(from.width, to.width, p)
 val h = lerp(from.height, to.height, p)
 val lift = sin(p * PI.toFloat()) * 70f
 val a = if (fadeOut) 1f - ((p - 0.8f) / 0.2f).coerceIn(0f, 1f) else 1f
 val tilt = (1f - p) * -5f
 Box(
  Modifier.offset { IntOffset((l - origin.x).roundToInt(), (t - origin.y - lift).roundToInt()) }
   .size(with(d) { w.toDp() }, with(d) { h.toDp() })
   .graphicsLayer { alpha = a; rotationZ = tilt }
 ) {
  RoomCover(item, Modifier.fillMaxSize(), describe = false)
 }
}

@Composable
private fun DeskScene(item: RoomItem, from: Rect, origin: Offset, onOpen: () -> Unit, onBack: () -> Unit) {
 val wall = ImageBitmap.imageResource(R.drawable.room_wall_bg)
 val desk = ImageBitmap.imageResource(R.drawable.room_desk)
 val lamp = ImageBitmap.imageResource(R.drawable.room_lamp_desk)
 val stack = ImageBitmap.imageResource(R.drawable.room_book_stack)
 val pendant = ImageBitmap.imageResource(R.drawable.room_lamp_pendant)
 val chair = ImageBitmap.imageResource(R.drawable.room_armchair)
 val flicker = rememberFlicker()
 val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
 var deskRect by remember { mutableStateOf<Rect?>(null) }
 val progress = remember(item) { Animatable(0f) }
 val ready = deskRect != null
 LaunchedEffect(item, ready) {
  if (ready) {
   progress.snapTo(0f)
   progress.animateTo(1f, tween(780, easing = FastOutSlowInEasing))
  }
 }
 BoxWithConstraints(Modifier.fillMaxSize()) {
  val w = maxWidth
  val deskH = w * 0.58f
  Image(wall, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.None)
  Box(Modifier.matchParentSize().vignette(0.62f))
  LampGlow(Modifier.size(220.dp).align(Alignment.TopCenter).offset(y = top), amber, 0.42f * flicker)
  Image(pendant, null, Modifier.align(Alignment.TopCenter).offset(y = top).width(84.dp), filterQuality = FilterQuality.None)
  Image(chair, null, Modifier.align(Alignment.BottomStart).padding(bottom = deskH * 0.55f).offset(x = (-18).dp).width(w * 0.46f), filterQuality = FilterQuality.None)
  Column(Modifier.align(Alignment.TopCenter).padding(top = top + 96.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
   Text("THE READING DESK", color = lime, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
   Text(item.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
  }
  Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(deskH).onGloballyPositioned { deskRect = it.boundsInRoot() }) {
   Image(desk, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.None)
   Row(
    Modifier.align(Alignment.BottomCenter).padding(horizontal = w * 0.08f).padding(bottom = deskH * 0.10f),
    horizontalArrangement = Arrangement.spacedBy(10.dp)
   ) {
    Button(
     onClick = onOpen, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
     colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6D3FD0), contentColor = Color.White)
    ) { Text("Open / Resume", fontSize = 13.sp, maxLines = 1) }
    OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
     Text("‹ Shelves", color = plum, fontSize = 13.sp, maxLines = 1)
    }
   }
  }
  LampGlow(Modifier.size(190.dp).align(Alignment.BottomStart).padding(bottom = deskH * 0.70f + 20.dp), amber, 0.38f * flicker)
  Image(lamp, null, Modifier.align(Alignment.BottomStart).padding(start = w * 0.03f, bottom = deskH * 0.70f).width(w * 0.22f), filterQuality = FilterQuality.None)
  Image(stack, null, Modifier.align(Alignment.BottomEnd).padding(end = w * 0.04f, bottom = deskH * 0.70f).width(w * 0.20f), filterQuality = FilterQuality.None)
  val dr = deskRect
  if (dr != null) {
   val bw = dr.width * 0.34f
   val bh = bw * 1.5f
   val cx = dr.center.x
   val base = dr.top + dr.height * 0.32f
   FlyingCover(item, from, Rect(cx - bw / 2f, base - bh, cx + bw / 2f, base), origin, progress.value, false)
  }
 }
}

@Composable
private fun JukeboxScene(item: RoomItem, from: Rect, origin: Offset, onOpen: () -> Unit, onBack: () -> Unit) {
 val wall = ImageBitmap.imageResource(R.drawable.room_wall_bg)
 val jukebox = ImageBitmap.imageResource(R.drawable.room_jukebox)
 val crate = ImageBitmap.imageResource(R.drawable.room_record_crate)
 val pendant = ImageBitmap.imageResource(R.drawable.room_lamp_pendant)
 val flicker = rememberFlicker()
 val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
 var jukeRect by remember { mutableStateOf<Rect?>(null) }
 val progress = remember(item) { Animatable(0f) }
 val ready = jukeRect != null
 LaunchedEffect(item, ready) {
  if (ready) {
   progress.snapTo(0f)
   progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
  }
 }
 val loaded = progress.value >= 1f
 BoxWithConstraints(Modifier.fillMaxSize()) {
  val w = maxWidth
  Image(wall, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.None)
  Box(Modifier.matchParentSize().vignette(0.7f))
  LampGlow(Modifier.size(w * 1.2f).align(Alignment.Center).offset(y = (-30).dp), Color(0xFF8A3FFC), (if (loaded) 0.55f else 0.38f) * flicker)
  LampGlow(Modifier.size(w * 0.8f).align(Alignment.Center).offset(y = (-30).dp), lime, (if (loaded) 0.30f else 0.14f) * flicker)
  Image(pendant, null, Modifier.align(Alignment.TopStart).offset(x = w * 0.14f, y = top).width(66.dp), filterQuality = FilterQuality.None)
  Image(crate, null, Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 120.dp).width(w * 0.26f), filterQuality = FilterQuality.None)
  Column(Modifier.align(Alignment.TopCenter).padding(top = top + 14.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
   Text("♫  THE JUKEBOX", color = lime, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
  }
  Box(
   Modifier.align(Alignment.Center).offset(y = (-30).dp).fillMaxHeight(0.52f).aspectRatio(278f / 401f)
    .onGloballyPositioned { jukeRect = it.boundsInRoot() }
  ) {
   Image(jukebox, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None)
  }
  Column(
   Modifier.align(Alignment.BottomCenter).fillMaxWidth()
    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF00B0614))))
    .padding(start = 16.dp, end = 16.dp, top = 30.dp, bottom = 14.dp),
   horizontalAlignment = Alignment.CenterHorizontally
  ) {
   Text(item.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
   Spacer(Modifier.height(8.dp))
   Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    Button(
     onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp),
     colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6D3FD0), contentColor = Color.White)
    ) { Text("▶ Play / Resume", fontSize = 13.sp, maxLines = 1) }
    OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("‹ Shelves", color = plum, fontSize = 13.sp, maxLines = 1) }
   }
  }
  val jr = jukeRect
  if (jr != null) {
   val s = jr.width * 0.24f
   val cx = jr.center.x
   val cy = jr.top + jr.height * 0.22f
   FlyingCover(item, from, Rect(cx - s / 2f, cy - s / 2f, cx + s / 2f, cy + s / 2f), origin, progress.value, true)
  }
 }
}

@Composable
private fun RoomCover(item: RoomItem, modifier: Modifier = Modifier, describe: Boolean = true) {
 val context = LocalContext.current
 val cover by produceState<Bitmap?>(null, item.book, item.file) {
  value = withContext(Dispatchers.IO) {
   runCatching { roomCoverBitmap(context, item.book, item.file, item.kind, item.title) }.getOrNull()
  }
 }
 val c = cover
 if (c != null) {
  Image(bitmap = c.asImageBitmap(), contentDescription = if (describe) item.title else null,
   modifier = modifier.clip(RoundedCornerShape(3.dp)), contentScale = ContentScale.Crop)
 } else {
  // Placeholder only when no artwork is available: a leather-bound look with the title.
  Box(
   modifier.background(Brush.verticalGradient(listOf(Color(0xFF6B3F2E), Color(0xFF2A1630))), RoundedCornerShape(3.dp)).padding(4.dp),
   contentAlignment = Alignment.Center
  ) {
   Text(item.title, color = Color(0xFFF3DFB8), fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 5, overflow = TextOverflow.Ellipsis)
  }
 }
}

private fun cleanRoomTitle(raw: String): String = raw.replace(Regex("^\\d{10,}[_\\s-]*"), "").replace(Regex("(?i)\\b(?:oceanofpdf\\.com|digital)\\b"), "").replace('_', ' ').replace(Regex("\\s+"), " ").trim(' ', '-', '.').ifBlank { raw }
