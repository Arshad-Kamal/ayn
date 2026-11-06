package com.arshad.ayn.core.backup

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.boundaryJson
import com.arshad.ayn.core.access.decodeOrNull
import com.arshad.ayn.core.access.nestsWithin
import com.arshad.ayn.core.store.MAX_PROFILES
import com.arshad.ayn.core.store.WATCH_LATER_SYNC_ID
import com.arshad.ayn.core.store.canonicalChannelUrl
import com.arshad.ayn.core.store.validPlaylistName
import com.arshad.ayn.core.store.validProfileName
import kotlinx.serialization.Serializable

internal const val BUNDLE_VERSION = 6

/** The first version whose profiles and playlists must carry their sync ids (M8-D13). */
internal const val SYNC_IDS_VERSION = 6
internal const val APP_MARKER = "Ayn"

/** Backups written before the app was renamed Ayn carry this marker; they still restore. */
internal const val LEGACY_APP_MARKER = "YouTube KE"

/** Far above a real backup (a few hundred bytes per record); keeps a hostile file out of memory. */
internal const val MAX_BACKUP_BYTES = 32 * 1024 * 1024

/** Longer than any title, name or URL YouTube gives, and far under the 2 MB a row may hold
 *  before Android can't read it back. */
internal const val MAX_TEXT_LENGTH = 4_096

/** A backup nests 7 deep; the parser has no depth guard of its own. */
internal const val MAX_JSON_DEPTH = 16

/** Far above any map a backup holds (a SponsorBlock category, a day or a listed channel per key);
 *  restore reports each key it doesn't know as its own skip. */
internal const val MAX_MAP_ENTRIES = 256

internal fun encodeBackup(bundle: BackupBundle): ByteArray =
    boundaryJson.encodeToString(BackupBundle.serializer(), bundle).encodeToByteArray()

/** Checks [bytes] in full; only a backup that passes every check comes back [BackupRead.Valid]. */
internal fun decodeBackup(bytes: ByteArray): BackupRead {
    if (bytes.size > MAX_BACKUP_BYTES) return BackupRead.Refused(BackupRefusal.TooLarge)
    val text = bytes.decodeToString().removePrefix(BYTE_ORDER_MARK)
    if (!nestsWithin(text, MAX_JSON_DEPTH)) return BackupRead.Refused(BackupRefusal.NotABackup)
    // The header first: a newer KE's file may not decode as this version's bundle, and should be
    // refused as newer rather than as damaged.
    val header = decodeOrNull(text, BundleHeader.serializer())
    val version = header?.bundleVersion
    return when {
        header?.app !in setOf(APP_MARKER, LEGACY_APP_MARKER) || version == null || version < 1 -> BackupRead.Refused(BackupRefusal.NotABackup)
        version > BUNDLE_VERSION -> BackupRead.Refused(BackupRefusal.NewerVersion)
        else -> decodeOrNull(text, BackupBundle.serializer())
            ?.takeIf { it.holdsTogether() }
            ?.let { BackupRead.Valid(ValidBackup(it)) }
            ?: BackupRead.Refused(BackupRefusal.Damaged)
    }
}

internal const val BYTE_ORDER_MARK = "\uFEFF"

@Serializable
private class BundleHeader(val app: String? = null, val bundleVersion: Int? = null)

/**
 * What the database would refuse, or KE couldn't read back, checked before anything is deleted:
 * at most one owner (an export may leave it out, KP-D24) among 1..[MAX_PROFILES] profiles, names as KE stores them (trimmed, in length) and
 * unique the way the database compares them, every record key present and unique, every video
 * id one a [VideoId] accepts (the stores rebuild one from each row they read), every other
 * text within [MAX_TEXT_LENGTH], every map within [MAX_MAP_ENTRIES], and an owner that isn't a kids
 * profile. Sync ids are unique where they name (profiles in the file, playlists in their profile),
 * Watch later's is its own, and from [SYNC_IDS_VERSION] every profile and playlist has one; each
 * removal record is there once. List rules a store would refuse are left out at restore, not
 * refused here (KP-D17).
 */
