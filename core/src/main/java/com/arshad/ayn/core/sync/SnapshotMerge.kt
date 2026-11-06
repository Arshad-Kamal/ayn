package com.arshad.ayn.core.sync

import com.arshad.ayn.core.access.boundaryJson
import com.arshad.ayn.core.backup.APP_MARKER
import com.arshad.ayn.core.backup.BUNDLE_VERSION
import com.arshad.ayn.core.backup.BackupBundle
import com.arshad.ayn.core.backup.BackupHiddenChannel
import com.arshad.ayn.core.backup.BackupHiddenVideo
import com.arshad.ayn.core.backup.BackupPlaylist
import com.arshad.ayn.core.backup.BackupPlaylistVideo
import com.arshad.ayn.core.backup.BackupProfile
import com.arshad.ayn.core.backup.BackupRemoval
import com.arshad.ayn.core.backup.BackupRestrictions
import com.arshad.ayn.core.backup.BackupRule
import com.arshad.ayn.core.backup.BackupSettings
import com.arshad.ayn.core.backup.BackupSubscription
import com.arshad.ayn.core.backup.BackupWatchEntry
import com.arshad.ayn.core.store.RemovalKind
import com.arshad.ayn.core.store.WATCH_LATER_SYNC_ID
import kotlinx.serialization.KSerializer

/**
 * Two sync snapshots joined into one (M8-D6, M8-D13): the same result in either order, and joining
 * a result with any of its inputs again changes nothing. Per key the newest copy wins, ties broken
 * so the choice never depends on order. A record stays only when it is newer than its removal
 * record and its container's (playlist, profile); a playlist or profile whose own row its record
 * covers stays while anything in it does. A dropped record is no newer than a removal record, and
 * records only move later, so any grouping of three or more snapshots keeps the same records. The
 * one exception: a profile kept only by what was written in it after its removal takes its name
 * and tint from the copies that meet it, since an emptied copy's row went with the removal (a
 * playlist's row moves with every item write, so its newest row always survives); the next merge
 * of the devices' results settles it. Both inputs are sync
 * snapshots (version 6 or later, or this device's own [SyncDatabase.snapshot]),
 * so every profile and playlist carries its sync id, with profiles already paired ([pairProfiles]).
 * The result carries no device section, the later export time, and every record in the
 * database's read order.
 */
internal fun mergeSnapshots(a: BackupBundle, b: BackupBundle): BackupBundle {
    val removals = (a.removals + b.removals)
        .groupBy { it.key }
        .map { (_, same) -> same.maxBy { it.removedAtMillis } }
        .sortedWith(compareBy({ it.profileSyncId }, { it.kind }, { it.itemKey }))
    val removed = Removals(removals)
    val profiles = (a.profiles + b.profiles)
        .groupBy { it.requireSyncId() }
        .mapNotNull { (syncId, copies) -> mergeProfile(syncId, copies, removed) }
        .sortedWith(PROFILE_ORDER)
    return BackupBundle(
        bundleVersion = BUNDLE_VERSION,
        app = APP_MARKER,
        exportedAtMillis = maxOf(a.exportedAtMillis, b.exportedAtMillis),
        profiles = profiles,
        removals = removals,
    )
}

/** The newest copy's own fields with every record kind joined over [copies]; `null` when the
 *  profile's removal record covers its row and everything in it. */
