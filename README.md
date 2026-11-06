# Ayn (عين)

Ayn (Kamal Edition) is a customizable, ad-free YouTube client for Android TV and Android phones.
It is built and tested on an NVIDIA Shield first. Your data (profiles, subscriptions, history,
playlists) stays on the device, with optional backup and sync to storage you choose.

Ayn is an independent project. It is not made by, endorsed by, or affiliated with YouTube or
Google.

## Requirements

Android 11 (API 30) or newer, on a TV or a phone. One APK serves both; the app picks the TV or the
mobile interface at runtime.

## Building

You need JDK 21 and the Android SDK. Point Gradle at the SDK with `sdk.dir` in
`local.properties` (or `ANDROID_HOME`), then:

```sh
./gradlew build            # tests, lint, and the release APK
./gradlew assembleRelease  # the APK only: app/build/outputs/apk/release/
```

Dependencies are checked against `gradle/verification-metadata.xml`.

Google Drive sync needs your own OAuth client: put `ayn.google.clientId` and
`ayn.google.clientSecret` in `local.properties`. Without them the Drive option is hidden and
everything else works.

## Modules

- `:app`: the single APK
- `:core`: data, storage, extraction, and streaming behind interfaces
- `:core:extractor`, `:core:sabr`, `:core:potoken`: vendored third-party code (see
  `THIRD-PARTY.md`)
- `:feature`: view models and state
- `:ui:tv`, `:ui:mobile`: the two interfaces (Jetpack Compose)

## License

GPL-3.0-or-later; see `LICENSE`. Borrowed code, its upstreams, and our changes to it are listed in
`THIRD-PARTY.md`.
