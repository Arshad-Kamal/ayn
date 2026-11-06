plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp) // Room codegen (D4); verified on the forced-KGP-2.4.20 combo
    alias(libs.plugins.androidx.room3)
    alias(libs.plugins.kotlin.serialization) // the D4 backup bundle (M5-D5)
}

android {
    namespace = "com.arshad.ayn.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Same KE_SMOKE bridge as :core:potoken: instrumentation never sees host env vars.
        if (System.getenv("KE_SMOKE") != null) {
            testInstrumentationRunnerArguments["KE_SMOKE"] = "1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Unit-test JVM: android.jar stubs return defaults instead of throwing (keeps OkHttp's
        // platform probe quiet in the smoke test).
        unitTests.isReturnDefaultValues = true
        // MigrationTestHelper reads the exported schemas as test assets (below).
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets {
        // MigrationTestHelper validates each migration against core/schemas, read as assets.
        named("test") { assets.directories.add("$projectDir/schemas") }
    }

    // Recorded extraction fixtures + their replay downloader, shared with consuming modules'
    // tests so they drive the real seam on recorded payloads.
    testFixtures {
        enable = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// ARCH-8: schemas are committed under core/schemas and reviewed with every version bump.
room3 {
    schemaDirectory("$projectDir/schemas")
}

// The unit-test asset merge would otherwise run before KSP exports the current schema and test
// migrations against a stale one (Room 3 migrations).
tasks.matching { it.name.matches(Regex("merge.*UnitTestAssets")) }.configureEach {
    dependsOn("copyRoomSchemas")
}

dependencies {
    implementation(projects.core.extractor) // vendored NewPipeExtractor behind our seams (D7)
    implementation(libs.jsoup) // the extractor's own HTML lib: video descriptions arrive as HTML
    implementation(libs.newpipe.nanojson) // the extractor's own JSON lib: the related call goes through its InnerTube helpers

    implementation(projects.core.sabr) // vendored SABR chain behind the SabrBridge adapter
    implementation(libs.androidx.core.ktx) // androidx.annotation (@OptIn for UnstableApi)
    implementation(libs.androidx.media3.exoplayer) // SabrBridge returns a MediaSource; :app declares it too

    implementation(libs.kotlinx.coroutines.android) // seam suspend surface (KT-CONC-01)
    implementation(libs.squareup.okhttp) // transport for the NewPipe Downloader seam (PL-7)
    implementation(libs.androidx.room3.runtime) // subscriptions store (D4)
    ksp(libs.androidx.room3.compiler)
    implementation(libs.kotlinx.serialization.json) // backup bundle + NewPipe import
    implementation(libs.google.re2j) // content filters' title patterns (KP-D7)

    // Fixture types (FixtureDownloader, NewPipeAccess's Downloader parameter) are extractor types.
    testFixturesApi(projects.core.extractor)
    testFixturesImplementation(libs.kotlinx.coroutines.android) // FakeWatchStore's flows and start gate
    // The SABR failure shapes the control plane's tests replay (the vendored type stays in :core).
    testFixturesImplementation(projects.core.sabr)
    testFixturesImplementation(libs.androidx.media3.exoplayer)
    testFixturesImplementation(libs.squareup.okhttp) // RecordedSegments serves its answer through the real seam

    testImplementation(libs.junit4) // network smoke test, opt-in via KE_SMOKE=1
    testImplementation(libs.kotlinx.coroutines.test) // KT-CONC-09
    testImplementation(libs.robolectric) // real Room 3 database on the platform SQLite (JVM)
    testImplementation(libs.androidx.room3.testing) // MigrationTestHelper (ARCH-8)
    testImplementation(libs.squareup.mockwebserver3) // sync clients replay recorded exchanges (M8-D10)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit) // on-device API-band smoke
}
