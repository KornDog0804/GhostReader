package com.korndog.ghostreader

import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/*
 * Backup / restore engine. Deliberately free of Android types so it can be unit-tested on a plain JVM.
 * LibraryBackup.kt is the thin Android layer (Context, Uri, SharedPreferences, JSON) around it.
 *
 * Safety rules enforced here:
 *  - Backup only READS the library. It writes to its own staging folder and the destination the user chose.
 *  - Restore is additive: it stages and fully validates the archive first, then adds new files by rename
 *    and rolls back the files it added if anything fails. Existing files, progress, bookmarks and covers
 *    are never overwritten or deleted.
 *  - Only one backup/restore runs at a time, and each run uses uniquely named temp files.
 */

private const val MAX_TOTAL_BYTES = 12L * 1024 * 1024 * 1024
private const val MAX_ENTRIES = 20_000
private const val BUFFER = 256 * 1024
private const val MARGIN_BYTES = 32L * 1024 * 1024
private const val MANIFEST_LIMIT = 32 * 1024 * 1024
private const val MANIFEST_ENTRY = "manifest.json"

/** A backup/restore failure that names the stage that failed, so the message says exactly what went wrong. */
internal class BackupException(val stage: String, message: String, cause: Throwable? = null) :
  Exception("$stage: $message", cause) {
  /** Last few log lines, filled in by the Android layer so the UI can show what happened just before the failure. */
  var details: String = ""
}

/** Timestamped step log. The last lines go into error details; the Android layer also forwards each line to logcat. */
internal class BackupTrace(private val sink: (String) -> Unit = {}) {
  private val started = System.nanoTime()
  private val lines = ArrayDeque<String>()
  fun log(message: String) {
    val line = "+${(System.nanoTime() - started) / 1_000_000}ms $message"
    synchronized(lines) {
      lines.addLast(line)
      while (lines.size > 300) lines.removeFirst()
    }
    sink(line)
  }
  fun tail(count: Int = 8): String = synchronized(lines) { lines.toList().takeLast(count).joinToString("\n") }
}

internal enum class BackupKind(val folder: String, val ext: String, val v1NameDir: String?, val extensions: Set<String>, val label: String) {
  BOOKS("books", "epub", "names", setOf("epub"), "ebooks"),
  AUDIO("audio", "bin", "audionames", setOf("m4b", "mp3", "m4a"), "audiobooks"),
  COMICS("comics", "cbz", null, setOf("cbz"), "comics"),
  PDFS("pdfs", "pdf", null, setOf("pdf"), "PDFs"),
  COVERS("covers", "jpg", null, setOf("jpg"), "cached covers"),
  MANUAL_COVERS("manual_covers", "jpg", null, setOf("jpg"), "chosen covers");

  val entryRegex = Regex("^$folder/[0-9]{4,6}\\.$ext$")
  val isCover: Boolean get() = this == COVERS || this == MANUAL_COVERS
  fun entryName(index: Int) = folder + "/" + index.toString().padStart(4, '0') + "." + ext
}

/** Every SharedPreferences file that holds reading/listening state. `cloudsync` is deliberately NOT here: it holds the sync key. */
internal val BACKUP_PREF_GROUPS = listOf(
  "reading", "comic_progress", "pdf_progress", "image_folder_progress", "image_folder_books", "ghostreader_shelf_order"
)

/** Settings written by v1 backups lost their Float type in JSON; these keys are known floats. */
private val LEGACY_FLOAT_KEYS = setOf("fontSize", "r_spacing")

/** type: i = Int, l = Long, f = Float, b = Boolean, s = String. */
internal data class PrefValue(val type: Char, val value: Any)

internal interface PrefsStore {
  fun snapshot(group: String): Map<String, PrefValue>
  fun contains(group: String, key: String): Boolean
  /** Writes synchronously; returns false if the write failed. */
  fun putAll(group: String, values: Map<String, PrefValue>): Boolean
}

internal data class FileRecord(val kind: BackupKind, val entry: String, val name: String, val size: Long)

internal class BackupManifest(
  val version: Int,
  val createdAt: Long,
  val filesDir: String,
  val files: List<FileRecord>,
  val prefs: Map<String, Map<String, PrefValue>>
)

internal interface ManifestCodec {
  fun encode(manifest: BackupManifest): ByteArray
  fun decode(bytes: ByteArray): BackupManifest
}

internal class SourceFile(val kind: BackupKind, val file: File, val size: Long)

internal class Inventory(val files: List<SourceFile>, val skipped: List<String>) {
  val totalBytes: Long get() = files.sumOf { it.size }
  fun count(kind: BackupKind) = files.count { it.kind == kind }
}

