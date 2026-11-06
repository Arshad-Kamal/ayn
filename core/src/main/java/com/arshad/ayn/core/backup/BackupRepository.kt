package com.arshad.ayn.core.backup

import android.net.Uri
import android.util.Log
import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import androidx.sqlite.SQLiteException
import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.lock.OwnerPass
import com.arshad.ayn.core.store.ContentRuleEntity
import com.arshad.ayn.core.store.DeviceSettingsStore
import com.arshad.ayn.core.store.HiddenChannelEntity
import com.arshad.ayn.core.store.HiddenVideoEntity
import com.arshad.ayn.core.store.KeDatabase
import com.arshad.ayn.core.store.MAX_PATTERNS
import com.arshad.ayn.core.store.MAX_PROFILES
import com.arshad.ayn.core.store.MAX_PROFILE_NAME
import com.arshad.ayn.core.store.MAX_RULES_PER_KIND
import com.arshad.ayn.core.store.NOT_INSERTED
import com.arshad.ayn.core.store.OWNER_MARK
import com.arshad.ayn.core.store.PlaylistEntity
import com.arshad.ayn.core.store.PlaylistItemEntity
import com.arshad.ayn.core.store.ProfileEntity
import com.arshad.ayn.core.store.ProfileRestrictions
import com.arshad.ayn.core.store.ProfileRestrictionsEntity
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.ProfileSettingsEntity
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.store.RemovalEntity
import com.arshad.ayn.core.store.RuleKind
import com.arshad.ayn.core.store.SelfRule
import com.arshad.ayn.core.store.SubscriptionEntity
import com.arshad.ayn.core.store.WATCH_LATER_MARK
import com.arshad.ayn.core.store.WATCH_LATER_SYNC_ID
import com.arshad.ayn.core.store.WatchEntryEntity
import com.arshad.ayn.core.store.canonicalChannelUrl
import com.arshad.ayn.core.store.collapseByChannel
import com.arshad.ayn.core.store.newSyncId
import com.arshad.ayn.core.store.toEntity
import com.arshad.ayn.core.store.toRestrictions
import com.arshad.ayn.core.store.toRule
import com.arshad.ayn.core.store.toSettings
import com.arshad.ayn.core.store.validRule
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * [BackupStore] over the database, [files] and [deviceSettings]. Export reads every table in one
 * read transaction; restore writes the chosen profiles in one write transaction (M5-D4, KP-D24),
 * so a failure leaves the profiles as they were; the device settings, outside the database, are
 * applied after it commits. Encoding, an opened file's check and import parsing run on [work];
 * the export's read-back check runs inside [files]' save, on its IO thread. A restored profile's
 * settings start from this device's [profileDefaults], as a never-written profile's do (M6-D6).
 * Sync ids travel with the records (M8-D13): a restored playlist keeps the file's, a profile as
 * [writeChosen] says. While [syncLinked], a restore stamps the records it writes with now, so they
 * win over older removal records at the next sync (M8-D14).
 */
