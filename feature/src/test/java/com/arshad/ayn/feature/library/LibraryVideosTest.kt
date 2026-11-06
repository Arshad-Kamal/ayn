package com.arshad.ayn.feature.library

import com.arshad.ayn.core.access.VideoId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Watch marks, history cards and the naming field's rules. */
class LibraryVideosTest {

    @Test
    fun aVideoLeftPartWayIsMarkedWithItsPlace() {
        assertEquals(WatchMark.PartWay(120_000, 600_000), watchEntry("aaaaaaaaaaa", positionMs = 120_000).mark())
    }

    @Test
    fun aVideoPlayedToTheEndIsWatched() {
        assertEquals(WatchMark.Watched, watchEntry("aaaaaaaaaaa", positionMs = 600_000, watched = true).mark())
    }

    @Test
    fun aWatchedVideoStartedAgainShowsItsNewPlace() {
        assertEquals(WatchMark.PartWay(90_000, 600_000), watchEntry("aaaaaaaaaaa", positionMs = 90_000, watched = true).mark())
    }

    @Test
    fun aBriefOpenOrAnUnknownLengthLeavesNoMark() {
        assertNull(watchEntry("aaaaaaaaaaa", positionMs = 29_000).mark())
        assertNull(watchEntry("bbbbbbbbbbb", positionMs = 120_000, durationMs = null).mark())
    }

    @Test
    fun historyCardsCarryTheRecordedCardAndLeaveOutUntitledEntries() {
        val entries = listOf(
            watchEntry("aaaaaaaaaaa", lastWatchedAtMillis = 2_000, positionMs = 120_000, durationMs = 2_861_000),
            watchEntry("bbbbbbbbbbb", lastWatchedAtMillis = 1_000, title = null),
        )

        val videos = entries.historyVideos()

        assertEquals(listOf(VideoId("aaaaaaaaaaa")), videos.map { it.video.id })
        assertEquals("title aaaaaaaaaaa", videos.single().video.title)
        assertEquals(2_861L, videos.single().video.durationSeconds)
        assertEquals(WatchMark.PartWay(120_000, 2_861_000), videos.single().mark)
        assertEquals(2_000L, videos.single().lastWatchedAt.toEpochMilliseconds())
        assertEquals(setOf(VideoId("aaaaaaaaaaa")), entries.marks().keys)
    }

    @Test
    fun theNameFieldHoldsAtMost150Characters() {
        val full = "x".repeat(150)

        val naming = PlaylistNaming().edited(full).edited(full + "y")

        assertEquals(full, naming.name)
    }

    @Test
    fun aBlankNameOrASaveUnderWayCannotBeSaved() {
        assertFalse(PlaylistNaming("   ").canSave)
        assertFalse(PlaylistNaming("Code breakers", saving = true).canSave)
        assertTrue(PlaylistNaming("Code breakers").canSave)
        assertEquals("Code breakers", PlaylistNaming("Code breakers", saving = true).edited("Code").name)
    }
}
