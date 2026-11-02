package com.korndog.ghostreader

import android.net.Uri
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