class BackupRepository(
    private val database: KeDatabase,
    private val session: ProfileSession,
    private val files: BackupFiles,
    private val deviceSettings: DeviceSettingsStore,
    private val clock: Clock = Clock.System,
    /** The day in a backup's file name is the TV's. */
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val work: CoroutineDispatcher = Dispatchers.Default,
    private val profileDefaults: ProfileSettings = ProfileSettings(),
    private val syncLinked: suspend () -> Boolean = { false },
) : BackupStore {

    override suspend fun export(owner: OwnerPass, profileIds: Set<Long>?): ExportResult {
        val nowMillis = clock.now().toEpochMilliseconds()
        val device = deviceSettings.settings().first().toBackup()
        val bundle = try {
            snapshot(nowMillis, profileIds, device)
        } catch (e: SQLiteException) {
            Log.w(BACKUP_LOG_TAG, "export read failed: ${e.javaClass.simpleName}")
            return ExportResult.Failed
        }
        if (bundle.profiles.isEmpty()) return ExportResult.Failed
        val bytes = withContext(work) { encodeBackup(bundle) }
        val saved = files.save(backupFileName(nowMillis, zone), bytes) { written -> decodeBackup(written) is BackupRead.Valid }
        return saved?.let { ExportResult.Saved(it, bundle.profiles.map(BackupProfile::summary)) } ?: ExportResult.Failed
    }

    override suspend fun savedBackups(): List<SavedBackup> = files.list()

    override suspend fun deleteBackup(owner: OwnerPass, uri: Uri): Boolean = files.delete(uri)

    override suspend fun open(uri: Uri): BackupRead {
        val bytes = when (val read = files.read(uri, MAX_BACKUP_BYTES)) {
            is FileRead.Bytes -> read.bytes
            FileRead.TooLarge -> return BackupRead.Refused(BackupRefusal.TooLarge)
            FileRead.Unreadable -> return BackupRead.Refused(BackupRefusal.Unreadable)
        }
        return withContext(work) { decodeBackup(bytes) }
    }

    override suspend fun restorePlan(backup: ValidBackup): RestorePlan? = try {
        database.withReadTransaction { planFor(backup.bundle, database.profileDao().all()) }
    } catch (e: SQLiteException) {
        Log.w(BACKUP_LOG_TAG, "restore plan read failed: ${e.javaClass.simpleName}")
        null
    }

    /** Not cancellable once started: a first launch's committed owner must become the session,
     *  and its device settings must follow. */
    override suspend fun restore(owner: OwnerPass, backup: ValidBackup, choices: List<RestoreChoice>): RestoreResult =
        withContext(NonCancellable) {
            val bundle = if (syncLinked()) backup.bundle.stampedAt(clock.now().toEpochMilliseconds()) else backup.bundle
            val written: Written? = try {
                database.withWriteTransaction { writeChosen(bundle, choices) }
            } catch (e: SQLiteException) {
                Log.w(BACKUP_LOG_TAG, "restore failed: ${e.javaClass.simpleName}")
                null
            }
            if (written == null) return@withContext RestoreResult.Failed
            if (written.firstLaunch) session.select(written.ownerId)
            val device = backup.bundle.device?.takeIf { written.firstLaunch && RestoreChoice.Skip !in choices }
            var deviceSkips = emptyList<SettingSkip>()
            device?.let { section ->
                deviceSettings.update { current -> section.restoreOnto(current).also { deviceSkips = it.skips }.value }
            }
            RestoreResult.Restored(written.ownerId, written.profiles, deviceSettings = device != null, deviceSkips = deviceSkips)
        }

    override suspend fun importSubscriptions(uri: Uri, profileId: Long): ImportResult {
        val bytes = when (val read = files.read(uri, MAX_SUBSCRIPTIONS_BYTES)) {
            is FileRead.Bytes -> read.bytes
            FileRead.TooLarge -> return ImportResult.TooLarge
            FileRead.Unreadable -> return ImportResult.Unreadable
        }
        val channels = withContext(work) { parseSubscriptionsFile(bytes) }
        val nowMillis = clock.now().toEpochMilliseconds()
        val rowIds = try {
            database.withWriteTransaction<List<Long>?> {
                if (database.profileDao().isKids(profileId)) {
                    null
                } else {
                    database.subscriptionDao().insertIfAbsent(
                        channels.map { SubscriptionEntity(profileId, it.channelUrl, it.name, avatarUrl = null, nowMillis, nowMillis) },
                    )
                }
            }
        } catch (e: SQLiteException) {
            Log.w(BACKUP_LOG_TAG, "import failed: ${e.javaClass.simpleName}")
            return ImportResult.Failed
        }
        if (rowIds == null) return ImportResult.Failed
        val added = rowIds.count { it != NOT_INSERTED }
        return ImportResult.Imported(added = added, alreadySubscribed = channels.size - added)
    }

    /** Inside the restore's write transaction: `null`, with nothing written, when [choices] aren't
     *  what the plan allows (KP-D24). A profile added as new takes the file's sync id unless a
     *  profile here has it (two copies of one profile on one device) or it arrives in another role
     *  (another device's owner added beside this one's), then a new one. */
    private suspend fun writeChosen(bundle: BackupBundle, choices: List<RestoreChoice>): Written? {
        val here = database.profileDao().all()
        val plan = planFor(bundle, here)
        if (!plan.allows(bundle, choices)) return null
        val taken = here.mapTo(HashSet()) { it.name.foldAsciiCase() }
        val syncIds = here.mapTo(HashSet()) { it.syncId }
        var ownerId = here.firstOrNull { it.ownerMark != null }?.id
        val restored = bundle.profiles.zip(choices).mapNotNull { (profile, choice) ->
            when (choice) {
                RestoreChoice.Skip -> null
                is RestoreChoice.Replace -> replace(here.first { it.id == choice.profileId }, profile)
                RestoreChoice.AddAsNew -> {
                    val asOwner = plan.firstLaunch && profile.owner
                    val name = freeName(profile.name, taken).also { taken += it.foldAsciiCase() }
                    val syncId = (profile.syncId?.takeIf { it !in syncIds && profile.owner == asOwner } ?: newSyncId())
                        .also { syncIds += it }
                    addAsNew(profile, name, syncId, asOwner).also { if (asOwner) ownerId = it.profileId }
                }
            }
        }
        return Written(plan.firstLaunch, checkNotNull(ownerId), restored)
    }

    /** [profile]'s contents in place of [old]'s: the row goes back under its id with its owner
     *  mark, lock and tries, and its kids-time days are carried over; the cascade clears the rest. */
    private suspend fun replace(old: ProfileEntity, profile: BackupProfile): RestoredProfile {
        val settings = database.profileSettingsDao().row(old.id)?.toSettings() ?: profileDefaults
        val restrictions = database.filterDao().restrictionsRow(old.id)?.toRestrictions() ?: ProfileRestrictions()
        val days = database.kidsTimeDao().allFor(old.id)
        database.profileDao().removeToReplace(old.id)
        database.profileDao().insert(old)
        database.kidsTimeDao().insertAll(days)
        return insertContents(old.id, old.name, replaced = true, profile, settings, restrictions)
    }

    private suspend fun addAsNew(profile: BackupProfile, name: String, syncId: String, asOwner: Boolean): RestoredProfile {
        val profileId = database.profileDao().insert(
            ProfileEntity(
                name = name,
                tint = ProfileTint.of(profile.tint).key,
                ownerMark = if (asOwner) OWNER_MARK else null,
                createdAtMillis = profile.createdAtMillis,
                updatedAtMillis = profile.updatedAtMillis,
                kids = profile.kids,
                syncId = syncId,
            ),
        )
        return insertContents(profileId, name, replaced = false, profile, profileDefaults, ProfileRestrictions())
    }

    private suspend fun snapshot(exportedAtMillis: Long, profileIds: Set<Long>?, device: BackupDevice?): BackupBundle =
        database.withReadTransaction { database.readSnapshot(exportedAtMillis, profileIds, device, withRemovals = false) }

    /** Inside the restore's write transaction: [profile]'s contents into the empty profile
     *  [profileId], its settings and restrictions over [baseSettings] and [baseRestrictions]
     *  (the replaced profile's, else the defaults: KP-D24). A profile on the defaults in the
     *  backup gets no settings row, so it takes a later build's defaults too. */
    private suspend fun insertContents(
        profileId: Long,
        name: String,
        replaced: Boolean,
        profile: BackupProfile,
        baseSettings: ProfileSettings,
        baseRestrictions: ProfileRestrictions,
    ): RestoredProfile {
        // A backup from before M5-D19 can hold one channel under two URL forms.
        val subscriptions = collapseByChannel(profile.subscriptions.map { it.toEntity(profileId) })
        database.subscriptionDao().insertAll(subscriptions)
        database.watchDao().insertAll(profile.history.map { it.toEntity(profileId) })
        profile.playlists.forEach { insert(it, profileId, watchLaterMark = null) }
        profile.watchLater?.let { insert(it, profileId, WATCH_LATER_MARK) }
        val settings = profile.settings?.let { backup ->
            backup.restoreOnto(baseSettings).also { database.profileSettingsDao().upsert(it.value.toEntity(profileId, backup.updatedAtMillis)) }
        }
        database.forYouDao().insertHiddenVideos(profile.hiddenVideos.map { HiddenVideoEntity(profileId, it.videoId, it.hiddenAtMillis) })
        database.forYouDao().insertHiddenChannels(
            profile.hiddenChannels.map { HiddenChannelEntity(profileId, canonicalChannelUrl(it.channelUrl), it.hiddenAtMillis) },
        )
        val restrictions = profile.restrictions?.let { backup ->
            backup.restoreOnto(baseRestrictions).also { database.filterDao().upsertRestrictions(it.value.toEntity(profileId, backup.updatedAtMillis)) }
        }
        val rules = restorableRules(profile.rules, profileId)
        database.filterDao().insertRules(rules)
        val settingSkips = settings?.skips.orEmpty()
        val total = settingsTotal(profile.settings)
        return RestoredProfile(
            profileId = profileId,
            name = name,
            replaced = replaced,
            counts = ProfileCounts(
                subscriptions = subscriptions.size,
                history = profile.history.size,
                playlists = profile.playlists.size,
                playlistVideos = profile.playlists.sumOf { it.videos.size },
                hidden = profile.hiddenVideos.size + profile.hiddenChannels.size +
                    rules.count { it.kind == RuleKind.CHANNEL || it.kind == RuleKind.VIDEO },
                approvedVideos = rules.count { it.kind == RuleKind.APPROVED_VIDEO },
            ),
            settingsApplied = total - settingSkips.size,
            settingsTotal = total,
            skips = settingSkips + restrictions?.skips.orEmpty(),
        )
    }

    private suspend fun insert(playlist: BackupPlaylist, profileId: Long, watchLaterMark: Int?) {
        val playlistId = database.playlistDao().insertPlaylist(
            PlaylistEntity(
                profileId = profileId,
                name = playlist.name,
                watchLaterMark = watchLaterMark,
                createdAtMillis = playlist.createdAtMillis,
                updatedAtMillis = playlist.updatedAtMillis,
                syncId = if (watchLaterMark != null) WATCH_LATER_SYNC_ID else playlist.syncId ?: newSyncId(),
            ),
        )
        database.playlistDao().insertItems(playlist.videos.mapIndexed { position, video -> video.toEntity(playlistId, position.toLong()) })
    }
}

