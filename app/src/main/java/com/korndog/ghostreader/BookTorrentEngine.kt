package com.korndog.ghostreader

import android.content.Context
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import java.io.File

private val fallbackTrackers = listOf(
 "udp://tracker.opentrackr.org:1337/announce",
 "udp://open.stealth.si:80/announce",
 "udp://tracker.torrent.eu.org:451/announce",
 "https://tracker.gbitt.info:443/announce"
)

/** Preserve any original trackers and add public fallback trackers for peer discovery. */
private fun withFallbackTrackers(magnet: String): String {
 val existing = magnet.substringAfter('?', "").split('&')
  .filter { it.startsWith("tr=", ignoreCase = true) }
  .map { java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
  .toSet()
 return magnet + fallbackTrackers.filterNot { it in existing }
  .joinToString("") { "&tr=" + android.net.Uri.encode(it) }
}

/** Reuses Race Lab's libtorrent4j 2.x engine, but downloads EPUB files instead of streaming video. */
class BookTorrentEngine(private val context: Context) {
 @Volatile private var cancelled = false
 @Volatile private var session: SessionManager? = null


 /** Distinguish a missing torrent handle from peer discovery and metadata transfer. */
 private fun metadataStatus(handle: TorrentHandle?): String {
  if (handle == null || !handle.isValid) return "waiting for torrent session to register magnet"
  return runCatching {
   val status=handle.status()
   "peers ${status.numPeers()}, metadata ${if(status.hasMetadata()) "received" else "pending"}, state ${status.state()}"
  }.getOrDefault("checking peer connections")
 }

 data class AudioOption(val index: Int, val name: String, val bytes: Long)

 /** Inspect magnet metadata, let the user select ONE authorized audiobook, then download it. */
 fun downloadAudio(magnet: String, shelf: File, progress: (String) -> Unit, choose: (List<AudioOption>) -> Int): File {
  require(magnet.startsWith("magnet:?xt=urn:btih:", true)) { "Paste a valid magnet URI" }
  val work = File(context.cacheDir, "ghostreader-audio-torrent").apply { mkdirs() }
  val sm = SessionManager()
  session = sm
  cancelled = false
  try {
   progress("Starting audiobook torrent with public tracker fallbacks...")
   sm.start()
   val hash = AddTorrentParams.parseMagnetUri(magnet).infoHashes.best
   sm.download(withFallbackTrackers(magnet), work, TorrentFlags.AUTO_MANAGED.or_(TorrentFlags.UPDATE_SUBSCRIBE))
   var handle: TorrentHandle? = null
   val metadataDeadline = System.currentTimeMillis() + 120_000L
   while (!cancelled && System.currentTimeMillis() < metadataDeadline) {
    handle = sm.find(hash)
    if (handle != null && handle.isValid && handle.status().hasMetadata()) break
    progress("Audiobook metadata: "+metadataStatus(handle))
    Thread.sleep(1000)
   }
   if (cancelled) error("Download cancelled")
   val th = handle?.takeIf { it.isValid && it.status().hasMetadata() } ?: error("Could not fetch audiobook metadata after 120 seconds. No reachable peers, or DHT/trackers may be blocked.")
   val info = th.torrentFile() ?: error("Torrent metadata unavailable")
   val files = info.files()
   val options = (0 until info.numFiles()).filter {
    val path = files.filePath(it).lowercase()
    (path.endsWith(".m4b") || path.endsWith(".mp3") || path.endsWith(".m4a")) &&
     files.fileSize(it) in 1..2_000_000_000L
   }.map { AudioOption(it, files.filePath(it), files.fileSize(it)) }
   if (options.isEmpty()) error("No M4B, MP3, or M4A audiobook files found")
   progress("Choose an audiobook to download")
   val index = choose(options)
   if (cancelled || index == -1) error("Download cancelled")
   if (index == -2 && options.size > 1) return downloadAudioBatchFromHandle(th, info, files, options, shelf, work, progress)
   val chosenIndex = if (index == -2 && options.size == 1) options.first().index else index
   if (options.none { it.index == chosenIndex }) error("Invalid audiobook selection")
   val relative = files.filePath(chosenIndex)
   val safeName = File(relative).name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
   val size = files.fileSize(chosenIndex)
   val free = shelf.usableSpace
   if (free < size + 100_000_000L) error("Not enough phone storage: need ${size / 1_000_000} MB plus free space")
   th.prioritizeFiles(Array(info.numFiles()) { if (it == chosenIndex) org.libtorrent4j.Priority.DEFAULT else org.libtorrent4j.Priority.IGNORE })
   th.resume()
   val offset = files.fileOffset(chosenIndex)
   val pieceSize = info.pieceLength().toLong()
   val first = (offset / pieceSize).toInt()
   val last = ((offset + size - 1) / pieceSize).toInt()
   val deadline = System.currentTimeMillis() + 6 * 60 * 60_000L
   while (!cancelled && System.currentTimeMillis() < deadline) {
    var ready = 0
    for (piece in first..last) if (th.havePiece(piece)) ready++
    val total = last - first + 1
    progress("Downloading $safeName: ${ready * 100 / total}%")
    if (ready == total) break
    Thread.sleep(2000)
   }
   if (cancelled) error("Download cancelled")
   if (!(first..last).all { th.havePiece(it) }) error("Audiobook download timed out")
   val source = File(work, relative)
   if (!source.canonicalPath.startsWith(work.canonicalPath + File.separator)) error("Unsafe torrent path")
   if (!source.isFile) error("Audiobook file not written to disk")
   val target = File(shelf, "${System.currentTimeMillis()}_$safeName")
   source.copyTo(target, overwrite = false)
   progress("Audiobook added to your library")
   return target
  } finally {
   runCatching { sm.stop() }
   session = null
   work.deleteRecursively()
  }
 }

 /** Download all audio tracks as one folder, preserving track order and original filenames. */
 private fun downloadAudioBatchFromHandle(
  th: TorrentHandle,
  info: org.libtorrent4j.TorrentInfo,
  files: org.libtorrent4j.FileStorage,
  options: List<AudioOption>,
  shelf: File,
  work: File,
  progress: (String) -> Unit
 ): File {
  val totalBytes = options.sumOf { it.bytes }
  require(totalBytes <= 12_000_000_000L) { "Audiobook collection exceeds 12 GB safety limit" }
  require(shelf.usableSpace > totalBytes * 2 + 150_000_000L) { "Not enough storage for the complete audiobook and temporary download" }
  val selected = options.map { it.index }.toSet()
  th.prioritizeFiles(Array(info.numFiles()) { if (it in selected) org.libtorrent4j.Priority.DEFAULT else org.libtorrent4j.Priority.IGNORE })
  th.resume()
  val pieceSize = info.pieceLength().toLong()
  val pieces = options.flatMap { opt ->
   val first = (files.fileOffset(opt.index) / pieceSize).toInt()
   val last = ((files.fileOffset(opt.index) + opt.bytes - 1) / pieceSize).toInt()
   (first..last).toList()
  }.toSet()
  val deadline = System.currentTimeMillis() + 12 * 60 * 60_000L
  while (!cancelled && System.currentTimeMillis() < deadline) {
   val ready = pieces.count { th.havePiece(it) }
   progress("Downloading complete audiobook: ${ready * 100 / pieces.size}% (${options.size} tracks)")
   if (ready == pieces.size) break
   Thread.sleep(2000)
  }
  if (cancelled) error("Download cancelled")
  if (!pieces.all { th.havePiece(it) }) error("Complete audiobook download timed out")
  val folderName = options.first().name.substringBeforeLast('/', "Audiobook").substringAfterLast('/')
   .replace(Regex("[^a-zA-Z0-9._ -]"), "_").take(90).ifBlank { "Audiobook" }
  val target = File(shelf, "${System.currentTimeMillis()}_${folderName}").apply { mkdirs() }
  try {
   options.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }).forEachIndexed { order, opt ->
    val source = File(work, opt.name)
    require(source.canonicalPath.startsWith(work.canonicalPath + File.separator) && source.isFile) { "Missing or unsafe track" }
    val name = File(opt.name).name.replace(Regex("[^a-zA-Z0-9._ -]"), "_")
    source.copyTo(File(target, "${order.toString().padStart(3, '0')}_${name}"))
   }
  } catch (e: Exception) { target.deleteRecursively(); throw e }
  progress("Complete audiobook downloaded: ${options.size} tracks")
  return target
 }

 /** Download one CBZ comic from an authorized torrent, without fetching unrelated files. */
 fun downloadComic(magnet:String, shelf:File, progress:(String)->Unit):File {
  require(magnet.startsWith("magnet:?xt=urn:btih:",true)) { "Paste a valid magnet URI" }
  val work=File(context.cacheDir,"ghostreader-comic-torrent").apply { mkdirs() }
  val sm=SessionManager()
  session=sm
  try {
   progress("Finding comic torrent metadata...")
   sm.start()
   val hash=AddTorrentParams.parseMagnetUri(magnet).infoHashes.best
   sm.download(withFallbackTrackers(magnet),work,TorrentFlags.AUTO_MANAGED.or_(TorrentFlags.UPDATE_SUBSCRIBE))
   var handle:TorrentHandle?=null
   val deadline=System.currentTimeMillis()+120_000L
   while(!cancelled && System.currentTimeMillis()<deadline) {
    handle=sm.find(hash)
    if(handle!=null && handle.isValid && handle.status().hasMetadata()) break
    progress("Comic metadata: "+metadataStatus(handle))
    Thread.sleep(1000)
   }
   if(cancelled) error("Download cancelled")
   val th=handle?.takeIf { it.isValid && it.status().hasMetadata() } ?: error("No comic metadata after 120 seconds. No reachable peers or blocked trackers/DHT.")
   val info=th.torrentFile() ?: error("Comic metadata unavailable")
   val files=info.files()
   val matches=(0 until info.numFiles()).filter {
    files.filePath(it).endsWith(".cbz",true) && files.fileSize(it) in 1..1_500_000_000L
   }
   if(matches.isEmpty()) error("No CBZ comic found. CBR archives are not supported yet.")
   val index=matches.first()
   val relative=files.filePath(index)
   val size=files.fileSize(index)
   require(shelf.usableSpace>size*2+100_000_000L) { "Not enough storage for CBZ download" }
   th.prioritizeFiles(Array(info.numFiles()) { if(it==index) org.libtorrent4j.Priority.DEFAULT else org.libtorrent4j.Priority.IGNORE })
   th.resume()
   val pieceSize=info.pieceLength().toLong()
   val first=(files.fileOffset(index)/pieceSize).toInt()
   val last=((files.fileOffset(index)+size-1)/pieceSize).toInt()
   val end=System.currentTimeMillis()+6*60*60_000L
   while(!cancelled && System.currentTimeMillis()<end) {
    val ready=(first..last).count { th.havePiece(it) }
    progress("Downloading CBZ: ${ready*100/(last-first+1)}%")
    if(ready==last-first+1) break
    Thread.sleep(2000)
   }
   if(cancelled) error("Download cancelled")
   if(!(first..last).all { th.havePiece(it) }) error("CBZ download timed out")
   val source=File(work,relative)
   require(source.canonicalPath.startsWith(work.canonicalPath+File.separator) && source.isFile) { "Unsafe or missing CBZ file" }
   val safe=File(relative).name.replace(Regex("[^a-zA-Z0-9._-]"),"_")
   val target=File(shelf,"${System.currentTimeMillis()}_$safe")
   source.copyTo(target,overwrite=false)
   progress("Comic downloaded")
   return target
  } finally {
   runCatching { sm.stop() }
   session=null
   work.deleteRecursively()
  }
 }

 fun cancel() {
  cancelled = true
  runCatching { session?.stop() }
 }

 /** Blocking; caller must run off the UI thread. */
 fun download(magnet: String, shelf: File, progress: (String) -> Unit): File {
  require(magnet.startsWith("magnet:?xt=urn:btih:", true)) { "Paste a valid magnet URI" }
  val work = File(context.cacheDir, "ghostreader-torrent").apply { mkdirs() }
  val sm = SessionManager()
  session = sm
  cancelled = false
  try {
   progress("Starting torrent engine with public tracker fallbacks...")
   sm.start()
   val hash = AddTorrentParams.parseMagnetUri(magnet).infoHashes.best
   sm.download(withFallbackTrackers(magnet), work, TorrentFlags.AUTO_MANAGED.or_(TorrentFlags.UPDATE_SUBSCRIBE))
   var handle: TorrentHandle? = null
   val metadataDeadline = System.currentTimeMillis() + 120_000L
   while (!cancelled && System.currentTimeMillis() < metadataDeadline) {
    handle = sm.find(hash)
    if (handle != null && handle.isValid && handle.status().hasMetadata()) break
    progress("EPUB metadata: "+metadataStatus(handle))
    Thread.sleep(1000)
   }
   if (cancelled) error("Download cancelled")
   val th = handle?.takeIf { it.isValid && it.status().hasMetadata() }
    ?: error("No torrent metadata after 120 seconds. Trackers and DHT may be blocked, or no reachable peers have the metadata. Try another well-seeded, authorized torrent or a different network.")
   val info = th.torrentFile() ?: error("Torrent metadata unavailable despite peer discovery")
   val files = info.files()
   val matches = (0 until info.numFiles()).filter {
    files.filePath(it).endsWith(".epub", ignoreCase = true) &&
     files.fileSize(it) in 1..30_000_000L
   }
   if (matches.isEmpty()) error("No supported EPUB under 30 MB in this torrent. Comics (CBZ/CBR) and PDF downloads require a separate file picker and reader support; this EPUB button cannot import them.")
   val index = matches.first()
   val relative = files.filePath(index)
   val safeName = File(relative).name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
   progress("Downloading $safeName...")
   // Select EPUB only; unrelated torrent files should not be downloaded.
   th.prioritizeFiles(Array(info.numFiles()) { if (it == index) org.libtorrent4j.Priority.DEFAULT else org.libtorrent4j.Priority.IGNORE })
   th.resume()
   val deadline = System.currentTimeMillis() + 20 * 60_000L
   val offset = files.fileOffset(index)
   val size = files.fileSize(index)
   val pieceSize = info.pieceLength().toLong()
   val first = (offset / pieceSize).toInt()
   val last = ((offset + size - 1) / pieceSize).toInt()
   while (!cancelled && System.currentTimeMillis() < deadline) {
    val ready = (first..last).count { th.havePiece(it) }
    val total = last - first + 1
    progress("Downloading $safeName: ${ready * 100 / total}%")
    if (ready == total) break
    Thread.sleep(1500)
   }
   if (cancelled) error("Download cancelled")
   if (!(first..last).all { th.havePiece(it) }) error("Torrent download timed out")
   val source = File(work, relative)
   if (!source.canonicalPath.startsWith(work.canonicalPath + File.separator)) error("Unsafe torrent path")
   if (!source.isFile) error("EPUB file not written to disk")
   val target = File(shelf, "${System.currentTimeMillis()}_$safeName")
   source.copyTo(target, overwrite = false)
   return target
  } finally {
   runCatching { sm.stop() }
   session = null
   work.deleteRecursively()
  }
 }
}
