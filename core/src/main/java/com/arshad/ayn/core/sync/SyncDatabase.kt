package com.arshad.ayn.core.sync

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import com.arshad.ayn.core.backup.BackupBundle
import com.arshad.ayn.core.backup.BackupPlaylist
import com.arshad.ayn.core.backup.BackupProfile
import com.arshad.ayn.core.backup.BackupRemoval
import com.arshad.ayn.core.backup.foldAsciiCase
import com.arshad.ayn.core.backup.freeName
import com.arshad.ayn.core.backup.readSnapshot
import com.arshad.ayn.core.backup.restoreOnto
import com.arshad.ayn.core.backup.toBackup
import com.arshad.ayn.core.backup.toEntity
import com.arshad.ayn.core.backup.validRules
import com.arshad.ayn.core.store.HiddenChannelEntity
import com.arshad.ayn.core.store.HiddenVideoEntity
import com.arshad.ayn.core.store.KeDatabase
import com.arshad.ayn.core.store.MAX_PROFILES
import com.arshad.ayn.core.store.PlaylistEntity
import com.arshad.ayn.core.store.PlaylistItemEntity
import com.arshad.ayn.core.store.ProfileEntity
import com.arshad.ayn.core.store.ProfileRestrictions
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.store.RemovalEntity
import com.arshad.ayn.core.store.RemovalKind
import com.arshad.ayn.core.store.WATCH_LATER_MARK
import com.arshad.ayn.core.store.canonicalChannelUrl
import com.arshad.ayn.core.store.toEntity
import com.arshad.ayn.core.store.toRestrictions
import com.arshad.ayn.core.store.toSettings
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * This device's database as cloud sync sees it (M8-D13, M8-D16): its snapshot, and other devices'
 * snapshots merged into it. A profile's settings that arrive from another device start from this
 * device's [profileDefaults] where they say nothing, as a restored profile's do.
 */