/** What a restore's transaction wrote. */
private class Written(val firstLaunch: Boolean, val ownerId: Long, val profiles: List<RestoredProfile>)

/** Every profile (or those in [profileIds]) and what it holds, with [device] and, [withRemovals],
 *  the removal records. Call it inside a transaction, so the tables are read at one moment. */
internal suspend fun KeDatabase.readSnapshot(
    exportedAtMillis: Long,
    profileIds: Set<Long>?,
    device: BackupDevice?,
    withRemovals: Boolean,
): BackupBundle {
    val subscriptions = subscriptionDao().all().groupBy { it.profileId }
    val history = watchDao().all().groupBy { it.profileId }
    val playlists = playlistDao().allPlaylists().groupBy { it.profileId }
    val videos = playlistDao().allItems().groupBy { it.playlistId }
    val settings = profileSettingsDao().all().associateBy { it.profileId }
    val hiddenVideos = forYouDao().allHiddenVideos().groupBy { it.profileId }
    val hiddenChannels = forYouDao().allHiddenChannels().groupBy { it.profileId }
    val restrictions = filterDao().allRestrictions().associateBy { it.profileId }
    val rules = filterDao().allRules().groupBy { it.profileId }
    val removals = if (withRemovals) removalDao().all() else emptyList()
    return BackupBundle(
        bundleVersion = BUNDLE_VERSION,
        app = APP_MARKER,
        exportedAtMillis = exportedAtMillis,
        device = device,
        removals = removals.map(RemovalEntity::toBackup),
        profiles = profileDao().all().filter { profileIds == null || it.id in profileIds }.map { profile ->
            val (watchLater, own) = playlists[profile.id].orEmpty().partition { it.watchLaterMark != null }
            profile.toBackup(
                subscriptions = subscriptions[profile.id].orEmpty().map(SubscriptionEntity::toBackup),
                history = history[profile.id].orEmpty().map(WatchEntryEntity::toBackup),
                playlists = own.map { it.toBackup(videos[it.id].orEmpty()) },
                watchLater = watchLater.singleOrNull()?.let { it.toBackup(videos[it.id].orEmpty()) },
                // Only a stored row: a profile still on the defaults gets later builds' defaults too.
                settings = settings[profile.id]?.let(ProfileSettingsEntity::toBackup),
                hiddenVideos = hiddenVideos[profile.id].orEmpty().map { BackupHiddenVideo(it.videoId, it.hiddenAtMillis) },
                hiddenChannels = hiddenChannels[profile.id].orEmpty().map { BackupHiddenChannel(it.channelUrl, it.hiddenAtMillis) },
                restrictions = restrictions[profile.id]?.let(ProfileRestrictionsEntity::toBackup),
                rules = rules[profile.id].orEmpty().map(ContentRuleEntity::toBackup),
            )
        },
    )
}