internal class PackResult(val zip: File, val records: List<FileRecord>, val crcs: Map<String, Long>, val dataBytes: Long, val zipBytes: Long, val extraEntries: List<String> = emptyList())

internal class CopyResult(val bytes: Long, val verified: Boolean)

internal class ExportReport(
  val counts: Map<BackupKind, Int>,
  val dataBytes: Long,
  val zipBytes: Long,
  val destinationVerified: Boolean,
  val skipped: List<String>
) {
  /** null = not attempted. Otherwise "ok" or a short reason it failed. */
  var downloadsCopy: String? = null

  /** Set when only one library item was saved. */
  var itemName: String? = null

  fun summary(): String {
    itemName?.let { name ->
      val text = StringBuilder("Saved and checked \u201c$name\u201d (${zipBytes / 1_000_000} MB) with its progress and bookmarks. Bring it back any time with Load library.")
      if (!destinationVerified) text.append(" The file was written but this location could not be re-read to confirm it, so open it once to check the size.")
      return text.toString()
    }
    val parts = BackupKind.values().filter { !it.isCover }.mapNotNull { k -> counts[k]?.takeIf { it > 0 }?.let { "$it ${k.label}" } }
    val covers = (counts[BackupKind.COVERS] ?: 0) + (counts[BackupKind.MANUAL_COVERS] ?: 0)
    val text = StringBuilder("Backup saved and checked: ")
    text.append(if (parts.isEmpty()) "no library files" else parts.joinToString(", "))
    if (covers > 0) text.append(", $covers covers")
    text.append(", plus reading and listening progress (${zipBytes / 1_000_000} MB).")
    if (!destinationVerified) text.append(" The file was written but this location could not be re-read to confirm it, so open it once to check the size.")
    when {
      downloadsCopy == "ok" -> text.append(" A copy is also in Downloads.")
      downloadsCopy != null -> text.append(" Downloads copy not made (${downloadsCopy}).")
    }
    if (skipped.isNotEmpty()) text.append(" Left out ${skipped.size} unsupported item(s).")
    return text.toString()
  }
}

internal class KindCounts {
  var restored = 0
  var duplicates = 0
  var renamed = 0
  var keptExisting = 0
}

internal class RestoreReport(
  val counts: Map<BackupKind, KindCounts>,
  val prefsAdded: Int,
  val prefsKept: Int,
  val completenessVerified: Boolean,
  val warnings: List<String>
) {
  fun added(kind: BackupKind) = counts[kind]?.restored ?: 0

  fun summary(): String {
    val parts = BackupKind.values().filter { !it.isCover }.mapNotNull { k -> added(k).takeIf { it > 0 }?.let { "$it ${k.label}" } }
    val text = StringBuilder(if (parts.isEmpty()) "Nothing new to add: everything in the backup is already on this device." else "Restored " + parts.joinToString(", ") + ".")
    val dupes = counts.values.sumOf { it.duplicates }
    if (dupes > 0) text.append(" $dupes already here, left alone.")
    text.append(" Added $prefsAdded progress/setting entries")
    if (prefsKept > 0) text.append(", kept $prefsKept you already had")
    text.append(".")
    if (!completenessVerified) text.append(" This is an older backup, so its completeness could not be verified.")
    warnings.forEach { text.append(" ").append(it) }
    return text.toString()
  }
}

private class CountingOutputStream(private val out: OutputStream) : OutputStream() {
  var count = 0L
  override fun write(b: Int) { out.write(b); count++ }
  override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
  override fun flush() = out.flush()
  override fun close() = out.close()
}

private fun mb(bytes: Long) = bytes / 1_000_000

private fun isNoSpace(e: IOException) = e.message.orEmpty().let { it.contains("ENOSPC") || it.contains("No space left", ignoreCase = true) }

private fun crcOf(file: File): Long {
  val crc = CRC32()
  val buf = ByteArray(BUFFER)
  file.inputStream().use { input ->
    while (true) {
      val n = input.read(buf)
      if (n < 0) break
      crc.update(buf, 0, n)
    }
  }
  return crc.value
}

private fun validName(kind: BackupKind, name: String): Boolean =
  name.isNotEmpty() && name.length <= 200 && name == File(name).name && name != "." && name != ".." &&
    !name.contains('\\') && !name.contains('\u0000') &&
    name.substringAfterLast('.', "").lowercase() in kind.extensions &&
    (!kind.isCover || Regex("[A-Za-z0-9._-]+").matches(name))

internal object BackupEngine {
  private val running = AtomicBoolean(false)

  /** Only one backup or restore at a time, process-wide. A second tap (or a recreated Activity) gets a clear error instead of corrupting the first run. */
  fun <T> exclusive(block: () -> T): T {
    if (!running.compareAndSet(false, true)) {
      throw BackupException("busy", "Another backup or restore is already running. Wait for it to finish, then try again.")
    }
    try {
      return block()
    } finally {
      running.set(false)
    }
  }

