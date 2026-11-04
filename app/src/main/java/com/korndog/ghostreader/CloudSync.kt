package com.korndog.ghostreader

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Two-way sync of ebooks, PDFs and CBZ comics through the user's own Cloudflare Worker (R2),
 * using the same format as the GhostReader website. Audiobooks stay on each device.
 * Nothing local is ever deleted or renamed; reading progress is not synced from here.
 */
internal object CloudSync {
    private const val MAX_FILE = 95L * 1024 * 1024

    fun url(context: Context) = context.getSharedPreferences("cloudsync", 0).getString("url", "") ?: ""
    fun key(context: Context) = context.getSharedPreferences("cloudsync", 0).getString("key", "") ?: ""
    fun save(context: Context, url: String, key: String) {
        context.getSharedPreferences("cloudsync", 0).edit().putString("url", url.trim().trimEnd('/')).putString("key", key.trim()).apply()
    }
    fun lastSync(context: Context) = context.getSharedPreferences("cloudsync", 0).getLong("last", 0L)

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private class Item(val file: File, val name: String, val format: String?, val hash: String)

    private fun cleanName(file: File): String {
        val n = file.name
        val i = n.indexOf('_')
        return if (i > 0 && n.substring(0, i).all { it.isDigit() }) n.substring(i + 1) else n
    }

    private fun localItems(context: Context): List<Item> {
        val out = mutableListOf<Item>()
        fun add(dir: String, ext: String, format: String?) {
            File(context.filesDir, dir).listFiles()?.filter { it.isFile && it.extension.equals(ext, true) }?.forEach {
                val name = cleanName(it)
                out += Item(it, name, format, sha1("books|" + name + "|" + it.length()))
            }
        }
        add("books", "epub", null)
        add("pdfs", "pdf", "pdf")
        add("comics", "cbz", "cbz")
        return out
    }

