package com.arshad.ayn.ui.tv.library

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.feature.library.BlockNotice
import com.arshad.ayn.feature.library.BlockProfiles
import com.arshad.ayn.feature.library.BlockTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The Block sheet's and notice's words (`kp-filters` blocksheet, blockchoose, blocked, blockmira). */
class BlockCopyTest {
    private val fern = BlockTarget.Channel("https://www.youtube.com/channel/UCfern", "fern")
    private val video = BlockTarget.Video(
        VideoItem(VideoId("aaaaaaaaaaa"), "A video", "fern", null, 60, 1, null, null, null, isLive = false),
    )

    @Test
    fun allProfilesNamesEveryProfile() {
        assertEquals("Arshad, Mira, Yuri and Guests", allProfilesHint(BlockProfiles(OWNER, ALL)))
    }

    @Test
    fun forAnyoneButTheOwnerAllProfilesSaysItAsksForTheOwnersLock() {
        assertEquals("Arshad, Mira, Yuri and Guests. Asks for Arshad’s sequence first", allProfilesHint(BlockProfiles(MIRA, ALL)))
    }

    @Test
    fun theNoteFollowsWhoBlocksAndWhatIsChosen() {
        assertEquals(
            "In the other profiles it shows under Set by Arshad, the owner. Lift it in Settings › Profiles › Restrictions.",
            blockNote(BlockProfiles(OWNER, ALL), choosing = false),
        )
        assertEquals("A restriction is one the profile can’t turn off; only you lift it.", blockNote(BlockProfiles(OWNER, ALL), choosing = true))
        assertEquals(
            "Blocking only in your profile needs no lock: choose profiles and leave Mira ticked.",
            blockNote(BlockProfiles(MIRA, ALL), choosing = false),
        )
        assertNull(blockNote(BlockProfiles(MIRA, listOf(OWNER.copy(lock = null), MIRA)), choosing = false))
    }

    @Test
    fun theNoticeNamesWhatWasBlockedAndWhere() {
        assertEquals("fern blocked", blockNoticeLine(BlockNotice(fern, listOf("Arshad", "Mira"), everywhere = false)).text)
        assertEquals("in Arshad and Mira", blockNoticeLine(BlockNotice(fern, listOf("Arshad", "Mira"), everywhere = false)).muted)
        assertEquals("in all profiles", blockNoticeLine(BlockNotice(video, listOf("Arshad"), everywhere = true)).muted)
        assertEquals("Video blocked", blockNoticeLine(BlockNotice(video, listOf("Arshad"), everywhere = true)).text)
    }

    @Test
    fun aBlockNothingTookSaysSoWithoutUndo() {
        val line = blockNoticeLine(BlockNotice(fern, emptyList(), everywhere = false))

        assertEquals("Couldn’t block fern", line.text)
        assertFalse(line.undo)
    }

    @Test
    fun theActionCountsTheTickedProfiles() {
        assertEquals("Block in 1 profile", blockInLabel(1))
        assertEquals("Block in 2 profiles", blockInLabel(2))
    }

    private companion object {
        val OWNER = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = LockKind.Sequence)
        val MIRA = Profile(2, "Mira", ProfileTint.Rose, isOwner = false, lock = null)
        val ALL = listOf(
            OWNER,
            MIRA,
            Profile(3, "Yuri", ProfileTint.Teal, isOwner = false, lock = null),
            Profile(4, "Guests", ProfileTint.Blue, isOwner = false, lock = null),
        )
    }
}