  /** Removes temp files left behind by a crashed run. Only ever touches ghostreader-* entries inside the two staging folders. */
  fun cleanStale(root: File, trace: BackupTrace) {
    if (root.name != "backup-staging" && root.name != "restore-staging") return
    root.listFiles()?.filter { it.name.startsWith("ghostreader-") }?.forEach {
      val ok = if (it.isDirectory) it.deleteRecursively() else it.delete()
      trace.log("removed leftover ${it.name} (${if (ok) "ok" else "failed"})")
    }
  }

  /** First candidate folder that is writable and has enough free space. */
  fun chooseStaging(candidates: List<File>, needed: Long, trace: BackupTrace): File {
    val problems = mutableListOf<String>()
    for (dir in candidates) {
      try {
        dir.mkdirs()
        if (!dir.isDirectory) { problems += "${dir.path}: cannot create folder"; continue }
        val probe = File(dir, "ghostreader-probe-${System.nanoTime()}")
        FileOutputStream(probe).use { it.write(1) }
        probe.delete()
        cleanStale(dir, trace)
        val free = dir.usableSpace
        trace.log("staging candidate ${dir.path}: ${mb(free)} MB free, need ${mb(needed)} MB")
        if (free >= needed) return dir
        problems += "${dir.path}: only ${mb(free)} MB free"
      } catch (e: IOException) {
        problems += "${dir.path}: not writable (${e.message})"
      }
    }
    throw BackupException("preflight", "Not enough free storage to build the backup (needs about ${mb(needed)} MB). " + problems.joinToString("; "))
  }

  fun neededSpace(dataBytes: Long) = dataBytes + dataBytes / 50 + MARGIN_BYTES

  fun collect(filesDir: File, trace: BackupTrace): Inventory {
    val files = mutableListOf<SourceFile>()
    val skipped = mutableListOf<String>()
    for (kind in BackupKind.values()) {
      val listing = File(filesDir, kind.folder).listFiles()
      if (listing == null) {
        trace.log("${kind.folder}/ is missing or unreadable, nothing to back up there")
        continue
      }
      var count = 0
      var bytes = 0L
      for (f in listing.sortedBy { it.name }) {
        val ext = f.extension.lowercase()
        when {
          kind.isCover && (!f.isFile || ext !in kind.extensions) -> Unit
          f.isDirectory -> skipped += "${kind.folder}/${f.name} (folder)"
          !f.isFile -> skipped += "${kind.folder}/${f.name} (not a regular file)"
          ext !in kind.extensions -> skipped += "${kind.folder}/${f.name} (unsupported type)"
          f.length() <= 0L -> skipped += "${kind.folder}/${f.name} (empty file)"
          else -> { files += SourceFile(kind, f, f.length()); count++; bytes += f.length() }
        }
      }
      trace.log("${kind.folder}/: $count files, ${mb(bytes)} MB")
    }
    skipped.forEach { trace.log("skipping $it") }
    val inv = Inventory(files, skipped)
    if (inv.files.size > MAX_ENTRIES) throw BackupException("preflight", "Library has ${inv.files.size} files, more than the $MAX_ENTRIES a backup can hold.")
    if (inv.totalBytes > MAX_TOTAL_BYTES) throw BackupException("preflight", "Library is ${mb(inv.totalBytes) / 1000} GB, over the ${MAX_TOTAL_BYTES / (1024L * 1024 * 1024)} GB limit that restore accepts, so this backup could not be loaded back.")
    return inv
  }

  fun snapshotPrefs(store: PrefsStore): Map<String, Map<String, PrefValue>> = BACKUP_PREF_GROUPS.associateWith { store.snapshot(it) }

  /** Just the progress/bookmark entries that belong to one library file (same key shapes restore remaps). */
  fun snapshotPrefsFor(store: PrefsStore, kind: BackupKind, name: String): Map<String, Map<String, PrefValue>> {
    fun pick(group: String, match: (String) -> Boolean) = mapOf(group to store.snapshot(group).filterKeys(match))
    return when (kind) {
      BackupKind.BOOKS -> pick("reading") { it == name || it.startsWith(name + "_") || it.startsWith(name + "::") }
      BackupKind.AUDIO -> pick("reading") { it == "audio_$name" }
      BackupKind.COMICS -> pick("comic_progress") { it == name }
      BackupKind.PDFS -> pick("pdf_progress") { it == name }
      else -> emptyMap()
    }
  }

