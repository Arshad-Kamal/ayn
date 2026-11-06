package com.arshad.ayn.core.access.newpipe

import com.arshad.ayn.core.access.BrowseKiosk
import com.arshad.ayn.core.access.ChannelTab
import com.arshad.ayn.core.access.Chapter
import com.arshad.ayn.core.access.Comment
import com.arshad.ayn.core.access.Storyboard
import kotlin.time.Instant

/*
 * The recorded extraction corpus (`resources/extract-fixtures/`): what it was recorded from and
 * the values read from its payloads. One home, so a re-record updates expectations here only.
 * Tests assert against these literals, never against values recomputed through the seam
 * (TEST-ORACLE-01).
 */

/** The fixed video every video-flow fixture artifact is pinned to. */
const val FIXED_VIDEO_ID = "dQw4w9WgXcQ"

/** A video with uploader chapters (the fixed video has none): the player mockup's video. */
const val CHAPTERS_VIDEO_ID = "JsBZOcqZerk"
const val CHAPTERS_VIDEO_FLOW = "video-chapters"

/** The video page of the recorded channel feed's newest entry ([RecordedChannel.FEED_ENTRIES]
 *  first): the Home hero's details. */
const val FEED_NEWEST_VIDEO_FLOW = "video-feed-newest"

/** The fixed video's page recorded with the player code available, so the WEB answer carries
 *  its full storyboard spec. The `video` flow was recorded before the WEB request carried the
 *  timestamp: its WEB answer has no storyboards, and it holds no player code. */
const val STORYBOARDS_VIDEO_FLOW = "video-storyboards"

/** The video page of a stream that was live when recorded (the Live kiosk's first item then). */
const val LIVE_VIDEO_FLOW = "video-live"

/** [CHAPTERS_VIDEO_ID]'s related list through the related call (one `next` request, FY.2). */
const val RELATED_FLOW = "related"

/** Video pages YouTube refuses (NewPipeExtractor's own test ids), both `LOGIN_REQUIRED` in the
 *  recorded playability status: an age gate ("Sign in to confirm your age") and a private video
 *  ("Please sign in"). */
const val AGE_RESTRICTED_VIDEO_ID = "rwcfPqbAx-0"
const val AGE_RESTRICTED_VIDEO_FLOW = "video-age-restricted"
const val PRIVATE_VIDEO_ID = "8VajtrESJzA"
const val PRIVATE_VIDEO_FLOW = "video-private"

/** Refusals the player names: a made-for-kids Dora episode (VISIONOS `UNPLAYABLE`
 *  "This video is not available") and a scheduled stream days before it began (`LIVE_STREAM_OFFLINE`,
 *  [UPCOMING_VIDEO_REASON] its recorded reason). */
const val MADE_FOR_KIDS_VIDEO_ID = "L8A4XbM5sXA"
const val MADE_FOR_KIDS_VIDEO_FLOW = "video-made-for-kids"
const val UPCOMING_VIDEO_ID = "ny7_1Wj3s7g"
const val UPCOMING_VIDEO_FLOW = "video-upcoming"
const val UPCOMING_VIDEO_REASON = "This live event will begin in 5 days."

/** A made-for-kids video resolved through MWEB (spec 12 KP-D29): VISIONOS refuses Pinkfong's
 *  video `UNPLAYABLE`, MWEB answers it with every format behind a `signatureCipher`. Recorded with
 *  [MWEB_PLACEHOLDER_PO_TOKEN] (MWEB answers alike with or without a token) and a cold player
 *  code, so its base.js is in the flow. */
const val MWEB_VIDEO_ID = "XqZsoesa55w"
const val MWEB_VIDEO_FLOW = "video-made-for-kids-mweb"

/** Base64url, as a minted token is; YouTube doesn't check it in the player request. */
const val MWEB_PLACEHOLDER_PO_TOKEN = "MnQwbG9yZW0taXBzdW0tZG9sb3Itc2l0LWFtZXQ="

/** The fixed video's comments in one replay session: page 1, page 2, then the replies of page
 *  1's first comment with replies, the pinned one ([RecordedComments.PINNED]). */
const val COMMENTS_FLOW = "comments"

/** A Short's first comments page (the Shorts smoke's Short). */
const val SHORT_COMMENTS_VIDEO_ID = "IHkp5Kq72ro"
const val SHORT_COMMENTS_FLOW = "comments-short"

/** A made-for-kids video: YouTube turns its comments off. */
const val COMMENTS_OFF_VIDEO_ID = "XqZsoesa55w"
const val COMMENTS_OFF_FLOW = "comments-off"

/** [PRIVATE_VIDEO_ID]'s comments: the vendored extractor fails on its `/next` answer. */
const val PRIVATE_COMMENTS_FLOW = "comments-private"

/** The fixed search query: its response mixes live streams and Shorts shelves. */
const val FIXED_SEARCH_QUERY = "lofi hip hop"

/** A search answered with a channel card first (the `b3-search` channel-led results) and Shorts
 *  shelves (the live Shorts smoke asks it too). */
const val CHANNEL_SEARCH_QUERY = "veritasium"
const val CHANNEL_SEARCH_FLOW = "search-channel"

/** A premiere listed before it starts ([UPCOMING_PREMIERE_ID], Chillhop's, starting at
 *  [UPCOMING_PREMIERE_START]): in a search (a `videoRenderer` with its start) and on its channel's
 *  Videos tab (a lockup reading "Premieres 03/11/2026, 16:00"). Recorded alone, before the start. */