    private class Remote(val base: String, val token: String) {
        private fun open(path: String, method: String): HttpURLConnection =
            (URL(base + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = 120000
                setRequestProperty("Authorization", "Bearer $token")
            }
        private fun check(c: HttpURLConnection) {
            if (c.responseCode == 401) throw IllegalStateException("Wrong sync key")
        }
        fun ping() {
            val c = open("/ping", "GET"); check(c)
            if (c.responseCode !in 200..299) throw IllegalStateException("Sync server said " + c.responseCode)
        }
        fun list(prefix: String): List<String> {
            val c = open("/list?prefix=" + URLEncoder.encode(prefix, "UTF-8"), "GET"); check(c)
            if (c.responseCode !in 200..299) throw IllegalStateException("List failed (" + c.responseCode + ")")
            val arr = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("keys")
            return (0 until arr.length()).map { arr.getString(it) }
        }
        fun getText(key: String): String? {
            val c = open("/o/" + URLEncoder.encode(key, "UTF-8"), "GET"); check(c)
            return if (c.responseCode in 200..299) c.inputStream.bufferedReader().readText() else null
        }
        fun getFile(key: String, target: File) {
            val c = open("/o/" + URLEncoder.encode(key, "UTF-8"), "GET"); check(c)
            if (c.responseCode !in 200..299) throw IllegalStateException("Download failed (" + c.responseCode + ")")
            c.inputStream.use { i -> target.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
        }
        fun putBytes(key: String, bytes: ByteArray, type: String) {
            val c = open("/o/" + URLEncoder.encode(key, "UTF-8"), "PUT")
            c.doOutput = true
            c.setRequestProperty("Content-Type", type)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            check(c)
            if (c.responseCode !in 200..299) throw IllegalStateException("Upload failed (" + c.responseCode + ")")
        }
        fun putFile(key: String, file: File) {
            val c = open("/o/" + URLEncoder.encode(key, "UTF-8"), "PUT")
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.setFixedLengthStreamingMode(file.length())
            file.inputStream().use { i -> c.outputStream.use { o -> i.copyTo(o, 256 * 1024) } }
            check(c)
            if (c.responseCode !in 200..299) throw IllegalStateException("Upload failed (" + c.responseCode + ")")
        }
    }

    private fun <T> retry(times: Int = 3, block: () -> T): T {
        var last: Exception? = null
        repeat(times) { attempt ->
            try { return block() } catch (e: IllegalStateException) { throw e } catch (e: Exception) {
                last = e
                Thread.sleep(1500L * (attempt + 1))
            }
        }
        throw last ?: IllegalStateException("Sync failed")
    }

    /** One full sync. Blocking: call from a background thread. Returns a one-line summary. */
    fun syncNow(context: Context, onStatus: (String) -> Unit): String {
        val base = url(context)
        val token = key(context)
        require(base.startsWith("https://") && token.isNotBlank()) { "Enter the sync address and key first" }
        val remote = Remote(base, token)
        onStatus("Connecting…")
        remote.ping()

        val prefs = context.getSharedPreferences("cloudsync", 0)
        val fetched = prefs.getStringSet("fetched", emptySet())!!.toMutableSet()
        val local = localItems(context)
        val have = local.map { it.hash }.toMutableSet().also { it.addAll(fetched) }
        val remoteMeta = remote.list("m/").map { it.removePrefix("m/").removeSuffix(".json") }.toSet()

        var up = 0; var down = 0; var skipped = 0; var failed = 0
        var n = 0
        for (item in local) {
            if (item.hash in remoteMeta) continue
            n++
            if (item.file.length() > MAX_FILE) { skipped++; continue }
            onStatus("Uploading $n: ${item.name}…")
            try { retry { remote.putFile("f/" + item.hash, item.file) } } catch (e: IllegalStateException) { throw e } catch (e: Exception) { failed++; continue }
            val meta = JSONObject()
                .put("store", "books").put("name", item.name).put("size", item.file.length())
                .put("title", item.name.substringBeforeLast('.').replace('_', ' '))
                .put("author", "")
                .put("format", item.format ?: JSONObject.NULL)
                .put("manga", false).put("added", System.currentTimeMillis())
            try { retry { remote.putBytes("m/${item.hash}.json", meta.toString().toByteArray(), "application/json") } } catch (e: IllegalStateException) { throw e } catch (e: Exception) { failed++; continue }
            up++
        }

        val toGet = remoteMeta.filter { it !in have }
        var d = 0
        for (hash in toGet) {
            d++
            val meta = runCatching { JSONObject(remote.getText("m/$hash.json") ?: "") }.getOrNull() ?: continue
            if (meta.optString("store") != "books") continue
            val name = meta.optString("name")
            val format = if (meta.isNull("format")) null else meta.optString("format")
            val (dir, ext) = when (format) {
                "pdf" -> "pdfs" to "pdf"
                "cbz" -> "comics" to "cbz"
                else -> "books" to "epub"
            }
            if (!name.endsWith(".$ext", true)) continue
            onStatus("Downloading $d of ${toGet.size}: $name…")
            val folder = File(context.filesDir, dir).apply { mkdirs() }
            val safe = name.replace(Regex("[^a-zA-Z0-9._ -]"), "_")
            val target = File(folder, "${System.currentTimeMillis()}_$safe")
            try {
                retry { remote.getFile("f/$hash", target) }
                if (target.length() <= 0L) { target.delete(); continue }
            } catch (e: IllegalStateException) { target.delete(); throw e } catch (e: Exception) { target.delete(); failed++; continue }
            fetched += hash
            prefs.edit().putStringSet("fetched", fetched).apply()
            down++
        }
        prefs.edit().putLong("last", System.currentTimeMillis()).apply()
        return "Synced. $up sent, $down received" + (if (skipped > 0) ", $skipped too big to sync" else "") + (if (failed > 0) ", $failed hit a connection problem, tap Save & sync again" else "") + "."
    }
}
