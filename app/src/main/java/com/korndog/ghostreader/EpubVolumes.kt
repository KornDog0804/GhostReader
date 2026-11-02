package com.korndog.ghostreader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Finds the separate books inside an omnibus / anthology EPUB.
 *
 * Each book is described by a spine range, written "#s<first>-<last>" (inclusive spine positions).
 */

private fun newBuilder() = DocumentBuilderFactory.newInstance().apply {
    isNamespaceAware = true
    // Not every Android XML parser supports these switches, so each is optional.
    runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
    runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
}.newDocumentBuilder().apply {
    // Many tables of contents carry a DOCTYPE; never fetch it.
    setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
}

/** Joins a link to the folder it was written in and cleans it into a zip entry name. */
internal fun resolvePath(dir: String, href: String): String {
    val raw = href.substringBefore('#')
    if (raw.isBlank()) return ""
    val decoded = runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)
    val joined = if (dir.isEmpty()) decoded else "$dir/$decoded"
    return runCatching { java.net.URI(null, null, "/$joined", null).normalize().path.removePrefix("/") }
        .getOrDefault(joined)
}

internal fun volumeRange(prefix: String): Pair<Int, Int> {
    val parts = prefix.removePrefix("#s").split('-')
    return (parts.getOrNull(0)?.toIntOrNull() ?: 0) to (parts.getOrNull(1)?.toIntOrNull() ?: Int.MAX_VALUE)
}

internal fun decodeBitmap(bytes: ByteArray, maxSide: Int = 500): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val sample = (maxOf(bounds.outWidth, bounds.outHeight) / maxSide).coerceAtLeast(1)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

private class Pkg(val opfDir: String, val opf: Document)

private fun loadPkg(zip: ZipFile): Pkg? {
    fun doc(path: String): Document? = zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { newBuilder().parse(it) } }
    val container = doc("META-INF/container.xml") ?: return null
    val root = container.getElementsByTagNameNS("*", "rootfile").item(0) as? Element ?: return null
    val opfPath = root.getAttribute("full-path")
    val opf = doc(opfPath) ?: return null
    return Pkg(opfPath.substringBeforeLast('/', ""), opf)
}

/** Zip entry name for each spine position ("" when the item is missing). */
private fun spineOf(pkg: Pkg): List<String> {
    val manifest = HashMap<String, String>()
    val items = pkg.opf.getElementsByTagNameNS("*", "item")
    for (i in 0 until items.length) {
        val e = items.item(i) as Element
        manifest[e.getAttribute("id")] = resolvePath(pkg.opfDir, e.getAttribute("href"))
    }
    val refs = pkg.opf.getElementsByTagNameNS("*", "itemref")
    return (0 until refs.length).map { manifest[(refs.item(it) as Element).getAttribute("idref")].orEmpty() }
}

private class TocNode(val label: String, val src: String, val children: List<TocNode>) {
    fun descendants(): Int = children.sumOf { 1 + it.descendants() }
}

private fun children(parent: Element, name: String): List<Element> {
    val out = mutableListOf<Element>()
    var n = parent.firstChild
    while (n != null) {
        if (n is Element && n.localName == name) out.add(n)
        n = n.nextSibling
    }
    return out
}

private fun ncxNodes(parent: Element, dir: String): List<TocNode> = children(parent, "navPoint").map { point ->
    val label = children(point, "navLabel").firstOrNull()?.textContent?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
    val src = children(point, "content").firstOrNull()?.getAttribute("src").orEmpty()
    TocNode(label, resolvePath(dir, src), ncxNodes(point, dir))
}

private fun navNodes(list: Element, dir: String): List<TocNode> = children(list, "li").map { li ->
    val link = children(li, "a").firstOrNull() ?: children(li, "span").firstOrNull()
    val label = link?.textContent?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
    val href = link?.getAttribute("href").orEmpty()
    val nested = children(li, "ol").firstOrNull()
    TocNode(label, resolvePath(dir, href), if (nested != null) navNodes(nested, dir) else emptyList())
}

