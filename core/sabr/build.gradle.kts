import com.google.protobuf.gradle.id

// Vendored LibreTube SABR chain — THIRD-PARTY.md entry 2.
// Upstream sources are used with a small, stamped delta severing LibreTube app couplings
// (see the "MODIFIED" headers in SabrClient/SabrDataSource/Representation/SabrManifest and
// the local SabrFormat model). Never reformat; keep the delta small and rebaseable (spec R4).
// `@UnstableApi` Media3 chunk classes stay confined to this module (house media3.md PL-8).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.google.protobuf)
}

android {
    namespace = "com.arshad.ayn.core.sabr"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Proto schemas: SABR/UMP wire formats (video_streaming/*, misc/common.proto). Upstream keeps
// the default proto package names (video_streaming, misc) — left untouched for rebase fidelity.
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                // Matches upstream's working Android config (builtins.named("java") fails on
                // the AGP variant tasks with "PluginOptions with name 'java' not found").
                id("java") {
                    option("lite")
                }
            }
        }
    }
}

tasks.withType<Jar>().configureEach {
    exclude("**/*.proto")
    includeEmptyDirs = false
}

dependencies {
    implementation(libs.androidx.core.ktx) // androidx.core.net.toUri in SabrDataSource
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.google.protobuf.javalite)
    implementation(libs.squareup.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric) // real android.net.Uri for SabrManifest in client tests
}
