# Third-party / vendored code — provenance ledger

Kept for GPL-3.0 compliance. One entry per borrowed block. Re-verify
the upstream license grant ("only" vs "or-later") at every vendoring event. Upstream license
headers stay in the files themselves; this ledger is the index.

## Entries

### 1. NewPipeExtractor (LibreTube fork) — `:core:extractor`

- **Upstream:** https://github.com/libre-tube/NewPipeExtractor (fork of TeamNewPipe/NewPipeExtractor)
- **Commit:** `3e863d7` ("[YouTube] Add missing playability check"), shallow snapshot 2026-09-26.
- **License:** GPL-3.0-or-later (`LICENSE` = GPLv3 text; only 2 of the 359 `.java` files carry an
  SPDX header, recounted 2026-10-30). One file inside it is MPL-2.0: entry 10.
- **Files taken:** `extractor/src/main/java/**` (incl. YouTube SABR/PO-token hooks:
  `PoTokenProvider.java`, `PoTokenResult.java`, `services/youtube/sabr/**`),
  `extractor/src/main/proto/**` (2 proto files, protobuf-lite codegen), and
  `timeago-parser/src/main/java/**` (upstream compiles it into the same artifact via `srcDir`).
- **Not taken:** upstream build scripts (our own `:core:extractor/build.gradle.kts`), checkstyle
  config, tests (upstream tests are live-network integration tests; our own seam tests replace
  them per M3.6), publishing/signing config.
