package com.korndog.ghostreader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Portable, user-owned GhostReader backup. Never contains signing credentials or the Cloudflare sync key.
 *
 * This is the Android layer: Context, Uri, SharedPreferences and JSON. All file/ZIP/validation logic lives in
 * BackupEngine so it can be tested on a plain JVM. Format v2 lists every file in manifest.json (so a cut-off
 * archive is detected on restore); v1 backups (books + audiobooks + reading prefs) still restore.
 */
internal object LibraryBackup {
  private const val TAG = "GhostBackup"

  /** Packs the whole library into a ZIP, validates it, saves it to [destination], and checks it arrived intact. */
  fun export(context: Context, destination: Uri, progress: (Int, String) -> Unit = { _, _ -> }): ExportReport {
    val trace = BackupTrace { Log.i(TAG, it) }
    return guarded("backup", trace) { BackupEngine.exclusive { doExport(context, destination, progress, trace) } }
  }

  /** Saves ONE library file with its progress and bookmarks, using the same verified pipeline. Load it back with [restore]. */
  fun exportOne(context: Context, kind: BackupKind, file: File, manualCover: File?, itemName: String, destination: Uri, progress: (Int, String) -> Unit = { _, _ -> }): ExportReport {
    val trace = BackupTrace { Log.i(TAG, it) }
    return guarded("save item", trace) {
      BackupEngine.exclusive {
        val inv = BackupEngine.singleItem(kind, file, manualCover, trace)
        val prefs = BackupEngine.snapshotPrefsFor(SharedPrefsStore(context), kind, file.name)
        runExport(context, inv, prefs, destination, progress, trace, makeDownloadsCopy = false, itemName = itemName)
      }
    }
  }

  /** Adds everything in the backup that is not already on this device. Never overwrites or deletes existing data. */
  fun restore(context: Context, source: Uri): RestoreReport {
    val trace = BackupTrace { Log.i(TAG, it) }
    return guarded("restore", trace) {
      BackupEngine.exclusive {
        val size = runCatching {
          context.contentResolver.query(source, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
          }
        }.getOrNull() ?: -1L
        trace.log("restore from archive of ${if (size >= 0) "$size bytes" else "unknown size"}")
        BackupEngine.restore({ context.contentResolver.openInputStream(source) }, size, context.filesDir, SharedPrefsStore(context), JsonManifestCodec, trace)
      }
    }
  }

  private fun <T> guarded(what: String, trace: BackupTrace, block: () -> T): T {
    try {
      return block()
    } catch (e: BackupException) {
      e.details = trace.tail()
      Log.e(TAG, "$what failed: ${e.message}\n${e.details}", e)
      throw e
    } catch (e: Exception) {
      val wrapped = BackupException("unexpected", "${e.javaClass.simpleName}: ${e.message}", e)
      wrapped.details = trace.tail()
      Log.e(TAG, "$what failed unexpectedly: ${wrapped.message}\n${wrapped.details}", e)
      throw wrapped
    }
  }

  private fun doExport(context: Context, destination: Uri, progress: (Int, String) -> Unit, trace: BackupTrace): ExportReport {
    val filesDir = context.filesDir
    progress(0, "Scanning your library")
    val inv = BackupEngine.collect(filesDir, trace)
    if (inv.files.isEmpty()) {
      throw BackupException("preflight", "Nothing to back up: no books, audiobooks, comics or PDFs were found in the app's library folder (${filesDir.path}).")
    }
    val prefs = BackupEngine.snapshotPrefs(SharedPrefsStore(context))
    return runExport(context, inv, prefs, destination, progress, trace, makeDownloadsCopy = true, itemName = null)
  }

