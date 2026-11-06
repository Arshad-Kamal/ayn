package com.arshad.ayn.core.sync

import com.arshad.ayn.core.backup.BackupPlaylist
import com.arshad.ayn.core.backup.BackupProfile
import org.junit.Assert.assertEquals
import org.junit.Test

/** [pairProfiles] (M8-D5, M8-D13): a device's first sync against the linked devices' profiles. */
class ProfilePairingTest {

    @Test
    fun theOwnersPairWhateverTheirNamesAndTheOthersByNameIgnoringAsciiCase() {
        val local = listOf(profile("l-owner", "Arshad", owner = true), profile("l-mira", "Mira"), profile("l-sam", "Sam"))
        val remote = listOf(profile("r-owner", "Dad", owner = true), profile("r-mira", "MIRA"), profile("r-yuri", "Yuri"))

        val pairing = pairProfiles(local, remote)

        assertEquals(mapOf("l-owner" to "r-owner", "l-mira" to "r-mira"), pairing.profiles)
        assertEquals(listOf("l-sam"), pairing.unpairedLocal)
        assertEquals(listOf("r-yuri"), pairing.unpairedRemote)
    }

    /** A profile restored from a version 6 backup already shares its id, whatever its name now. */
    @Test
    fun theSameSyncIdPairsBeforeAnyName() {
        val local = listOf(profile("p-owner", "Arshad", owner = true), profile("p-mira", "Mira"), profile("l-mira2", "Mira 2"))
        val remote = listOf(profile("p-owner", "Arshad", owner = true), profile("p-mira", "Mira 2"), profile("r-mira", "Mira"))

        val pairing = pairProfiles(local, remote)

        assertEquals(mapOf("p-owner" to "p-owner", "p-mira" to "p-mira"), pairing.profiles)
        assertEquals(listOf("l-mira2"), pairing.unpairedLocal)
        assertEquals(listOf("r-mira"), pairing.unpairedRemote)
    }

    /** As a restore's Replace target (KP-D24): a kids profile never pairs with a standard one,
     *  and only the owner pairs with the owner. */
    @Test
    fun aNameOnAProfileOfAnotherKindOrTheOwnerDoesntPair() {
        val local = listOf(profile("l-owner", "Arshad", owner = true), profile("l-zainab", "Zainab", kids = true), profile("l-sam", "Sam"))
        val remote = listOf(profile("r-owner", "Sam", owner = true), profile("r-zainab", "Zainab"))

        val pairing = pairProfiles(local, remote)

        assertEquals(mapOf("l-owner" to "r-owner"), pairing.profiles)
        assertEquals(listOf("l-zainab", "l-sam"), pairing.unpairedLocal)
        assertEquals(listOf("r-zainab"), pairing.unpairedRemote)
    }

    /** Two linked devices each hold the owner: it is one remote profile, its playlists from both. */
    @Test
    fun playlistsPairBySyncIdThenByNameAcrossEveryRemoteCopy() {
        val local = listOf(
            profile("l-owner", "Arshad", owner = true, playlists = listOf(playlist("l-road", "Road trip"), playlist("pl-gym", "Gym"), playlist("l-new", "New"))),
        )
        val remote = listOf(
            profile("r-owner", "Arshad", owner = true, playlists = listOf(playlist("pl-gym", "Workout"))),
            profile("r-owner", "Arshad", owner = true, playlists = listOf(playlist("pl-gym", "Workout"), playlist("r-road", "Road trip"))),
        )

        val pairing = pairProfiles(local, remote)

        assertEquals(mapOf("l-owner" to "r-owner"), pairing.profiles)
        assertEquals(mapOf("l-owner" to mapOf("pl-gym" to "pl-gym", "l-road" to "r-road")), pairing.playlists)
        assertEquals(emptyList<String>(), pairing.unpairedRemote)
    }

    /** A profile or playlist renamed on one linked device matches by its newest name, as the merge
     *  keeps it, whichever device's file comes first. */
    @Test
    fun aRemoteProfileOrPlaylistOnSeveralDevicesMatchesByItsNewestCopy() {
        val local = listOf(
            profile("l-owner", "Arshad", owner = true, playlists = listOf(playlist("l-road", "Road trip"))),
            profile("l-mira", "Mira"),
        )
        val older = listOf(
            profile("r-owner", "Arshad", owner = true, playlists = listOf(playlist("r-road", "Road"))),
            profile("r-mira", "Mia"),
        )
        val newer = listOf(
            profile("r-owner", "Arshad", owner = true, playlists = listOf(playlist("r-road", "Road trip", updatedAtMillis = 9))),
            profile("r-mira", "Mira", updatedAtMillis = 9),
        )

        val pairing = pairProfiles(local, older + newer)

        assertEquals(mapOf("l-owner" to "r-owner", "l-mira" to "r-mira"), pairing.profiles)
        assertEquals(mapOf("l-road" to "r-road"), pairing.playlists["l-owner"])
    }