  /** An inventory holding exactly one library file (plus its chosen cover, if any), with the same checks a full backup applies. */
  fun singleItem(kind: BackupKind, file: File, manualCover: File?, trace: BackupTrace): Inventory {
    if (kind.isCover) throw BackupException("preflight", "Covers are saved together with their book.")
    if (!file.isFile || !file.canRead()) throw BackupException("preflight", "${file.name} is not available on this device any more.")
    if (file.length() <= 0L) throw BackupException("preflight", "${file.name} is empty, so there is nothing to save.")
    if (file.extension.lowercase() !in kind.extensions || !validName(kind, file.name)) throw BackupException("preflight", "${file.name} is not a file type GhostReader can save.")
    val files = mutableListOf(SourceFile(kind, file, file.length()))
    if (manualCover != null && manualCover.isFile && manualCover.length() > 0L && validName(BackupKind.MANUAL_COVERS, manualCover.name)) {
      files += SourceFile(BackupKind.MANUAL_COVERS, manualCover, manualCover.length())
    }
    trace.log("saving one item: ${file.name} (${file.length()} bytes)${if (files.size > 1) " + chosen cover" else ""}")
    return Inventory(files, emptyList())
  }

  /** Streams every file into a ZIP in [staging]. Nothing is held in memory beyond one buffer. */
  fun pack(
    inv: Inventory,
    prefs: Map<String, Map<String, PrefValue>>,
    filesDirPath: String,
    staging: File,
    codec: ManifestCodec,
    progress: (Int, String) -> Unit,
    trace: BackupTrace,
    fault: ((String) -> Unit)? = null
  ): PackResult {
    val next = HashMap<BackupKind, Int>()
    val records = inv.files.map { s ->
      val index = next[s.kind] ?: 0
      next[s.kind] = index + 1
      FileRecord(s.kind, s.kind.entryName(index), s.file.name, s.size)
    }
    val manifestBytes = codec.encode(BackupManifest(2, System.currentTimeMillis(), filesDirPath, records, prefs))
    val zip = File(staging, "ghostreader-export-${System.currentTimeMillis()}-${System.nanoTime() % 100000}.zip")
    trace.log("packing ${records.size} files (${mb(inv.totalBytes)} MB) into ${zip.path}")
    val crcs = HashMap<String, Long>()
    // GhostKernel's "GhostReader library" import pairs audio/NNNN.bin with audionames/NNNN.txt, so audiobooks keep writing that entry.
    val extraEntries = mutableListOf<String>()
    val total = inv.totalBytes.coerceAtLeast(1L)
    var done = 0L
    var success = false
    try {
      val fos = FileOutputStream(zip)
      val counting = CountingOutputStream(fos)
      fos.use {
        val out = ZipOutputStream(BufferedOutputStream(counting, BUFFER))
        var ok = false
        try {
          out.setLevel(Deflater.DEFAULT_COMPRESSION)
          out.putNextEntry(ZipEntry(MANIFEST_ENTRY))
          out.write(manifestBytes)
          out.closeEntry()
          // EPUB, CBZ, PDF, JPEG and audio are already compressed: store them, don't burn battery re-deflating.
          out.setLevel(Deflater.NO_COMPRESSION)
          val buf = ByteArray(BUFFER)
          for ((i, rec) in records.withIndex()) {
            fault?.invoke("pack:" + rec.entry)
            out.putNextEntry(ZipEntry(rec.entry))
            val crc = CRC32()
            var n = 0L
            inv.files[i].file.inputStream().use { input ->
              while (true) {
                val r = try { input.read(buf) } catch (e: IOException) {
                  throw BackupException("read", "Cannot read ${rec.name}: ${e.message}", e)
                }
                if (r < 0) break
                out.write(buf, 0, r)
                crc.update(buf, 0, r)
                n += r
                done += r
                progress((done * 60 / total).toInt().coerceIn(0, 60), "Saving ${rec.name}")
              }
            }
            out.closeEntry()
            if (n != rec.size) {
              throw BackupException("pack", "${rec.name} changed while it was being backed up (expected ${rec.size} bytes, read $n). Stop anything that is downloading into your library and try again.")
            }
            crcs[rec.entry] = crc.value
            if (rec.kind == BackupKind.AUDIO) {
              val nameEntry = "audionames/" + rec.entry.substringAfter('/').substringBefore('.') + ".txt"
              out.putNextEntry(ZipEntry(nameEntry))
              out.write(rec.name.toByteArray(Charsets.UTF_8))
              out.closeEntry()
              extraEntries += nameEntry
            }
            if (!rec.kind.isCover) trace.log("packed ${rec.entry} <- ${rec.name} (${rec.size} bytes)")
          }
          out.finish()
          out.flush()
          fos.fd.sync()
          out.close()
          ok = true
        } finally {
          if (!ok) runCatching { out.close() }
        }
      }
      if (!zip.exists()) {
        throw BackupException("pack", "The temporary backup file disappeared while it was being written (${zip.path}). Another backup was running, or Android cleared the cache because storage is low.")
      }
      val onDisk = zip.length()
      if (onDisk <= 0L || onDisk != counting.count) {
        throw BackupException("pack", "The temporary backup file has $onDisk bytes but ${counting.count} were written (${zip.path}). It was truncated while writing.")
      }
      trace.log("packed ${mb(onDisk)} MB ($onDisk bytes) OK")
      success = true
      return PackResult(zip, records, crcs, inv.totalBytes, onDisk, extraEntries)
    } catch (e: IOException) {
      if (isNoSpace(e)) throw BackupException("pack", "Ran out of storage while building the backup in ${staging.path}. Free up space and try again.", e)
      throw BackupException("pack", "Could not write the temporary backup file: ${e.message}", e)
    } finally {
      if (!success) zip.delete()
    }
  }