/** Every record's merge time (M8-D13) set to [nowMillis]: a restore while sync is linked
 *  (M8-D14). The times records are shown by (subscribed, last watched, added to a playlist) stay;
 *  hidden items and list rules have one time, which moves. */
private fun BackupBundle.stampedAt(nowMillis: Long): BackupBundle {
    fun BackupPlaylist.stamped() = copy(updatedAtMillis = nowMillis, videos = videos.map { it.copy(updatedAtMillis = nowMillis) })
    return copy(
        profiles = profiles.map { profile ->
            profile.copy(
                updatedAtMillis = nowMillis,
                subscriptions = profile.subscriptions.map { it.copy(updatedAtMillis = nowMillis) },
                history = profile.history.map { it.copy(updatedAtMillis = nowMillis) },
                playlists = profile.playlists.map { it.stamped() },
                watchLater = profile.watchLater?.stamped(),
                settings = profile.settings?.copy(updatedAtMillis = nowMillis),
                hiddenVideos = profile.hiddenVideos.map { it.copy(hiddenAtMillis = nowMillis) },
                hiddenChannels = profile.hiddenChannels.map { it.copy(hiddenAtMillis = nowMillis) },
                restrictions = profile.restrictions?.copy(updatedAtMillis = nowMillis),
                rules = profile.rules.map { it.copy(addedAtMillis = nowMillis) },
            )
        },
    )
}

