package com.arshad.ayn.core.sync

import com.arshad.ayn.core.backup.BackupBundle
import com.arshad.ayn.core.backup.BackupPlaylist
import com.arshad.ayn.core.backup.BackupPlaylistVideo
import com.arshad.ayn.core.backup.BackupProfile
import com.arshad.ayn.core.backup.BackupRead
import com.arshad.ayn.core.backup.BackupRemoval
import com.arshad.ayn.core.backup.BackupRestrictions
import com.arshad.ayn.core.backup.BackupSettings
import com.arshad.ayn.core.backup.BackupSubscription
import com.arshad.ayn.core.backup.BackupWatchEntry
import com.arshad.ayn.core.backup.decodeBackup
import com.arshad.ayn.core.backup.encodeBackup
import com.arshad.ayn.core.store.RemovalKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [mergeSnapshots] (M8-D6, M8-D13) over the golden TV and phone snapshots in `/sync/`, whose
 * merge `merged.json` writes out by hand, and over single-rule pairs.
 */
class SnapshotMergeTest {
    private val tv = snapshot("tv.json")
    private val phone = snapshot("phone.json")

    @Test
    fun theTvAndPhoneSnapshotsMergeToTheHandWrittenResult() {
        assertEquals(snapshot("merged.json"), mergeSnapshots(tv, phone))
    }

    @Test
    fun eitherOrderGivesTheSameResult() {
        assertEquals(mergeSnapshots(tv, phone), mergeSnapshots(phone, tv))
    }

    @Test
    fun mergingTheResultAgainWithEitherSnapshotOrItselfChangesNothing() {
        val merged = mergeSnapshots(tv, phone)

        listOf(mergeSnapshots(merged, tv), mergeSnapshots(phone, merged), mergeSnapshots(merged, merged))
            .forEach { assertEquals(merged, it) }
    }

    /** A third device re-subscribes to a channel the phone dropped, drops a video the phone
     *  watched later and holds an older copy of a playlist: every grouping and order agrees. */
    @Test
    fun threeDevicesConvergeInAnyGroupingAndOrder() {
        val owner = phone.profiles.first()
        val tablet = phone.copy(
            exportedAtMillis = 9200,
            profiles = listOf(
                owner.copy(
                    subscriptions = listOf(subscription(CHANNEL_B, "B again", updatedAtMillis = 900)),
                    history = owner.history.filter { it.videoId != "v4ddddddddd" },
                    playlists = listOf(owner.playlists.single().copy(name = "Road trip", updatedAtMillis = 290)),
                ),
            ),
            removals = listOf(BackupRemoval("p-owner", RemovalKind.WATCH, "v4ddddddddd", 900)),
        )
        val all = mergeSnapshots(mergeSnapshots(tv, phone), tablet)

        listOf(
            mergeSnapshots(tv, mergeSnapshots(phone, tablet)),
            mergeSnapshots(mergeSnapshots(tablet, tv), phone),
            mergeSnapshots(phone, mergeSnapshots(tablet, tv)),
        ).forEach { assertEquals(all, it) }
        val merged = all.profiles.first()
        assertEquals("B again", merged.subscriptions.single { it.channelUrl == CHANNEL_B }.name)
        assertEquals(listOf("v2bbbbbbbbb", "v1aaaaaaaaa"), merged.history.map { it.videoId })
        assertEquals("Road trip 2026", merged.playlists.single { it.syncId == "pl-road" }.name)
    }