const val UPCOMING_SEARCH_QUERY = "Forest Retreat lofi beats chillhop x planet wild"
const val UPCOMING_SEARCH_FLOW = "search-upcoming"
const val UPCOMING_CHANNEL_URL = "https://www.youtube.com/channel/UCOxqgCwgOqC2lMqC5PYz_Dg"
const val UPCOMING_CHANNEL_FLOW = "channel-upcoming"
const val UPCOMING_PREMIERE_ID = "34GOO-dOZNU"
const val UPCOMING_PREMIERE_START = "2026-11-03T16:00:00Z"

/** The search-suggestions flow: one replay session answering [SUGGESTION_QUERIES] in order. */
const val SUGGESTIONS_FLOW = "suggestions"

/** A prefix of [FIXED_SEARCH_QUERY], a prefix outside ISO-8859-1 (the endpoint sends it as
 *  numeric character references), and a query with no completions. */
val SUGGESTION_QUERIES = listOf("lofi hip", "東京", "zzqxjvkw")

/** The channel the channel-scoped flows (`channel-videos`, `channel-feed`) were recorded from:
 *  the fixed video's uploader. */
const val RECORDED_CHANNEL_URL = "https://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw"

/** The recorded channel's page (header + tabs; `channel-page`). */
const val CHANNEL_PAGE_FLOW = "channel-page"

/** The recorded channel's tabs with a recorded first page besides `channel-videos`. */
val CHANNEL_TAB_FLOWS: Map<ChannelTab, String> = mapOf(
    ChannelTab.LIVE to "channel-live",
    ChannelTab.RELEASES to "channel-releases",
    ChannelTab.PLAYLISTS to "channel-playlists",
)

/** Shorts-heavy channel for the filter fixture (D5); the fixed video's uploader has no shorts. */
const val SHORTS_CHANNEL_URL = "https://www.youtube.com/channel/UCX6OQ3DkcsbYNE6H8uQQuVA"

/** A playlist from the recorded channel's Playlists tab ("Rick Astley: Complete", 132 videos):
 *  YouTube serves all of it in the first page. */
const val PLAYLIST_URL = "https://www.youtube.com/playlist?list=PLlaN88a7y2_rosKX2WQt2VjFbjyDQXOkR"
const val PLAYLIST_FLOW = "playlist"

/** The recorded channel's uploads playlist (438 videos, 100 per page): its first two pages in one
 *  replay session. Its Shorts arrive as plain watch videos (no Shorts marker in a playlist). */
const val PAGED_PLAYLIST_URL = "https://www.youtube.com/playlist?list=UUuAXFkgsw1L7xaCfnd5JJOw"
const val PAGED_PLAYLIST_FLOW = "playlist-pages"

/** The recorded channel's first Releases-tab album ([RecordedChannel.RELEASES_TAB_FIRST]). */
const val ALBUM_PLAYLIST_URL = "https://www.youtube.com/playlist?list=OLAK5uy_k0Ob3gxnIKX5SU4di2PRhpNiVfGe7_rbk"
const val ALBUM_PLAYLIST_FLOW = "playlist-album"

/** A well-formed playlist id that doesn't exist: YouTube's "not available" answer. */
const val UNAVAILABLE_PLAYLIST_URL = "https://www.youtube.com/playlist?list=PLlaN88a7y2_0000000000000000000000"
const val PLAYLIST_UNAVAILABLE_FLOW = "playlist-unavailable"

/** Each exposed kiosk's recorded flow directory. */
val KIOSK_FLOWS: Map<BrowseKiosk, String> = mapOf(
    BrowseKiosk.TRENDING_MUSIC to "kiosk-trending-music",
    BrowseKiosk.TRENDING_GAMING to "kiosk-trending-gaming",
    BrowseKiosk.TRENDING_MOVIES_AND_SHOWS to "kiosk-trending-movies",
    BrowseKiosk.TRENDING_PODCASTS to "kiosk-trending-podcasts",
    BrowseKiosk.LIVE to "kiosk-live",
)

object RecordedVideo {
    const val TITLE = "Rick Astley - Never Gonna Give You Up (Official Video) (4K Remaster)"
    const val UPLOADER_NAME = "Rick Astley"
    const val DURATION_SECONDS = 213L
    val VP9_VIDEO_ITAGS = listOf(313, 271, 248, 247, 244, 243, 242, 278)
    val AUDIO_ITAGS = listOf(139, 140, 249, 250, 251)
    const val UHD_ITAG = 313
    const val UHD_FPS = 25
    const val UHD_LAST_MODIFIED = 1766963492248817L
    const val UHD_BITRATE = 18076636
    const val OPUS_ITAG = 251
    const val OPUS_LAST_MODIFIED = 1766955883819090L

    /** The VISIONOS answer's caption tracks in its order: language code, YouTube's name, and
     *  whether YouTube made it (`vssId` "a.…"). */
    val CAPTION_TRACKS = listOf(
        Triple("en", "English", false),
        Triple("en", "English (auto-generated)", true),
        Triple("de-DE", "German (Germany)", false),
        Triple("ja", "Japanese", false),
        Triple("pt-BR", "Portuguese (Brazil)", false),
        Triple("es-419", "Spanish (Latin America)", false),
    )