  /** Re-opens the finished ZIP and proves it contains exactly the expected files with exactly the source bytes. */
  fun validate(pack: PackResult, codec: ManifestCodec, progress: (Int, String) -> Unit, trace: BackupTrace) {
    val total = pack.dataBytes.coerceAtLeast(1L)
    var done = 0L
    try {
      ZipFile(pack.zip).use { zf ->
        val expected = pack.records.map { it.entry } + MANIFEST_ENTRY + pack.extraEntries
        val actual = zf.entries().asSequence().map { it.name }.toList()
        if (actual.size != expected.size || actual.toSet() != expected.toSet()) {
          throw BackupException("validate", "The archive has ${actual.size} entries but ${expected.size} were expected.")
        }
        val manifestEntry = zf.getEntry(MANIFEST_ENTRY) ?: throw BackupException("validate", "manifest.json is missing from the new archive.")
        val decoded = try {
          codec.decode(zf.getInputStream(manifestEntry).use { it.readBytes() })
        } catch (e: BackupException) {
          throw e
        } catch (e: Exception) {
          throw BackupException("validate", "manifest.json in the new archive cannot be read back: ${e.message}", e)
        }
        if (decoded.version != 2 || decoded.files != pack.records) {
          throw BackupException("validate", "manifest.json in the new archive does not match what was packed.")
        }
        val buf = ByteArray(BUFFER)
        for (rec in pack.records) {
          val entry = zf.getEntry(rec.entry) ?: throw BackupException("validate", "${rec.name} is missing from the new archive.")
          if (entry.size != rec.size) throw BackupException("validate", "${rec.name} is ${entry.size} bytes in the archive but ${rec.size} on disk.")
          if (entry.crc != pack.crcs[rec.entry]) throw BackupException("validate", "${rec.name}: archive checksum does not match the source file.")
          val crc = CRC32()
          var n = 0L
          zf.getInputStream(entry).use { input ->
            while (true) {
              val r = input.read(buf)
              if (r < 0) break
              crc.update(buf, 0, r)
              n += r
              done += r
              progress(60 + (done * 25 / total).toInt().coerceIn(0, 25), "Checking ${rec.name}")
            }
          }
          if (n != rec.size || crc.value != entry.crc) {
            throw BackupException("validate", "${rec.name}: data read back from the archive is corrupt (checksum mismatch).")
          }
        }
      }
    } catch (e: ZipException) {
      throw BackupException("validate", "The new archive is not a readable ZIP: ${e.message}", e)
    } catch (e: IOException) {
      throw BackupException("validate", "The new archive could not be read back: ${e.message}", e)
    }
    trace.log("validated ${pack.records.size} entries: names, sizes and checksums all match the source files")
  }

