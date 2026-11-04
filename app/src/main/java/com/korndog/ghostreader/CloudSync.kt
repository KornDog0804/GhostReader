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
        fun putPart(key: String, file: File, offset: Long, length: Long) {
            val c = open("/o/" + URLEncoder.encode(key, "UTF-8"), "PUT")
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.setFixedLengthStreamingMode(length)
            file.inputStream().use { i ->
                var skipped = 0L
                while (skipped < offset) { val n = i.skip(offset - skipped); if (n <= 0) break; skipped += n }
                c.outputStream.use { o ->
                    val buf = ByteArray(256 * 1024)
                    var left = length
                    while (left > 0) {
                        val n = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n <= 0) throw java.io.IOException("File ended early")
                        o.write(buf, 0, n); left -= n
                    }
                }
            }
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
        val allLocal = localItems(context)
        val remoteMeta = remote.list("m/").map { it.removePrefix("m/").removeSuffix(".json") }.toSet()
        // Items deleted "everywhere" from another device (the website records them in state.json as "x|<hash>").
        val gone = runCatching {
            val st = JSONObject(remote.getText("state.json") ?: "{}")
            st.keys().asSequence().filter { it.startsWith("x|") }.map { it.removePrefix("x|") }.toSet()
        }.getOrDefault(emptySet())
        var removed = 0
        val local = allLocal.filter { item ->
            if (item.hash in gone) { if (item.file.delete()) removed++; false } else true
        }
        // Remember everything this phone has ever had, so something deleted here is not downloaded again.
        fetched.addAll(local.map { it.hash })
        prefs.edit().putStringSet("fetched", fetched).apply()
        val have = local.map { it.hash }.toMutableSet().also { it.addAll(fetched) }

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

        val toGet = remoteMeta.filter { it !in have && it !in gone }
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
        return "Synced. $up sent, $down received" + (if (skipped > 0) ", $skipped too big to sync" else "") + (if (removed > 0) ", $removed removed (deleted on another device)" else "") + (if (failed > 0) ", $failed hit a connection problem, tap Save & sync again" else "") + "."
    }

    private const val PART = 50L * 1024 * 1024

    /**
     * Sends one audiobook to the cloud in 50 MB pieces (the Worker only accepts ~100 MB per request).
     * Pieces are stored as ordinary objects, so the Worker needs no changes. Safe to re-run: pieces
     * already in the cloud are skipped, so a dropped connection just resumes.
     */
    fun sendAudiobook(context: Context, file: File, title: String, cover: android.graphics.Bitmap?, onStatus: (String) -> Unit): String {
        val base = url(context)
        val token = key(context)
        require(base.startsWith("https://") && token.isNotBlank()) { "Set up the sync address and key first (Settings → Sync)" }
        require(file.isFile && file.canRead()) { "Audiobook file is unavailable" }
        val remote = Remote(base, token)
        onStatus("Connecting…")
        remote.ping()
        val name = cleanName(file)
        val hash = sha1("audio|" + name + "|" + file.length())
        val total = file.length()
        val parts = ((total + PART - 1) / PART).toInt().coerceAtLeast(1)
        val existing = remote.list("f/").toHashSet()
        var sent = 0
        for (i in 0 until parts) {
            val key = "f/" + sha1("$hash|part|$i")
            if (key in existing) continue
            val off = i * PART
            val len = minOf(PART, total - off)
            onStatus("Uploading part ${i + 1} of $parts (${(off * 100 / total.coerceAtLeast(1))}%)…")
            retry(4) { remote.putPart(key, file, off, len) }
            sent++
        }
        val meta = JSONObject()
            .put("store", "audio").put("name", name).put("size", total)
            .put("title", title).put("author", "").put("format", JSONObject.NULL)
            .put("parts", parts).put("partSize", PART).put("manga", false).put("added", System.currentTimeMillis())
        retry { remote.putBytes("m/$hash.json", meta.toString().toByteArray(), "application/json") }
        if (cover != null) runCatching {
            val bos = java.io.ByteArrayOutputStream()
            cover.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, bos)
            val data = "data:image/jpeg;base64," + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
            remote.putBytes("c/$hash", data.toByteArray(), "text/plain")
        }
        return if (sent == 0) "Already in the cloud." else "Sent to the cloud ($parts parts)."
    }
}