internal class SyncDatabase(
    private val database: KeDatabase,
    private val clock: Clock = Clock.System,
    private val profileDefaults: ProfileSettings = ProfileSettings(),
) {
    /** Every profile with its sync ids, and the removal records, without this device's settings
     *  (M8-D3, M8-D4): what this device uploads. Throws what the read throws. */
    suspend fun snapshot(): BackupBundle = database.withReadTransaction { readSyncSnapshot(nowMillis()) }

    /**
     * [remotes] (other devices' snapshots, version 6 or later) merged into this device's data in
     * one write transaction: a failure (`androidx.sqlite.SQLiteException`) writes nothing. [pair]
     * on this device's first sync since linking: its profiles and playlists first take the sync
     * ids of the remote ones they pair with ([pairProfiles], the owner's [choices] first). A
     * remote profile in [MatchChoices.skipped] isn't added here (M8-D18). [openProfileId] is kept
     * even when the merge drops it.
     */
    suspend fun apply(remotes: List<BackupBundle>, pair: Boolean, openProfileId: Long?, choices: MatchChoices = MatchChoices()) {
        val nowMillis = nowMillis()
        database.withWriteTransaction {
            if (pair) {
                val mine = readSyncSnapshot(nowMillis).profiles
                adopt(pairingRename(pairProfiles(mine, remotes.flatMap { it.profiles }, choices)))
            }
            val others = unifyOwners(remotes)
            val local = readSyncSnapshot(nowMillis)
            Writer(nowMillis, openProfileId, choices.skipped).write(local, others.fold(local, ::mergeSnapshots))
        }
    }

    private fun nowMillis(): Long = clock.now().toEpochMilliseconds()

    /** Profiles and playlists in the merge's order, so an applied merge reads back as it was. */
    private suspend fun readSyncSnapshot(nowMillis: Long): BackupBundle =
        database.readSnapshot(nowMillis, profileIds = null, device = null, withRemovals = true).let { bundle ->
            bundle.copy(profiles = bundle.profiles.sortedWith(PROFILE_ORDER).map { it.copy(playlists = it.playlists.sortedWith(PLAYLIST_ORDER)) })
        }

    /** This device's ids renamed as [rename] says: profiles, playlists, and the removal records
     *  that name them. */
    private suspend fun adopt(rename: IdRename) {
        if (rename.isEmpty()) return
        rename.profiles.forEach { (from, to) -> database.profileDao().setSyncId(from, to) }
        val ids = database.profileDao().all().associate { it.syncId to it.id }
        rename.playlists.forEach { (profileFrom, playlists) ->
            val profileId = ids.getValue(rename.profiles[profileFrom] ?: profileFrom)
            playlists.forEach { (from, to) -> database.playlistDao().setSyncId(profileId, from, to) }
        }
        val records = database.removalDao().all()
        val renamed = rename.removals(records.map(RemovalEntity::toBackup)).map(BackupRemoval::toEntity)
        if (renamed.toSet() != records.toSet()) {
            database.removalDao().deleteAll(records)
            database.removalDao().upsertAll(renamed)
        }
    }

    /** Two devices linked at once, each to an empty folder, each have their own owner id: every
     *  device takes the smallest. Rewrites this device's owner (and its removal records) when
     *  its id isn't the smallest; returns [remotes] with theirs rewritten likewise. */
    private suspend fun unifyOwners(remotes: List<BackupBundle>): List<BackupBundle> {
        val mine = database.profileDao().owner()?.syncId ?: return remotes
        val theirs = remotes.map { remote -> remote.profiles.firstOrNull { it.owner }?.syncId }
        val owner = (theirs.filterNotNull() + mine).min()
        adopt(IdRename(profiles = mapOf(mine to owner).filterNot { (from, to) -> from == to }))
        return remotes.zip(theirs) { remote, id -> if (id == null || id == owner) remote else IdRename(mapOf(id to owner)).renamedIn(remote) }
    }

    /** The writes that turn [local] into [merged] (M8-D16); nothing for what is already so. */
    private inner class Writer(private val nowMillis: Long, private val openProfileId: Long?, private val skipped: Set<String>) {
        private val profiles = database.profileDao()
        private val playlists = database.playlistDao()

        suspend fun write(local: BackupBundle, merged: BackupBundle) {
            val keptRemovals = merged.removals.filter { it.removedAtMillis > nowMillis - REMOVAL_KEEP_MILLIS }
            writeRecords(
                local.removals.map(BackupRemoval::toEntity), keptRemovals.map(BackupRemoval::toEntity),
                key = { Triple(it.profileSyncId, it.kind, it.itemKey) },
                upsert = { database.removalDao().upsertAll(it) },
                delete = { database.removalDao().deleteAll(it) },
            )
            val mergedIds = merged.profiles.mapTo(HashSet()) { it.requireSyncId() }
            profiles.all().filter { it.syncId !in mergedIds && it.ownerMark == null && it.id != openProfileId }
                .forEach { profiles.removeNonOwner(it.id) }
            val rows = profiles.all().associateBy { it.syncId }
            val kept = keptProfiles(merged.profiles, rows)
            val keptIds = kept.mapTo(HashSet()) { it.syncId }
            val names = namesFor(kept, taken = rows.values.filter { it.syncId !in keptIds }.map { it.name })
            parkRenamed(rows.values.filter { it.syncId in keptIds && it.name != names.getValue(it.syncId) })
            val playlistRows = playlists.allPlaylists().groupBy { it.profileId }
            val items = playlists.allItems().groupBy { it.playlistId }
            val localProfiles = local.profiles.associateBy { it.syncId }
            kept.forEach { profile ->
                val name = names.getValue(profile.requireSyncId())
                val profileId = writeRow(rows[profile.syncId], profile.copy(name = name, updatedAtMillis = if (name == profile.name) profile.updatedAtMillis else nowMillis))
                writeContents(profileId, localProfiles[profile.syncId], profile)
                writePlaylists(profileId, playlistRows[profileId].orEmpty(), items, profile)
            }
        }

        /** [merged]'s profiles this device holds: those it has, then new ones not [skipped] in
         *  merge order while there is room ([MAX_PROFILES]); the rest stay in their devices'
         *  files. */
        private fun keptProfiles(merged: List<BackupProfile>, rows: Map<String, ProfileEntity>): List<BackupProfile> {
            val room = (MAX_PROFILES - rows.size).coerceAtLeast(0)
            val added = merged.filter { it.syncId !in rows && it.syncId !in skipped }.take(room).mapTo(HashSet()) { it.syncId }
            return merged.filter { it.syncId in rows || it.syncId in added }
        }

        /** Each of [renamed] under its sync id until its new name is written: a name moving between
         *  two profiles here would clash with the other one until that one moves too. */
        private suspend fun parkRenamed(renamed: List<ProfileEntity>) {
            renamed.forEach { profiles.setRow(it.id, it.syncId, it.tint, it.createdAtMillis, it.updatedAtMillis) }
        }

        /** Each profile's name, in merge order; one already [taken] here or by an earlier profile
         *  gets " 2", " 3"…, as a restore's Add as new does. */
        private fun namesFor(profiles: List<BackupProfile>, taken: List<String>): Map<String, String> {
            val folded = taken.mapTo(HashSet()) { it.foldAsciiCase() }
            return profiles.associate { profile ->
                profile.requireSyncId() to freeName(profile.name, folded).also { folded += it.foldAsciiCase() }
            }
        }

        /** [profile]'s row here: [row] updated, or a new row (never the owner: this device's owner
         *  carries the merged owner's id). Returns its id. */
        private suspend fun writeRow(row: ProfileEntity?, profile: BackupProfile): Long {
            val tint = ProfileTint.of(profile.tint).key
            if (row == null) {
                return profiles.insert(
                    ProfileEntity(
                        name = profile.name,
                        tint = tint,
                        ownerMark = null,
                        createdAtMillis = profile.createdAtMillis,
                        updatedAtMillis = profile.updatedAtMillis,
                        kids = profile.kids,
                        syncId = profile.requireSyncId(),
                    ),
                )
            }
            if (row.syncedFields() != listOf(profile.name, tint, profile.createdAtMillis, profile.updatedAtMillis)) {
                profiles.setRow(row.id, profile.name, tint, profile.createdAtMillis, profile.updatedAtMillis)
            }
            return row.id
        }

        private suspend fun writeContents(profileId: Long, mine: BackupProfile?, theirs: BackupProfile) {
            writeRecords(
                mine?.subscriptions.orEmpty().map { it.toEntity(profileId) }, theirs.subscriptions.map { it.toEntity(profileId) },
                key = { it.channelUrl },
                upsert = { database.subscriptionDao().upsertAll(it) },
                delete = { database.subscriptionDao().deleteAll(it) },
            )
            writeRecords(
                mine?.history.orEmpty().map { it.toEntity(profileId) }, theirs.history.map { it.toEntity(profileId) },
                key = { it.videoId },
                upsert = { database.watchDao().upsertAll(it) },
                delete = { database.watchDao().deleteAll(it) },
            )
            writeRecords(
                mine?.hiddenVideos.orEmpty().map { HiddenVideoEntity(profileId, it.videoId, it.hiddenAtMillis) },
                theirs.hiddenVideos.map { HiddenVideoEntity(profileId, it.videoId, it.hiddenAtMillis) },
                key = { it.videoId },
                upsert = { database.forYouDao().upsertHiddenVideos(it) },
                delete = { database.forYouDao().deleteHiddenVideos(it) },
            )
            writeRecords(
                mine?.hiddenChannels.orEmpty().map { HiddenChannelEntity(profileId, it.channelUrl, it.hiddenAtMillis) },
                theirs.hiddenChannels.map { HiddenChannelEntity(profileId, canonicalChannelUrl(it.channelUrl), it.hiddenAtMillis) },
                key = { it.channelUrl },
                upsert = { database.forYouDao().upsertHiddenChannels(it) },
                delete = { database.forYouDao().deleteHiddenChannels(it) },
            )
            writeRecords(
                mine?.rules.orEmpty().map { it.toEntity(profileId) }, validRules(theirs.rules, profileId),
                key = { Triple(it.byOwner, it.kind, it.value) },
                upsert = { database.filterDao().upsertRules(it) },
                delete = { database.filterDao().deleteRules(it) },
            )
            writeSettings(profileId, theirs)
        }

        /** A value this build can't take keeps this device's, as a restore's does (KP-D17). */
        private suspend fun writeSettings(profileId: Long, theirs: BackupProfile) {
            val settingsDao = database.profileSettingsDao()
            val settingsRow = settingsDao.row(profileId)
            val settings = theirs.settings?.let { it.restoreOnto(settingsRow?.toSettings() ?: profileDefaults).value.toEntity(profileId, it.updatedAtMillis) }
            when {
                settings == null -> if (settingsRow != null) settingsDao.delete(profileId)
                settings != settingsRow -> settingsDao.upsert(settings)
            }
            val filterDao = database.filterDao()
            val restrictionsRow = filterDao.restrictionsRow(profileId)
            val restrictions = theirs.restrictions?.let {
                it.restoreOnto(restrictionsRow?.toRestrictions() ?: ProfileRestrictions()).value.toEntity(profileId, it.updatedAtMillis)
            }
            when {
                restrictions == null -> if (restrictionsRow != null) filterDao.deleteRestrictions(profileId)
                restrictions != restrictionsRow -> filterDao.upsertRestrictions(restrictions)
            }
        }

        /** Each playlist's row and, when they differ in content or order, its items in the
         *  merge's order. */
        private suspend fun writePlaylists(
            profileId: Long,
            rows: List<PlaylistEntity>,
            items: Map<Long, List<PlaylistItemEntity>>,
            theirs: BackupProfile,
        ) {
            val wanted = theirs.playlists.map { it to null } + listOfNotNull(theirs.watchLater).map { it to WATCH_LATER_MARK }
            val wantedIds = wanted.mapTo(HashSet()) { (playlist, _) -> playlist.requireSyncId() }
            rows.filter { it.syncId !in wantedIds }.forEach { playlists.deleteForSync(it.id) }
            val bySyncId = rows.associateBy { it.syncId }
            wanted.forEach { (playlist, watchLaterMark) ->
                val row = bySyncId[playlist.syncId]
                val playlistId = row?.id ?: playlists.insertPlaylist(
                    PlaylistEntity(
                        profileId = profileId,
                        name = playlist.name,
                        watchLaterMark = watchLaterMark,
                        createdAtMillis = playlist.createdAtMillis,
                        updatedAtMillis = playlist.updatedAtMillis,
                        syncId = playlist.requireSyncId(),
                    ),
                )
                if (row != null && row.syncedFields() != playlist.syncedFields()) {
                    playlists.setRow(playlistId, playlist.name, playlist.createdAtMillis, playlist.updatedAtMillis)
                }
                val videos = playlist.videos.mapIndexed { position, video -> video.toEntity(playlistId, position.toLong()) }
                val current = items[playlistId].orEmpty().mapIndexed { position, item -> item.copy(position = position.toLong()) }
                if (videos != current) {
                    playlists.clearItems(playlistId)
                    playlists.insertItems(videos)
                }
            }
        }
    }
}