    /** How the first track's link starts in the recording. */
    const val FIRST_CAPTION_LINK_PREFIX = "\"captionTracks\":[{\"baseUrl\":\"https://www.youtube.com/api/timedtext?"

    /** The attributed description's text through its first two links (a hashtag and a URL,
     *  each shown as its own text), and its line count. */
    const val DESCRIPTION_PREFIX = "The official video for “Never Gonna Give You Up” by Rick Astley. \n\n" +
        "Never: The Autobiography 📚 OUT NOW! \n" +
        "Follow this link to get your copy and listen to Rick’s ‘Never’ playlist ❤️ #RickAstleyNever\n" +
        "https://linktr.ee/rickastleynever"
    const val DESCRIPTION_LINES = 57

    /** The storyboard spec's levels with frames, page URLs aside (its L0 has no frame duration
     *  and is skipped), and each level's page count. */
    val STORYBOARDS_WITHOUT_PAGES = listOf(
        Storyboard(
            pageUrls = emptyList(), frameWidth = 80, frameHeight = 45, frameCount = 108,
            frameDurationMs = 2000, framesPerPageX = 10, framesPerPageY = 10,
        ),
        Storyboard(
            pageUrls = emptyList(), frameWidth = 160, frameHeight = 90, frameCount = 108,
            frameDurationMs = 2000, framesPerPageX = 5, framesPerPageY = 5,
        ),
    )
    val STORYBOARD_PAGE_COUNTS = listOf(2, 5)
    const val STORYBOARD_LARGEST_FIRST_PAGE =
        "https://i.ytimg.com/sb/dQw4w9WgXcQ/storyboard3_L2/M0.jpg?sqp=-oaymwGhAUg48quKqQOYAYgBAZUBAAAEQpgBMq" +
            "ABPKgBBLIBQBANDBAVHyYtDg4PEhcrLCkPDhAVHyoyKQ8RFBgmPTgtERQeKjFLRzYVHCkuOUdNPyUuNz1HUlFFM0BCQ0xERkO6" +
            "AUARERUjRENDQxETFi9DQ0NDFRYpQ0NDQ0MjL0NDQ0NDQ0RDQ0NDQ0JCQ0NDQ0NCQkJDQ0NDQkJCQkNDQ0JCQkJCovOX_wMGCNfE" +
            "98EG&sigh=rs\$AOn4CLDyMxW8G-P665J32lpJ-kENgxaqpQ"
}

/** The fixed video's WEB storyboard spec ([STORYBOARDS_VIDEO_FLOW]; recorded 2026-10-04): the
 *  levels with frames (L0 skipped), the largest 320x180 on 3x3 pages. */
object RecordedStoryboardsVideo {
    val STORYBOARDS_WITHOUT_PAGES = listOf(
        Storyboard(
            pageUrls = emptyList(), frameWidth = 80, frameHeight = 45, frameCount = 108,
            frameDurationMs = 2000, framesPerPageX = 10, framesPerPageY = 10,
        ),
        Storyboard(
            pageUrls = emptyList(), frameWidth = 160, frameHeight = 90, frameCount = 108,
            frameDurationMs = 2000, framesPerPageX = 5, framesPerPageY = 5,
        ),
        Storyboard(
            pageUrls = emptyList(), frameWidth = 320, frameHeight = 180, frameCount = 108,
            frameDurationMs = 2000, framesPerPageX = 3, framesPerPageY = 3,
        ),
    )
    const val LARGEST_PAGE_COUNT = 12

    /** The streaming client's (VISIONOS) levels in the same recording: what frames fall back to. */
    val STREAMING_CLIENT_LEVELS = STORYBOARDS_WITHOUT_PAGES.take(2)

    /** `signatureTimestamp` in the recorded `base.js`. */
    const val SIGNATURE_TIMESTAMP = 20725
    const val LARGEST_FIRST_PAGE =
        "https://i.ytimg.com/sb/dQw4w9WgXcQ/storyboard3_L3/M0.jpg?sqp=-oaymwGhAUg48quKqQOYAYgBAZUBAAAEQpgBMq" +
            "ABPKgBBLIBQBANDBAVHyYtDg4PEhcrLCkPDhAVHyoyKQ8RFBgmPTgtERQeKjFLRzYVHCkuOUdNPyUuNz1HUlFFM0BCQ0xERkO6" +
            "AUARERUjRENDQxETFi9DQ0NDFRYpQ0NDQ0MjL0NDQ0NDQ0RDQ0NDQ0JCQ0NDQ0NCQkJDQ0NDQkJCQkNDQ0JCQkJCovOX_wMGCNfE" +
            "98EG&sigh=rs\$AOn4CLBw0a8jqAS7wFwQYMJBAZ6zuP4XRw"
}

/** The feed's newest video ([FEED_NEWEST_VIDEO_FLOW]; recorded 2026-10-03): its tallest video
 *  streams (1080p VP9 and AVC, both 25 fps), length, picture, the description's first sentence,
 *  and its premiere's broadcast start. */
