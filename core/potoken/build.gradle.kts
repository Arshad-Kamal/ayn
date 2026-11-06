// PO-token minting (M3.4): vendored LibreTube WebView BotGuard implementation (THIRD-PARTY.md
// entry 3) behind our PoTokenSource seam. Vendored files keep upstream packages with a small
// stamped delta; our adapter lives in com.arshad.ayn.core.access.potoken.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.arshad.ayn.core.potoken"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Bridge the KE_SMOKE opt-in from the invoking shell to the on-device instrumentation:
        // instrumentation processes never see host env vars, runner arguments are the seam.
        if (System.getenv("KE_SMOKE") != null) {
            testInstrumentationRunnerArguments["KE_SMOKE"] = "1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    sourceSets {
        // EjsNChallengeSolverTest solves on the recorded cbcfbf2f player, read as an asset.
        named("androidTest") {
            assets.directories.add("$rootDir/core/src/testFixtures/resources/extract-fixtures/video-made-for-kids-mweb")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(projects.core) // PoTokenSource seam
    implementation(projects.core.extractor) // vendored PoTokenGenerator implements the extractor's PoTokenProvider

    implementation(libs.androidx.core.ktx) // androidx.annotation (@MainThread etc.)

    implementation(libs.squareup.okhttp) // BotguardService transport
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json) // JavaScriptUtil + request payload

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric) // ShadowWebView drives the minting WebView on the JVM
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // KP.1a MWEB smoke: the vendored SABR client as MWEB (its media3/protobuf types are not api).
    androidTestImplementation(projects.core.sabr)
    androidTestImplementation(libs.androidx.media3.exoplayer)
    androidTestImplementation(libs.google.protobuf.javalite)
}