- **Imported:** 2026-09-27. **Modifications:** stamped `MODIFIED (YouTube KE, …)` in-file:
  - `utils/Utils.java` `encodeUrlUtf8`/`decodeUrlUtf8` use the charset-name overloads (the
    `Charset` overloads are JDK 10+ / Android API 33+ and crash the API 30 reference device;
    found at M3.5 TV first frames).
  - `Collectors.toUnmodifiableList()` (JDK 10+ / Android API 33+) replaced with
    `collectingAndThen(toList(), Collections::unmodifiableList)` at its 8 call sites
    (`YoutubeParsingHelper`, `YoutubeChannelExtractor`, `YoutubeMixPlaylistExtractor`,
    `YoutubeSuggestionExtractor`, `BandcampChannelExtractor`, `BandcampExtractorHelper`,
    `PeertubeParsingHelper`, `SoundcloudParsingHelper`) — same unmodifiable-list semantics on
    Java 8 APIs. Found at M4.10: listing paths (browse/search/channel tabs → thumbnail
    collection) crashed with `NoSuchMethodError` on the API 30 Shield, killing every listing
    surface while `video()` (which bypasses item collectors) kept working.
  - `YoutubeStreamHelper.getWebMetadataPlayerResponse` sends the player code's signature
    timestamp when it can be read (else the request is unchanged) and adds `storyboards` to its
    `$fields`; `YoutubeStreamExtractor.getFrames()` reads that WEB spec when present, else the
    streaming client's. VISIONOS's spec stops at 160×90; WEB's reaches 320×180, for a sharper
    scrub preview (Effort B B14i, 2026-10-04). `YoutubeJavaScriptPlayerManager.getSignatureTimestamp`
    also stores a player-code fetch failure, so a failed fetch costs one attempt per process
    while no code is held (2026-10-23: `prepareThrottlingParameterSolver` always tries the fetch,
    and a fetched code lifts the stored failure).
  - `YoutubeSearchExtractor.collectStreamsFrom` reads WEB search's Shorts shelves
    (`gridShelfViewModel` → `shortsLockupViewModel`) through the fork's
    `YoutubeShortsLockupInfoItemExtractor`; neither the fork nor upstream TeamNewPipe reads them
    (M7.8a, 2026-10-07; the seam keeps Shorts apart, M7-D12).
  - `SubtitlesStream` keeps the service's name for a track (`getTrackName()`, builder
    `setTrackName`), which `YoutubeStreamExtractor.getSubtitles` reads from each caption track's
    `name`; upstream drops it, so two tracks in one language look alike (M7.c1, 2026-10-08).
  - Made-for-kids playback through MWEB (KP.1b, 2026-10-23, spec 12 KP-D29/D30):
    `YoutubeStreamExtractor` answers a VISIONOS `UNPLAYABLE` refusal that names no reason with an
    MWEB player request (web poToken from the provider; base.js + solver prepare on a side thread
    while it mints), reads that answer's formats as `sabr://` only, solves its SABR URL's `n`, and
    exposes `getMwebClient()`; `YoutubeStreamHelper.getMwebPlayerResponse`,
    `InnertubeClientRequestInfo.ofMwebClient`; `YoutubeJavaScriptPlayerManager` uses an installed
    `ThrottlingParameterSolver` instead of Rhino for `n` (+ `prepareThrottlingParameterSolver`).
    ADDED `YoutubeMwebClient` (the MWEB identity's one home) and `ThrottlingParameterSolver`.
  - `YoutubeStreamExtractor.getLiveStartDate()` reads the WEB microformat's
    `liveBroadcastDetails.startTimestamp` (LIVE.3b, 2026-10-29, spec 19 LIVE-D24): the live
    chrome's "Started streaming …"; upstream reads it only as a fallback upload date, behind
    `uploadDate`/`publishDate`, which a live page also carries.
  - `YoutubeStreamExtractor.getConversationBar()` returns the WEB `next` answer's
    `conversationBar` (LIVE.5, 2026-11-02, spec 19 LIVE-D6): the live chat's or replay's seed,
    read by `:core`'s chat parser; upstream doesn't expose it.
  When modifying a vendored file, stamp change + date + modifier identity at the top of the
  file [GPL-3] and update this entry.
- **Notes:** our own delta on this fork must stay small and rebaseable (spec R4 model: LibreTube's
  own delta is ~690 lines). Rhino pinned at 1.8.1 per upstream (1.9.0 raises minSdk concerns
  upstream; revisit separately, do not drift silently).

## Planned entries (not yet imported)

- Flow `player/sabr/**` + `player/resolver/*` (GPL-3.0) — reference/borrow as needed (M3.4+).
- PipePipe `services/youtube/sabr/**` + client `SabrSegmentDataSource` family (GPL-3.0) — wire
  format reference (M3.4+).

(No Grayjay, no ReVanced glue — spec D1 boundaries. Kodi YouTube addon is GPL-2.0-only:
logic reference only; any code from it is a logged clean-room rewrite.)

### 2. LibreTube SABR player chain — `:core:sabr`

- **Upstream:** https://github.com/libre-tube/LibreTube
- **Commit:** `b51f20e`, shallow snapshot 2026-09-26.
- **License:** GPL-3.0-or-later (README "License": "GNU General Public License version 3 or
  later"; `LICENSE` = GPLv3 text; source files carry no per-file headers). Re-verified
  2026-10-02 against the snapshot.
- **Files taken:** `player/SabrMediaSource.kt`, `SabrMediaPeriod.kt`, `SabrChunkSource.kt`,
  `DefaultSabrChunkSource.kt`, `SabrDataSource.kt`, `player/manifest/{AdaptationSet,
  Representation,SabrManifest}.kt`, `player/parser/{CompositeBuffer,SabrClient,UmpParser,
  Xtags}.kt`, and `app/src/main/proto/**` (27 wire schemas: `video_streaming/*`,
  `misc/common.proto`; proto packages kept native for rebase fidelity; from googlevideo, MIT:
  entry 8).
- **Not taken here:** LibreTube's `PoTokenGenerator`/`PoTokenWebView` (vendored separately into
  `:core:potoken`, entry 3), its UI/player glue and models (`Streams`/`PipedStream`), build
  scripts (ours mirror its working protobuf config).
- **Imported:** 2026-09-27. **Modifications:** stamped `MODIFIED (YouTube KE, …)` in-file:
  - `SabrClient.kt`: LibreTube app couplings severed (PoTokenGenerator → injected
    `webPoTokenCache` (cached token) and `webPoTokenMint` (fresh mint for the attestation
    re-mint) lambdas; DefaultBandwidthMeter/LibreTubeApp singleton → injected
    `BandwidthMeter`; DisplayHelper HDR probe → injected `hdrSupported`; ShareDialog URL
    constant → local; unused YoutubeParsingHelper import dropped); protocol signals throw typed
    `SabrFailure`s (was bare `Exception` message strings — M3.6) and the non-2xx fetch path
    closes its response (was a connection leak). 2026-09-29 (M4.10): the "requested segment
    for finished format" and UMP left-over-data guards throw `SabrFailure.ProtocolError`
    instead of `assert` — both sit on ExoPlayer's loader thread, and `Loader` catches
    `Exception`, not `Error` (the finished-format case killed the process on-device);
    `SabrDataSource.read`'s contract assert became `check` (ISE) for the same reason.
    The reload-part comment no longer claims a purposeful crash. 2026-10-02 (AUD R3): one
    `Mutex` (was a 1-thread dispatcher, which interleaves at suspension points) guards all
    client state, including the reads `getNextSegment` made before entering it, held across the
    fetch and run on `Dispatchers.IO` (the loader interrupt never reaches the blocking re-mint);
    fields touched outside it `@Volatile`; unused unsynchronized `getEndSegmentNumber`
    removed; the fetch is cancellable (`enqueue` + cancel on coroutine cancellation, body read
    in the callback; Media3's `Loader` interrupts the loading thread on cancel); the
    finished-format guard compares the requested segment's start, not the playhead (a seek or
    resume near the end failed as protocol-fatal); the `OkHttpClient` is injected (the app's
    shared client); a failed re-mint keeps the current token. 2026-10-09 (M7P-3): each response
    body read is one network transfer on the injected meter's `TransferListener` (bytes reported
    per read, timed from the response headers to the last byte or the failure); upstream `c826398` still meters
    through the data source. 2026-10-23 (KP.1a): the client identity (`ClientInfo` name, version,
    device, OS, and the `User-Agent`) is an injected `SabrClientIdentity` (ADDED class, same
    file; default `VISIONOS` = the former hard-coded values), so an MWEB session speaks as MWEB.
  - `SabrFailures.kt`: ADDED (2026-09-27) — typed SABR protocol failures (PL-6 taxonomy input).
  - `SabrMediaSource.kt`: `Factory` carries the injected seams (2026-10-02: plus the shared
    `OkHttpClient`; 2026-10-23, KP.1b: plus the `SabrClientIdentity`).
  - `SabrMediaPeriod.kt`: Media3 1.11.1 API adjustment — `ChunkSampleStream` gained
    `firstChunkStartTimeUs`; passes `C.TIME_UNSET` (server-decided first chunk).
    2026-11-02 (PERF P16): `selectNewStreams` lets a new selection make its initial choice
    (`updateSelectedTrack` while its reason is `UNKNOWN`) and names that format to the client,
    not the selection's index 0 (an `AdaptiveTrackSelection`'s top format until its first update):
    every start's first request (audio's or video's) asked the top format, then the chunk source
    fetched the estimate's pick at position 0 again. Upstream `master` unchanged (2026-06-09).
  - `SabrDataSource.kt`: segment-fetch `IOException` now carries its cause (PL-6); 2026-10-02:
    a response without the requested segment throws `IOException`, not an NPE from `!!`
    (the session classifier treats a `RuntimeException` the data source caught as a bug).
    2026-10-09 (M7P-3): `BaseDataSource(false)` (was `true`): its `open` timed the whole fetch
    wait against one segment's bytes, so a fresh `DefaultBandwidthMeter` collapsed and every
    first 4K start dropped to 144p; `SabrClient` meters the network instead.
  - `Representation.kt` / `SabrManifest.kt`: local `SabrFormat`/`SabrStreamSet` models replace
    `PipedStream`/`Streams` (ADDED file `SabrFormats.kt`; fields mirror upstream 1:1; its
    `toSabrManifest` factory is the app-side construction seam with the endpoint precondition,
    added 2026-09-27).