object RecordedFeedNewestVideo {
    const val DURATION_SECONDS = 231L
    const val MAX_HEIGHT = 1080
    const val FPS = 25
    /** The largest of the page's pictures (1920×1080; the RSS entry offers 480×360). */
    const val THUMBNAIL_URL = "https://i.ytimg.com/vi/PXC_PYeB6F8/maxresdefault.jpg?v=6a3bcea7"
    const val DESCRIPTION_PREFIX = "Taken from Rick Astley’s sold-out Reflections Tour, this live performance of " +
        "“Angels On My Side” was recorded at London’s O2 Arena in April 2026."

    /** Its WEB player answer's `liveBroadcastDetails` (not live now) starts 2026-06-26T16:00:07+00:00
     *  and ends 16:05:58. */
    val BROADCAST_STARTED_AT: Instant = Instant.parse("2026-06-26T16:00:07Z")

    /** Its WEB `next` answer's chat replay seed (`liveChatRenderer.isReplay`), as written. */
    const val CHAT_REPLAY_SEED = "op2w0wRyGl5DaWtxSndvWVZVTjFRVmhHYTJkemR6Rk1OM2hoUTJadVpEVktTazkzRWd0UVdFTmZVRmxsUWpaR09Cb1Q2cWpkdVFFTkNndFFXRU5mVUZsbFFqWkdPQ0FCTUFBJTNEQAFyDAgEGAIgACgAMAA4AHgB"
}

/** The live stream recorded in [LIVE_VIDEO_FLOW] (2026-10-03; its player answer: `isLive`, length 0). */
object RecordedLiveVideo {
    const val ID = "hXvqMjC_kuE"

    /** How its VISIONOS answer's `hlsManifestUrl` begins (the variant playlist on the manifest host). */
    const val HLS_URL_PREFIX = "https://manifest.googlevideo.com/api/manifest/hls_variant/"

    /** Its WEB player answer's `liveBroadcastDetails.startTimestamp` (2026-10-03T23:50:06+00:00);
     *  the same answer's `uploadDate` is 2 minutes earlier. */
    val STARTED_AT: Instant = Instant.parse("2026-10-03T23:50:06Z")

    /** Its WEB `next` answer's chat seed (`conversationBar.liveChatRenderer`, no `isReplay`), its
     *  trailing `%3D%3D` decoded. */
    const val CHAT_SEED = "0ofMyAOEARpeQ2lrcUp3b1lWVU5yTTFOeVgwbEtTQzF6T1dwdVFYVnFiM1JZUXkxbkVndG9XSFp4VFdwRFgydDFSUm9UNnFqZHVRRU5DZ3RvV0haeFRXcERYMnQxUlNBQk1BQSUzRDABggEMCAQYAiAAKAAwADgAiAEBoAGz9NWBi5-XA6gBALIBAA=="
}

/** The chapters video ([CHAPTERS_VIDEO_ID]): its chapter panel, in panel order. */
object RecordedChaptersVideo {
    const val DURATION_SECONDS = 2861L

    /** The owner block's channel (Veritasium). */
    const val UPLOADER_NAME = "Veritasium"
    const val UPLOADER_URL = "https://www.youtube.com/channel/UCHnyfMqiRRG1u-2MsSQLbXA"

    /** The `next` payload's like button: "like this video along with 121,954 other people". */
    const val LIKE_COUNT = 121_954L

    /** That like button's label, as the payload carries it. */
    const val LIKE_LABEL = "\"accessibilityText\":\"like this video along with 121,954 other people\""

    /** The VISIONOS answer's caption tracks' names: one manual English track and YouTube's own. */
    val CAPTION_TRACK_NAMES = listOf("English", "English (auto-generated)")

    /** The `next` payload's owner block: "21.3m subscribers", as YouTube rounds it. */
    const val SUBSCRIBER_COUNT = 21_300_000L

    /** That count's text, as the payload carries it. */
    const val SUBSCRIBER_TEXT = "\"simpleText\":\"21.3m subscribers\""

    /** The owner block's largest avatar (176 px) in the `next` payload. */
    const val UPLOADER_AVATAR_URL =
        "https://yt3.ggpht.com/7vCbvtCqtjQ3YLgsJt7Y952MQV1sBvhllSCSxHP8_sVZdcPCBrITfhkN2RdyCuwPnsByq-1GoA=s176-c-k-c0x00ffffff-no-rj"
    val CHAPTERS = listOf(
        Chapter("Who invented Enigma?", 0),
        Chapter("How did Enigma work?", 256),
        Chapter("The Secret Spy Mansion", 651),
        Chapter("The First Codebreakers Who Broke Enigma", 790),
        Chapter("Trying To Decrypt An Enigma Message", 1005),
        Chapter("Who was Alan Turing?", 1355),
        Chapter("How Enigma’s Flaws Were Exploited", 1590),
        Chapter("Breaking Enigma - The Bombe", 2067),
        Chapter("The Naval Enigma", 2499),
        Chapter("What did Alan Turing do after Bletchley Park?", 2675),
    )

    /** The attributed description's text through its first link (its quotes travel as `&quot;`
     *  in the extractor's HTML), and its line count. */
    const val DESCRIPTION_PREFIX =
        "How was the \"unbreakable\" enigma cracked? Sponsored by Incogni - use code veritasium at " +
            "https://incogni.com/veritasium"
    const val DESCRIPTION_LINES = 64
}