    /** For a record, a playlist and a profile alike; a profile's record also covers its settings
     *  and restrictions, which keep it when written after. */
    @Test
    fun aRemovalBeatsAnOlderEditLosesToANewerOneAndTakesATie() {
        val removedAt = 500L
        val mira = BackupRemoval("p-mira", RemovalKind.PROFILE, "p-mira", removedAt)
        val miraHolding = { settings: BackupSettings?, restrictions: BackupRestrictions? ->
            bundle(profile(), profile(syncId = "p-mira", name = "Mira", owner = false, settings = settings, restrictions = restrictions))
        }
        val miraKept = { merged: BackupBundle -> merged.profiles.any { it.syncId == "p-mira" } }
        val cases = listOf(
            Case("subscription", BackupRemoval(OWNER, RemovalKind.SUBSCRIPTION, CHANNEL_B, removedAt), { editedAt ->
                bundle(profile(subscriptions = listOf(subscription(CHANNEL_B, "B", editedAt))))
            }) { merged -> merged.profiles.single().subscriptions.isNotEmpty() },
            Case("playlist", BackupRemoval(OWNER, RemovalKind.PLAYLIST, "l1", removedAt), { editedAt ->
                bundle(profile(playlists = listOf(playlist("l1", updatedAtMillis = editedAt))))
            }) { merged -> merged.profiles.single().playlists.isNotEmpty() },
            Case("profile row", mira, { editedAt ->
                bundle(profile(), profile(syncId = "p-mira", name = "Mira", owner = false, updatedAtMillis = editedAt))
            }, miraKept),
            Case("profile settings", mira, { editedAt -> miraHolding(settings(editedAt), null) }, miraKept),
            Case("profile restrictions", mira, { editedAt -> miraHolding(null, restrictions(editedAt)) }, miraKept),
        )

        cases.forEach { case ->
            val kept = listOf(removedAt - 1, removedAt, removedAt + 1).map { editedAt ->
                case.holds(mergeSnapshots(bundle(profile(), removals = listOf(case.record)), case.edited(editedAt)))
            }
            assertEquals(case.name, listOf(false, false, true), kept)
        }
    }

    /** M8-D13's one exception: a profile kept only by use after its removal takes its row from the
     *  copies that meet it, so two groupings may name it differently; its records agree, and the
     *  devices' next exchange settles the name for good. */
    @Test
    fun aProfileKeptOnlyByLaterUseSettlesItsNameAtTheNextExchange() {
        val renamed = bundle(profile(), profile(syncId = "p-mira", name = "Mia", owner = false, updatedAtMillis = 5))
        val removal = bundle(profile(), removals = listOf(BackupRemoval("p-mira", RemovalKind.PROFILE, "p-mira", 6)))
        val used = bundle(
            profile(),
            profile(syncId = "p-mira", name = "Mira", owner = false, updatedAtMillis = 1, history = listOf(watchEntry(1, "Later", updatedAtMillis = 10))),
        )

        val one = mergeSnapshots(mergeSnapshots(renamed, removal), used)
        val other = mergeSnapshots(renamed, mergeSnapshots(removal, used))
        val settled = mergeSnapshots(one, other)

        assertEquals(listOf("Mira", "Mia"), listOf(one, other).map { merged -> merged.profiles.single { it.syncId == "p-mira" }.name })
        assertEquals(one.profiles[1].history, other.profiles[1].history)
        assertEquals("Mia", settled.profiles.single { it.syncId == "p-mira" }.name)
        listOf(renamed, removal, used, one, other).forEach { assertEquals(settled, mergeSnapshots(settled, it)) }
    }

    /** A playlist removed on one device and edited later on another comes back with what is newer
     *  than its removal, whichever two snapshots meet first. */
    @Test
    fun aPlaylistRemovedThenEditedElsewhereMergesTheSameInEveryGrouping() {
        val old = bundle(profile(playlists = listOf(playlist("l1", updatedAtMillis = 10, video("v1aaaaaaaaa", addedAtMillis = 10)))))
        val removal = bundle(profile(), removals = listOf(BackupRemoval(OWNER, RemovalKind.PLAYLIST, "l1", 20)))
        val edited = bundle(profile(playlists = listOf(playlist("l1", updatedAtMillis = 30, video("v2bbbbbbbbb", addedAtMillis = 30)))))

        val merged = sameInEveryGrouping(old, removal, edited)

        assertEquals(listOf("v2bbbbbbbbb"), merged.profiles.single().playlists.single().videos.map { it.videoId })
    }

