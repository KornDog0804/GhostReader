package com.korndog.ghostreader

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable, user-owned GhostReader backup. Never contains signing credentials. */
internal object LibraryBackup {
 private const val LIMIT = 12L * 1024 * 1024 * 1024
 private const val MAX_BOOKS = 2000

 fun export(context: Context, destination: Uri): Int {
  val shelf = File(context.filesDir, "books")
  val books = shelf.listFiles()?.filter { it.isFile && it.extension.equals("epub", true) } ?: emptyList()
  val audio = File(context.filesDir, "audio").listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("m4b", "mp3", "m4a") } ?: emptyList()
  val prefs = context.getSharedPreferences("reading", Context.MODE_PRIVATE)
  val settings = JSONObject()
  prefs.all.forEach { (key, value) ->
   when (value) {
    is Int, is Long, is Float, is Boolean, is String -> settings.put(key, value)
   }
  }
  val manifest = JSONObject().put("format", "ghostreader-library").put("version", 1)
   .put("settings", settings)
  val stream = context.contentResolver.openOutputStream(destination, "w") ?: error("Cannot write backup")
  stream.use { output ->
   ZipOutputStream(output.buffered()).use { zip ->
    zip.putNextEntry(ZipEntry("manifest.json"))
    zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
    zip.closeEntry()
    audio.forEachIndexed { index, file ->
     val id = index.toString().padStart(4, '0')
     zip.putNextEntry(ZipEntry("audio/$id.bin"))
     file.inputStream().use { it.copyTo(zip) }
     zip.closeEntry()
     zip.putNextEntry(ZipEntry("audionames/$id.txt"))
     zip.write(file.name.toByteArray(Charsets.UTF_8))
     zip.closeEntry()
    }
    books.forEachIndexed { index, book ->
     zip.putNextEntry(ZipEntry("books/${index.toString().padStart(4, '0')}.epub"))
     book.inputStream().use { it.copyTo(zip) }
     zip.closeEntry()
     zip.putNextEntry(ZipEntry("names/${index.toString().padStart(4, '0')}.txt"))
     zip.write(book.name.toByteArray(Charsets.UTF_8))
     zip.closeEntry()
    }
   }
  }
  return books.size
 }

 fun restore(context: Context, source: Uri): Int {
  val stage = File(context.cacheDir, "ghostreader-restore-${System.nanoTime()}").apply { mkdirs() }
  try {
   var manifest: JSONObject? = null
   val names = mutableMapOf<String, String>()
   val audioNames = mutableMapOf<String, String>()
   val audioFiles = mutableMapOf<String, File>()
   val books = mutableMapOf<String, File>()
   var total = 0L
   val input = context.contentResolver.openInputStream(source) ?: error("Cannot open backup")
   input.use { stream ->
    ZipInputStream(stream.buffered()).use { zip ->
     while (true) {
      val entry = zip.nextEntry ?: break
      if (entry.isDirectory) { zip.closeEntry(); continue }
      val path = entry.name
      if (path == "manifest.json") {
       val bytes = zip.readBytesLimited(1_000_000)
       manifest = JSONObject(String(bytes, Charsets.UTF_8))
      } else if (Regex("books/[0-9]{4}\\.epub").matches(path)) {
       if (books.size >= MAX_BOOKS) error("Too many books in backup")
       val target = File(stage, path.substringAfter('/'))
       target.outputStream().use { output ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
         val count = zip.read(buffer)
         if (count < 0) break
         total += count
         if (total > LIMIT) error("Backup is too large")
         output.write(buffer, 0, count)
        }
       }
       books[path.substringAfter('/').substringBefore('.')] = target
      } else if (Regex("names/[0-9]{4}\\.txt").matches(path)) {
       names[path.substringAfter('/').substringBefore('.')] = String(zip.readBytesLimited(1024), Charsets.UTF_8)
      }
      zip.closeEntry()
     }
    }
   }
   val meta = manifest ?: error("Not a GhostReader backup")
   if (meta.optString("format") != "ghostreader-library" || meta.optInt("version") != 1) error("Unsupported backup format")
   val prefs = meta.optJSONObject("settings") ?: error("Missing reading settings")
   val shelf = File(context.filesDir, "books").apply { mkdirs() }
   // Validate all books before modifying the user's existing shelf.
   books.values.forEach { book ->
    java.util.zip.ZipFile(book).use { epub ->
     if (epub.getEntry("META-INF/container.xml") == null) error("Backup contains an invalid EPUB")
    }
   }
   val restoredNames = mutableMapOf<String, String>()
   val audioDir = File(context.filesDir, "audio").apply { mkdirs() }
   audioFiles.forEach { (id, file) ->
    val original = audioNames[id] ?: error("Missing audiobook filename")
    if (original != File(original).name || original.substringAfterLast('.', "").lowercase() !in setOf("m4b", "mp3", "m4a")) error("Invalid audiobook filename")
    val target = File(audioDir, original)
    if (!target.exists()) file.copyTo(target)
   }
   books.forEach { (id, file) ->
    val original = names[id] ?: error("Missing book filename")
    if (original != File(original).name || !original.endsWith(".epub", true)) error("Invalid book filename")
    val target = File(shelf, original)
    // Preserve existing books. Identical filenames get a new name, with matching progress keys.
    val finalTarget = if (!target.exists()) target else {
     var counter = 1
     var candidate: File
     do {
      candidate = File(shelf, original.removeSuffix(".epub") + "_restored_${counter}.epub")
      counter++
     } while (candidate.exists())
     candidate
    }
    file.copyTo(finalTarget)
    restoredNames[original] = finalTarget.name
   }
   val editor = context.getSharedPreferences("reading", Context.MODE_PRIVATE).edit()
   val iterator = prefs.keys()
   while (iterator.hasNext()) {
    val key = iterator.next()
    val value = prefs.get(key)
    val mappedKey = restoredNames.entries.firstOrNull { key == it.key || key.startsWith(it.key + "_") }?.let {
     it.value + key.removePrefix(it.key)
    } ?: key
    when (value) {
     is Int -> editor.putInt(mappedKey, value)
     is Long -> editor.putLong(mappedKey, value)
     is Double -> editor.putFloat(mappedKey, value.toFloat())
     is Boolean -> editor.putBoolean(mappedKey, value)
     is String -> editor.putString(mappedKey, value)
    }
   }
   editor.apply()
   return books.size
  } finally { stage.deleteRecursively() }
 }

 private fun ZipInputStream.readBytesLimited(limit: Int): ByteArray {
  val output = java.io.ByteArrayOutputStream()
  val buffer = ByteArray(4096)
  while (true) {
   val n = read(buffer)
   if (n < 0) break
   if (output.size() + n > limit) error("Backup metadata too large")
   output.write(buffer, 0, n)
  }
  return output.toByteArray()
 }
}