/** The related items in the recorded `next` (WEB) answers (`secondaryResults` lockups, in order). */
object RecordedRelatedVideos {
    /** [CHAPTERS_VIDEO_FLOW]: 20 video lockups. */
    val CHAPTERS_VIDEO_IDS = listOf(
        "MiUHjLxm3V0", "twDvxNooI_4", "EkLmxmUfgBU", "_L1Rim7-IPo", "eNkxTjirY-Q",
        "isNCO2P24fA", "ugPCuLZz350", "Tt6WQYtefXA", "OETnuwwsv9U", "0QWP4IZOu0I",
        "A5w-dEgIU1M", "C_sUSQGT83A", "Oz7GXguhCfA", "s-Vv9qq4iT0", "BXLGV0Sj0n8",
        "xkXvL8YCtIE", "fHRS_NOs24w", "wt4p2oalmRY", "YyhzgNdSnbQ", "s7KhLuc3Mck",
    )

    /** The chapters page's first lockup as its payload shows it: badge `55:00`, `60m` views
     *  (labelled "60 million views"), `9 mo ago`, and its largest picture (336×188). */
    const val CHAPTERS_FIRST_TITLE = "The Closest Thing We Have to Alien Technology"
    const val CHAPTERS_FIRST_UPLOADER_NAME = "Veritasium"
    const val CHAPTERS_FIRST_UPLOADER_URL = "https://www.youtube.com/channel/UCHnyfMqiRRG1u-2MsSQLbXA"
    const val CHAPTERS_FIRST_DURATION_SECONDS = 3300L
    const val CHAPTERS_FIRST_VIEW_COUNT = 60_000_000L
    const val CHAPTERS_FIRST_TEXTUAL_UPLOAD_DATE = "9 mo ago"
    const val CHAPTERS_FIRST_THUMBNAIL_URL =
        "https://i.ytimg.com/vi/MiUHjLxm3V0/hqdefault.jpg?sqp=-oaymwEjCNACELwBSFryq4qpAxUIARUAAAAAGAElAADIQj0AgKJDeAE=" +
            "&rs=AOn4CLDMHaxvD6EebagtNAlCHG_hE5vuJA"

    /** [LIVE_VIDEO_FLOW]: 26 video lockups; the 16th carries the LIVE badge. */
    const val LIVE_PAGE_COUNT = 26
    const val LIVE_PAGE_LIVE_INDEX = 15
    const val LIVE_PAGE_LIVE_ID = "82CIirYB7RY"

    /** [STORYBOARDS_VIDEO_FLOW]: 25 video lockups and one mix (`RDdQw4w9WgXcQ`, 8th). */
    const val STORYBOARDS_PAGE_VIDEO_COUNT = 25

    /** [RELATED_FLOW] (the related call's own answer, recorded 2026-10-09): 20 video lockups,
     *  then a continuation; the first's badge is `24:39`. */
    val RELATED_FLOW_IDS = listOf(
        "J_n1FZaKzF8", "J3i3F2e4IYs", "pVxSuzRLWEM", "CBPacbMFmYI", "ksn5yrsC3Wg",
        "BXLGV0Sj0n8", "rnjX3AunsMM", "tZ8ehplVFp4", "tz23G_UXCGA", "EkLmxmUfgBU",
        "MNu4jXqHwIM", "QwN0K2ZSAoQ", "kcQ7n9-hkRU", "tQFE_3Mr4yQ", "sjqnBZVz65E",
        "OETnuwwsv9U", "lfkjm2YRG-Q", "DW-aL_MiUz4", "Q56PMJbCFXQ", "hhjhU5MXZOo",
    )
    const val RELATED_FLOW_FIRST_DURATION_SECONDS = 1479L
}

object RecordedSearch {
    const val PAGE_1_VIDEOS = 19
    const val PAGE_1_FIRST_ID = "n61ULEU7CO0"
    const val PAGE_2_VIDEOS = 19
    const val PAGE_2_FIRST_ID = "ovS27OGTajs"

    /** Page 1's three Shorts shelves (`gridShelfViewModel`): 25 Shorts, each once; the first's
     *  "1.1k views" and its larger (405×720) picture source. */
    const val PAGE_1_SHORTS = 25
    const val PAGE_1_FIRST_SHORT_ID = "lKz5bucgvGk"
    const val PAGE_1_FIRST_SHORT_VIEWS = 1_100L
    const val PAGE_1_FIRST_SHORT_PICTURE =
        "https://i.ytimg.com/vi/lKz5bucgvGk/oar2.jpg?sqp=-oaymwEkCJUDENAFSFqQAgHyq4qpAxMIARUAAAAAJQAAyEI9AICiQ3gB" +
            "&rs=AOn4CLAW5hWdUjBGQO8-Nh7fP-PFSxetjQ&usqp=CCk"
    /** The one Short whose only picture source is square (720×720). */
    const val PAGE_1_SQUARE_PICTURE_SHORT_ID = "axb1HFlJBOA"
    const val PAGE_1_LAST_SHORT_ID = "AMSypSjbOiM"
    const val PAGE_2_SHORTS = 25
    const val PAGE_2_FIRST_SHORT_ID = "_hg9QIQP9Vw"
}

/** [CHANNEL_SEARCH_FLOW] (recorded 2026-10-03): the channel's `channelRenderer` first, then 19
 *  `videoRenderer`s; its two video shelves (`shelfRenderer`, `gridShelfViewModel`) go unparsed. */
