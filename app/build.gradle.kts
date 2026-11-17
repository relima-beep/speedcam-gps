plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.speedcam.gps"
    compileSdk = 35
    defaultConfig { applicationId = "com.speedcam.gps"; minSdk = 29; targetSdk = 35; versionCode = 9; versionName = "9.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    val camerax = "1.4.1"
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
}