- **Notes:** `@UnstableApi` chunk classes stay confined to this module (project `media3.md`
  PL-8, house `media3.md` M3-PIPE-08); the app-side seam surface is `SabrMediaSource.Factory` +
  `SabrManifest` construction from our models (M3.5).

### 3. LibreTube PO-token WebView minting — `:core:potoken`

- **Upstream:** https://github.com/libre-tube/LibreTube (same snapshot as entry 2, `b51f20e`).
- **License:** GPL-3.0-or-later (as entry 2).
- **Files taken:** `api/poToken/{PoTokenWebView, PoTokenGenerator, JavaScriptUtil}.kt` and the
  `assets/po_token.html` BotGuard bootstrap page (derived from BgUtils, MIT: entry 9).
- **Not taken:** `PoTokenWebView`'s Retrofit coupling (replaced by the added `BotguardService`
  seam, implemented with OkHttp in our package), LibreTube's `BuildConfig`/`LibreTubeApp` usage.
- **Imported:** 2026-09-27. **Modifications:** stamped `MODIFIED (YouTube KE, …)` in-file:
  `BotguardService` fun interface added (replaces RetrofitInstance); `PoTokenGenerator` takes
  injected `Context` + `BotguardService`; `DEBUG_LOG` replaces `BuildConfig.DEBUG` and raw token
  values are removed from log messages (security core SEC-LOG); `BOTGUARD_USER_AGENT` + the web
  API key constants are local (verbatim upstream values); `PoTokenGenerator.poToken` cache field
  is `@Volatile` (cross-thread read/write from the M3.5 wiring); `PoTokenGenerator.close()` teardown
  added (2026-09-28, M4.8 — posts the WebView close on main and clears caches, the recreate path's
  own idiom); `PoTokenWebView` emits the mint identifier as a JSON-encoded JS string literal (was
  spliced raw into the evaluated script, CWE-94; stamped at the call site). 2026-10-02 (AUD R4),
  `PoTokenWebView`: a `WebViewClient` handles render-process loss (destroy only, fail the init
  or pending mints, report handled; unhandled, the platform crashes or kills the app);
  `close()` idempotent and fails pending mints; a closed WebView reports `isExpired()` so the
  generator recreates; init (30 s) and mint (10 s) waits bounded, failing as
  `PoTokenException`, a timed-out init closing its WebView; pending mints keyed per request in
  a `ConcurrentHashMap` (the JS callbacks carry the key; the token still binds to the video
  id). `PoTokenGenerator` (same date): the failure retry recreates only the WebView that failed
  (was unconditional, so concurrent failures rebuilt twice), reuses a replacement, and gives up
  when `close()` dropped it (was a rebuild after teardown). 2026-10-23 (KP.1a, spec 12 KP-D28):
  the BotGuard challenge comes from YouTube's homepage (`window.ytAtN` `bgChallenge`, its
  interpreter fetched from `www.google.com` only) with the page's `EVENT_ID` set as `yt.config_`
  (was the `jnn/v1/Create` challenge, whose tokens GVS refuses); `JavaScriptUtil`'s
  `parseChallengeData`/`descramble` replaced by `parseHomepageChallenge`; `BotguardService` is a
  plain interface with `get()` beside `post()`. 2026-10-25 (PASS-9): `po_token.html`'s
  `obtainPoToken` makes BotGuard's minter once per page (integrity token) and reuses it for every
  identifier, as bgutil's `WebPoMinter` does (was `getMinter` per mint, whose tokens after a page's
  first GVS refuses past ~65 s of media). Our adapter `WebViewPoTokenSource` implements the `PoTokenSource` seam (web-client
  minting only, matching upstream).

