// Vendored NewPipeExtractor (LibreTube fork) — THIRD-PARTY.md entry 1.
// Upstream sources are used as-is (never reformat; GPL-COMPLIANCE.md [GPL-7]); our delta on
// this fork stays small and rebaseable (spec R4). Pure JVM module: no Android, no Kotlin.
plugins {
    `java-library`
    alias(libs.plugins.google.protobuf)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// The vendored tree mirrors upstream's build model: timeago-parser sources compile into the
// same artifact (upstream does this via java.srcDir); proto files are protobuf-lite codegen.

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                named("java") {
                    option("lite")
                }
            }
        }
    }
}

tasks.jar {
    // Match upstream: proto files are build inputs, not jar content.
    exclude("**/*.proto")
    includeEmptyDirs = false
}

dependencies {
    api(libs.google.jsr305) // @Nullable appears in public API signatures
    implementation(libs.newpipe.nanojson)
    implementation(libs.jsoup)
    implementation(libs.google.protobuf.javalite)
    implementation(libs.mozilla.rhino)
    implementation(libs.mozilla.rhino.engine)
}