private fun mergeProfile(syncId: String, copies: List<BackupProfile>, removed: Removals): BackupProfile? {
    fun covered(kind: String) = { key: String, timeMillis: Long -> removed.covers(syncId, kind, key, timeMillis) }
    fun coveredByRemoval(timeMillis: Long): Boolean = removed.covers(syncId, RemovalKind.PROFILE, syncId, timeMillis)
    val row = copies.newestRow()
    val merged = row.copy(
        subscriptions = newestPerKey(
            copies.flatMap { it.subscriptions }, BackupSubscription.serializer(),
            key = { it.channelUrl }, timeMillis = { it.updatedAtMillis }, covered = covered(RemovalKind.SUBSCRIPTION),
        ).sortedWith(compareByDescending<BackupSubscription> { it.subscribedAtMillis }.thenBy { it.channelUrl }),
        history = newestPerKey(
            copies.flatMap { it.history }, BackupWatchEntry.serializer(),
            key = { it.videoId }, timeMillis = { it.updatedAtMillis }, covered = covered(RemovalKind.WATCH),
            tieBreak = compareBy { it.positionMs },
        ).sortedWith(compareByDescending<BackupWatchEntry> { it.lastWatchedAtMillis }.thenBy { it.videoId }),
        playlists = copies.flatMap { it.playlists }
            .groupBy { it.requireSyncId() }
            .mapNotNull { (playlistId, same) -> mergePlaylist(syncId, playlistId, same, removed) }
            .sortedWith(PLAYLIST_ORDER),
        watchLater = copies.mapNotNull { it.watchLater }.takeIf { it.isNotEmpty() }
            ?.let { same -> mergePlaylist(syncId, WATCH_LATER_SYNC_ID, same, removed) },
        settings = copies.mapNotNull { it.settings }.newestOrNull(BackupSettings.serializer()) { it.updatedAtMillis }
            ?.takeUnless { coveredByRemoval(it.updatedAtMillis) },
        hiddenVideos = newestPerKey(
            copies.flatMap { it.hiddenVideos }, BackupHiddenVideo.serializer(),
            key = { it.videoId }, timeMillis = { it.hiddenAtMillis }, covered = covered(RemovalKind.HIDDEN_VIDEO),
        ).sortedWith(compareBy({ it.hiddenAtMillis }, { it.videoId })),
        hiddenChannels = newestPerKey(
            copies.flatMap { it.hiddenChannels }, BackupHiddenChannel.serializer(),
            key = { it.channelUrl }, timeMillis = { it.hiddenAtMillis }, covered = covered(RemovalKind.HIDDEN_CHANNEL),
        ).sortedWith(compareBy({ it.hiddenAtMillis }, { it.channelUrl })),
        restrictions = copies.mapNotNull { it.restrictions }.newestOrNull(BackupRestrictions.serializer()) { it.updatedAtMillis }
            ?.takeUnless { coveredByRemoval(it.updatedAtMillis) },
        rules = newestPerKey(
            copies.flatMap { it.rules }, BackupRule.serializer(),
            key = { it.removalKey() }, timeMillis = { it.addedAtMillis }, covered = covered(RemovalKind.RULE),
        ).sortedWith(compareBy({ it.addedAtMillis }, { it.kind }, { it.value }, { it.byOwner })),
    )
    return merged.takeUnless { coveredByRemoval(row.updatedAtMillis) && merged.holdsNothing() }
}

/** The newest copy's name and times with every copy's videos joined; `null` when its removal
 *  record covers its row and every video. Watch later has no record of its own (M8-D12). */
private fun mergePlaylist(profileSyncId: String, playlistId: String, copies: List<BackupPlaylist>, removed: Removals): BackupPlaylist? {
    val row = copies.newestRow()
    val videos = newestPerKey(
        copies.flatMap { it.videos }, BackupPlaylistVideo.serializer(),
        key = { it.videoId }, timeMillis = { it.updatedAtMillis },
        covered = { videoId, timeMillis ->
            removed.covers(profileSyncId, RemovalKind.PLAYLIST_ITEM, "$playlistId/$videoId", timeMillis) ||
                removed.covers(profileSyncId, RemovalKind.PLAYLIST, playlistId, timeMillis)
        },
    ).sortedWith(compareBy({ it.addedAtMillis }, { it.videoId }))
    if (removed.covers(profileSyncId, RemovalKind.PLAYLIST, playlistId, row.updatedAtMillis) && videos.isEmpty()) return null
    return row.copy(videos = videos, syncId = playlistId)
}

/** A merge's profile order: the owner, then oldest first. A sync snapshot reads in it too. */
internal val PROFILE_ORDER: Comparator<BackupProfile> =
    compareByDescending<BackupProfile> { it.owner }.thenBy { it.createdAtMillis }.thenBy { it.syncId }

