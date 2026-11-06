package com.arshad.ayn.core.store

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Upsert
import com.arshad.ayn.core.access.SegmentCategory
import kotlinx.coroutines.flow.Flow

/**
 * A profile's settings (M7-D9); goes with its profile. Plain columns, no enum converter: a value
 * this build doesn't know reads as the default ([toSettings]) instead of failing the read.
 * `sb_<key>` holds the [SegmentAction] key of [SegmentCategory] `<key>`.
 */
@Entity(
    tableName = "profile_settings",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ProfileSettingsEntity(
    @PrimaryKey val profileId: Long,
    val speedPercent: Int,
    val resume: Boolean,
    val captionsLanguage: String?,
    val sponsorBlock: Boolean,
    @ColumnInfo(name = "sb_sponsor") val sbSponsor: String,
    @ColumnInfo(name = "sb_selfpromo") val sbSelfPromo: String,
    @ColumnInfo(name = "sb_interaction") val sbInteraction: String,
    @ColumnInfo(name = "sb_intro") val sbIntro: String,
    @ColumnInfo(name = "sb_outro") val sbOutro: String,
    @ColumnInfo(name = "sb_preview") val sbPreview: String,
    @ColumnInfo(name = "sb_hook") val sbHook: String,
    @ColumnInfo(name = "sb_music_offtopic") val sbMusicOffTopic: String,
    @ColumnInfo(name = "sb_filler") val sbFiller: String,
    val skipNote: Boolean,
    val dearrowTitles: Boolean,
    val dearrowThumbnails: Boolean,
    val dislikes: Boolean,
    val showShorts: Boolean,
    /** Added in v7 (FY-D10); rows from before it read as on. */
    @ColumnInfo(defaultValue = "1") val forYou: Boolean,
    /** Written on every change; the D4 LWW key (ARCH-7). */
    val updatedAtMillis: Long,
    /** The profile's own filters, added in v8 (KP-D18): a [RestrictedMode] key, NULL (rows from
     *  before it) = off; the rest read as off. */
    val restrictedMode: String?,
    @ColumnInfo(defaultValue = "0") val hideLive: Boolean,
    @ColumnInfo(defaultValue = "0") val hideMembersOnly: Boolean,
    val minLengthMinutes: Int?,
    val maxLengthMinutes: Int?,
    /** The profile's own filter packs, added in v11 (FPK-D16). */
    @Embedded(prefix = PACK_COLUMNS_PREFIX) val filterPacks: FilterPacksColumns,
    /** A [ChatPlacement] key, added in v13 (LIVE-D21); rows from before it read as Beside. */
    @ColumnInfo(defaultValue = "'beside'") val chatPlacement: String,
)

@Dao
interface ProfileSettingsDao {
    @Query("SELECT * FROM profile_settings WHERE profileId = :profileId")
    fun settings(profileId: Long): Flow<ProfileSettingsEntity?>

    @Query("SELECT * FROM profile_settings WHERE profileId = :profileId")
    suspend fun row(profileId: Long): ProfileSettingsEntity?

    /** Every row, for a backup. */
    @Query("SELECT * FROM profile_settings")
    suspend fun all(): List<ProfileSettingsEntity>

    @Upsert
    suspend fun upsert(row: ProfileSettingsEntity)

    /** A sync's apply (M8-D16): settings the merge dropped (their profile's removal covers them). */
    @Query("DELETE FROM profile_settings WHERE profileId = :profileId")
    suspend fun delete(profileId: Long)
}

internal fun ProfileSettingsEntity.toSettings(): ProfileSettings = ProfileSettings(
    speedPercent = speedOrDefault(speedPercent),
    resume = resume,
    captionsLanguage = captionsLanguageOrOff(captionsLanguage),
    sponsorBlock = sponsorBlock,
    segmentActions = SegmentCategory.entries.associateWith { actionOrDefault(it, actionKey(it)) },
    skipNote = skipNote,
    dearrowTitles = dearrowTitles,
    dearrowThumbnails = dearrowThumbnails,
    dislikes = dislikes,
    showShorts = showShorts,
    forYou = forYou,
    restrictedMode = RestrictedMode.of(restrictedMode),
    hideLive = hideLive,
    hideMembersOnly = hideMembersOnly,
    lengthLimits = LengthLimits.orNone(minLengthMinutes, maxLengthMinutes),
    filterPacks = filterPacks.toPacks(),
    chatPlacement = ChatPlacement.of(chatPlacement) ?: ChatPlacement.Beside,
)

internal fun ProfileSettings.toEntity(profileId: Long, updatedAtMillis: Long): ProfileSettingsEntity {
    fun action(category: SegmentCategory): String = segmentActions.getValue(category).key
    return ProfileSettingsEntity(
        profileId = profileId,
        speedPercent = speedPercent,
        resume = resume,
        captionsLanguage = captionsLanguage,
        sponsorBlock = sponsorBlock,
        sbSponsor = action(SegmentCategory.Sponsor),
        sbSelfPromo = action(SegmentCategory.SelfPromo),
        sbInteraction = action(SegmentCategory.Interaction),
        sbIntro = action(SegmentCategory.Intro),
        sbOutro = action(SegmentCategory.Outro),
        sbPreview = action(SegmentCategory.Preview),
        sbHook = action(SegmentCategory.Hook),
        sbMusicOffTopic = action(SegmentCategory.MusicOffTopic),
        sbFiller = action(SegmentCategory.Filler),
        skipNote = skipNote,
        dearrowTitles = dearrowTitles,
        dearrowThumbnails = dearrowThumbnails,
        dislikes = dislikes,
        showShorts = showShorts,
        forYou = forYou,
        updatedAtMillis = updatedAtMillis,
        restrictedMode = restrictedMode.key,
        hideLive = hideLive,
        hideMembersOnly = hideMembersOnly,
        minLengthMinutes = lengthLimits.minMinutes,
        maxLengthMinutes = lengthLimits.maxMinutes,
        filterPacks = filterPacks.toColumns(),
        chatPlacement = chatPlacement.key,
    )
}

private fun ProfileSettingsEntity.actionKey(category: SegmentCategory): String = when (category) {
    SegmentCategory.Sponsor -> sbSponsor
    SegmentCategory.SelfPromo -> sbSelfPromo
    SegmentCategory.Interaction -> sbInteraction
    SegmentCategory.Intro -> sbIntro
    SegmentCategory.Outro -> sbOutro
    SegmentCategory.Preview -> sbPreview
    SegmentCategory.Hook -> sbHook
    SegmentCategory.MusicOffTopic -> sbMusicOffTopic
    SegmentCategory.Filler -> sbFiller
}