    /** A profile's row moves only on a rename or tint, so what was written in it after its removal
     *  keeps it; what was written before goes with the removal. */
    @Test
    fun aProfileRemovedThenUsedElsewhereMergesTheSameInEveryGroupingKeepingOnlyTheLaterRecords() {
        val mira = { updatedAtMillis: Long, history: List<BackupWatchEntry> ->
            bundle(profile(), profile(syncId = "p-mira", name = "Mira", owner = false, updatedAtMillis = updatedAtMillis, history = history))
        }
        val before = mira(5, listOf(watchEntry(positionMs = 1, title = "Before", videoId = "v1aaaaaaaaa", updatedAtMillis = 4)))
        val removal = bundle(profile(), removals = listOf(BackupRemoval("p-mira", RemovalKind.PROFILE, "p-mira", 6)))
        val after = mira(5, listOf(watchEntry(positionMs = 1, title = "After", videoId = "v2bbbbbbbbb", updatedAtMillis = 10)))

        val merged = sameInEveryGrouping(before, removal, after)

        assertEquals(listOf("After"), merged.profiles.single { it.syncId == "p-mira" }.history.map { it.title })
        assertEquals(listOf(OWNER), sameInEveryGrouping(before, removal, mira(5, emptyList())).profiles.map { it.syncId })
    }

    /** Charter D4: at the same write time the further position wins; past that, the same copy
     *  whichever snapshot comes first. */
    @Test
    fun equalWriteTimesTakeTheFurtherPositionThenTheSameCopyInEitherOrder() {
        val near = bundle(profile(history = listOf(watchEntry(positionMs = 10_000, title = "Near"))))
        val far = bundle(profile(history = listOf(watchEntry(positionMs = 90_000, title = "Far"))))
        val renamed = bundle(profile(history = listOf(watchEntry(positionMs = 90_000, title = "Renamed"))))

        assertEquals("Far", mergeSnapshots(near, far).profiles.single().history.single().title)
        assertEquals("Far", mergeSnapshots(far, near).profiles.single().history.single().title)
        assertEquals(mergeSnapshots(far, renamed), mergeSnapshots(renamed, far))
    }

    /** M8-D12: Watch later itself is never removed, but a video taken out of it is. */
    @Test
    fun aVideoTakenOutOfWatchLaterStaysOutAndWatchLaterStays() {
        val saved = bundle(profile(watchLater = watchLater(video("v6fffffffff", addedAtMillis = 400))))
        val taken = bundle(
            profile(watchLater = watchLater()),
            removals = listOf(BackupRemoval(OWNER, RemovalKind.PLAYLIST_ITEM, "watch-later/v6fffffffff", 401)),
        )

        assertEquals(emptyList<BackupPlaylistVideo>(), mergeSnapshots(saved, taken).profiles.single().watchLater?.videos)
    }

    /** A profile one device removed and the other only holds an older copy of is gone, its own
     *  record kept for the next device. */
    @Test
    fun aRemovedProfileStaysRemovedWithItsRecordKept() {
        val record = BackupRemoval("p-guest", RemovalKind.PROFILE, "p-guest", 80)
        val removed = bundle(profile(), removals = listOf(record))
        val held = bundle(profile(), profile(syncId = "p-guest", name = "Guest", owner = false, updatedAtMillis = 70))

        val merged = mergeSnapshots(removed, held)

        assertEquals(listOf(OWNER), merged.profiles.map { it.syncId })
        assertEquals(listOf(record), merged.removals)
    }

    @Test
    fun theResultIsAValidBackupThatReadsBackAsItself() {
        val merged = mergeSnapshots(tv, phone)

        val read = decodeBackup(encodeBackup(merged))

        assertTrue("expected a valid backup, got $read", read is BackupRead.Valid)
        assertEquals(merged, (read as BackupRead.Valid).backup.bundle)
    }

    /** The three merged in every grouping and order, which must agree. */
    private fun sameInEveryGrouping(a: BackupBundle, b: BackupBundle, c: BackupBundle): BackupBundle {
        val all = mergeSnapshots(mergeSnapshots(a, b), c)
        listOf(
            mergeSnapshots(a, mergeSnapshots(b, c)),
            mergeSnapshots(mergeSnapshots(c, a), b),
            mergeSnapshots(b, mergeSnapshots(c, a)),
            mergeSnapshots(mergeSnapshots(c, b), a),
        ).forEach { assertEquals(all, it) }
        return all
    }