private fun readToc(zip: ZipFile, pkg: Pkg): List<TocNode> {
    val items = pkg.opf.getElementsByTagNameNS("*", "item")
    var ncxPath = ""
    var navPath = ""
    for (i in 0 until items.length) {
        val e = items.item(i) as Element
        val path = resolvePath(pkg.opfDir, e.getAttribute("href"))
        if (e.getAttribute("media-type") == "application/x-dtbncx+xml") ncxPath = path
        if (e.getAttribute("properties").split(' ').contains("nav")) navPath = path
    }
    if (ncxPath.isNotEmpty()) {
        val doc = zip.getEntry(ncxPath)?.let { e -> runCatching { zip.getInputStream(e).use { newBuilder().parse(it) } }.getOrNull() }
        val map = doc?.getElementsByTagNameNS("*", "navMap")?.item(0) as? Element
        if (map != null) {
            val nodes = ncxNodes(map, ncxPath.substringBeforeLast('/', ""))
            if (nodes.isNotEmpty()) return nodes
        }
    }
    if (navPath.isNotEmpty()) {
        val doc = zip.getEntry(navPath)?.let { e -> runCatching { zip.getInputStream(e).use { newBuilder().parse(it) } }.getOrNull() }
        if (doc != null) {
            val navs = doc.getElementsByTagNameNS("*", "nav")
            var chosen: Element? = null
            for (i in 0 until navs.length) {
                val nav = navs.item(i) as Element
                val type = nav.getAttributeNS("http://www.idpf.org/2007/ops", "type") + nav.getAttribute("epub:type")
                if (type.contains("toc")) { chosen = nav; break }
            }
            val nav = chosen ?: navs.item(0) as? Element
            val list = nav?.getElementsByTagNameNS("*", "ol")?.item(0) as? Element
            if (list != null) return navNodes(list, navPath.substringBeforeLast('/', ""))
        }
    }
    return emptyList()
}

private val volumeLabel = Regex(
    "(?i)^\\s*(book|volume|vol\\.?)\\s+([0-9]+|[ivxlc]+|one|two|three|four|five|six|seven|eight|nine|ten)\\b"
)

/** Returns (title, "#s<first>-<last>") for each book in an omnibus, or nothing for an ordinary book. */
internal fun detectVolumes(file: File): List<Pair<String, String>> = runCatching {
    ZipFile(file).use { zip ->
        val none = emptyList<Pair<String, String>>()
        val pkg = loadPkg(zip) ?: return@use none
        val spine = spineOf(pkg)
        if (spine.size < 4) return@use none
        val position = HashMap<String, Int>()
        spine.forEachIndexed { index, path -> if (path.isNotEmpty() && !position.containsKey(path)) position[path] = index }

        val top = readToc(zip, pkg)
        if (top.size < 2) return@use none

        // Case 1: each book is a top-level entry that holds its own chapters.
        var volumes = top.filter { it.descendants() >= 3 && !Regex("(?i)^\\s*part\\b").containsMatchIn(it.label) }
        // Case 2: a flat list where books are marked "Book 1", "Volume Two", ...
        if (volumes.size < 2) volumes = top.filter { volumeLabel.containsMatchIn(it.label) }
        if (volumes.size < 2 || volumes.size > 40) return@use none

        fun start(node: TocNode): Int? = position[node.src] ?: node.children.firstNotNullOfOrNull { position[it.src] }
        val starts = volumes.mapNotNull { node -> start(node)?.let { node to it } }
            .sortedBy { it.second }
            .distinctBy { it.second }
        if (starts.size < 2) return@use none

        starts.mapIndexed { index, (node, first) ->
            val last = if (index < starts.lastIndex) starts[index + 1].second - 1 else spine.lastIndex
            val title = node.label.replace(Regex("(?i)\\s+anthology$"), "").trim().ifBlank { "Book ${index + 1}" }
            title to "#s$first-$last"
        }
    }
}.getOrDefault(emptyList())

/** The picture on the first pages of one book inside an omnibus (its own cover), if there is one. */
internal fun volumeCover(file: File, range: String): Bitmap? = runCatching {
    ZipFile(file).use { zip ->
        val pkg = loadPkg(zip) ?: return@use null
        val spine = spineOf(pkg)
        val (first, last) = volumeRange(range)
        val image = Regex("""(?:src|href)\s*=\s*["']([^"']+\.(?:jpe?g|png|webp|gif))["']""", RegexOption.IGNORE_CASE)
        for (index in first..minOf(last, first + 3)) {
            val path = spine.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: continue
            val html = zip.getEntry(path)?.let { e -> zip.getInputStream(e).bufferedReader().use { it.readText() } } ?: continue
            val dir = path.substringBeforeLast('/', "")
            for (match in image.findAll(html)) {
                val entry = zip.getEntry(resolvePath(dir, match.groupValues[1])) ?: continue
                if (entry.size !in 2_000..8_000_000L) continue
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val bitmap = decodeBitmap(bytes) ?: continue
                if (bitmap.height >= bitmap.width) return@use bitmap
            }
        }
        null
    }
}.getOrNull()
