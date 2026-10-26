plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
}
android {
 namespace = "com.korndog.ghostreader"
 compileSdk = 35
 defaultConfig {
  applicationId = "com.korndog.ghostreader"
  minSdk = 26
  targetSdk = 35
  versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
  versionName = "0.2.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
 }
 signingConfigs {
  create("ghostreaderRelease") {
   val storePath = System.getenv("GHOSTREADER_KEYSTORE_PATH")
   if (!storePath.isNullOrBlank()) {
    storeFile = file(storePath)
    storePassword = System.getenv("GHOSTREADER_STORE_PASSWORD")
    keyAlias = System.getenv("GHOSTREADER_KEY_ALIAS")
    keyPassword = System.getenv("GHOSTREADER_KEY_PASSWORD")
   }
  }
 }
 buildTypes {
  getByName("release") {
   isMinifyEnabled = false
   signingConfig = signingConfigs.getByName("ghostreaderRelease")
  }
 }
 buildFeatures { compose = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2024.12.01"))
 implementation("androidx.activity:activity-compose:1.9.3")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.media3:media3-exoplayer:1.5.1")
 implementation("androidx.media3:media3-session:1.5.1")
 implementation("org.libtorrent4j:libtorrent4j:2.1.0-35")
 implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-35")
 implementation("org.libtorrent4j:libtorrent4j-android-arm:2.1.0-35")
 implementation("org.libtorrent4j:libtorrent4j-android-x86_64:2.1.0-35")
}