/** The tables a sync snapshot reads (M8-D4): a write to one is a local change to push. */
val SYNCED_TABLES: Array<String> = arrayOf(
    "profiles", "subscriptions", "watch_entries", "playlists", "playlist_items", "profile_settings",
    "hidden_videos", "hidden_channels", "profile_restrictions", "content_rules", "removals",
)

/** How long a removal record is kept (M8-D6): a device offline longer can bring the item back. */
private val REMOVAL_KEEP_MILLIS = 90.days.inWholeMilliseconds

/** Upserts the [merged] rows that differ from [local] and deletes the [local] rows whose [key] the
 *  merge doesn't have; neither when there are none. The merge's deletes carry their removal
 *  records, so nothing is recorded here. */
private suspend fun <T> writeRecords(
    local: List<T>,
    merged: List<T>,
    key: (T) -> Any,
    upsert: suspend (List<T>) -> Unit,
    delete: suspend (List<T>) -> Unit,
) {
    val mergedKeys = merged.mapTo(HashSet(), key)
    local.filter { key(it) !in mergedKeys }.takeIf { it.isNotEmpty() }?.let { delete(it) }
    val localByKey = local.associateBy(key)
    merged.filter { localByKey[key(it)] != it }.takeIf { it.isNotEmpty() }?.let { upsert(it) }
}