### 4. Archivo variable font — `:ui:tv` `res/font/archivo.ttf`

- **Upstream:** https://github.com/google/fonts `ofl/archivo/Archivo[wdth,wght].ttf` (last
  upstream change `6c70c82`, 2021-02-04); project https://github.com/Omnibus-Type/Archivo.
- **License:** SIL Open Font License 1.1 (GPL-3.0-compatible; no Reserved Font Name). Text kept
  at `feature/src/main/res/raw/licence_ofl_1_1_archivo.txt`, so it ships in the APK and shows in
  Settings › About › Licences (M7.5c).
- **File:** Version 2.001, axes wght 100–900 / wdth 62–125, sha256
  `0e094a7d3c7c4c25cf1310c4b30014f1dae9332220b1c2c88f4fa996f0b05053`; unmodified (renamed to a
  resource name only). Same font as the Cinema mockups (glyph outlines checked equal 2026-10-03).
- **Imported:** 2026-10-03 (Effort B B8).

### 5. yt-dlp EJS (`n` solver) — `:core:potoken` `assets/ejs/`

- **Upstream:** https://github.com/yt-dlp/ejs, release 0.8.0 assets `yt.solver.lib.min.js`
  (sha256 `c55987fe697e5b9ee18830163f7af85327e9bb5c3e674b969d38c8d205eaa577`, 151,561 B) and
  `yt.solver.core.min.js` (`18da6ce0758b416e7ae645084f4f8801f9f9d59d6c477c05eaa0ff94ebd8cc00`,
  6,945 B); pinned by `EjsAssetsTest`.