private fun BackupBundle.holdsTogether(): Boolean =
    profiles.size in 1..MAX_PROFILES &&
        profiles.count { it.owner } <= 1 &&
        profiles.none { it.owner && it.kids } &&
        profiles.all { validProfileName(it.name) == it.name } &&
        profiles.keysUnique { it.name.foldAsciiCase() } &&
        profiles.mapNotNull { it.syncId }.keysUnique { it } &&
        profiles.all { it.holdsTogether(syncIdsRequired = bundleVersion >= SYNC_IDS_VERSION) } &&
        removals.distinctBy { it.key }.size == removals.size &&
        removals.all { textsFit(it.profileSyncId, it.kind, it.itemKey) }

private fun BackupProfile.holdsTogether(syncIdsRequired: Boolean): Boolean =
    (!syncIdsRequired || (syncId != null && playlists.all { it.syncId != null })) &&
        textsFit(syncId) &&
        playlists.mapNotNull { it.syncId }.keysUnique { it } &&
        playlists.all { textsFit(it.syncId) && it.syncId != WATCH_LATER_SYNC_ID } &&
        (watchLater?.syncId ?: WATCH_LATER_SYNC_ID) == WATCH_LATER_SYNC_ID &&
        subscriptions.keysUnique { it.channelUrl } &&
        subscriptions.all { textsFit(it.channelUrl, it.name, it.avatarUrl) } &&
        history.videoIdsValidAndUnique { it.videoId } &&
        history.all { textsFit(it.title, it.uploaderName, it.uploaderUrl, it.thumbnailUrl) } &&
        (playlists + listOfNotNull(watchLater)).all { playlist ->
            validPlaylistName(playlist.name) == playlist.name &&
                playlist.videos.videoIdsValidAndUnique { it.videoId } &&
                playlist.videos.all { textsFit(it.title, it.uploaderName, it.uploaderUrl, it.thumbnailUrl) }
        } &&
        hiddenVideos.videoIdsValidAndUnique { it.videoId } &&
        // Restore stores channels by their canonical URL, so two forms of one channel would collide.
        hiddenChannels.keysUnique { canonicalChannelUrl(it.channelUrl) } &&
        hiddenChannels.all { textsFit(it.channelUrl) } &&
        rules.all { textsFit(it.kind, it.value, it.title, it.channelName, it.channelUrl, it.thumbnailUrl) } &&
        mapFits(settings?.segmentActions) &&
        mapFits(settings?.filterPacks?.islamicChannels) &&
        mapFits(restrictions?.dailyLimitMinutes) &&
        mapFits(restrictions?.filterPacks?.islamicChannels)

private fun textsFit(vararg texts: String?): Boolean = texts.all { it == null || it.length <= MAX_TEXT_LENGTH }

private fun mapFits(map: Map<String, *>?): Boolean =
    map == null || (map.size <= MAX_MAP_ENTRIES && map.keys.all { it.length <= MAX_TEXT_LENGTH })

private inline fun <T> List<T>.keysUnique(key: (T) -> String): Boolean {
    val keys = HashSet<String>(size)
    return all { item -> key(item).let { it.isNotBlank() && keys.add(it) } }
}

private inline fun <T> List<T>.videoIdsValidAndUnique(videoId: (T) -> String): Boolean =
    all { VideoId.isValid(videoId(it)) } && keysUnique(videoId)

/** Case folding as SQLite's NOCASE does it (ASCII letters only), so the check matches the
 *  database's unique index exactly: anything one install stored restores. */
internal fun String.foldAsciiCase(): String = buildString(length) {
    for (c in this@foldAsciiCase) append(if (c in 'A'..'Z') c.lowercaseChar() else c)
}
