# R8 rules for the release build. Library consumer rules (Media3, Room, Coil, OkHttp,
# kotlinx-serialization) apply automatically; these cover what they don't.

# Private app: keep class/member names so stack traces from the field stay readable.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# Vendored NewPipeExtractor (core/extractor): the Rhino JavaScript engine is reflection-driven.
# Rules match LibreTube's shipping set for the same extractor fork.
-keep class org.mozilla.javascript.** { *; }
-keep class javax.script.** { *; }
-keep class jdk.dynalink.** { *; }
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn com.google.re2j.**
-dontwarn java.beans.**
-dontwarn javax.annotation.**

# protobuf-lite messages (core/sabr SABR wire protos, core/extractor protos) are parsed via
# field-name reflection and protobuf-javalite ships no consumer rules: keep their fields
# (protobuf's documented lite rule).
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