/** The row's fields a sync writes; lock, owner mark and kind stay this device's. */
private fun ProfileEntity.syncedFields(): List<Any> = listOf(name, tint, createdAtMillis, updatedAtMillis)

private fun PlaylistEntity.syncedFields(): List<Any> = listOf(name, createdAtMillis, updatedAtMillis)

private fun BackupPlaylist.syncedFields(): List<Any> = listOf(name, createdAtMillis, updatedAtMillis)

/**
 * Sync ids to rename: [profiles] old → new, and by a profile's old id its [playlists] old → new.
 * Removal records follow the ids they name; two that become one keep the later time.
 */
internal class IdRename(
    val profiles: Map<String, String> = emptyMap(),
    val playlists: Map<String, Map<String, String>> = emptyMap(),
) {
    fun isEmpty(): Boolean = profiles.isEmpty() && playlists.values.all { it.isEmpty() }

    /** [bundle]'s profiles under their new ids, and its removal records; playlists keep theirs. */
    fun renamedIn(bundle: BackupBundle): BackupBundle = bundle.copy(
        profiles = bundle.profiles.map { profile -> profile.copy(syncId = profile.syncId?.let { profiles[it] ?: it }) },
        removals = removals(bundle.removals),
    )

    fun removals(records: List<BackupRemoval>): List<BackupRemoval> = records.map { it.renamed() }
        .groupBy { it.key }
        .map { (_, same) -> same.maxBy { it.removedAtMillis } }

    private fun BackupRemoval.renamed(): BackupRemoval {
        val playlistIds = playlists[profileSyncId].orEmpty()
        val key = when (kind) {
            RemovalKind.PROFILE -> profiles[itemKey] ?: itemKey
            RemovalKind.PLAYLIST -> playlistIds[itemKey] ?: itemKey
            RemovalKind.PLAYLIST_ITEM -> playlistIds[itemKey.substringBefore('/')]?.let { "$it/${itemKey.substringAfter('/')}" } ?: itemKey
            else -> itemKey
        }
        return copy(profileSyncId = profiles[profileSyncId] ?: profileSyncId, itemKey = key)
    }
}

/** The renames that give this device's paired profiles and playlists their pairs' ids. */
internal fun pairingRename(pairing: Pairing): IdRename = IdRename(
    profiles = pairing.profiles.filter { (local, remote) -> local != remote },
    playlists = pairing.playlists.mapValues { (_, pairs) -> pairs.filter { (local, remote) -> local != remote } },
)