    private fun snapshot(name: String): BackupBundle {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/sync/$name")).use { it.readBytes() }
        val read = decodeBackup(bytes)
        assertTrue("$name: expected a valid snapshot, got $read", read is BackupRead.Valid)
        return (read as BackupRead.Valid).backup.bundle
    }
}

private const val OWNER = "p-owner"
private const val CHANNEL_B = "https://www.youtube.com/channel/UCbbbbbbbbbbbbbbbbbbbbbb"

/** [record] against a snapshot whose matching item was [edited] at a time; [holds]: whether that
 *  item is in a merge. */
private class Case(
    val name: String,
    val record: BackupRemoval,
    val edited: (editedAt: Long) -> BackupBundle,
    val holds: (merged: BackupBundle) -> Boolean,
)

private fun settings(updatedAtMillis: Long): BackupSettings = BackupSettings(
    speedPercent = 100, resume = true, sponsorBlock = true, segmentActions = emptyMap(), skipNote = true, dearrowTitles = true,
    dearrowThumbnails = true, dislikes = true, showShorts = false, updatedAtMillis = updatedAtMillis,
)

private fun restrictions(updatedAtMillis: Long): BackupRestrictions =
    BackupRestrictions(restrictedMode = "off", hideShorts = false, hideLive = false, hideMembersOnly = false, updatedAtMillis = updatedAtMillis)

private fun bundle(vararg profiles: BackupProfile, removals: List<BackupRemoval> = emptyList()): BackupBundle =
    BackupBundle(bundleVersion = 6, app = "Ayn", exportedAtMillis = 1, profiles = profiles.toList(), removals = removals)

private fun profile(
    syncId: String = OWNER,
    name: String = "Arshad",
    owner: Boolean = true,
    updatedAtMillis: Long = 2,
    subscriptions: List<BackupSubscription> = emptyList(),
    history: List<BackupWatchEntry> = emptyList(),
    playlists: List<BackupPlaylist> = emptyList(),
    watchLater: BackupPlaylist? = null,
    settings: BackupSettings? = null,
    restrictions: BackupRestrictions? = null,
): BackupProfile = BackupProfile(
    name = name,
    tint = "amber",
    owner = owner,
    createdAtMillis = 1,
    updatedAtMillis = updatedAtMillis,
    subscriptions = subscriptions,
    history = history,
    playlists = playlists,
    watchLater = watchLater,
    settings = settings,
    restrictions = restrictions,
    syncId = syncId,
)

private fun subscription(channelUrl: String, name: String, updatedAtMillis: Long): BackupSubscription =
    BackupSubscription(channelUrl, name, avatarUrl = null, subscribedAtMillis = 1, updatedAtMillis = updatedAtMillis)

private fun watchEntry(positionMs: Long, title: String, videoId: String = "v1aaaaaaaaa", updatedAtMillis: Long = 300): BackupWatchEntry =
    BackupWatchEntry(videoId, title, null, null, null, positionMs, 600_000, false, updatedAtMillis, updatedAtMillis)

private fun playlist(syncId: String, updatedAtMillis: Long, vararg videos: BackupPlaylistVideo): BackupPlaylist =
    BackupPlaylist("Mix", createdAtMillis = 1, updatedAtMillis = updatedAtMillis, videos = videos.toList(), syncId = syncId)

private fun watchLater(vararg videos: BackupPlaylistVideo): BackupPlaylist =
    BackupPlaylist("Watch later", createdAtMillis = 1, updatedAtMillis = 1, videos = videos.toList(), syncId = "watch-later")

private fun video(videoId: String, addedAtMillis: Long): BackupPlaylistVideo =
    BackupPlaylistVideo(videoId, "T", null, null, 60, null, null, addedAtMillis, addedAtMillis)
