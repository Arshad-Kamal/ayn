package com.arshad.ayn.core.backup

import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.store.RuleKind
import kotlinx.serialization.Serializable

/**
 * A KE backup file (spec D4, M5-D5/D14): every profile and what it holds, with no lock secrets and
 * no database ids. Times are epoch milliseconds and restore writes them verbatim; each record's
 * `updatedAtMillis` is the D4 Phase 2 merge key. Nullable fields are left out when null. Field
 * names are the wire format: renaming one needs a `bundleVersion` bump (the golden fixture tests
 * pin them). Version 2 added [BackupProfile.settings]; a version 1 file restores with defaults.
 * Version 3 added For you's feedback ([BackupProfile.hiddenVideos], [BackupProfile.hiddenChannels])
 * and [BackupSettings.forYou]; an older file restores with none hidden and For you on.
 * Version 4 added content filters and kids profiles (KP-D18): [BackupProfile.kids],
 * [BackupProfile.restrictions], [BackupProfile.rules] and the filter fields of [BackupSettings];
 * an older file restores with standard profiles and no filters. It also added the chosen-profiles
 * export (KP-D24: the owner may be absent) and [BackupBundle.device]. [BackupDevice.backgroundAudio]
 * and [BackupWatchEntry.short] joined version 4 later without a bump: a file without them restores
 * background audio on and every entry as not a Short, the defaults.
 * Version 5 added filter packs (FPK-D20): [BackupSettings.filterPacks] and
 * [BackupRestrictions.filterPacks]; an older file restores with every pack off (FPK-D21). Bumped,
 * not added to 4: an older build ignores unknown keys, so it would restore the owner's packs off.
 * Version 6 is also the cloud sync payload (M8-D3, M8-D13): [BackupProfile.syncId] and
 * [BackupPlaylist.syncId], required from it, and [removals]; an older file restores with new ids.
 * [BackupSettings.chatPlacement] joined version 6 later without a bump: a file without it keeps the
 * restored-onto profile's value (beside the video on a new one), so an older build's sync copy, which
 * drops the key and can win the merge's tie at an equal time, leaves each device's choice as it is.
 */
@Serializable
internal data class BackupBundle(
    val bundleVersion: Int,
    val app: String,
    val exportedAtMillis: Long,
    /** The owner (when exported) first, then the others in the order they were added. */
    val profiles: List<BackupProfile>,
    /** The exporting device's settings (version 4); absent in older files and sync snapshots. */
    val device: BackupDevice? = null,
    /** A sync snapshot's removal records (version 6), by profile, kind and key; an export leaves
     *  them out (absent when empty) and a restore ignores them. */
    val removals: List<BackupRemoval> = emptyList(),
)

/** A removal record (M8-D6), as `core.store.RemovalEntity` holds it. */
@Serializable
internal data class BackupRemoval(val profileSyncId: String, val kind: String, val itemKey: String, val removedAtMillis: Long) {
    /** What the record is of: one record per key. */
    val key: Triple<String, String, String> get() = Triple(profileSyncId, kind, itemKey)
}

/** A device's own settings (M7-D9), restored only onto a device with no owner yet (KP-D17). Read
 *  leniently as [BackupSettings] are: a value this build doesn't offer is skipped. */
@Serializable
internal data class BackupDevice(
    val maxVideoHeight: Int,
    val frameRateMatching: Boolean,
    val interfaceScalePercent: Int,
    val clockInPlayer: Boolean,
    val heroOnHome: Boolean,
    /** Absent in files written before background audio (M6-D5): restores as on. */
    val backgroundAudio: Boolean = true,
)

@Serializable
internal data class BackupProfile(
    val name: String,
    /** A [com.arshad.ayn.core.store.ProfileTint] key. */
    val tint: String,
    val owner: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val subscriptions: List<BackupSubscription>,
    /** The most recently watched first. */
    val history: List<BackupWatchEntry>,
    /** The profile's own playlists, oldest first. */
    val playlists: List<BackupPlaylist>,
    /** Absent until the profile first saves to Watch later. */
    val watchLater: BackupPlaylist? = null,
    /** Absent until the profile first changes a setting (and in every version 1 file): the
     *  defaults. */
    val settings: BackupSettings? = null,
    /** Not interested, oldest first; absent (and left out when empty) = none. */
    val hiddenVideos: List<BackupHiddenVideo> = emptyList(),
    /** Don't recommend this channel, oldest first; absent (and left out when empty) = none. */
    val hiddenChannels: List<BackupHiddenChannel> = emptyList(),
    /** Absent (and left out when false) = a standard profile. */
    val kids: Boolean = false,
    /** The owner's restrictions; absent until the owner first sets one. */
    val restrictions: BackupRestrictions? = null,
    /** Both setters' list rules, oldest first; absent (and left out when empty) = none. */
    val rules: List<BackupRule> = emptyList(),
    /** The profile across devices (version 6, M8-D5). */
    val syncId: String? = null,
)

/**
 * A profile's settings (M7-D9). Values are read leniently, one field at a time: one this build
 * doesn't know restores as that field's default, and the file is not refused for it. Types stay
 * strict: a wrong JSON type makes the file damaged (the decoder still reads a quoted number as a
 * number, here as everywhere in the bundle).
 */
