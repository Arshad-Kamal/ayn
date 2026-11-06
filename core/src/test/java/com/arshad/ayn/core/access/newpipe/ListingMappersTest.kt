package com.arshad.ayn.core.access.newpipe

import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.PlaylistItem
import com.arshad.ayn.core.access.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.Image.ResolutionLevel
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * Synthetic mapping tests: the Shorts filter, the LIVE marker (D11), unknown-count
 * pass-through, and the PageToken round-trip, each pinned without network fixtures.
 */
class ListingMappersTest {

    @Test
    fun shortsUrlsAreFilteredEvenWithoutTheFlag() {
        // The RSS feed path carries no isShortFormContent signal; the URL is the guard.
        val shortsUrl = StreamInfoItem(
            0,
            "https://www.youtube.com/shorts/E_MGy41IYVw",
            "A short",
            StreamType.VIDEO_STREAM,
        )

        assertNull(shortsUrl.toVideoItemOrNull())
    }

    @Test
    fun shortsLinkShapesAreGuarded() {
        // Shapes the extractor and RSS feeds actually emit; the corpus only carries the
        // clean www form, so the rest are pinned here.
        assertTrue("https://www.youtube.com/shorts/x?feature=share".isShortsLink())
        assertTrue("https://m.youtube.com/shorts/x".isShortsLink())
        assertTrue("www.youtube.com/shorts/x".isShortsLink())
        assertFalse("https://www.youtube.com/watch?v=x".isShortsLink())
        assertFalse("https://www.youtube.com/watch?v=x&shorts=1".isShortsLink())
        assertFalse("https://youtu.be/x".isShortsLink())
        assertFalse(null.isShortsLink())
    }

    @Test
    fun invalidVendoredPagesProduceNoToken() {
        assertNull(Page(null, null, emptyList(), emptyMap(), null).toPageToken())
    }

    @Test
    fun channelInfoMapsToTheHeaderModel() {
        val info = ChannelInfo(
            0,
            "UCcccccccccccccccccccccc",
            "https://www.youtube.com/channel/UCcccccccccccccccccccccc",
            "https://www.youtube.com/channel/UCcccccccccccccccccccccc",
            "Bench & Solder",
        )
        info.avatars = listOf(Image("https://example.com/avatar.png", 512, 512, ResolutionLevel.MEDIUM))
        info.banners = listOf(
            Image("https://example.com/banner-small", 100, 1700, ResolutionLevel.MEDIUM),
            Image("https://example.com/banner", 256, 4384, ResolutionLevel.HIGH),
        )
        info.subscriberCount = 234_000
        info.description = "Repair logs and full recaps of vintage audio gear."
        info.isVerified = true

        val mapped = info.toChannelItem()

        assertEquals("Bench & Solder", mapped.name)
        assertEquals("https://www.youtube.com/channel/UCcccccccccccccccccccccc", mapped.url)
        assertEquals("https://example.com/avatar.png", mapped.avatarUrl)
        assertEquals("https://example.com/banner", mapped.bannerUrl) // bestImage picks the largest
        assertEquals(234_000, mapped.subscriberCount)
        assertEquals("Repair logs and full recaps of vintage audio gear.", mapped.description)
        assertTrue(mapped.verified)
    }

    @Test
    fun aYt3BannerCropAsksForTheUncroppedArtAtTvWidth() {
        val crop = "https://yt3.googleusercontent.com/pi1YBgce=w2560-fcrop64=1,00005a57ffffa5a8-k-c0xffffffff-no-nd-rj"

        assertEquals("https://yt3.googleusercontent.com/pi1YBgce=w1920", uncroppedBanner(crop))
    }

    @Test
    fun aBannerUrlWithoutYt3OptionsIsKept() {
        assertEquals("https://example.com/banner", uncroppedBanner("https://example.com/banner"))
        assertEquals(
            "https://yt3.googleusercontent.com/a=b/banner",
            uncroppedBanner("https://yt3.googleusercontent.com/a=b/banner"),
        )
        assertEquals("https://example.com/a=w100", uncroppedBanner("https://example.com/a=w100"))
    }

    @Test
    fun cancellationFlowsThroughCatchOrNull() {
        try {
            catchingNull<Any> { throw kotlinx.coroutines.CancellationException("stop") }
            fail("cancellation must be rethrown, not swallowed (KT-CONC-03)")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // expected
        }
        assertNull(catchingNull { throw RuntimeException("any other failure drops the item") })
    }

    @Test
    fun shortFormContentIsFiltered() {
        val item = streamItem().apply { isShortFormContent = true }

        assertNull("Shorts never cross the seam (charter D5)", item.toVideoItemOrNull())
        assertNull(item.toListingItem())
    }

    @Test
    fun activeLiveKindsCarryTheMarker() {
        assertTrue(streamItem(streamType = StreamType.LIVE_STREAM).toVideoItemOrNull()!!.isLive)
        assertTrue(streamItem(streamType = StreamType.AUDIO_LIVE_STREAM).toVideoItemOrNull()!!.isLive)
        // Post-live items are finished broadcasts that play as VODs.
        assertFalse(streamItem(streamType = StreamType.POST_LIVE_STREAM).toVideoItemOrNull()!!.isLive)
        assertFalse(streamItem(streamType = StreamType.VIDEO_STREAM).toVideoItemOrNull()!!.isLive)
    }

