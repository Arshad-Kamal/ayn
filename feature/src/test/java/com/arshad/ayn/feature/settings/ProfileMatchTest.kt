package com.arshad.ayn.feature.settings

import com.arshad.ayn.core.sync.MatchChoices
import com.arshad.ayn.core.sync.MatchPreview
import com.arshad.ayn.core.sync.MatchProfile
import com.arshad.ayn.core.sync.SyncDevice
import org.junit.Assert.assertEquals
import org.junit.Test

/** Match your profiles' choices (M8-D18): Ayn's proposal, what each row may choose, the room here
 *  ([com.arshad.ayn.core.store.MAX_PROFILES] = 5) and the choices the runner keeps. */
class ProfileMatchTest {

    @Test
    fun theProposalTakesTheRunnersPairsAndAddsRemoteOnlyProfilesWhileThereIsRoom() {
        val match = ProfileMatch.of(preview(local = HERE, remote = THERE + KIDS2))

        assertEquals(mapOf("a1" to "A", "m1" to "M", "z1" to "Z"), match.with)
        assertEquals(listOf("G", "K2"), match.remoteOnly.map { it.syncId })
        assertEquals(listOf("G"), match.added.map { it.syncId })
        assertEquals(setOf("K2"), match.skipped)
        assertEquals(5, match.countHere)
    }

    @Test
    fun theOwnersAndASharedSyncIdAreFixedAndOfferNoOtherChoice() {
        val shared = profile("s1", "Restored")
        val match = ProfileMatch.of(
            preview(local = HERE + shared, remote = THERE + shared, pairs = PAIRS + ("s1" to "s1"), fixed = FIXED + ("s1" to "s1")),
        )

        assertEquals(mapOf("a1" to "A", "s1" to "s1"), match.fixed)
        assertEquals(emptyList<MatchProfile>(), match.candidates("a1"))
        assertEquals(match, match.keptApart("a1"))
    }

    @Test
    fun aLocalProfileMayBeWithANonOwnerOfItsKindNoOtherRowHolds() {
        val match = ProfileMatch.of(preview(local = HERE, remote = THERE))

        assertEquals(listOf("G"), match.candidates("y1").map { it.syncId })
        assertEquals(listOf("M", "G"), match.candidates("m1").map { it.syncId })
        assertEquals(listOf("Z"), match.candidates("z1").map { it.syncId })
        assertEquals(match, match.pairedWith("y1", "Z"))
    }

    @Test
    fun keepingAProfileApartAddsTheOneItWasWithWhileThereIsRoomElseNot() {
        val roomy = ProfileMatch.of(preview(local = HERE.take(3), remote = THERE))
        assertEquals(listOf("M", "G"), roomy.keptApart("m1").added.map { it.syncId })

        val full = ProfileMatch.of(preview(local = HERE, remote = THERE))
        val apart = full.keptApart("m1")
        assertEquals(listOf("G"), apart.added.map { it.syncId })
        assertEquals(setOf("M"), apart.skipped)
        assertEquals(5, apart.countHere)
    }

    @Test
    fun aRemoteOnlyProfileIsAddedOnlyWhileThereIsRoom() {
        val full = ProfileMatch.of(preview(local = HERE, remote = THERE + KIDS2))
        assertEquals(full, full.addedHere("K2"))

        val roomFreed = full.notAdded("G").addedHere("K2")
        assertEquals(listOf("K2"), roomFreed.added.map { it.syncId })
    }

    @Test
    fun pairingWithAnAddedProfileTakesItOffTheRemoteOnlyRows() {
        val match = ProfileMatch.of(preview(local = HERE, remote = THERE)).pairedWith("y1", "G")

        assertEquals(emptyList<MatchProfile>(), match.remoteOnly)
        assertEquals(4, match.countHere)
    }

    @Test
    fun pairingARowWithAnotherProfileAddsTheOneItLeftOnlyWhileThereIsRoom() {
        val full = ProfileMatch.of(preview(local = HERE + profile("k1", "Kai"), remote = THERE))

        val repaired = full.pairedWith("m1", "G")

        assertEquals("G", repaired.with["m1"])
        assertEquals(setOf("M"), repaired.skipped)
        assertEquals(5, repaired.countHere)
    }

    @Test
    fun theChoicesNameEveryPairApartAndSkippedProfileButNotTheFixedPairs() {
        val match = ProfileMatch.of(preview(local = HERE, remote = THERE + KIDS2)).keptApart("m1")

        assertEquals(
            MatchChoices(pairs = mapOf("z1" to "Z"), apart = setOf("m1", "y1"), skipped = setOf("M", "K2")),
            match.choices(),
        )
    }

    private companion object {
        val PIXEL = SyncDevice("pixel-7-pro-1a2b3c4d", 1_000)
        val HERE = listOf(
            profile("a1", "Arshad", owner = true),
            profile("m1", "Mira"),
            profile("z1", "Zainab", kids = true),
            profile("y1", "Yuri"),
        )
        val THERE = listOf(
            profile("A", "Arshad", owner = true, there = true),
            profile("M", "Mira", there = true),
            profile("Z", "Zainab", kids = true, there = true),
            profile("G", "Guests", there = true),
        )
        val KIDS2 = profile("K2", "Sara", kids = true, there = true)
        val PAIRS = mapOf("a1" to "A", "m1" to "M", "z1" to "Z")
        val FIXED = mapOf("a1" to "A")

        fun profile(syncId: String, name: String, owner: Boolean = false, kids: Boolean = false, there: Boolean = false) =
            MatchProfile(syncId, name, "amber", owner, kids, historyCount = 18, devices = if (there) listOf(PIXEL) else emptyList())

        fun preview(local: List<MatchProfile>, remote: List<MatchProfile>, pairs: Map<String, String> = PAIRS, fixed: Map<String, String> = FIXED) =
            MatchPreview(local, remote, pairs.filterKeys { id -> local.any { it.syncId == id } }, fixed)
    }
}
