plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "hu.sx4.diagnosztika"
    compileSdk = 35
    defaultConfig { applicationId = "hu.sx4.diagnosztika"; minSdk = 26; targetSdk = 28; versionCode = 1; versionName = "0.1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
