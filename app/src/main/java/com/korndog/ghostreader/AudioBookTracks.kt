package com.korndog.ghostreader

import java.io.File

/**
 * Groups a completed multi-track audiobook as one library item.
 *
 * Only direct children of an audiobook folder are considered. The existing
 * single-file shelf continues to work unchanged.
 */
internal object AudioBookTracks {
 private val extensions = setOf("mp3", "m4a", "m4b")

 fun tracks(entry: File): List<File> {
  if (entry.isFile) return listOfNotNull(entry.takeIf(::isAudio))
  if (!entry.isDirectory) return emptyList()
  return entry.listFiles().orEmpty()
   .filter { it.isFile && isAudio(it) }
   .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
 }

 fun scan(shelf: File): List<File> =
  shelf.listFiles().orEmpty()
   .filter { (it.isFile && isAudio(it)) || (it.isDirectory && tracks(it).isNotEmpty()) }
   .sortedByDescending { it.lastModified() }

 private fun isAudio(file: File) = file.extension.lowercase() in extensions
}