object RecordedChannelSearch {
    const val CHANNEL_NAME = "Veritasium"
    const val CHANNEL_URL = "https://www.youtube.com/channel/UCHnyfMqiRRG1u-2MsSQLbXA"
    /** "21.3m subscribers" in the payload. */
    const val SUBSCRIBERS = 21_300_000L
    const val VIDEOS = 19
    const val FIRST_VIDEO_ID = "Y-W-w8yNiKU"
    const val FIRST_VIDEO_TITLE = "Why is there antimatter?"
    /** "10,090,589 views" and "9 months ago" in the payload. */
    const val FIRST_VIDEO_VIEWS = 10_090_589L
    const val FIRST_VIDEO_AGE = "9 months ago"

    /** Its Shorts shelves list 56 Shorts, 35 of them distinct (shelves repeat); first and last
     *  in page order. */
    const val SHORTS = 35
    const val FIRST_SHORT_ID = "IHkp5Kq72ro"
    const val FIRST_SHORT_TITLE = "How Dangerous Are Non-Stick Pans?"
    const val LAST_SHORT_ID = "96-wuwhtwn8"
}

/** Each [SUGGESTION_QUERIES] entry's recorded answer, in YouTube's order (references decoded
 *  with Python's `html.unescape`); YouTube echoes the typed query among them. */
object RecordedSuggestions {
    val LOFI_HIP = listOf(
        "lofi hip hop", "lofi hip hop coffee shop", "lofi hip hop beats", "lofi hip hop girl",
        "lofi hip hop radio", "lofi hip hop jazz", "lofi hip hop instrumental",
        "lofi hip hop radio - beats to relax/study to", "lofi hip hop sleep", "lofi hip hop study",
        "lofi hip hop live", "lofi hip hop study music", "lofi hip hop mix", "lofi hip",
    )
    val TOKYO = listOf(
        "東京大学", "東京", "東京喰種", "東京メトロ", "東京盆踊り", "東京サマーセッション", "東京アイドル劇場",
        "東京ニュース通信社チャンネル", "東京大空襲", "東京フラッシュ", "東京音頭", "東京愛情故事主題曲",
        "東京リベンジャーズ", "東京人壽",
    )
}

/** Video counts per non-live kiosk (the live kiosk's count varies by shelf and is not pinned). */
object RecordedKiosks {
    val VIDEO_COUNTS: Map<BrowseKiosk, Int> = mapOf(
        BrowseKiosk.TRENDING_MUSIC to 30,
        BrowseKiosk.TRENDING_GAMING to 50,
        BrowseKiosk.TRENDING_MOVIES_AND_SHOWS to 30,
        BrowseKiosk.TRENDING_PODCASTS to 99,
    )
    const val TRENDING_MUSIC_FIRST_ID = "V-uIp-WuD60"
}

object RecordedChannel {
    const val UPLOADS_TAB_VIDEOS = 30

    /** The page header's largest banner crop (2560×424, `=w2560-fcrop64=…`) as its uncropped art. */
    const val BANNER_URL =
        "https://yt3.googleusercontent.com/pi1YBgcetxpQG-xtEKHBLwe3nUxoo-cT1AKYfqLxh8krCgnkpZZM5zOtP2VlFfSFnRFi8EyA=w1920"

    /** The uploads tab's first eight ids, in tab order; the first four share "3 months ago". */
    val UPLOADS_TAB_FIRST_IDS = listOf(
        "PXC_PYeB6F8", "LaOUkDBDjW8", "wvr7-pDJUOA", "dE2TxYSDy4Y",
        "LjmOX9jGoR4", "zRVjZ2DJ9Cg", "WyK7s-osTLs", "2v3XUO0l7eE",
    )
    const val UPLOADS_TAB_FIRST_DURATION_SECONDS = 231L

    /** The RSS feed's watch-linked entries (its 11 Shorts never cross the seam), each with its
     *  recorded `<published>` instant, newest first. */
    val FEED_ENTRIES: List<Pair<String, Instant>> = listOf(
        "PXC_PYeB6F8" to Instant.parse("2026-06-26T16:00:07Z"),
        "LaOUkDBDjW8" to Instant.parse("2026-06-24T16:00:06Z"),
        "wvr7-pDJUOA" to Instant.parse("2026-06-22T14:00:06Z"),
        "dE2TxYSDy4Y" to Instant.parse("2026-06-19T16:00:06Z"),
    )

    /** The page's tabs in page order (its Home and Posts tabs have no seam kind). */
    val PAGE_TABS = listOf(
        ChannelTab.VIDEOS, ChannelTab.SHORTS, ChannelTab.LIVE, ChannelTab.RELEASES, ChannelTab.PLAYLISTS,
    )

    /** Playlists tab: 13 playlists; the first three as (list id, title, video count). */
    const val PLAYLISTS_TAB_COUNT = 13
    val PLAYLISTS_TAB_FIRST = listOf(
        Triple("PLlaN88a7y2_qHDbY9eQbuNTAuEJUSEeuu", "Rick Astley Live at The O2", 3L),
        Triple("PLlaN88a7y2_oK0nKMjZSwdU_njxUYWykm", "Rick Astley (リック・アストリー) － 名曲ミュージックビデオ（日本語字幕）", 5L),
        Triple("PLlaN88a7y2_pVVkl4fGdTny21_LRyT85E", "Forever and More", 2L),
    )