/** [bundle]'s profiles against the profiles [here] (KP-D24): a Replace target is the profile of
 *  the same name and kind; at first launch (no owner here) there are none. */
private fun planFor(bundle: BackupBundle, here: List<ProfileEntity>): RestorePlan {
    val firstLaunch = here.none { it.ownerMark != null }
    return RestorePlan(
        firstLaunch = firstLaunch,
        roomForNew = (MAX_PROFILES - here.size).coerceAtLeast(0),
        profiles = bundle.profiles.map { profile ->
            val target = here.takeUnless { firstLaunch }
                ?.firstOrNull { it.name.foldAsciiCase() == profile.name.foldAsciiCase() && it.kids == profile.kids }
            PlannedProfile(profile.summary(), target?.id)
        },
    )
}

/** One choice per profile, something restored, Replace only onto the planned target, no more
 *  added than there is room for; at first launch only Add as new, the backup's owner among them
 *  (a backup without its owner can't start a device, Q18). */
private fun RestorePlan.allows(bundle: BackupBundle, choices: List<RestoreChoice>): Boolean {
    if (choices.size != profiles.size || choices.all { it == RestoreChoice.Skip }) return false
    if (choices.count { it == RestoreChoice.AddAsNew } > roomForNew) return false
    val replacesPlanned = choices.zip(profiles).all { (choice, planned) ->
        choice !is RestoreChoice.Replace || choice.profileId == planned.replaces
    }
    if (!firstLaunch) return replacesPlanned
    val owner = bundle.profiles.indexOfFirst { it.owner }
    return owner >= 0 && choices[owner] == RestoreChoice.AddAsNew && choices.none { it is RestoreChoice.Replace }
}