- **License:** Unlicense; `lib` bundles meriyah 6.1.4 (ISC) and astring 1.9.0 (MIT), their notices
  in the file's banner (kept). All GPL-3.0-compatible.
- **Files:** both, unmodified. Ours beside them: `ejs/solver.html` (the Worker host page) and
  `EjsNChallengeSolver` (spec 12 KP-D27, KP-D30).
- **Imported:** 2026-10-23 (KP.1b).

### 6. QR Code generator (Project Nayuki) — `:ui:tv` dependency `io.nayuki:qrcodegen`

- **Upstream:** https://www.nayuki.io/page/qr-code-generator-library, Maven Central
  `io.nayuki:qrcodegen:1.8.0` (jar sha256 pinned in `gradle/verification-metadata.xml`).
- **License:** MIT (GPL-3.0-compatible). The jar carries no licence file: the notice from its
  sources' header ships as `feature/src/main/res/raw/licence_mit_qrcodegen.txt` (About › Licences).
- **Files:** none copied; a Gradle dependency, unmodified. Ours: `SyncQr.kt` draws its matrix
  (spec 20 Q20, M8.7c).
- **Added:** 2026-10-30 (M8.7c).

### 7. RE2/J (Google) — `:core` dependency `com.google.re2j:re2j`

- **Upstream:** https://github.com/google/re2j, Maven Central `com.google.re2j:re2j:1.8` (jar
  sha256 pinned in `gradle/verification-metadata.xml`).
- **License:** BSD-3-Clause (the Go licence; GPL-3.0-compatible). The jar carries no licence file:
  upstream's `LICENSE` at tag `re2j-1.8` ships verbatim as
  `feature/src/main/res/raw/licence_bsd_3_clause_re2j.txt` (About › Licences).
- **Files:** none copied; a Gradle dependency, unmodified. Ours: `TitleMatcher` compiles the
  filters' title patterns with it (spec 12 KP-D7).
- **Added:** 2026-10-10 (KP.9); listed in About › Licences 2026-10-30.

### 8. googlevideo SABR/UMP protobuf schemas (via LibreTube) — `:core:sabr` `src/main/proto/**`

- **Upstream:** https://github.com/LuanRT/googlevideo `protos/{misc,video_streaming}/*.proto`,
  tag `googlevideo-v4.0.3` (`e0be476`, 2025-08-16; the `.proto` set is unchanged from `a744d6a`
  through v4.0.3). Reached us through LibreTube (entry 2; imported there in `1875129`,
  2025-09-29), which does not credit googlevideo.
- **License:** MIT, `Copyright (c) 2024 LuanRT` (`LICENSE` at that tag; files carry no headers).
  GPL-3.0-compatible. Ships as `feature/src/main/res/raw/licence_mit_luanrt.txt` (About › Licences).
- **Files:** 26 `video_streaming/*.proto` + `misc/common.proto`. Diffed 2026-10-30: 23 identical
  to v4.0.3; 4 carry LibreTube edits (`common.proto` + `XTags`; `media_capabilities.proto` +
  `VideoCodec`/`efficient`; `media_header.proto` `lmt`→`last_modified`; `ump_part_id.proto`
  `CONFIG` deprecated). All 27 are identical to LibreTube `b51f20e`; no KE modifications.