  /** Copies the validated ZIP to the user's chosen location, then reads it back to confirm it arrived whole. */
  fun copyToDestination(
    zip: File,
    openOut: () -> OutputStream?,
    openIn: () -> InputStream?,
    progress: (Int, String) -> Unit,
    trace: BackupTrace
  ): CopyResult {
    val length = zip.length()
    val crc = CRC32()
    var written = 0L
    val out = try {
      openOut()
    } catch (e: Exception) {
      throw BackupException("destination", "Cannot open the chosen location for writing: ${e.message}", e)
    } ?: throw BackupException("destination", "Cannot open the chosen location for writing.")
    try {
      out.use { o ->
        zip.inputStream().use { input ->
          val buf = ByteArray(BUFFER)
          while (true) {
            val r = input.read(buf)
            if (r < 0) break
            o.write(buf, 0, r)
            crc.update(buf, 0, r)
            written += r
            progress(85 + (written * 10 / length.coerceAtLeast(1L)).toInt().coerceIn(0, 10), "Copying to your chosen location")
          }
        }
        o.flush()
      }
    } catch (e: IOException) {
      if (isNoSpace(e)) throw BackupException("destination", "The chosen location ran out of space after $written of $length bytes.", e)
      throw BackupException("destination", "Writing to the chosen location failed after $written of $length bytes: ${e.message}", e)
    }
    if (written != length) throw BackupException("destination", "Only $written of $length bytes were written.")
    trace.log("copied $written bytes to destination")
    val verified = try {
      val input = openIn()
      if (input == null) {
        trace.log("destination cannot be re-read; skipping read-back check")
        false
      } else {
        val check = CRC32()
        var read = 0L
        input.use { i ->
          val buf = ByteArray(BUFFER)
          while (true) {
            val r = i.read(buf)
            if (r < 0) break
            check.update(buf, 0, r)
            read += r
            progress(95 + (read * 4 / length.coerceAtLeast(1L)).toInt().coerceIn(0, 4), "Verifying the saved file")
          }
        }
        if (read != length || check.value != crc.value) {
          throw BackupException("destination", "The file at the chosen location has $read of $length bytes or fails its checksum, so the backup was NOT saved correctly (a cloud provider can cut a file short). Try saving to a different location.")
        }
        trace.log("destination read back OK ($read bytes, checksum matches)")
        true
      }
    } catch (e: BackupException) {
      throw e
    } catch (e: Exception) {
      trace.log("read-back check unavailable: ${e.javaClass.simpleName}: ${e.message}")
      false
    }
    return CopyResult(written, verified)
  }

  private class Staged(val file: File, val size: Long, val crc: Long)
  private class Item(val kind: BackupKind, val name: String, val staged: Staged)

