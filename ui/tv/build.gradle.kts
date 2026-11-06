plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.arshad.ayn.ui.tv"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Compose semantics tests on the JVM (M4.4 verify line): Robolectric provides the
        // Android environment; resources must be included for the test inflater.
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(projects.core)
    implementation(projects.feature)

    implementation(libs.androidx.tv.material)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose) // viewModel(initializer) wiring (M4.4)
    implementation(libs.androidx.lifecycle.runtime.compose) // collectAsStateWithLifecycle
    implementation(libs.coil.compose) // D3: thumbnails at UI resolution (TVU-3)
    implementation(libs.coil.network.okhttp) // network fetcher; self-registers via ServiceLoader
    implementation(libs.androidx.media3.ui.compose) // D2: PlayerSurface + state holders (M4.8)
    implementation(libs.androidx.media3.session) // MediaController binding from the player destination
    implementation(libs.nayuki.qrcodegen) // Q20: Settings › Sync's sign-in QR
    // Typed routes (spec D7): reified composable<T>/toRoute call sites need the
    // serialization runtime; @Serializable classes themselves live in :feature.
    implementation(libs.kotlinx.serialization.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric) // :ui:tv Compose semantics tests on JVM
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(testFixtures(projects.core)) // recorded payloads through the real seam
    testImplementation(testFixtures(projects.feature)) // the player test kit (ScriptedPlayer, FakePlaybackService)
    debugImplementation(libs.androidx.compose.ui.test.manifest) // CMP-TEST-01
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
