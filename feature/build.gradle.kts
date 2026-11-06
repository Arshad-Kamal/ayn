plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.arshad.ayn.feature"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testFixtures {
        enable = true // the player test kit both UIs' tests share
    }

    testOptions {
        // ViewModel tests replace Dispatchers.Main (KT-CONC-09); JVM needs default returns.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(projects.core)
    // Typed Nav routes (spec D7) are @Serializable objects; core is their runtime.
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.androidx.lifecycle.viewmodel) // HomeViewModel + viewModelScope
    implementation(libs.androidx.media3.session) // the player screens' MediaController binding, shared (M6 Q32)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime) // snapshot state of the shared player holders (M3-UI-01; no Compose compiler, M6 Q33)

    testFixturesImplementation(projects.core)
    testFixturesImplementation(testFixtures(projects.core)) // the recorded Enigma page
    testFixturesImplementation(libs.androidx.media3.session)
    testFixturesImplementation(libs.kotlinx.coroutines.android)
    // Lifts media3-session's older lifecycle/annotation floor to the versions :feature builds with.
    testFixturesImplementation(libs.androidx.lifecycle.viewmodel)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric) // the profile gate's restore takes an android.net.Uri; the player holders run on the main looper
    testImplementation(testFixtures(projects.core)) // recorded payloads through the real seam
}