    /** The first playlist's cover (its lockup's one thumbnail source). */
    const val PLAYLISTS_TAB_FIRST_THUMBNAIL =
        "https://i.ytimg.com/vi/PXC_PYeB6F8/hqdefault.jpg?sqp=-oaymwEXCOADEI4CSFryq4qpAwkIARUAAIhCGAE=&rs=AOn4CLA_XxBpjZPBAbBTTuMudhKwQrrHDA"

    /** Releases tab: 30 albums/singles; the first as (list id, title, video count). */
    const val RELEASES_TAB_COUNT = 30
    val RELEASES_TAB_FIRST = Triple("OLAK5uy_k0Ob3gxnIKX5SU4di2PRhpNiVfGe7_rbk", "Raindrops", 2L)

    /** Live tab: one past stream (a VOD now, 12:39). */
    val LIVE_TAB_IDS = listOf("YDl88bMN64E")
    const val LIVE_TAB_FIRST_DURATION_SECONDS = 759L

    /** A feed-test clock two days after the newest entry. */
    val FEED_CLOCK: Instant = Instant.parse("2026-06-28T16:00:00Z")

    /** Entries carrying the feed's "New" badge at [FEED_CLOCK] (4-day window): 06-26 and 06-24;
     *  06-22T14:00 is outside it. */
    val FEED_NEW_IDS = listOf("PXC_PYeB6F8", "LaOUkDBDjW8")

    /** The RSS feed's 11 `/shorts/` entries, newest first, with their `<published>` instants; the
     *  first has `views="33603"`. */
    val FEED_SHORTS: List<Pair<String, Instant>> = listOf(
        "E_MGy41IYVw" to Instant.parse("2026-09-20T08:52:27Z"),
        "ihRdK3x3cUY" to Instant.parse("2026-08-31T15:56:01Z"),
        "JjI4o2w6D5A" to Instant.parse("2026-08-05T16:19:00Z"),
        "GdbjNGtWPe4" to Instant.parse("2026-07-27T14:30:23Z"),
        "YJi5uH8YHG8" to Instant.parse("2026-07-27T12:14:54Z"),
        "WmXE7IfqXQ8" to Instant.parse("2026-07-27T12:11:28Z"),
        "S069PVmKXZ4" to Instant.parse("2026-06-29T12:54:29Z"),
        "jbHtuMS-7m0" to Instant.parse("2026-06-29T12:53:06Z"),
        "QVpvmDYrnPo" to Instant.parse("2026-06-22T14:46:13Z"),
        "qB6u-nqQ67w" to Instant.parse("2026-06-22T14:26:14Z"),
        "Jtnz8GsmWCI" to Instant.parse("2026-06-22T14:24:25Z"),
    )
    const val FEED_FIRST_SHORT_VIEWS = 33_603L
}

/** The `channel-shorts` flow: [SHORTS_CHANNEL_URL]'s Shorts tab, first page (it continues). */
object RecordedShortsTab {
    const val SHORTS = 48
    const val FIRST_ID = "CEJXqm2eiJ0"
    const val FIRST_TITLE = "What’s Inside My Briefcase?"
    /** "11m views" in the payload. */
    const val FIRST_VIEWS = 11_000_000L
    /** The first's larger (405×720) picture source. */
    const val FIRST_PICTURE =
        "https://i.ytimg.com/vi/CEJXqm2eiJ0/sardefault.jpg?sqp=-oaymwEkCJUDENAFSFqQAgHyq4qpAxMIARUAAAAAJQAAyEI9AICiQ3gB" +
            "&rs=AOn4CLDOJsTazSXO5_h5chQGIRm_OLQ18A"
}

/** [PLAYLIST_FLOW] and [PAGED_PLAYLIST_FLOW] (recorded 2026-10-03). */
object RecordedPlaylist {
    const val TITLE = "Rick Astley: Complete"
    const val UPLOADER_NAME = "Rick Astley"
    const val VIDEO_COUNT = 132L

    /** The sidebar's largest owner avatar (176 px) and cover (336×188). */
    const val UPLOADER_AVATAR_URL =
        "https://yt3.ggpht.com/_ShEGOdg-t5YGJse14Ooq6FYBZqX_QSlhaTNjG06miYusm5lWm1UroLyVxnYtahADfRTVLtuVQ=s176-c-k-c0x00ffffff-no-rj"
    const val THUMBNAIL_URL =
        "https://i.ytimg.com/vi/LjmOX9jGoR4/hqdefault.jpg?sqp=-oaymwEXCNACELwBSFryq4qpAwkIARUAAIhCGAE=&rs=AOn4CLCDlEdFTHsVkJMghwjxm5arQV4ITA"

    /** The playlist's first three videos, in playlist order (all 132 arrive in the first page). */
    val FIRST_IDS = listOf("LjmOX9jGoR4", "wvr7-pDJUOA", "PXC_PYeB6F8")
    const val LAST_ID = "dQw4w9WgXcQ"

    /** The playlist holds this video twice, at [REPEATED_POSITIONS] (0-based). */
    const val REPEATED_ID = "1fQzZThC3hc"
    val REPEATED_POSITIONS = listOf(26, 27)

    /** The first video's row: 3:36, "133k views", "5 months ago". */
    const val FIRST_DURATION_SECONDS = 216L
    const val FIRST_VIEW_COUNT = 133_000L
    const val FIRST_AGE = "5 months ago"