/** [name], or with " 2", " 3"… when a profile here has it ([taken] holds folded names), cut to
 *  fit [MAX_PROFILE_NAME]. */
internal fun freeName(name: String, taken: Set<String>): String {
    if (name.foldAsciiCase() !in taken) return name
    return generateSequence(2) { it + 1 }
        .map { n -> " $n".let { suffix -> name.take(MAX_PROFILE_NAME - suffix.length).trimEnd() + suffix } }
        .first { it.foldAsciiCase() !in taken }
}

/** `Ayn-backup-YYYY-MM-DD.json`, the day [nowMillis] falls on in [zone]. */
internal fun backupFileName(nowMillis: Long, zone: ZoneId): String =
    "$BACKUP_NAME_PREFIX${Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()}.json"

/** [validRules] within the caps, as the store would have taken them (KP-D17, D18). */
private fun restorableRules(rules: List<BackupRule>, profileId: Long): List<ContentRuleEntity> =
    validRules(rules, profileId)
        .groupBy { it.byOwner to it.kind }
        .flatMap { (key, rows) -> rows.take(if (key.second == RuleKind.PATTERN) MAX_PATTERNS else MAX_RULES_PER_KIND) }

/**
 * Kinds this build knows, values [validRule] accepts, patterns and approvals only from the owner,
 * each rule once; the rest are left out. A sync applies them without the caps: a cap would drop
 * the same rules on every device, with no removal record (M8-D16).
 */
internal fun validRules(rules: List<BackupRule>, profileId: Long): List<ContentRuleEntity> =
    rules.mapNotNull { backup ->
        val rule = backup.toEntity(profileId).toRule()?.let(::validRule)
        rule?.takeIf { backup.byOwner || it is SelfRule }?.toEntity(profileId, backup.byOwner, backup.addedAtMillis)
    }.distinctBy { Triple(it.byOwner, it.kind, it.value) }

internal fun RemovalEntity.toBackup(): BackupRemoval = BackupRemoval(profileSyncId, kind, itemKey, removedAtMillis)

internal fun BackupRemoval.toEntity(): RemovalEntity = RemovalEntity(profileSyncId, kind, itemKey, removedAtMillis)

private fun ProfileEntity.toBackup(
    subscriptions: List<BackupSubscription>,
    history: List<BackupWatchEntry>,
    playlists: List<BackupPlaylist>,
    watchLater: BackupPlaylist?,
    settings: BackupSettings?,
    hiddenVideos: List<BackupHiddenVideo>,
    hiddenChannels: List<BackupHiddenChannel>,
    restrictions: BackupRestrictions?,
    rules: List<BackupRule>,
): BackupProfile = BackupProfile(
    name = name,
    tint = tint,
    owner = ownerMark != null,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    subscriptions = subscriptions,
    history = history,
    playlists = playlists,
    watchLater = watchLater,
    settings = settings,
    hiddenVideos = hiddenVideos,
    hiddenChannels = hiddenChannels,
    kids = kids,
    restrictions = restrictions,
    rules = rules,
    syncId = syncId,
)

private fun ProfileRestrictionsEntity.toBackup(): BackupRestrictions = toRestrictions().let { restrictions ->
    BackupRestrictions(
        restrictedMode = restrictions.restrictedMode.key,
        hideShorts = restrictions.hideShorts,
        hideLive = restrictions.hideLive,
        hideMembersOnly = restrictions.hideMembersOnly,
        minLengthMinutes = restrictions.lengthLimits.minMinutes,
        maxLengthMinutes = restrictions.lengthLimits.maxMinutes,
        dailyLimitMinutes = restrictions.dailyLimitMinutes.mapKeys { (day, _) -> day.backupKey() },
        bedtimeStartMinute = restrictions.bedtime?.startMinute,
        bedtimeEndMinute = restrictions.bedtime?.endMinute,
        updatedAtMillis = updatedAtMillis,
        filterPacks = restrictions.filterPacks.toBackup(),
    )
}

private fun ContentRuleEntity.toBackup(): BackupRule =
    BackupRule(kind, value, byOwner, title, channelName, channelUrl, thumbnailUrl, durationSeconds, addedAtMillis)

