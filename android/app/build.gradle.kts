plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
}
val backendWsUrl = (project.findProperty("BACKEND_WS_URL") as String?) ?: "wss://YOUR_DOMAIN/ws"
val allowCleartext = (project.findProperty("ALLOW_CLEARTEXT") as String?) ?: "false"
android {
 namespace="com.spled.intelligence"; compileSdk=35
 defaultConfig { manifestPlaceholders["allowCleartextTraffic"] = allowCleartext; buildConfigField("String", "BACKEND_WS_URL", "\"$backendWsUrl\""); applicationId="com.spled.intelligence"; minSdk=26; targetSdk=35; versionCode=8; versionName="0.8.0" }
 compileOptions {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
 }
 kotlinOptions {
  jvmTarget = "17"
 }
 buildFeatures { compose=true; buildConfig=true }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2025.02.00"))
 implementation("androidx.activity:activity-compose:1.10.1")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 testImplementation("junit:junit:4.13.2")
}
