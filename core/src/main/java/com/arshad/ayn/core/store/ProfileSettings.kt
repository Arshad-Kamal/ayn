package com.arshad.ayn.core.store

import com.arshad.ayn.core.access.SegmentCategory
import kotlinx.coroutines.flow.Flow

/**
 * Per-profile settings seam (M7-D9, `06-tv-ui-rebuild.md` B-D9): one row per profile, carried in
 * backups. A profile with no row has the defaults, so a new profile needs no write.
 */
interface ProfileSettingsStore {
    /** The session profile's settings, re-emitted on a change or a profile switch. */
    fun settings(): Flow<ProfileSettingsState>

    /**
     * Applies [transform] to [profileId]'s settings (the defaults when it has none) in one write
     * transaction. Pass the id of the state the screen shows, never "whoever is selected now"
     * (M5-D20). `false` when the write failed, e.g. the profile was removed by a restore; the
     * flow then still holds the stored value.
     */
    suspend fun update(profileId: Long, transform: (ProfileSettings) -> ProfileSettings): Boolean
}

data class ProfileSettingsState(
    val profileId: Long,
    val settings: ProfileSettings,
    /** A kids profile: its self toggles are hidden and DeArrow is off whatever [settings] say
     *  (KP-D11, D12). */
    val kids: Boolean = false,
)

/** A profile's settings; every default is the approved mockup's (`b3-settings`). */
data class ProfileSettings(
    /** One of [SPEED_PERCENTS]. */
    val speedPercent: Int = DEFAULT_SPEED_PERCENT,
    val resume: Boolean = true,
    /** A language tag (BCP-47 shape, at most 35 characters), or `null` for captions Off. */
    val captionsLanguage: String? = null,
    /** Where a live stream's chat shows when opened (LIVE-D21, D22); the player changes it for one stream. */
    val chatPlacement: ChatPlacement = ChatPlacement.Beside,
    val sponsorBlock: Boolean = true,
    /** Every [SegmentCategory]'s action. */
    val segmentActions: Map<SegmentCategory, SegmentAction> = DEFAULT_SEGMENT_ACTIONS,
    /** The note shown after an automatic skip. */
    val skipNote: Boolean = true,
    val dearrowTitles: Boolean = true,
    val dearrowThumbnails: Boolean = true,
    val dislikes: Boolean = true,
    val showShorts: Boolean = false,
    /** For you's hero and row, and its requests to YouTube (FY-D10). */
    val forYou: Boolean = true,
    /** The profile's own filters (preferences, KP-D12): it can turn them off again. The owner's
     *  restrictions ([ProfileRestrictions]) add to them. */
    val restrictedMode: RestrictedMode = RestrictedMode.Off,
    val hideLive: Boolean = false,
    val hideMembersOnly: Boolean = false,
    val lengthLimits: LengthLimits = LengthLimits(),
    val filterPacks: FilterPacks = FilterPacks(),
) {
    init {
        require(speedPercent in SPEED_PERCENTS) { "speed $speedPercent% is not offered" }
        require(captionsLanguageOrOff(captionsLanguage) == captionsLanguage) { "captions language is not a language tag" }
        require(segmentActions.keys == SegmentCategory.entries.toSet()) { "every segment category needs an action" }
    }

    /** The categories whose segments the player needs: every one not Off, none with SponsorBlock off. */
    fun segmentCategoriesToFetch(): Set<SegmentCategory> =
        if (sponsorBlock) segmentActions.filterValues { it != SegmentAction.Off }.keys else emptySet()
}

/** What the player does with a category's segments; [key] is the stored and backed-up value. */
enum class SegmentAction(val key: String) {
    /** Skip automatically (with the skip note when [ProfileSettings.skipNote] is on). */
    Skip("skip"),

    /** Show a skip button. */
    Button("button"),

    /** Mark the seek bar only. */
    Bar("bar"),
    Off("off"),
}

/** Where a live stream's chat shows (LIVE-D21): beside the picture, or over it with no backdrop;
 *  [key] is the stored and backed-up value. */
enum class ChatPlacement(val key: String) {
    /** The panel beside the picture, which moves aside (LIVE-D15); the one chat that scrolls on TV. */
    Beside("beside"),

    /** Over the picture, along its right side. */
    Side("side"),
    TopLeft("top-left"),
    TopRight("top-right"),
    BottomLeft("bottom-left"),
    BottomRight("bottom-right"),
    ;

    companion object {
        /** The placement stored as [key], or `null` when this build doesn't know it. */
        fun of(key: String): ChatPlacement? = entries.firstOrNull { it.key == key }
    }
}

/** The player panel's speeds, in percent. */
val SPEED_PERCENTS: List<Int> = listOf(50, 75, 100, 125, 150, 175, 200)

const val DEFAULT_SPEED_PERCENT = 100

/** Each category's action until the profile changes it; exhaustive, so a new category can't
 *  compile without one. */
private fun defaultAction(category: SegmentCategory): SegmentAction = when (category) {
    SegmentCategory.Sponsor -> SegmentAction.Button
    SegmentCategory.SelfPromo -> SegmentAction.Skip
    SegmentCategory.Interaction -> SegmentAction.Skip
    SegmentCategory.Intro -> SegmentAction.Button
    SegmentCategory.Outro -> SegmentAction.Button
    SegmentCategory.Preview -> SegmentAction.Button
    SegmentCategory.Hook -> SegmentAction.Bar
    SegmentCategory.MusicOffTopic -> SegmentAction.Button
    SegmentCategory.Filler -> SegmentAction.Off
}

private val DEFAULT_SEGMENT_ACTIONS: Map<SegmentCategory, SegmentAction> = SegmentCategory.entries.associateWith(::defaultAction)

// Stored and restored values are read leniently, one field at a time: a value this build doesn't
// know (written by a later build, or a damaged one) reads as that field's default.

/** [stored] when it is one of [SPEED_PERCENTS], else the default. */
internal fun speedOrDefault(stored: Int): Int = if (stored in SPEED_PERCENTS) stored else DEFAULT_SPEED_PERCENT

/** The action stored as [key], else [category]'s default. */
internal fun actionOrDefault(category: SegmentCategory, key: String?): SegmentAction =
    SegmentAction.entries.firstOrNull { it.key == key } ?: defaultAction(category)

/** [stored] when it has a language tag's shape, else `null` (Off). */
internal fun captionsLanguageOrOff(stored: String?): String? =
    stored?.takeIf { it.length <= MAX_LANGUAGE_TAG && LANGUAGE_TAG.matches(it) }

private const val MAX_LANGUAGE_TAG = 35
private val LANGUAGE_TAG = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8})*")
