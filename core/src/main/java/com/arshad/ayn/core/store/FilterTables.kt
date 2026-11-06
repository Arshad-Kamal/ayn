package com.arshad.ayn.core.store

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Upsert
import com.arshad.ayn.core.access.boundaryJson
import com.arshad.ayn.core.access.decodeOrNull
import com.arshad.ayn.core.filter.packs.IslamicOnly
import java.time.DayOfWeek
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * The owner's restrictions on a profile (KP-D12, D18); goes with its profile, carried in backups.
 * Plain columns, read leniently ([toRestrictions]). `limit_<day>` is that day's limit in minutes,
 * NULL for none.
 */
@Entity(
    tableName = "profile_restrictions",
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ProfileRestrictionsEntity(
    @PrimaryKey val profileId: Long,
    /** A [RestrictedMode] key. */
    val restrictedMode: String,
    val hideShorts: Boolean,
    val hideLive: Boolean,
    val hideMembersOnly: Boolean,
    val minLengthMinutes: Int?,
    val maxLengthMinutes: Int?,
    @ColumnInfo(name = "limit_mon") val limitMonday: Int?,
    @ColumnInfo(name = "limit_tue") val limitTuesday: Int?,
    @ColumnInfo(name = "limit_wed") val limitWednesday: Int?,
    @ColumnInfo(name = "limit_thu") val limitThursday: Int?,
    @ColumnInfo(name = "limit_fri") val limitFriday: Int?,
    @ColumnInfo(name = "limit_sat") val limitSaturday: Int?,
    @ColumnInfo(name = "limit_sun") val limitSunday: Int?,
    val bedtimeStartMinute: Int?,
    val bedtimeEndMinute: Int?,
    /** Written on every change; the D4 LWW key (ARCH-7). */
    val updatedAtMillis: Long,
    @Embedded(prefix = PACK_COLUMNS_PREFIX) val filterPacks: FilterPacksColumns,
)

/**
 * One setter's filter pack choices ([FilterPacks], FPK-D16) as columns, embedded under
 * [PACK_COLUMNS_PREFIX] in [ProfileSettingsEntity] and [ProfileRestrictionsEntity]. Added in v11;
 * rows from before it read as off. [islamicChannels] is a JSON object of channel id to on, NULL
 * for none.
 */
data class FilterPacksColumns(
    @ColumnInfo(defaultValue = "0") val harmfulForKids: Boolean,
    @ColumnInfo(defaultValue = "0") val overstimulating: Boolean,
    @ColumnInfo(defaultValue = "0") val challengesAndVlogs: Boolean,
    @ColumnInfo(defaultValue = "0") val halalOnly: Boolean,
    @ColumnInfo(defaultValue = "0") val stageMagic: Boolean,
    @ColumnInfo(defaultValue = "0") val festivals: Boolean,
    @ColumnInfo(defaultValue = "0") val lootBoxes: Boolean,
    @ColumnInfo(defaultValue = "0") val islamicOnly: Boolean,
    val islamicChannels: String?,
)

internal const val PACK_COLUMNS_PREFIX = "fp_"

/** Lenient (FPK-D18): channel text that isn't a JSON object of booleans reads as no switches. */
internal fun FilterPacksColumns.toPacks(): FilterPacks = FilterPacks(
    harmfulForKids = harmfulForKids,
    overstimulating = overstimulating,
    challengesAndVlogs = challengesAndVlogs,
    halalOnly = halalOnly,
    stageMagic = stageMagic,
    festivals = festivals,
    lootBoxes = lootBoxes,
    islamicOnly = islamicOnly,
    islamicChannels = islamicChannels?.let { decodeOrNull(it, ISLAMIC_SWITCHES) }?.let(::listedSwitches).orEmpty(),
)

internal fun FilterPacks.toColumns(): FilterPacksColumns = FilterPacksColumns(
    harmfulForKids = harmfulForKids,
    overstimulating = overstimulating,
    challengesAndVlogs = challengesAndVlogs,
    halalOnly = halalOnly,
    stageMagic = stageMagic,
    festivals = festivals,
    lootBoxes = lootBoxes,
    islamicOnly = islamicOnly,
    islamicChannels = islamicChannels.takeIf { it.isNotEmpty() }?.let { boundaryJson.encodeToString(ISLAMIC_SWITCHES, it) },
)

/** The switches of channels [IslamicOnly] lists; a later list may drop one, and an export then
 *  carries no dead ids. */
internal fun listedSwitches(switches: Map<String, Boolean>): Map<String, Boolean> =
    switches.filterKeys { IslamicOnly.channel(it) != null }

private val ISLAMIC_SWITCHES = MapSerializer(String.serializer(), Boolean.serializer())

/**
 * One list rule (KP-D18), set by the profile itself or, when [byOwner], by the owner. [kind] is a
 * [RuleKind] key; [value] the rule's [ContentRule.key]. The display columns are filled for the
 * kinds that show a video or channel. Goes with its profile, carried in backups.
 */
@Entity(
    tableName = "content_rules",
    primaryKeys = ["profileId", "byOwner", "kind", "value"],
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ContentRuleEntity(
    val profileId: Long,
    val byOwner: Boolean,
    val kind: String,
    val value: String,
    val title: String?,
    val channelName: String?,
    val channelUrl: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val addedAtMillis: Long,
)

@Dao
interface FilterDao {
    @Query("SELECT * FROM profile_restrictions WHERE profileId = :profileId")
    fun restrictions(profileId: Long): Flow<ProfileRestrictionsEntity?>

    @Query("SELECT * FROM profile_restrictions WHERE profileId = :profileId")
    suspend fun restrictionsRow(profileId: Long): ProfileRestrictionsEntity?

    @Upsert
    suspend fun upsertRestrictions(row: ProfileRestrictionsEntity)

    /** Oldest first. */
    @Query("SELECT * FROM content_rules WHERE profileId = :profileId ORDER BY addedAtMillis, kind, value")
    fun rules(profileId: Long): Flow<List<ContentRuleEntity>>

    /** Whether [profileId] is a kids profile; `null` for an unknown id. */
    @Query("SELECT kids FROM profiles WHERE id = :profileId")
    fun kids(profileId: Long): Flow<Boolean?>

    /** [profileId]'s subscribed channels (canonical URLs): a kids profile's approved channels (KP-D10). */
    @Query("SELECT channelUrl FROM subscriptions WHERE profileId = :profileId")
    fun subscribedChannels(profileId: Long): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM content_rules WHERE profileId = :profileId AND byOwner = :byOwner AND kind = :kind")
    suspend fun count(profileId: Long, byOwner: Boolean, kind: String): Int

    /** A rule the setter already has keeps its first time ([NOT_INSERTED]). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRule(row: ContentRuleEntity): Long

    /** Record the removal first ([RemovalDao.rule], M8-D6). */
    @Query("DELETE FROM content_rules WHERE profileId = :profileId AND byOwner = :byOwner AND kind = :kind AND value = :value")
    suspend fun deleteRule(profileId: Long, byOwner: Boolean, kind: String, value: String): Int

    /** Every profile's restrictions, for a backup. */
    @Query("SELECT * FROM profile_restrictions")
    suspend fun allRestrictions(): List<ProfileRestrictionsEntity>

    /** Every profile's rules, for a backup: by profile, then oldest first (a sync merge's order). */
    @Query("SELECT * FROM content_rules ORDER BY profileId, addedAtMillis, kind, value, byOwner")
    suspend fun allRules(): List<ContentRuleEntity>

    @Insert
    suspend fun insertRules(rows: List<ContentRuleEntity>)

    /** A sync's apply (M8-D16): the merge's copies, as they are. */
    @Upsert
    suspend fun upsertRules(rows: List<ContentRuleEntity>)

    /** A sync's apply: rules the merge dropped, whose removal records it carries. */
    @Delete
    suspend fun deleteRules(rows: List<ContentRuleEntity>)

    /** A sync's apply: restrictions the merge dropped (their profile's removal covers them). */
    @Query("DELETE FROM profile_restrictions WHERE profileId = :profileId")
    suspend fun deleteRestrictions(profileId: Long)
}

/** The stored [ContentRuleEntity.kind] of each rule type. */
internal object RuleKind {
    const val CHANNEL = "channel"
    const val VIDEO = "video"
    const val KEYWORD = "keyword"
    const val PATTERN = "pattern"
    const val APPROVED_VIDEO = "approved_video"
}

internal val ContentRule.kind: String
    get() = when (this) {
        is BlockedChannel -> RuleKind.CHANNEL
        is BlockedVideo -> RuleKind.VIDEO
        is Keyword -> RuleKind.KEYWORD
        is Pattern -> RuleKind.PATTERN
        is ApprovedVideo -> RuleKind.APPROVED_VIDEO
    }

internal fun ContentRule.toEntity(profileId: Long, byOwner: Boolean, addedAtMillis: Long): ContentRuleEntity {
    fun row(
        title: String? = null,
        channelName: String? = null,
        channelUrl: String? = null,
        thumbnailUrl: String? = null,
        durationSeconds: Long? = null,
    ): ContentRuleEntity =
        ContentRuleEntity(profileId, byOwner, kind, key, title, channelName, channelUrl, thumbnailUrl, durationSeconds, addedAtMillis)
    return when (this) {
        is BlockedChannel -> row(channelName = name)
        is BlockedVideo -> row(title = title, channelUrl = channelUrl)
        is Keyword, is Pattern -> row()
        is ApprovedVideo -> row(title, channelName, channelUrl, thumbnailUrl, durationSeconds)
    }
}

/** The rule this row holds, or `null` for a kind this build doesn't know (a later build's). */
internal fun ContentRuleEntity.toRule(): ContentRule? = when (kind) {
    RuleKind.CHANNEL -> BlockedChannel(value, channelName)
    RuleKind.VIDEO -> BlockedVideo(value, title, channelUrl)
    RuleKind.KEYWORD -> Keyword(value)
    RuleKind.PATTERN -> Pattern(value)
    RuleKind.APPROVED_VIDEO -> ApprovedVideo(value, title.orEmpty(), channelName, channelUrl, thumbnailUrl, durationSeconds)
    else -> null
}

/** Lenient, one field at a time (KP-D18): an unknown level reads Strict; a length, limit or
 *  bedtime out of range reads as unset; filter packs as [FilterPacksColumns.toPacks] reads them. */
internal fun ProfileRestrictionsEntity.toRestrictions(): ProfileRestrictions = ProfileRestrictions(
    restrictedMode = RestrictedMode.of(restrictedMode),
    hideShorts = hideShorts,
    hideLive = hideLive,
    hideMembersOnly = hideMembersOnly,
    lengthLimits = LengthLimits.orNone(minLengthMinutes, maxLengthMinutes),
    dailyLimitMinutes = dailyLimitsOrUnset(
        mapOf(
            DayOfWeek.MONDAY to limitMonday,
            DayOfWeek.TUESDAY to limitTuesday,
            DayOfWeek.WEDNESDAY to limitWednesday,
            DayOfWeek.THURSDAY to limitThursday,
            DayOfWeek.FRIDAY to limitFriday,
            DayOfWeek.SATURDAY to limitSaturday,
            DayOfWeek.SUNDAY to limitSunday,
        ),
    ),
    bedtime = Bedtime.orNull(bedtimeStartMinute, bedtimeEndMinute),
    filterPacks = filterPacks.toPacks(),
)

internal fun ProfileRestrictions.toEntity(profileId: Long, updatedAtMillis: Long): ProfileRestrictionsEntity =
    ProfileRestrictionsEntity(
        profileId = profileId,
        restrictedMode = restrictedMode.key,
        hideShorts = hideShorts,
        hideLive = hideLive,
        hideMembersOnly = hideMembersOnly,
        minLengthMinutes = lengthLimits.minMinutes,
        maxLengthMinutes = lengthLimits.maxMinutes,
        limitMonday = dailyLimitMinutes[DayOfWeek.MONDAY],
        limitTuesday = dailyLimitMinutes[DayOfWeek.TUESDAY],
        limitWednesday = dailyLimitMinutes[DayOfWeek.WEDNESDAY],
        limitThursday = dailyLimitMinutes[DayOfWeek.THURSDAY],
        limitFriday = dailyLimitMinutes[DayOfWeek.FRIDAY],
        limitSaturday = dailyLimitMinutes[DayOfWeek.SATURDAY],
        limitSunday = dailyLimitMinutes[DayOfWeek.SUNDAY],
        bedtimeStartMinute = bedtime?.startMinute,
        bedtimeEndMinute = bedtime?.endMinute,
        updatedAtMillis = updatedAtMillis,
        filterPacks = filterPacks.toColumns(),
    )

/** The days whose stored limit is in range; the rest have none. */
internal fun dailyLimitsOrUnset(stored: Map<DayOfWeek, Int?>): Map<DayOfWeek, Int> =
    stored.mapNotNull { (day, minutes) -> minutes?.takeIf { it in 0..MINUTES_PER_DAY }?.let { day to it } }.toMap()