    @Test
    fun unknownCountsPassThrough() {
        val item = streamItem().apply {
            duration = -1
            viewCount = -1
        }

        val video = item.toVideoItemOrNull()!!
        assertEquals(-1L, video.durationSeconds)
        assertEquals(-1L, video.viewCount)
    }

    @Test
    fun unparseableUrlDropsTheItemNotThePage() {
        val item = StreamInfoItem(
            0,
            "https://www.youtube.com/playlist?list=PLxyz",
            "not a stream url",
            StreamType.VIDEO_STREAM,
        )

        assertNull(item.toVideoItemOrNull())
    }

    @Test
    fun channelItemsMapWithIdentity() {
        val item = ChannelInfoItem(0, "https://www.youtube.com/@someone", "Someone").apply {
            description = "makes videos"
            subscriberCount = 42
            isVerified = true
            thumbnails = listOf(Image("https://example.com/a.png", 64, 64, ResolutionLevel.MEDIUM))
        }

        assertEquals(
            ChannelItem(
                name = "Someone",
                url = "https://www.youtube.com/@someone",
                avatarUrl = "https://example.com/a.png",
                subscriberCount = 42,
                description = "makes videos",
                verified = true,
            ),
            item.toChannelItem(),
        )
    }

    @Test
    fun channelItemsWithoutAnIdentityKeyAreDropped() {
        val noUrl = ChannelInfoItem(0, null, "Someone")
        val noName = ChannelInfoItem(0, "https://www.youtube.com/@someone", null)

        assertNull(noUrl.toChannelItem())
        assertNull(noName.toChannelItem())
    }

    @Test
    fun bestImagePrefersUnknownDimensionsLast() {
        val images = listOf(
            Image("https://example.com/unknown", -1, -1, ResolutionLevel.LOW),
            Image("https://example.com/small", 120, 68, ResolutionLevel.MEDIUM),
        )

        assertEquals("unknown-dimension images never outrank a known size", "https://example.com/small", bestImage(images))
    }

    @Test
    fun playlistsOutsideChannelTabsAreDropped() {
        val item = PlaylistInfoItem(0, "https://www.youtube.com/playlist?list=PLxyz", "A playlist")

        assertNull(item.toListingItem())
    }

    @Test
    fun channelTabPlaylistsMapToPlaylistItems() {
        val item = PlaylistInfoItem(0, "https://www.youtube.com/playlist?list=PLxyz", "A playlist")
            .apply { streamCount = 7 }

        val mapped = item.toChannelTabItem() as PlaylistItem

        assertEquals("https://www.youtube.com/playlist?list=PLxyz", mapped.url)
        assertEquals("A playlist", mapped.title)
        assertEquals(7L, mapped.videoCount)
    }

    @Test
    fun aPlaylistWithAnUnparsedCountReportsItUnknown() {
        val item = PlaylistInfoItem(0, "https://www.youtube.com/playlist?list=PLxyz", "A playlist")

        assertEquals(-1L, (item.toChannelTabItem() as PlaylistItem).videoCount)
    }

    @Test
    fun playlistsWithoutANameOrUrlAreDropped() {
        assertNull(PlaylistInfoItem(0, null, "A playlist").toChannelTabItem())
        assertNull(PlaylistInfoItem(0, "https://www.youtube.com/playlist?list=PLxyz", null).toChannelTabItem())
    }

    @Test
    fun pageTokenRoundTripsAllFields() {
        val page = Page(
            "https://www.youtube.com/youtubei/v1/browse",
            "continuation-id",
            listOf("a", "b"),
            mapOf("SID" to "x"),
            "token".toByteArray(),
        )

        val token = page.toPageToken()!!
        val restored = token.toVendoredPage()

        assertEquals(page.url, restored.url)
        assertEquals(page.id, restored.id)
        assertEquals(page.ids, restored.ids)
        assertEquals(page.cookies, restored.cookies)
        assertTrue("body must survive the Base64 round-trip", page.body!!.contentEquals(restored.body))
        assertEquals(token, token.copy()) // value equality for Bundle/saved-state use
    }

    @Test
    fun sparsePagesProduceEmptyCollectionsNotNulls() {
        // The vendored Page's url-only constructor leaves ids/cookies null (Java side).
        val token = Page("https://www.youtube.com/next").toPageToken()!!

        assertTrue(token.ids.isEmpty())
        assertTrue(token.cookies.isEmpty())
        assertNull(token.bodyBase64)
    }

    @Test
    fun bestImagePicksTheLargest() {
        val images = listOf(
            Image("https://example.com/small", 120, 68, ResolutionLevel.MEDIUM),
            Image("https://example.com/large", 1280, 720, ResolutionLevel.HIGH),
        )

        assertEquals("https://example.com/large", bestImage(images))
    }

    private fun streamItem(streamType: StreamType = StreamType.VIDEO_STREAM) = StreamInfoItem(
        0,
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        "A title",
        streamType,
    )
}
