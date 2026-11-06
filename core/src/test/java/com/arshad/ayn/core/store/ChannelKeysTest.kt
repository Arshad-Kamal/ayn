package com.arshad.ayn.core.store

import org.junit.Assert.assertEquals
import org.junit.Test

/** The store's channel key (M5-D19): which URLs name a channel id, and how rows merge. */
class ChannelKeysTest {

    @Test
    fun theTwoFormsTheShieldStoredAreOneKey() {
        // The M5.10 pass's export (M5P-1).
        assertEquals(BIJAN, canonicalChannelUrl("https://www.youtube.com/channel/UCOCahKBCEUuzDJawM7yN1dg/videos"))
        assertEquals(BIJAN, canonicalChannelUrl("https://www.youtube.com/channel/UCOCahKBCEUuzDJawM7yN1dg"))
    }

    @Test
    fun anyYouTubeHostSchemeTabOrQueryNamesTheSameChannel() {
        listOf(
            "http://www.youtube.com/channel/UCOCahKBCEUuzDJawM7yN1dg",
            "https://youtube.com/channel/UCOCahKBCEUuzDJawM7yN1dg/",
            "https://m.youtube.com/channel/UCOCahKBCEUuzDJawM7yN1dg/streams?view=0",
            "HTTPS://WWW.YOUTUBE.COM/channel/UCOCahKBCEUuzDJawM7yN1dg#about",
        ).forEach { assertEquals(it, BIJAN, canonicalChannelUrl(it)) }
    }

    @Test
    fun theKeyIsItsOwnKey() {
        assertEquals(BIJAN, canonicalChannelUrl(canonicalChannelUrl("https://m.youtube.com/channel/UCOCahKBCEUuzDJawM7yN1dg/videos")))
    }

    @Test
    fun urlsThatDontNameAnIdInTheirPathStayAsGiven() {
        listOf(
            "https://www.youtube.com/@BijanBowen",
            "https://www.youtube.com/c/Veritasium",
            // An id one character too long, or in another case, is not this channel's.
            "https://www.youtube.com/channel/UCOCahKBCEUuzDJawM7yN1dgX",
            "https://www.youtube.com/channel/ucOCahKBCEUuzDJawM7yN1dg",
            "https://www.youtube.com/CHANNEL/UCOCahKBCEUuzDJawM7yN1dg",
            // An id in a query or under another path segment.
            "https://www.youtube.com/redirect?q=/channel/UCOCahKBCEUuzDJawM7yN1dg",
            "https://www.youtube.com/feed/channel/UCOCahKBCEUuzDJawM7yN1dg",
            "https://example.com/channel/UCOCahKBCEUuzDJawM7yN1dg",
            "https://notyoutube.com/channel/UCOCahKBCEUuzDJawM7yN1dg",
            "ftp://www.youtube.com/channel/UCOCahKBCEUuzDJawM7yN1dg",
            "Bijan Bowen",
            "https://www.youtube.com/channel/UC OCah",
        ).forEach { assertEquals(it, it, canonicalChannelUrl(it)) }
    }

    @Test
    fun oneChannelsRowsMergeOnTheEarliestSubscribeAndTheNewestNameAndPicture() {
        val rows = listOf(
            row("$BIJAN/videos", name = "Bijan Bowen", avatar = "https://img/new", subscribed = 20, updated = 25),
            row(BIJAN, name = "Bijan", avatar = "https://img/old", subscribed = 5, updated = 6),
        )

        assertEquals(listOf(row(BIJAN, name = "Bijan Bowen", avatar = "https://img/new", subscribed = 5, updated = 25)), collapseByChannel(rows))
    }

    @Test
    fun aMissingNameOrPictureComesFromTheNextNewestRowThatHasOne() {
        val rows = listOf(
            row("$BIJAN/videos", name = " ", avatar = null, subscribed = 20, updated = 30),
            row(BIJAN, name = "Bijan Bowen", avatar = "https://img/a", subscribed = 10, updated = 10),
        )

        assertEquals(listOf(row(BIJAN, name = "Bijan Bowen", avatar = "https://img/a", subscribed = 10, updated = 30)), collapseByChannel(rows))
    }

    @Test
    fun eachProfileKeepsItsOwnRowAndOtherChannelsAndHandlesAreUntouched() {
        val rows = listOf(
            row(VERITASIUM, profile = 1, subscribed = 1, updated = 1),
            row("$BIJAN/videos", profile = 1, subscribed = 2, updated = 2),
            row(BIJAN, profile = 2, subscribed = 3, updated = 3),
            row(HANDLE, profile = 1, subscribed = 4, updated = 4),
        )

        assertEquals(
            listOf(
                row(VERITASIUM, profile = 1, subscribed = 1, updated = 1),
                row(BIJAN, profile = 1, subscribed = 2, updated = 2),
                row(BIJAN, profile = 2, subscribed = 3, updated = 3),
                row(HANDLE, profile = 1, subscribed = 4, updated = 4),
            ),
            collapseByChannel(rows),
        )
    }

    @Test
    fun rowsAlreadyOnePerChannelComeBackUnchanged() {
        val rows = listOf(row(VERITASIUM, subscribed = 1, updated = 2), row(BIJAN, subscribed = 3, updated = 4))

        assertEquals(rows, collapseByChannel(rows))
    }

    private fun row(
        url: String,
        profile: Long = 1,
        name: String = "Channel",
        avatar: String? = null,
        subscribed: Long,
        updated: Long,
    ): SubscriptionEntity = SubscriptionEntity(profile, url, name, avatar, subscribed, updated)
}

private const val BIJAN = "https://www.youtube.com/channel/UCOCahKBCEUuzDJawM7yN1dg"
private const val VERITASIUM = "https://www.youtube.com/channel/UCHnyfMqiRRG1u-2MsSQLbXA"
private const val HANDLE = "https://www.youtube.com/@BijanBowen"
