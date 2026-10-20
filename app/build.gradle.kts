import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

/** Runs git in the repository; null when git or the history is unavailable (e.g. ZIP download). */
fun git(vararg args: String): String? = runCatching {
    providers.exec { commandLine("git", *args); isIgnoreExitValue = true }.standardOutput.asText.get().trim().ifEmpty { null }
}.getOrNull()

val baseVersion = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
    .getProperty("VERSION_NAME")
// CI: monotonically increasing GitHub run number, so each new APK installs over the previous one.
// Local: commit count. Never lower than 2 (the first published APK used versionCode 1).
val buildNumber = (System.getenv("APP_BUILD_NUMBER")?.toIntOrNull() ?: git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 0)
    .coerceAtLeast(2)
// On pull_request CI the checkout is GitHub's temporary merge commit; the workflow passes the real head.
val gitSha = System.getenv("APP_GIT_SHA")?.take(8) ?: git("rev-parse", "--short=8", "HEAD") ?: "unknown"
val gitDirty = git("status", "--porcelain")?.isNotEmpty() == true

// Stable signing key from the environment (GitHub Actions secrets). The same key must sign every
// future APK, otherwise Android refuses the update and only uninstalling (= data loss) helps.
val keystorePath = System.getenv("SX4_KEYSTORE_FILE")?.takeIf { file(it).isFile }

android {
    namespace = "hu.sx4.diagnosztika"
    compileSdk = 35
    defaultConfig {
        applicationId = "hu.sx4.diagnosztika"; minSdk = 26; targetSdk = 35
        versionCode = buildNumber
        versionName = "$baseVersion-build$buildNumber"
        buildConfigField("String", "GIT_SHA", "\"$gitSha${if (gitDirty) "-dirty" else ""}\"")
        buildConfigField("int", "BUILD_NUMBER", "$buildNumber")
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        if (keystorePath != null) create("stable") {
            storeFile = file(keystorePath)
            storePassword = System.getenv("SX4_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("SX4_KEY_ALIAS")
            keyPassword = System.getenv("SX4_KEY_PASSWORD")
        }
    }
    buildTypes {
        // Debug and release share the stable key, so either can be installed over the other.
        getByName("debug") { if (keystorePath != null) signingConfig = signingConfigs.getByName("stable") }
        getByName("release") { isMinifyEnabled = false; if (keystorePath != null) signingConfig = signingConfigs.getByName("stable") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}


dependencies { testImplementation("junit:junit:4.13.2") }