@Serializable
internal data class BackupSettings(
    val speedPercent: Int,
    val resume: Boolean,
    /** A BCP-47 language tag; absent = captions Off. */
    val captionsLanguage: String? = null,
    val sponsorBlock: Boolean,
    /** Action key by SponsorBlock category name; an unknown category is ignored, a missing one
     *  takes its default. */
    val segmentActions: Map<String, String>,
    val skipNote: Boolean,
    val dearrowTitles: Boolean,
    val dearrowThumbnails: Boolean,
    val dislikes: Boolean,
    val showShorts: Boolean,
    /** Absent (and left out when on) = on. */
    val forYou: Boolean = true,
    val updatedAtMillis: Long,
    /** The profile's own filters (version 4); each absent = off. A Restricted Mode level this
     *  build doesn't know restores as Strict (KP-D18). */
    val restrictedMode: String? = null,
    val hideLive: Boolean = false,
    val hideMembersOnly: Boolean = false,
    val minLengthMinutes: Int? = null,
    val maxLengthMinutes: Int? = null,
    /** The profile's own filter packs (version 5); absent = all off. */
    val filterPacks: BackupFilterPacks? = null,
    /** A [com.arshad.ayn.core.store.ChatPlacement] key (version 6, LIVE-D21), always written;
     *  absent keeps the base's, as a missing SponsorBlock category does. */
    val chatPlacement: String? = null,
)

/**
 * The owner's restrictions on a profile (KP-D12). Read leniently as [BackupSettings] are: a
 * Restricted Mode level this build doesn't know restores as Strict, a length, limit or bedtime out
 * of range as unset.
 */
@Serializable
internal data class BackupRestrictions(
    val restrictedMode: String,
    val hideShorts: Boolean,
    val hideLive: Boolean,
    val hideMembersOnly: Boolean,
    val minLengthMinutes: Int? = null,
    val maxLengthMinutes: Int? = null,
    /** Minutes by day (`monday`…`sunday`); a missing day has no limit, an unknown key is ignored. */
    val dailyLimitMinutes: Map<String, Int> = emptyMap(),
    val bedtimeStartMinute: Int? = null,
    val bedtimeEndMinute: Int? = null,
    val updatedAtMillis: Long,
    /** The owner's filter packs (version 5); absent = all off. */
    val filterPacks: BackupFilterPacks? = null,
)

/** One setter's filter packs (FPK-D20); each switch absent (and left out when off) = off. */
@Serializable
internal data class BackupFilterPacks(
    val harmfulForKids: Boolean = false,
    val overstimulating: Boolean = false,
    val challengesAndVlogs: Boolean = false,
    val halalOnly: Boolean = false,
    val stageMagic: Boolean = false,
    val festivals: Boolean = false,
    val lootBoxes: Boolean = false,
    val islamicOnly: Boolean = false,
    /** Islamic content only's switches moved off their defaults, by channel id; a channel this
     *  build doesn't list is skipped at restore. */
    val islamicChannels: Map<String, Boolean> = emptyMap(),
)

/** One list rule (KP-D18): [kind] and [value] as the database stores them; a kind this build
 *  doesn't know is left out of the restore. */
@Serializable
internal data class BackupRule(
    val kind: String,
    val value: String,
    val byOwner: Boolean,
    val title: String? = null,
    val channelName: String? = null,
    val channelUrl: String? = null,
    val thumbnailUrl: String? = null,
    val durationSeconds: Long? = null,
    val addedAtMillis: Long,
)

@Serializable
internal data class BackupHiddenVideo(val videoId: String, val hiddenAtMillis: Long)

@Serializable
internal data class BackupHiddenChannel(val channelUrl: String, val hiddenAtMillis: Long)

@Serializable
internal data class BackupSubscription(
    val channelUrl: String,
    val name: String,
    val avatarUrl: String?,
    val subscribedAtMillis: Long,
    val updatedAtMillis: Long,
)

@Serializable
internal data class BackupWatchEntry(
    val videoId: String,
    val title: String?,
    val uploaderName: String?,
    val uploaderUrl: String?,
    val thumbnailUrl: String?,
    val positionMs: Long,
    val durationMs: Long?,
    val watched: Boolean,
    val lastWatchedAtMillis: Long,
    val updatedAtMillis: Long,
    /** Played as a Short (PAF-D6); absent (and left out when false) = not one. */
    val short: Boolean = false,
)

@Serializable
internal data class BackupPlaylist(
    val name: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    /** In playlist order. */
    val videos: List<BackupPlaylistVideo>,
    /** The playlist across its profile's devices (version 6, M8-D12); Watch later's is always
     *  `watch-later`. */
    val syncId: String? = null,
)

@Serializable
internal data class BackupPlaylistVideo(
    val videoId: String,
    val title: String,
    val uploaderName: String?,
    val uploaderUrl: String?,
    /** -1 when YouTube didn't give a length. */
    val durationSeconds: Long,
    val uploadDateMillis: Long?,
    val thumbnailUrl: String?,
    val addedAtMillis: Long,
    val updatedAtMillis: Long,
)

internal fun BackupProfile.summary(): BackupProfileSummary = BackupProfileSummary(
    name = name,
    tint = ProfileTint.of(tint),
    owner = owner,
    kids = kids,
    counts = ProfileCounts(
        subscriptions = subscriptions.size,
        history = history.size,
        playlists = playlists.size,
        playlistVideos = playlists.sumOf { it.videos.size },
        hidden = hiddenVideos.size + hiddenChannels.size + rules.count { it.kind == RuleKind.CHANNEL || it.kind == RuleKind.VIDEO },
        approvedVideos = rules.count { it.kind == RuleKind.APPROVED_VIDEO },
    ),
)
