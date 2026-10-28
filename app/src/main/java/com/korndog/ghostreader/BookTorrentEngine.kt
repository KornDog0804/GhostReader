package com.korndog.ghostreader

import android.content.Context
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import java.io.File

/** Reuses Race Lab's libtorrent4j 2.x engine, but downloads EPUB files instead of streaming video. */
class BookTorrentEngine(private val context: Context) {
 @Volatile private var cancelled = false
 @Volatile private var session: SessionManager? = null


 data class AudioOption(val index: Int, val name: String, val bytes: Long)

 /** Inspect magnet metadata, let the user select ONE authorized audiobook, then download it. */
 fun downloadAudio(magnet: String, shelf: File, progress: (String) -> Unit, choose: (List<AudioOption>) -> Int): File {
  require(magnet.startsWith("magnet:?xt=urn:btih:", true)) { "Paste a valid magnet URI" }
  val work = File(context.cacheDir, "ghostreader-audio-torrent").apply { mkdirs() }
  val sm = SessionManager()
  session = sm
  try {
   progress("Starting audiobook torrent...")
   sm.start()
   val hash = AddTorrentParams.parseMagnetUri(magnet).infoHashes.best
   sm.download(magnet, work, TorrentFlags.AUTO_MANAGED.or_(TorrentFlags.UPDATE_SUBSCRIBE))
   var handle: TorrentHandle? = null
   val metadataDeadline = System.currentTimeMillis() + 120_000L
   while (!cancelled && System.currentTimeMillis() < metadataDeadline) {
    handle = sm.find(hash)
    if (handle != null && handle.isValid && handle.status().hasMetadata()) break
    progress("Finding peers and audiobook metadata...")
    Thread.sleep(1000)
   }
   if (cancelled) error("Download cancelled")
   val th = handle?.takeIf { it.isValid && it.status().hasMetadata() } ?: error("Could not fetch torrent metadata")
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

 /** Download files sequentially, immediately placing each completed book on the shelf.
  * Completed books survive cancellation or a later file failure.
  * Torrent pieces can overlap file boundaries, so keep already completed pieces
  * available and do not remove the torrent's working directory until stopped.
  */
 private fun downloadAudioBatchFromHandle(
  th: TorrentHandle,
  info: org.libtorrent4j.TorrentInfo,
  files: org.libtorrent4j.FileStorage,
  options: List<AudioOption>,
  shelf: File,
  work: File,
  progress: (String) -> Unit
 ): File {
  require(options.isNotEmpty()) { "No audio files selected" }
  shelf.mkdirs()
  require(shelf.isDirectory) { "Audiobook library is unavailable" }
  val ordered = options.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
  val pieceSize = info.pieceLength().toLong()
  val reserve = 200_000_000L
  val largest = ordered.maxOf { it.bytes }
  // A piece may span the end of one file and the start of another.
  // Only one full file and boundary pieces should need temporary space.
  val required = largest + 2 * pieceSize + reserve
  require(shelf.usableSpace > required) {
   "Not enough storage for the largest book: need ${required / 1_000_000L} MB free, have ${shelf.usableSpace / 1_000_000L} MB"
  }
  val imported = mutableListOf<File>()
  val finished = mutableSetOf<Int>()
  val deadline = System.currentTimeMillis() + 24 * 60 * 60_000L
  for ((order, opt) in ordered.withIndex()) {
   if (cancelled) error("Download cancelled; ${imported.size} completed books remain in your library")
   val freeNeeded = opt.bytes + 2 * pieceSize + reserve
   require(shelf.usableSpace > freeNeeded) {
    "Storage low before book ${order + 1}: need ${freeNeeded / 1_000_000L} MB free, have ${shelf.usableSpace / 1_000_000L} MB. ${imported.size} completed books were saved."
   }
   // Only request this file. Completed files have been moved out of the
   // torrent work directory; they are never copied a second time.
   th.prioritizeFiles(Array(info.numFiles()) {
    if (it == opt.index) org.libtorrent4j.Priority.DEFAULT else org.libtorrent4j.Priority.IGNORE
   })
   th.resume()
   val offset = files.fileOffset(opt.index)
   val first = (offset / pieceSize).toInt()
   val last = ((offset + opt.bytes - 1) / pieceSize).toInt()
   val total = last - first + 1
   while (!cancelled && System.currentTimeMillis() < deadline) {
    val ready = (first..last).count { th.havePiece(it) }
    progress("Book ${order + 1}/${ordered.size}: ${ready * 100 / total}% • ${imported.size} saved")
    if (ready == total) break
    Thread.sleep(2000)
   }
   if (cancelled) error("Download cancelled; ${imported.size} completed books remain in your library")
   if (!(first..last).all { th.havePiece(it) }) error("Book ${order + 1} timed out; ${imported.size} completed books remain in your library")
   val source = File(work, opt.name)
   require(source.canonicalPath.startsWith(work.canonicalPath + File.separator) && source.isFile && source.length() >= opt.bytes) {
    "Downloaded book ${order + 1} is incomplete; ${imported.size} completed books remain saved"
   }
   val name = File(opt.name).name.replace(Regex("[^a-zA-Z0-9._ -]"), "_")
   val target = File.createTempFile("gr_${System.currentTimeMillis()}_${order}_", "_${name}", shelf)
   target.delete()
   // Both directories are app-private on the same volume in normal installs.
   // Never fall back to a silent full-size copy when storage is constrained.
   require(source.renameTo(target)) {
    "Unable to move book ${order + 1} into library without duplicating it; ${imported.size} books already saved"
   }
   imported.add(target)
   finished.add(opt.index)
   progress("Saved ${order + 1} of ${ordered.size}: ${name}")
  }
  progress("Added ${imported.size} audiobooks to your library")
  return imported.first()
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
  try {
   progress("Starting torrent engine...")
   sm.start()
   val hash = AddTorrentParams.parseMagnetUri(magnet).infoHashes.best
   sm.download(magnet, work, TorrentFlags.AUTO_MANAGED.or_(TorrentFlags.UPDATE_SUBSCRIBE))
   var handle: TorrentHandle? = null
   val metadataDeadline = System.currentTimeMillis() + 120_000L
   while (!cancelled && System.currentTimeMillis() < metadataDeadline) {
    handle = sm.find(hash)
    if (handle != null && handle.isValid && handle.status().hasMetadata()) break
    progress("Finding peers and torrent metadata...")
    Thread.sleep(1000)
   }
   if (cancelled) error("Download cancelled")
   val th = handle ?: error("No peers found for magnet")
   val info = th.torrentFile() ?: error("Torrent metadata unavailable")
   val files = info.files()
   val matches = (0 until info.numFiles()).filter {
    files.filePath(it).endsWith(".epub", ignoreCase = true) &&
     files.fileSize(it) in 1..30_000_000L
   }
   if (matches.isEmpty()) error("Torrent has no EPUB file under 30 MB")
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
