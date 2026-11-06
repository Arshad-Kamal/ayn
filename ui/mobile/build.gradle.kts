plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization) // NavKeys are saved through kotlinx.serialization
}

android {
    namespace = "com.arshad.ayn.ui.mobile"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Compose semantics tests on the JVM, as :ui:tv's (Robolectric, SDK pinned in @Config).
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

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3) // a ViewModel store per NavEntry
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose) // the app's activity line (system BACK reaches NavDisplay through it)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.coil.compose) // D3: thumbnails at UI resolution, as :ui:tv
    implementation(libs.coil.network.okhttp) // network fetcher; self-registers via ServiceLoader
    implementation(libs.androidx.media3.ui.compose) // PlayerSurface + state holders, as :ui:tv (M6.4)
    implementation(libs.androidx.media3.session) // the watch page's MediaController binding

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(testFixtures(projects.core)) // the gate's stores, faked as for :ui:tv
    testImplementation(testFixtures(projects.feature)) // the player test kit (ScriptedPlayer, FakePlaybackService)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// D5 stability policy, shared with :ui:tv (gradle/compose-stability.conf).
val stabilityConf = rootProject.layout.projectDirectory.file("gradle/compose-stability.conf")
composeCompiler {
    stabilityConfigurationFiles.add(stabilityConf)
}
// The compiler gets the conf by path only (Kotlin 2.4.20): an edit to it must still recompile.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    inputs.file(stabilityConf).withPathSensitivity(PathSensitivity.NONE)
}