    /** M8-D18: the owner matched Sam with the phone's Mira and kept this Mira apart, so neither
     *  name rule pairs her. */
    @Test
    fun theOwnersChoicesPairFirstAndAProfileKeptApartPairsWithNone() {
        val local = listOf(profile("l-owner", "Arshad", owner = true), profile("l-mira", "Mira"), profile("l-sam", "Sam"))
        val remote = listOf(profile("r-owner", "Arshad", owner = true), profile("r-mira", "Mira"), profile("r-sam", "Sam"))

        val pairing = pairProfiles(local, remote, MatchChoices(pairs = mapOf("l-sam" to "r-mira"), apart = setOf("l-mira")))

        assertEquals(mapOf("l-owner" to "r-owner", "l-sam" to "r-mira"), pairing.profiles)
        assertEquals(listOf("l-mira"), pairing.unpairedLocal)
        assertEquals(listOf("r-sam"), pairing.unpairedRemote)
    }

    /** A choice naming an owner, a profile of another kind, a profile that isn't there or a remote
     *  one already chosen falls back to the rules. */
    @Test
    fun aChoiceAcrossOwnersOrKindsOrTwiceForOneProfileIsIgnored() {
        val local = listOf(
            profile("l-owner", "Arshad", owner = true), profile("l-mira", "Mira"), profile("l-sam", "Sam"), profile("l-zainab", "Zainab", kids = true),
        )
        val remote = listOf(profile("r-owner", "Arshad", owner = true), profile("r-mira", "Mira"), profile("r-zainab", "Zainab", kids = true))
        val choices = MatchChoices(
            pairs = mapOf("l-owner" to "r-mira", "l-zainab" to "r-mira", "l-mira" to "r-owner", "l-gone" to "r-zainab", "l-sam" to "r-mira", "l-x" to "r-x"),
        )

        val pairing = pairProfiles(local, remote, choices)

        assertEquals(mapOf("l-owner" to "r-owner", "l-sam" to "r-mira", "l-zainab" to "r-zainab"), pairing.profiles)
        assertEquals(listOf("l-mira"), pairing.unpairedLocal)
    }

    /** A profile restored with the remote's sync id is that profile, whatever the owner chose: a
     *  second local profile taking the id would break the id's uniqueness on every apply. */
    @Test
    fun aSharedSyncIdHoldsAgainstAChoiceOrKeepApart() {
        val local = listOf(profile("l-owner", "Arshad", owner = true), profile("l-sam", "Sam"), profile("p-mira", "Mira"))
        val remote = listOf(profile("r-owner", "Arshad", owner = true), profile("p-mira", "Mira"))

        val pairing = pairProfiles(local, remote, MatchChoices(pairs = mapOf("l-sam" to "p-mira"), apart = setOf("p-mira")))

        assertEquals(mapOf("p-mira" to "p-mira", "l-owner" to "r-owner"), pairing.profiles)
        assertEquals(listOf("l-sam"), pairing.unpairedLocal)
    }

    @Test
    fun twoChoicesOfOneRemoteProfileKeepTheFirstAndAChoiceKeptApartIsDropped() {
        val local = listOf(profile("l-owner", "Arshad", owner = true), profile("l-sam", "Sam"), profile("l-ali", "Ali"), profile("l-noor", "Noor"))
        val remote = listOf(profile("r-owner", "Arshad", owner = true), profile("r-mira", "Mira"), profile("r-yuri", "Yuri"))
        val choices = MatchChoices(pairs = mapOf("l-sam" to "r-mira", "l-ali" to "r-mira", "l-noor" to "r-yuri"), apart = setOf("l-noor"))

        val pairing = pairProfiles(local, remote, choices)

        assertEquals(mapOf("l-owner" to "r-owner", "l-sam" to "r-mira"), pairing.profiles)
        assertEquals(listOf("l-ali", "l-noor"), pairing.unpairedLocal)
    }

    /** Q19: Don't add means the profile stays on its device; neither its name nor a choice pairs
     *  it here. */
    @Test
    fun aSkippedRemoteProfilePairsWithNone() {
        val local = listOf(profile("l-owner", "Arshad", owner = true), profile("l-mira", "Mira"), profile("l-sam", "Sam"))
        val remote = listOf(profile("r-owner", "Arshad", owner = true), profile("r-mira", "Mira"), profile("r-yuri", "Yuri"))

        val pairing = pairProfiles(local, remote, MatchChoices(pairs = mapOf("l-sam" to "r-yuri"), skipped = setOf("r-mira", "r-yuri")))

        assertEquals(mapOf("l-owner" to "r-owner"), pairing.profiles)
        assertEquals(listOf("l-mira", "l-sam"), pairing.unpairedLocal)
    }
}

private fun profile(
    syncId: String,
    name: String,
    owner: Boolean = false,
    kids: Boolean = false,
    playlists: List<BackupPlaylist> = emptyList(),
    updatedAtMillis: Long = 1,
): BackupProfile = BackupProfile(
    name = name,
    tint = "amber",
    owner = owner,
    createdAtMillis = 1,
    updatedAtMillis = updatedAtMillis,
    subscriptions = emptyList(),
    history = emptyList(),
    playlists = playlists,
    kids = kids,
    syncId = syncId,
)

private fun playlist(syncId: String, name: String, updatedAtMillis: Long = 1): BackupPlaylist =
    BackupPlaylist(name, createdAtMillis = 1, updatedAtMillis = updatedAtMillis, videos = emptyList(), syncId = syncId)
