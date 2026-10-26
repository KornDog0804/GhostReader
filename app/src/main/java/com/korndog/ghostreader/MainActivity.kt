package com.korndog.ghostreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  setContent {
   MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF0B0B10), primary = Color(0xFFB49AFF))) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
     Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
      Text("GHOSTREADER", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
      Spacer(Modifier.height(12.dp))
      Text("Your library. Your rules.", color = Color.White)
      Spacer(Modifier.height(24.dp))
      Text("Reader engine and local library coming next.", color = Color.LightGray)
     }
    }
   }
  }
 }
}