/** A merge's playlist order: oldest first. A sync snapshot reads in it too. */
internal val PLAYLIST_ORDER: Comparator<BackupPlaylist> = compareBy({ it.createdAtMillis }, { it.syncId })

/** The newest copy's own fields (its records emptied), owner when any copy is. */
internal fun List<BackupProfile>.newestRow(): BackupProfile {
    val owner = any { it.owner }
    return map {
        it.copy(
            owner = owner, subscriptions = emptyList(), history = emptyList(), playlists = emptyList(), watchLater = null,
            settings = null, hiddenVideos = emptyList(), hiddenChannels = emptyList(), restrictions = null, rules = emptyList(),
        )
    }.maxWith(newestFirst(BackupProfile.serializer(), { it.updatedAtMillis }))
}

/** The newest copy's own fields, its videos emptied. */
internal fun List<BackupPlaylist>.newestRow(): BackupPlaylist =
    map { it.copy(videos = emptyList()) }.maxWith(newestFirst(BackupPlaylist.serializer(), { it.updatedAtMillis }))

private fun BackupProfile.holdsNothing(): Boolean =
    subscriptions.isEmpty() && history.isEmpty() && playlists.isEmpty() && watchLater == null && settings == null &&
        hiddenVideos.isEmpty() && hiddenChannels.isEmpty() && restrictions == null && rules.isEmpty()

/** Per [key], the newest of [records] by [newestFirst], unless [covered] by a removal. */
private fun <T> newestPerKey(
    records: List<T>,
    serializer: KSerializer<T>,
    key: (T) -> String,
    timeMillis: (T) -> Long,
    covered: (key: String, timeMillis: Long) -> Boolean,
    tieBreak: Comparator<T>? = null,
): List<T> = records.groupBy(key).mapNotNull { (itemKey, same) ->
    same.maxWith(newestFirst(serializer, timeMillis, tieBreak)).takeUnless { covered(itemKey, timeMillis(it)) }
}

private fun <T : Any> List<T>.newestOrNull(serializer: KSerializer<T>, timeMillis: (T) -> Long): T? =
    maxWithOrNull(newestFirst(serializer, timeMillis))

/** By [timeMillis], then [tieBreak], then the canonical JSON, so equal times never leave the
 *  choice to input order. */
private fun <T> newestFirst(serializer: KSerializer<T>, timeMillis: (T) -> Long, tieBreak: Comparator<T>? = null): Comparator<T> {
    val byTime = compareBy(timeMillis)
    // Converged devices hold equal copies: those tie without encoding either.
    return (tieBreak?.let(byTime::then) ?: byTime).then { a, b ->
        if (a == b) 0 else compareValuesBy(a, b) { boundaryJson.encodeToString(serializer, it) }
    }
}

/** The removal records by profile, kind and key. */
private class Removals(records: List<BackupRemoval>) {
    private val removedAt = records.associate { it.key to it.removedAtMillis }

    /** Whether item [key] of [kind] in [profileSyncId], last written at [timeMillis], was removed
     *  then or later: by its own record, or by its profile's (which covers everything in it). */
    fun covers(profileSyncId: String, kind: String, key: String, timeMillis: Long): Boolean =
        removedAtOrAfter(Triple(profileSyncId, kind, key), timeMillis) ||
            removedAtOrAfter(Triple(profileSyncId, RemovalKind.PROFILE, profileSyncId), timeMillis)

    private fun removedAtOrAfter(key: Triple<String, String, String>, timeMillis: Long): Boolean =
        removedAt[key]?.let { timeMillis <= it } ?: false
}

/** [RemovalKind.RULE]'s key: `<1 by the owner, else 0>/<rule kind>/<value>`. */
private fun BackupRule.removalKey(): String = "${if (byOwner) 1 else 0}/$kind/$value"

/** A sync snapshot's profiles carry their sync ids (version 6 requires them). */
internal fun BackupProfile.requireSyncId(): String = requireNotNull(syncId) { "a sync snapshot's profile without a sync id" }

internal fun BackupPlaylist.requireSyncId(): String = requireNotNull(syncId) { "a sync snapshot's playlist without a sync id" }
