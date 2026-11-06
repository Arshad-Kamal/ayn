package com.arshad.ayn.core.access

/**
 * SponsorBlock seam (M7-D1/D2/D3): the community-marked segments of a video. Main-safe; never
 * throws for a network or server problem, which comes back as [SegmentLookup.Failed], so a
 * failure can't stop playback.
 */
fun interface SegmentSource {
    /** [videoId]'s skippable segments in [categories], in start order. */
    suspend fun segments(videoId: VideoId, categories: Set<SegmentCategory>): SegmentLookup
}

/** The SponsorBlock categories KE offers (M7 derisk ledger: the server's skip-capable set);
 *  [key] is the API's name, also the backup's and the settings column's. */
enum class SegmentCategory(val key: String) {
    Sponsor("sponsor"),
    SelfPromo("selfpromo"),
    Interaction("interaction"),
    Intro("intro"),
    Outro("outro"),
    Preview("preview"),
    Hook("hook"),
    MusicOffTopic("music_offtopic"),
    Filler("filler"),
    ;

    companion object {
        /** The category named [key]; `null` for one this build doesn't offer. */
        fun of(key: String): SegmentCategory? = entries.firstOrNull { it.key == key }
    }
}

/** [startMs] < [endMs], both from the video's start. */
data class Segment(val category: SegmentCategory, val startMs: Long, val endMs: Long)

sealed interface SegmentLookup {
    /** At least one segment, in start order. */
    data class Found(val segments: List<Segment>) : SegmentLookup

    /** SponsorBlock has none for the video in the asked categories. */
    data object None : SegmentLookup

    /** SponsorBlock couldn't be reached or its answer couldn't be read. */
    data object Failed : SegmentLookup
}
