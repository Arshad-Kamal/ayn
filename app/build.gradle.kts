import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Google Drive sync's OAuth client: from the gitignored local.properties, so forks don't run
// on the maintainer's Google project; none = Drive hidden (M8-D1).
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

fun localString(key: String): String =
    "\"" + localProperties.getProperty(key, "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// The release key (RP-D3) is kept offline; its path and passwords come from the gitignored
// keystore.properties (storeFile, storePassword, keyAlias, keyPassword). Without that file the
// release build is signed with the debug key, so forks and CI still build.
val keystoreProperties = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

android {
    namespace = "com.arshad.ayn"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.arshad.ayn"
        minSdk = 30
        targetSdk = 37
        // Never below KeDatabase.SCHEMA_VERSION (VersionCodeTest), so an older build can't install
        // over a newer database (Room couldn't open it).
        versionCode = 13
        versionName = "1.0.0"
        buildConfigField("String", "GOOGLE_CLIENT_ID", localString("ayn.google.clientId"))
        buildConfigField("String", "GOOGLE_CLIENT_SECRET", localString("ayn.google.clientSecret"))
    }

    signingConfigs {
        if (keystoreProperties != null) {
            create("release") {
                fun required(key: String): String =
                    keystoreProperties.getProperty(key)?.takeIf { it.isNotBlank() }
                        ?: error("keystore.properties lacks $key")
                storeFile = rootProject.file(required("storeFile"))
                storePassword = required("storePassword")
                keyAlias = required("keyAlias")
                keyPassword = required("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // A release-key APK can't install over a debug-key one (different signature): moving
            // a device across means a backup, an uninstall and a restore.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            // Compose performance is only meaningful with R8 (developer.android.com/develop/ui/
            // compose/performance; house compose.md "measure in release builds with R8").
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true // VersionCodeTest reads VERSION_CODE; the Google client (above)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(projects.core)
    implementation(projects.core.extractor) // AppContainer builds the extractor's transport (OkHttpDownloader)
    implementation(projects.feature)
    implementation(projects.ui.tv)
    implementation(projects.ui.mobile)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.room3.runtime) // AppContainer holds the KeDatabase (a RoomDatabase)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)

    // Production player: MediaSessionService control plane + WebView PO-token minting
    implementation(projects.core.potoken)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.exoplayer.hls) // live streams (LIVE-D1)
    implementation(libs.androidx.media3.datasource.okhttp) // live traffic through AppContainer.httpClient
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.squareup.okhttp) // AppContainer.httpClient's type

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test) // ItemResolverTest's virtual time (KT-CONC-09)
    testImplementation(libs.robolectric) // in-process MediaSession + MediaController on the JVM
    testImplementation(testFixtures(projects.core)) // recorded video pages through the resolve's item
}

// D5 stability policy: seam models are declared stable for composable parameters
// (gradle/compose-stability.conf).
val stabilityConf = rootProject.layout.projectDirectory.file("gradle/compose-stability.conf")
composeCompiler {
    stabilityConfigurationFiles.add(stabilityConf)
}
// The compiler gets the conf by path only (Kotlin 2.4.20): an edit to it must still recompile.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    inputs.file(stabilityConf).withPathSensitivity(PathSensitivity.NONE)
}
