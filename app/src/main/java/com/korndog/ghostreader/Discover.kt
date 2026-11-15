package com.korndog.ghostreader

import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

private data class TorrentResult(val name:String,val hash:String,val size:Long,val seeds:Int,val files:Int)
private val purple=Color(0xFFB784FF)

@Composable
internal fun DiscoverScreen(onMagnet:(String)->Unit) {
 var source by remember { mutableStateOf("Pirate Bay") }
 var browser by remember { mutableStateOf<WebView?>(null) }
 var browserError by remember { mutableStateOf<String?>(null) }
 var browserUrl by remember { mutableStateOf("https://audiobookbay.lu/") }
 var canGoBack by remember { mutableStateOf(false) }
 val context = LocalContext.current
 var query by remember { mutableStateOf("") }
 var category by remember { mutableStateOf("0") }
 var loading by remember { mutableStateOf(false) }
 var message by remember { mutableStateOf("Search the Pirate Bay index. Download only content you have permission to obtain.") }
 var results by remember { mutableStateOf<List<TorrentResult>>(emptyList()) }
 var searchId by remember { mutableIntStateOf(0) }
 LaunchedEffect(searchId) {
  if(searchId==0) return@LaunchedEffect
  loading=true; results=emptyList()
  try {
   results=withContext(Dispatchers.IO) {
    val url=URL("https://apibay.org/q.php?q="+Uri.encode(query.trim())+"&cat="+category)
    val conn=url.openConnection() as HttpURLConnection
    try {
     conn.connectTimeout=12000;conn.readTimeout=15000;conn.setRequestProperty("Accept","application/json")
     if(conn.responseCode!=200) throw IllegalStateException("Search service returned HTTP "+conn.responseCode)
     val json=JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
     (0 until json.length()).mapNotNull { i ->
      val o=json.getJSONObject(i)
      val hash=o.optString("info_hash")
      if(o.optString("id")=="0" || !hash.matches(Regex("[0-9a-fA-F]{40}"))) null
      else TorrentResult(o.optString("name"),hash,o.optString("size").toLongOrNull()?:0L,o.optString("seeders").toIntOrNull()?:0,o.optString("num_files").toIntOrNull()?:0)
     }
    } finally { conn.disconnect() }
   }
   message=if(results.isEmpty()) "No results found." else "${results.size} results. Verify rights and contents before downloading."
  } catch(e:Exception) { message="Search unavailable: "+(e.message?:"network error") }
  finally { loading=false }
 }
 Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal=16.dp, vertical=12.dp)) {
  Text("Discover",style=MaterialTheme.typography.titleLarge,color=Color.White)
  Spacer(Modifier.height(10.dp))
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
   listOf("Pirate Bay", "AudiobookBay").forEach { name ->
    FilterChip(selected = source == name, onClick = { source = name }, label = { Text(name) })
   }
  }
  if (source == "AudiobookBay") {
   Text("Website browser • Only download content you have permission to use.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
   BackHandler(enabled = canGoBack) { browser?.goBack() }
   Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    TextButton(onClick = { browser?.goBack() }, enabled = canGoBack) { Text("Back") }
    TextButton(onClick = { browserError = null; browser?.reload() }) { Text("Refresh") }
    TextButton(onClick = { browserError = null; browser?.loadUrl("https://audiobookbay.lu/") }) { Text("Home") }
   }
   Button(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(browserUrl))) }.onFailure { browserError = "No browser available to open this page." } }, modifier = Modifier.fillMaxWidth()) { Text("Open this page in Chrome / browser") }
   Text("Website sign-in and download links may work better in your regular browser.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
   if (browserError != null) Text(browserError!!, color = Color(0xFFFFA6A6), style = MaterialTheme.typography.bodySmall)
   AndroidView(
    factory = { context -> WebView(context).apply {
     settings.javaScriptEnabled = true
     settings.domStorageEnabled = true
     webViewClient = object : WebViewClient() {
      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
       val url = request.url
       if (url.scheme == "magnet") {
        onMagnet(url.toString())
        return true
       }
       if (url.scheme == "http" && url.host.equals("audiobookbay.lu", ignoreCase = true)) {
        view.loadUrl(url.buildUpon().scheme("https").build().toString())
        return true
       }
       if (url.scheme == "http" || url.scheme == "https") return false
       // Do not let unexpected schemes navigate the embedded browser.
       return true
      }
      override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
       browserUrl = url
       canGoBack = view.canGoBack()
       browserError = null
      }
      override fun onPageFinished(view: WebView, url: String) {
       canGoBack = view.canGoBack()
      }
      override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
       if (request.isForMainFrame) browserError = "Page could not load: ${error.description}. Try Open externally."
      }
     }
     loadUrl("https://audiobookbay.lu/")
     browser = this
    } },
    modifier = Modifier.fillMaxWidth().weight(1f)
   )
  } else {
  OutlinedTextField(value=query,onValueChange={query=it},label={Text("Search Pirate Bay")},singleLine=true,modifier=Modifier.fillMaxWidth())
  Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
   listOf("All" to "0","Audio" to "102","Books" to "601","Comics" to "602").forEach { (label,code) ->
    FilterChip(selected=category==code,onClick={category=code},label={Text(label)})
   }
  }
  Button(onClick={searchId++},enabled=query.isNotBlank()&&!loading,modifier=Modifier.fillMaxWidth()) { Text(if(loading) "Searching…" else "Search") }
  Text(message,color=Color.LightGray,style=MaterialTheme.typography.bodySmall)
  Spacer(Modifier.height(8.dp))
  LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
   items(results) { item ->
    Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF21192B))) {
     Column(Modifier.fillMaxWidth().padding(12.dp)) {
      Text(item.name,color=Color.White)
      Text("${"%.1f".format(item.size/1048576.0)} MB • ${item.seeds} seeders • ${item.files} files",color=Color.LightGray,style=MaterialTheme.typography.bodySmall)
      TextButton(onClick={
       onMagnet("magnet:?xt=urn:btih:${item.hash}&dn=${Uri.encode(item.name)}")
      }) { Text("Review magnet in Downloads",color=purple) }
     }
    }
   }
  }
  }
 }
}
