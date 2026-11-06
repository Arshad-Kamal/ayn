package com.arshad.ayn.core.access

/**
 * DeArrow seam (M7-D1/D2/D4): the community's titles and thumbnails for a video, in place of
 * YouTube's. Main-safe; never throws for a network or server problem, which comes back as
 * [BrandingLookup.Failed], so a failure leaves YouTube's own title and picture.
 */
fun interface BrandingSource {
    /**
     * [videoId]'s branding. [originalThumbnailUrl] is the YouTube picture the asking surface shows;
     * a DeArrow thumbnail's URL falls back to it when DeArrow's thumbnail server has no frame
     * (without one, such a URL loads nothing and the surface shows its image placeholder).
     */
    suspend fun branding(videoId: VideoId, originalThumbnailUrl: String?): BrandingLookup

    /**
     * [videoId]'s branding when it is already at hand, without asking DeArrow; `null` when only
     * [branding] can tell. Lets a surface draw a known answer in its first frame.
     */
    fun cached(videoId: VideoId, originalThumbnailUrl: String?): BrandingLookup? = null
}

/** What DeArrow replaces: a `null` [title] or [thumbnailUrl] keeps YouTube's; never both `null`. */
data class Branding(val title: String?, val thumbnailUrl: String?) {
    init {
        require(title != null || thumbnailUrl != null) { "a Branding replaces something" }
    }
}

sealed interface BrandingLookup {
    data class Found(val branding: Branding) : BrandingLookup

    /** DeArrow has nothing trusted for the video, or its best is YouTube's own. */
    data object None : BrandingLookup

    /** DeArrow couldn't be reached or its answer couldn't be read. */
    data object Failed : BrandingLookup
}