  private fun runExport(
    context: Context, inv: Inventory, prefs: Map<String, Map<String, PrefValue>>, destination: Uri,
    progress: (Int, String) -> Unit, trace: BackupTrace, makeDownloadsCopy: Boolean, itemName: String?
  ): ExportReport {
    val filesDir = context.filesDir
    trace.log("exporting: ${inv.files.size} files, ${inv.totalBytes} bytes; ${prefs.values.sumOf { it.size }} progress/setting entries")
    // Staging lives in app storage that Android does not clear on its own (NOT cacheDir), after a free-space check.
    val candidates = listOfNotNull(
      context.getExternalFilesDir(null)?.let { File(it, "backup-staging") },
      File(filesDir, "backup-staging")
    )
    val staging = BackupEngine.chooseStaging(candidates, BackupEngine.neededSpace(inv.totalBytes), trace)
    var zip: File? = null
    try {
      val pack = BackupEngine.pack(inv, prefs, filesDir.absolutePath, staging, JsonManifestCodec, progress, trace)
      zip = pack.zip
      BackupEngine.validate(pack, JsonManifestCodec, progress, trace)
      val resolver = context.contentResolver
      val copy = BackupEngine.copyToDestination(pack.zip, { resolver.openOutputStream(destination, "wt") }, { resolver.openInputStream(destination) }, progress, trace)
      val report = ExportReport(BackupKind.values().associateWith { inv.count(it) }, pack.dataBytes, pack.zipBytes, copy.verified, inv.skipped)
      report.itemName = itemName
      if (makeDownloadsCopy) {
        progress(99, "Saving a copy to Downloads")
        report.downloadsCopy = copyToDownloads(context, pack.zip, trace)
      }
      progress(100, "Backup saved")
      trace.log("backup complete: ${report.summary()}")
      return report
    } finally {
      zip?.delete()
    }
  }