- **Imported:** 2026-09-27 (with entry 2); provenance recorded 2026-10-30.

### 9. BgUtils BotGuard client (via NewPipe → LibreTube) — `:core:potoken` `assets/po_token.html`

- **Upstream:** https://github.com/LuanRT/BgUtils `src/core/botGuardClient.ts` +
  `src/core/webPoMinter.ts`, closest tag `v3.1.2` (`7be24e8`, 2025-01-05; v3.1.1–v3.1.3 match
  equally), hand-ported to plain JS by TeamNewPipe/NewPipe PR #11955 (`53b599b`, credited in the
  PR text only), then copied by LibreTube `d927dff` (whitespace-only diff), vendored here at
  `b51f20e` (entry 3).
- **License:** MIT, `Copyright (c) 2024 LuanRT` (BgUtils `LICENSE`, byte-identical to
  googlevideo's); the NewPipe/LibreTube port adds GPL-3.0 on top. Ships with entry 8's notice
  (`licence_mit_luanrt.txt`) while this asset ships.
- **Files:** `po_token.html` only. Its JSDoc and error strings are verbatim BgUtils. KE
  modification: the 2026-10-25 page-level minter cache (stamped in-file; entry 3).
- **Imported:** 2026-09-27 (with entry 3); provenance recorded 2026-10-30.

### 10. Mozilla Rhino TokenStream (inside the NewPipeExtractor fork) — `:core:extractor`

- **Upstream:** https://github.com/mozilla/rhino `src/org/mozilla/javascript/TokenStream.java`,
  tag `Rhino1_7_14_Release` (`5a917b5`), adapted by TeamNewPipe/NewPipeExtractor (last reworked
  `0c3ac0a`, 2025-03-05) as `utils/jsextractor/EcmaScriptTokenStream.java`; arrives with entry 1.
- **License:** MPL-2.0 (the file keeps the MPL Exhibit A notice and names its Rhino origin; no
  Exhibit B, so it combines with GPL-3.0). Its source is in this repo (MPL §3.2).
- **Files:** that one file, identical to the fork at `3e863d7`; no KE modifications. Keep the
  header; any KE change to it stays MPL-2.0.
- **Imported:** 2026-09-27 (with entry 1); provenance recorded 2026-10-30.

### 11. nanojson (TeamNewPipe fork) — `:core:extractor` dependency `com.github.TeamNewPipe:nanojson`

- **Upstream:** https://github.com/TeamNewPipe/nanojson @ `e9d656ddb49a412a5a0a5d5ef20ca7ef09549996`
  (JitPack; pinned in `gradle/libs.versions.toml`), fork of https://github.com/mmastrac/nanojson.
- **License:** Apache-2.0, chosen: every source file is headed "Copyright 2011 The nanojson
  Authors / Licensed under the Apache License, Version 2.0"; upstream's README alone says
  "dual-licensed under the MIT and Apache Public License" (recipient's choice). Neither repo has a
  LICENSE or NOTICE file and the JitPack jar carries none: the Apache-2.0 text ships (About ›
  Licences, the Apache group).
- **Files:** none copied; a Gradle dependency, unmodified (used by the extractor, entry 1).
- **Added:** 2026-09-27 (with entry 1); provenance recorded 2026-10-30.

### 12. SmartTube MediaServiceCore chat fixtures — `:core` test fixtures `resources/live/smarttube/`

- **Upstream:** https://github.com/yuliskov/MediaServiceCore
  `youtubeapi/src/test/resources/chat_comments/` @ `9a360d3` (files last changed `ea646d7`,
  2024-03-28); byte-identical copies.
- **License:** MIT, `Copyright (c) 2020-present yuliskov` (repo `LICENSE`, copied beside the files).
- **Files:** `get_live_chat.json`, `get_live_chat2.json`, `reload_live_chat.json` (recorded
  InnerTube chat answers: badges, retractions, a timed and a reload-only continuation). Test data
  only; nothing ships in the app (spec 19 LIVE-D8, D9).
- **Imported:** 2026-11-02 (LIVE.5).