  private fun readLimited(zip: ZipInputStream, limit: Int, what: String): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
      val n = zip.read(buf)
      if (n < 0) break
      if (out.size() + n > limit) throw BackupException("read", "$what is too large to be a GhostReader backup.")
      out.write(buf, 0, n)
    }
    return out.toByteArray()
  }

  /**
   * Restores additively. Stage + validate everything first; only then add new files (rename, with rollback),
   * and finally add progress/settings that do not already exist. Existing data is never overwritten.
   */
  fun restore(
    openSource: () -> InputStream?,
    archiveSize: Long,
    filesDir: File,
    prefs: PrefsStore,
    codec: ManifestCodec,
    trace: BackupTrace,
    fault: ((String) -> Unit)? = null
  ): RestoreReport {
    val root = File(filesDir, "restore-staging").apply { mkdirs() }
    cleanStale(root, trace)
    if (archiveSize > 0) {
      val need = archiveSize + archiveSize / 50 + MARGIN_BYTES
      val free = root.usableSpace
      trace.log("restore needs about ${mb(need)} MB, ${mb(free)} MB free")
      if (free < need) throw BackupException("preflight", "Not enough free storage to restore: need about ${mb(need)} MB, only ${mb(free)} MB free. Nothing was changed.")
    }
    val stage = File(root, "ghostreader-restore-${System.nanoTime()}")
    if (!stage.mkdirs()) throw BackupException("preflight", "Cannot create a temporary folder in ${root.path}. Nothing was changed.")
    try {
      // ---- 1. Stream the archive into the staging folder. Nothing in the library is touched yet. ----
      val staged = LinkedHashMap<String, Staged>()
      val legacyNames = HashMap<String, String>()
      var manifestBytes: ByteArray? = null
      var total = 0L
      var seq = 0
      val source = try {
        openSource()
      } catch (e: Exception) {
        throw BackupException("open", "Cannot open the backup file: ${e.message}", e)
      } ?: throw BackupException("open", "Cannot open the backup file.")
      val legacyNameRegex = Regex("^(names|audionames)/[0-9]{4}\\.txt$")
      try {
        source.use { raw ->
          ZipInputStream(raw.buffered(BUFFER)).use { zip ->
            val buf = ByteArray(BUFFER)
            while (true) {
              val entry = zip.nextEntry ?: break
              val name = entry.name
              if (entry.isDirectory) { zip.closeEntry(); continue }
              when {
                name == MANIFEST_ENTRY -> {
                  if (manifestBytes != null) throw BackupException("validate", "The backup contains two manifests. Nothing was changed.")
                  manifestBytes = readLimited(zip, MANIFEST_LIMIT, "manifest.json")
                }
                BackupKind.values().any { it.entryRegex.matches(name) } -> {
                  if (staged.containsKey(name)) throw BackupException("validate", "The backup lists $name twice. Nothing was changed.")
                  if (staged.size >= MAX_ENTRIES) throw BackupException("validate", "The backup holds too many files. Nothing was changed.")
                  val file = File(stage, "${seq++}.dat")
                  val crc = CRC32()
                  var size = 0L
                  file.outputStream().use { output ->
                    while (true) {
                      val n = zip.read(buf)
                      if (n < 0) break
                      total += n
                      if (total > MAX_TOTAL_BYTES) throw BackupException("validate", "The backup is larger than the ${MAX_TOTAL_BYTES / (1024L * 1024 * 1024)} GB limit. Nothing was changed.")
                      output.write(buf, 0, n)
                      crc.update(buf, 0, n)
                      size += n
                    }
                  }
                  staged[name] = Staged(file, size, crc.value)
                }
                legacyNameRegex.matches(name) -> legacyNames[name] = String(readLimited(zip, 1024, name), Charsets.UTF_8)
                else -> trace.log("ignoring unrecognised entry in backup")
              }
              zip.closeEntry()
            }
          }
        }
      } catch (e: EOFException) {
        throw BackupException("read", "The backup file is incomplete: it ends early (${e.message}). Nothing was changed.", e)
      } catch (e: ZipException) {
        throw BackupException("read", "The backup file is damaged (${e.message}). Nothing was changed.", e)
      } catch (e: IOException) {
        if (isNoSpace(e)) throw BackupException("read", "Ran out of storage while unpacking the backup. Nothing was changed.", e)
        throw BackupException("read", "Could not read the backup file: ${e.message}. Nothing was changed.", e)
      }
      trace.log("staged ${staged.size} files (${mb(total)} MB) for validation")

      // ---- 2. Validate manifest, completeness and contents. Still nothing touched. ----
      val manifest = try {
        codec.decode(manifestBytes ?: throw BackupException("validate", "This is not a GhostReader backup (manifest.json is missing). Nothing was changed."))
      } catch (e: BackupException) {
        throw e
      } catch (e: Exception) {
        throw BackupException("validate", "The backup's manifest cannot be read (${e.message}). Nothing was changed.", e)
      }
      val items = ArrayList<Item>()
      val verified: Boolean
      if (manifest.version >= 2) {
        verified = true
        val listed = HashSet<String>()
        for (rec in manifest.files) {
          if (!rec.kind.entryRegex.matches(rec.entry) || !listed.add(rec.entry)) {
            throw BackupException("validate", "The backup's file list is invalid (${rec.entry}). Nothing was changed.")
          }
          val s = staged[rec.entry] ?: throw BackupException("validate", "The backup is incomplete: ${rec.name} is listed but missing from the file, so it was probably cut off while saving. Nothing was changed.")
          if (s.size != rec.size) throw BackupException("validate", "The backup is incomplete: ${rec.name} is ${s.size} bytes but should be ${rec.size}. Nothing was changed.")
          items += Item(rec.kind, rec.name, s)
        }
        if (staged.keys.any { it !in listed }) throw BackupException("validate", "The backup contains files that are not in its file list. Nothing was changed.")
      } else {
        verified = false
        for (kind in listOf(BackupKind.BOOKS, BackupKind.AUDIO)) {
          for ((entry, s) in staged) {
            if (!kind.entryRegex.matches(entry)) continue
            val id = entry.substringAfter('/').substringBefore('.')
            val name = legacyNames[kind.v1NameDir + "/" + id + ".txt"]
              ?: throw BackupException("validate", "The backup is missing the filename for $entry. Nothing was changed.")
            items += Item(kind, name, s)
          }
        }
      }
      for (item in items) {
        if (!validName(item.kind, item.name)) throw BackupException("validate", "The backup contains an unsafe or invalid filename (${item.name.take(60)}). Nothing was changed.")
        checkContent(item)
      }
      trace.log("validated ${items.size} files, manifest v${manifest.version}, completeness ${if (verified) "verified" else "not verifiable (old format)"}")

      // ---- 3. Commit: add new files by rename, rolling back what this run added if anything fails. ----
      val counts = BackupKind.values().associateWith { KindCounts() }
      val renames = BackupKind.values().associateWith { mutableMapOf<String, String>() }
      val created = mutableListOf<File>()
      try {
        for (item in items) {
          val dir = File(filesDir, item.kind.folder).apply { mkdirs() }
          val c = counts.getValue(item.kind)
          var target = File(dir, item.name)
          if (target.exists()) {
            if (sameContent(target, item.staged)) {
              c.duplicates++
              continue
            }
            if (item.kind.isCover) { c.keptExisting++; continue }
            // A different file already uses this name: keep it, and add ours as "<name>_restored_N".
            // If an earlier restore already added this exact file under such a name, reuse it instead of adding another copy.
            val base = item.name.substringBeforeLast('.')
            val ext = item.name.substringAfterLast('.')
            var n = 1
            var earlier: File? = null
            while (true) {
              val candidate = File(dir, "${base}_restored_$n.$ext")
              if (!candidate.exists()) { target = candidate; break }
              if (sameContent(candidate, item.staged)) { earlier = candidate; break }
              n++
            }
            renames.getValue(item.kind)[item.name] = (earlier ?: target).name
            if (earlier != null) { c.duplicates++; continue }
            c.renamed++
          }
          fault?.invoke("commit:" + item.name)
          place(item.staged.file, target)
          created += target
          c.restored++
        }
      } catch (e: Exception) {
        created.forEach { it.delete() }
        trace.log("commit failed, rolled back ${created.size} added file(s): ${e.message}")
        if (e is BackupException) throw e
        throw BackupException("commit", "Could not add a file to your library (${e.message}). Everything this restore added was removed; your library is unchanged.", e)
      }
      trace.log("added ${created.size} file(s) to the library")

      // ---- 4. Progress, bookmarks and settings: add only keys that do not exist yet. ----
      var added = 0
      var kept = 0
      val warnings = mutableListOf<String>()
      for ((group, values) in manifest.prefs) {
        if (group !in BACKUP_PREF_GROUPS) { trace.log("ignoring unknown settings group in backup"); continue }
        val toAdd = LinkedHashMap<String, PrefValue>()
        for ((key, original) in values) {
          val mapped = remapKey(group, key, renames)
          var value = original
          if (manifest.version < 2 && group == "reading" && value.type == 'i' && mapped in LEGACY_FLOAT_KEYS) {
            value = PrefValue('f', (value.value as Int).toFloat())
          }
          if (prefs.contains(group, mapped) || toAdd.containsKey(mapped)) kept++ else toAdd[mapped] = value
        }
        if (toAdd.isNotEmpty()) {
          if (prefs.putAll(group, toAdd)) added += toAdd.size else warnings += "Some progress or settings could not be saved ($group)."
        }
      }
      trace.log("progress/settings: added $added, kept $kept existing")
      return RestoreReport(counts, added, kept, verified, warnings)
    } finally {
      stage.deleteRecursively()
    }
  }

  private fun remapKey(group: String, key: String, renames: Map<BackupKind, Map<String, String>>): String {
    when (group) {
      "reading" -> {
        if (key.startsWith("audio_")) renames.getValue(BackupKind.AUDIO)[key.removePrefix("audio_")]?.let { return "audio_$it" }
        for ((orig, renamed) in renames.getValue(BackupKind.BOOKS).entries.sortedByDescending { it.key.length }) {
          // Book keys look like "<file>", "<file>_off", "<file>_bm", "<file>_page" and "<file>::<section>".
          if (key == orig || key.startsWith(orig + "_") || key.startsWith(orig + "::")) return renamed + key.substring(orig.length)
        }
      }
      "comic_progress" -> renames.getValue(BackupKind.COMICS)[key]?.let { return it }
      "pdf_progress" -> renames.getValue(BackupKind.PDFS)[key]?.let { return it }
    }
    return key
  }

  private fun sameContent(existing: File, staged: Staged) =
    existing.isFile && existing.length() == staged.size && crcOf(existing) == staged.crc

  private fun checkContent(item: Item) {
    fun bad(why: String): Nothing = throw BackupException("validate", "${item.name} in the backup $why. Nothing was changed.")
    val file = item.staged.file
    try {
      when (item.kind) {
        BackupKind.BOOKS -> ZipFile(file).use { if (it.getEntry("META-INF/container.xml") == null) bad("is not a valid EPUB") }
        BackupKind.COMICS -> ZipFile(file).use { zf -> if (zf.entries().asSequence().none { !it.isDirectory }) bad("has no pages") }
        BackupKind.PDFS -> file.inputStream().use { i -> val h = ByteArray(5); if (i.read(h) < 5 || String(h, Charsets.ISO_8859_1) != "%PDF-") bad("is not a valid PDF") }
        BackupKind.COVERS, BackupKind.MANUAL_COVERS -> file.inputStream().use { i -> if (i.read() != 0xFF || i.read() != 0xD8) bad("is not a valid image") }
        BackupKind.AUDIO -> if (item.staged.size <= 0L) bad("is empty")
      }
    } catch (e: ZipException) {
      bad("is damaged (${e.message})")
    }
  }

  private fun place(staged: File, target: File) {
    if (target.exists()) throw IOException("${target.name} already exists")
    if (staged.renameTo(target)) return
    val tmp = File(target.parentFile, target.name + ".part")
    try {
      staged.inputStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o, BUFFER) } }
      if (tmp.length() != staged.length()) throw IOException("short copy of ${target.name}")
      if (!tmp.renameTo(target)) throw IOException("could not move ${target.name} into place")
    } finally {
      tmp.delete()
    }
  }
}