    /** The uploads playlist: 438 videos, 100 per page. */
    const val PAGED_VIDEO_COUNT = 438L
    const val PAGE_SIZE = 100
    const val PAGE_1_FIRST_ID = "E_MGy41IYVw"
    const val PAGE_2_FIRST_ID = "wZQYjRH7MKQ"
}

/** [ALBUM_PLAYLIST_FLOW] (recorded 2026-10-03): an album's sidebar names no owner, and its cover is
 *  the microformat's square art (largest 1200×1200). */
object RecordedAlbumPlaylist {
    const val TITLE = "Raindrops"
    const val VIDEO_COUNT = 2L
    const val THUMBNAIL_URL =
        "https://i9.ytimg.com/s_p/OLAK5uy_k0Ob3gxnIKX5SU4di2PRhpNiVfGe7_rbk/maxresdefault.jpg?sqp=CNSrhtYGir7X7AMICO3Wlc4GEAE=&rs=AOn4CLAZpglp1klAifyjSYqzRSOYWExP1w&v=1774545773&days_since_epoch=20729"
    val IDS = listOf("LjmOX9jGoR4", "zRVjZ2DJ9Cg")
}

/** The `comments` and `comments-short` flows, read from their `/next` answers (the comment entity
 *  mutations and the comments header). */
object RecordedComments {
    const val TOTAL_COUNT = 2_458_514L

    val PAGE_1_IDS = listOf(
        "Ugzge340dBgB75hWBm54AaABAg", "UgyEnXfdC-umwvTt8JF4AaABAg", "Ugw1tpIYg_PAwdPneep4AaABAg",
        "Ugw_ueQKh1eJhMfZF6B4AaABAg", "Ugwh4Y8VwARbJByeUeh4AaABAg", "UgwxEqSh_78DU_MOKpt4AaABAg",
        "UgzKYksqeQ5gA3t0g0F4AaABAg", "UgxPYbA7XtJzYXPPPHV4AaABAg", "UgxkJEVKXx7Pdr5jl714AaABAg",
        "Ugxzbv_ZeZXNRcfwsSF4AaABAg", "UgxPj5pJUKVWcrWBM7R4AaABAg", "UgxIOV-sXAchpKX94cB4AaABAg",
        "UgzAPyU-lz3PsMHOtW14AaABAg", "UgwPwTibxdgqCHZxktd4AaABAg", "UgxnXZ9YL49LZldVyId4AaABAg",
        "Ugw10bSluCDSZMIxUSB4AaABAg", "UgyGkhCUtvH0jO7k5-p4AaABAg", "UgyyEqaBXOKgoi4cf7l4AaABAg",
        "UgyDzA9Cfn_4Ix7XC1p4AaABAg", "UgwW_cL1iucaVqoLCZV4AaABAg",
    )

    /** Page 1's first comment, pinned and hearted by the channel (its replies token aside), and
     *  its first with replies. */
    val PINNED = Comment(
        id = "Ugzge340dBgB75hWBm54AaABAg",
        authorName = "@YouTube",
        authorUrl = "https://www.youtube.com/channel/UCBR8-60-B28hp2BmDPdntcQ",
        authorAvatarUrl = "https://yt3.ggpht.com/3s6evpqAiDU9tQR4sC2siJippbH2RWVPnwHgyl4V0th2iuQz0VDQZbUhQBGmsxLYo-mjG6TqZQ=s88-c-k-c0x00ffffff-no-rj",
        text = "can confirm: he never gave us up",
        age = "1 year ago",
        edited = false,
        likes = "324k",
        replyCount = 962,
        repliesToken = null,
        pinned = true,
        hearted = true,
        byChannelOwner = false,
    )

    /** Page 1's 7th comment: YouTube's age reads "6 years ago (edited)". */
    const val EDITED_INDEX = 6
    const val EDITED_AGE = "6 years ago"

    /** Page 1's 12th comment: two lines, written with "\r\n". */
    const val TWO_LINES_INDEX = 11
    const val TWO_LINES_TEXT = "1987 : normal song \n2026 : national anthem of the universe"

    /** Page 1's 13th comment has no replies. */
    const val NO_REPLIES_INDEX = 12

    val PAGE_2_FIRST_IDS = listOf("Ugz1GHqskNUi7wsSw6V4AaABAg", "UgyW4kfw4IeIP2SVP6N4AaABAg", "UgwqDetKBODbawt925p4AaABAg")
    const val PAGE_2_SIZE = 20

    /** The first replies page of [PINNED] (it continues: 962 replies). */
    val REPLY_IDS = listOf(
        "AHE9eIiztxR", "AHEAB_-JmDA", "AHEAOCSlaNN", "AHEAQwtwdoZ", "AHEB5iPJLDp",
        "AHEBI08hWqs", "AHEBULT5fJJ", "AHEBWJTfo_r", "AHEBXV4hkL0", "AHECJEr09Uk",
    ).map { "Ugzge340dBgB75hWBm54AaABAg.AHE8_QAWJx9$it" }

    const val SHORT_TOTAL_COUNT = 1_013L
    const val SHORT_PAGE_SIZE = 20
    val SHORT_FIRST_IDS = listOf("UgyxBtDdxGdUBoaVgGV4AaABAg", "UgyFTIh7tzvqq7InNxR4AaABAg", "Ugx65f3fD--GK_Sg3p94AaABAg")
}