  /** Optional second copy in Downloads. Its failure never fails the backup, but it is reported honestly. */
  private fun copyToDownloads(context: Context, zip: File, trace: BackupTrace): String {
    if (android.os.Build.VERSION.SDK_INT < 29) return "needs Android 10 or newer"
    val resolver = context.contentResolver
    var row: Uri? = null
    try {
      val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, "GhostReader-Library-${System.currentTimeMillis() / 1000}.zip")
        put(MediaStore.Downloads.MIME_TYPE, "application/zip")
      }
      row = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
      if (row == null) return "Downloads would not accept the file"
      val out = resolver.openOutputStream(row) ?: return "could not open the Downloads file"
      val copied = out.use { o -> zip.inputStream().use { it.copyTo(o, 256 * 1024) } }
      if (copied != zip.length()) throw java.io.IOException("copied $copied of ${zip.length()} bytes")
      return "ok"
    } catch (e: Exception) {
      trace.log("Downloads copy failed: ${e.javaClass.simpleName}: ${e.message}")
      row?.let { runCatching { resolver.delete(it, null, null) } }
      return (e.message ?: e.javaClass.simpleName).take(80)
    }
  }

  private class SharedPrefsStore(private val context: Context) : PrefsStore {
    private fun prefs(group: String) = context.getSharedPreferences(group, Context.MODE_PRIVATE)

    override fun snapshot(group: String): Map<String, PrefValue> {
      val out = LinkedHashMap<String, PrefValue>()
      for ((key, value) in prefs(group).all) {
        when (value) {
          is Int -> out[key] = PrefValue('i', value)
          is Long -> out[key] = PrefValue('l', value)
          is Float -> out[key] = PrefValue('f', value)
          is Boolean -> out[key] = PrefValue('b', value)
          is String -> out[key] = PrefValue('s', value)
        }
      }
      return out
    }

    override fun contains(group: String, key: String) = prefs(group).contains(key)

    override fun putAll(group: String, values: Map<String, PrefValue>): Boolean {
      val editor = prefs(group).edit()
      for ((key, v) in values) {
        when (v.type) {
          'i' -> editor.putInt(key, v.value as Int)
          'l' -> editor.putLong(key, v.value as Long)
          'f' -> editor.putFloat(key, v.value as Float)
          'b' -> editor.putBoolean(key, v.value as Boolean)
          's' -> editor.putString(key, v.value as String)
        }
      }
      return editor.commit()
    }
  }

  /** manifest.json. Every preference carries a type tag so Floats/Longs come back as the same type. */
  private object JsonManifestCodec : ManifestCodec {
    override fun encode(manifest: BackupManifest): ByteArray {
      val files = JSONArray()
      manifest.files.forEach { f ->
        files.put(JSONObject().put("kind", f.kind.name).put("entry", f.entry).put("name", f.name).put("size", f.size))
      }
      val prefs = JSONObject()
      manifest.prefs.forEach { (group, values) ->
        val g = JSONObject()
        values.forEach { (key, v) ->
          val raw: Any = if (v.type == 'f') (v.value as Float).toDouble() else v.value
          g.put(key, JSONObject().put("t", v.type.toString()).put("v", raw))
        }
        prefs.put(group, g)
      }
      val root = JSONObject()
        .put("format", "ghostreader-library")
        .put("version", manifest.version)
        .put("createdAt", manifest.createdAt)
        .put("filesDir", manifest.filesDir)
        .put("files", files)
        .put("prefs", prefs)
      return root.toString().toByteArray(Charsets.UTF_8)
    }

    override fun decode(bytes: ByteArray): BackupManifest {
      val root = JSONObject(String(bytes, Charsets.UTF_8))
      if (root.optString("format") != "ghostreader-library") throw BackupException("validate", "This is not a GhostReader backup. Nothing was changed.")
      val version = root.optInt("version")
      if (version > 2) throw BackupException("validate", "This backup was made by a newer GhostReader (format $version). Update the app, then try again. Nothing was changed.")
      if (version < 1) throw BackupException("validate", "Unsupported backup format. Nothing was changed.")
      val prefs = LinkedHashMap<String, Map<String, PrefValue>>()
      val files = ArrayList<FileRecord>()
      if (version == 1) {
        // v1: one untyped "settings" object holding the "reading" prefs.
        val settings = root.optJSONObject("settings") ?: throw BackupException("validate", "The backup has no reading settings. Nothing was changed.")
        val values = LinkedHashMap<String, PrefValue>()
        val keys = settings.keys()
        while (keys.hasNext()) {
          val key = keys.next()
          when (val v = settings.get(key)) {
            is Int -> values[key] = PrefValue('i', v)
            is Long -> values[key] = PrefValue('l', v)
            is Double -> values[key] = PrefValue('f', v.toFloat())
            is Boolean -> values[key] = PrefValue('b', v)
            is String -> values[key] = PrefValue('s', v)
          }
        }
        prefs["reading"] = values
      } else {
        val list = root.optJSONArray("files") ?: throw BackupException("validate", "The backup has no file list. Nothing was changed.")
        for (i in 0 until list.length()) {
          val o = list.getJSONObject(i)
          val kind = BackupKind.values().firstOrNull { it.name == o.optString("kind") }
            ?: throw BackupException("validate", "The backup holds an item type this version does not know. Nothing was changed.")
          files += FileRecord(kind, o.getString("entry"), o.getString("name"), o.getLong("size"))
        }
        val groups = root.optJSONObject("prefs")
        if (groups != null) {
          val names = groups.keys()
          while (names.hasNext()) {
            val group = names.next()
            val g = groups.getJSONObject(group)
            val values = LinkedHashMap<String, PrefValue>()
            val keys = g.keys()
            while (keys.hasNext()) {
              val key = keys.next()
              val o = g.getJSONObject(key)
              values[key] = when (o.getString("t")) {
                "i" -> PrefValue('i', o.getInt("v"))
                "l" -> PrefValue('l', o.getLong("v"))
                "f" -> PrefValue('f', o.getDouble("v").toFloat())
                "b" -> PrefValue('b', o.getBoolean("v"))
                "s" -> PrefValue('s', o.getString("v"))
                else -> throw BackupException("validate", "The backup holds a setting type this version does not know. Nothing was changed.")
              }
            }
            prefs[group] = values
          }
        }
      }
      return BackupManifest(version, root.optLong("createdAt"), root.optString("filesDir"), files, prefs)
    }
  }
}