internal fun BackupRule.toEntity(profileId: Long): ContentRuleEntity =
    ContentRuleEntity(profileId, byOwner, kind, value, title, channelName, channelUrl, thumbnailUrl, durationSeconds, addedAtMillis)

private fun ProfileSettingsEntity.toBackup(): BackupSettings = toSettings().let { settings ->
    BackupSettings(
        speedPercent = settings.speedPercent,
        resume = settings.resume,
        captionsLanguage = settings.captionsLanguage,
        sponsorBlock = settings.sponsorBlock,
        segmentActions = SegmentCategory.entries.associate { it.key to settings.segmentActions.getValue(it).key },
        skipNote = settings.skipNote,
        dearrowTitles = settings.dearrowTitles,
        dearrowThumbnails = settings.dearrowThumbnails,
        dislikes = settings.dislikes,
        showShorts = settings.showShorts,
        forYou = settings.forYou,
        updatedAtMillis = updatedAtMillis,
        restrictedMode = settings.restrictedMode.key,
        hideLive = settings.hideLive,
        hideMembersOnly = settings.hideMembersOnly,
        minLengthMinutes = settings.lengthLimits.minMinutes,
        maxLengthMinutes = settings.lengthLimits.maxMinutes,
        filterPacks = settings.filterPacks.toBackup(),
        chatPlacement = settings.chatPlacement.key,
    )
}

private fun SubscriptionEntity.toBackup(): BackupSubscription =
    BackupSubscription(channelUrl, name, avatarUrl, subscribedAtMillis, updatedAtMillis)

internal fun BackupSubscription.toEntity(profileId: Long): SubscriptionEntity =
    SubscriptionEntity(profileId, channelUrl, name, avatarUrl, subscribedAtMillis, updatedAtMillis)

private fun WatchEntryEntity.toBackup(): BackupWatchEntry = BackupWatchEntry(
    videoId = videoId,
    title = title,
    uploaderName = uploaderName,
    uploaderUrl = uploaderUrl,
    thumbnailUrl = thumbnailUrl,
    positionMs = positionMs,
    durationMs = durationMs,
    watched = watched,
    lastWatchedAtMillis = lastWatchedAtMillis,
    updatedAtMillis = updatedAtMillis,
    short = isShort,
)

internal fun BackupWatchEntry.toEntity(profileId: Long): WatchEntryEntity = WatchEntryEntity(
    profileId = profileId,
    videoId = videoId,
    title = title,
    uploaderName = uploaderName,
    uploaderUrl = uploaderUrl,
    thumbnailUrl = thumbnailUrl,
    positionMs = positionMs,
    durationMs = durationMs,
    watched = watched,
    lastWatchedAtMillis = lastWatchedAtMillis,
    updatedAtMillis = updatedAtMillis,
    isShort = short,
)

private fun PlaylistEntity.toBackup(items: List<PlaylistItemEntity>): BackupPlaylist = BackupPlaylist(
    name = name,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    videos = items.map(PlaylistItemEntity::toBackup),
    syncId = syncId,
)

private fun PlaylistItemEntity.toBackup(): BackupPlaylistVideo = BackupPlaylistVideo(
    videoId = videoId,
    title = title,
    uploaderName = uploaderName,
    uploaderUrl = uploaderUrl,
    durationSeconds = durationSeconds,
    uploadDateMillis = uploadDateMillis,
    thumbnailUrl = thumbnailUrl,
    addedAtMillis = addedAtMillis,
    updatedAtMillis = updatedAtMillis,
)

internal fun BackupPlaylistVideo.toEntity(playlistId: Long, position: Long): PlaylistItemEntity = PlaylistItemEntity(
    playlistId = playlistId,
    videoId = videoId,
    position = position,
    title = title,
    uploaderName = uploaderName,
    uploaderUrl = uploaderUrl,
    durationSeconds = durationSeconds,
    uploadDateMillis = uploadDateMillis,
    thumbnailUrl = thumbnailUrl,
    addedAtMillis = addedAtMillis,
    updatedAtMillis = updatedAtMillis,
)
