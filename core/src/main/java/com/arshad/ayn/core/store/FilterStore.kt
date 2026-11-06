package com.arshad.ayn.core.store

import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.lock.OwnerPass
import java.time.DayOfWeek
import kotlinx.coroutines.flow.Flow

/**
 * Content filters seam (KP-D1, D12, D18): a profile's own rules (preferences: it can remove them)
 * and the owner's restrictions on it (written only with an [OwnerPass]). A video either one hides
 * stays hidden. The filters on [ProfileSettings] are the profile's own toggles; this store holds
 * the owner's toggles and time rules ([ProfileRestrictions]) and both setters' list rules, and is
 * where the owner curates a kids profile (KP-D10): approved videos and blocks are owner rules,
 * approved channels are the kid's subscriptions, which only the owner writes.
 */
interface FilterStore {
    /** [profileId]'s restrictions and rules, re-emitted when they change; an unknown id has none. */
    fun rules(profileId: Long): Flow<ProfileRules>

    /** Adds a rule the profile set for itself. */
    suspend fun addOwn(profileId: Long, rule: SelfRule): RuleEdit

    /** Removes a rule the profile set for itself; `false` when it had none or the write failed. */
    suspend fun removeOwn(profileId: Long, rule: SelfRule): Boolean

    /** Adds an owner's rule to [profileId]: any kind, patterns and approvals included (KP-D7, D10). */
    suspend fun addRestriction(owner: OwnerPass, profileId: Long, rule: ContentRule): RuleEdit

    /** Removes an owner's rule from [profileId]; `false` when it had none or the write failed. */
    suspend fun removeRestriction(owner: OwnerPass, profileId: Long, rule: ContentRule): Boolean

    /** Applies [transform] to [profileId]'s restrictions (none when it has no row) in one write
     *  transaction; `false` when the write failed, e.g. the profile was removed. */
    suspend fun updateRestrictions(
        owner: OwnerPass,
        profileId: Long,
        transform: (ProfileRestrictions) -> ProfileRestrictions,
    ): Boolean

    /** Kids profile [profileId]'s approved channels, most recently approved first; empty for any
     *  other profile. */
    fun approvedChannels(profileId: Long): Flow<List<Subscription>>

    /** Approves [channel] for kids profile [profileId]: all its videos, new ones too (it joins the
     *  kid's subscriptions). Approving it again refreshes its name and picture. `false` when
     *  [profileId] is not a kids profile or the write failed. */
    suspend fun approveChannel(owner: OwnerPass, profileId: Long, channel: ChannelItem): Boolean

    /** Takes [channelUrl] (any URL form) off kids profile [profileId]'s approved channels; `false`
     *  when it wasn't approved there or the write failed. */
    suspend fun removeApprovedChannel(owner: OwnerPass, profileId: Long, channelUrl: String): Boolean
}

data class ProfileRules(
    val restrictions: ProfileRestrictions = ProfileRestrictions(),
    /** Both setters' rules, oldest first. */
    val rules: List<StoredRule> = emptyList(),
    /** A kids profile shows only what the owner approved (KP-D3). */
    val kids: Boolean = false,
    /** A kids profile's subscriptions (canonical URLs): its approved channels; empty otherwise. */
    val approvedChannels: Set<String> = emptySet(),
) {
    /** The videos the owner approved one by one, newest first. */
    val approvedVideos: List<ApprovedVideo>
        get() = rules.asReversed().mapNotNull { it.rule as? ApprovedVideo }
}

data class StoredRule(val rule: ContentRule, val byOwner: Boolean, val addedAtMillis: Long)

/**
 * The owner's restrictions on a profile (KP-D12): the profile's filter toggles plus Shorts, and
 * a kids profile's time rules (KP-D8). Every default restricts nothing.
 */
data class ProfileRestrictions(
    val restrictedMode: RestrictedMode = RestrictedMode.Off,
    val hideShorts: Boolean = false,
    val hideLive: Boolean = false,
    val hideMembersOnly: Boolean = false,
    val lengthLimits: LengthLimits = LengthLimits(),
    /** Minutes the profile may watch on each day; a day missing from the map has no limit. */
    val dailyLimitMinutes: Map<DayOfWeek, Int> = emptyMap(),
    val bedtime: Bedtime? = null,
    val filterPacks: FilterPacks = FilterPacks(),
) {
    init {
        require(dailyLimitMinutes.values.all { it in 0..MINUTES_PER_DAY }) { "a daily limit is out of range" }
    }
}

/** YouTube's Restricted Mode level sent with a profile's requests (KP-D2, D6); [key] is stored.
 *  Declared least to most restrictive: the stricter of two levels is their [maxOf]. */
enum class RestrictedMode(val key: String) {
    Off("off"),
    Strict("strict"),
    ;

    companion object {
        /** The level stored as [key]: `null` is Off, a level this build doesn't know is Strict
         *  (fails closed, KP-D18). */
        fun of(key: String?): RestrictedMode = if (key == null || key == Off.key) Off else Strict
    }
}

/** Hides videos shorter than [minMinutes] or longer than [maxMinutes]; `null` = no bound. */
data class LengthLimits(val minMinutes: Int? = null, val maxMinutes: Int? = null) {
    init {
        require(minMinutes == null || minMinutes in 1..MINUTES_PER_DAY) { "minimum length out of range" }
        require(maxMinutes == null || maxMinutes in 1..MINUTES_PER_DAY) { "maximum length out of range" }
        require(minMinutes == null || maxMinutes == null || minMinutes < maxMinutes) { "minimum length not below maximum" }
    }

    companion object {
        /** The limits stored as [min] and [max], or none when they don't make valid limits. */
        fun orNone(min: Int?, max: Int?): LengthLimits =
            runCatching { LengthLimits(min, max) }.getOrDefault(LengthLimits())
    }
}

/** No watching from [startMinute] to [endMinute] (minutes after local midnight; the window may
 *  cross midnight, KP-D8). */
data class Bedtime(val startMinute: Int, val endMinute: Int) {
    init {
        require(startMinute in 0..<MINUTES_PER_DAY && endMinute in 0..<MINUTES_PER_DAY) { "bedtime out of range" }
        require(startMinute != endMinute) { "bedtime is empty" }
    }

    companion object {
        /** The bedtime stored as [start] and [end], or `null` when either is missing or they
         *  don't make a valid window. */
        fun orNull(start: Int?, end: Int?): Bedtime? =
            if (start == null || end == null) null else runCatching { Bedtime(start, end) }.getOrNull()
    }
}

/** One list rule; [key] identifies it within its kind (a canonical channel URL, a video id, a
 *  word, a pattern). */
sealed interface ContentRule {
    val key: String
}

/** The kinds a profile may set for itself; patterns and approvals are the owner's (KP-D7, D10). */
sealed interface SelfRule : ContentRule

data class BlockedChannel(val channelUrl: String, val name: String?) : SelfRule {
    override val key: String get() = channelUrl
}

data class BlockedVideo(val videoId: String, val title: String?, val channelUrl: String?) : SelfRule {
    override val key: String get() = videoId
}

/** A whole word matched in titles; stored as entered (trimmed), folded for matching by the
 *  filter engine (F5: NFKC, case, quotes). */
data class Keyword(val word: String) : SelfRule {
    override val key: String get() = word
}

/** A regular expression over titles (KP-D7); checked when saved by the filter engine. */
data class Pattern(val pattern: String) : ContentRule {
    override val key: String get() = pattern
}

/** A video a kids profile may watch without its channel being approved (KP-D10). */
data class ApprovedVideo(
    val videoId: String,
    val title: String,
    val channelName: String?,
    val channelUrl: String?,
    val thumbnailUrl: String?,
    /** `null` when YouTube didn't give a length. */
    val durationSeconds: Long?,
) : ContentRule {
    override val key: String get() = videoId

    /** The card a kids profile's Home and Search show for it; what an approval doesn't keep
     *  (views, upload date) is unknown. `null` for an id no video can have. */
    fun toVideoItem(): VideoItem? = if (!VideoId.isValid(videoId)) null else VideoItem(
        id = VideoId(videoId),
        title = title,
        uploaderName = channelName,
        uploaderUrl = channelUrl,
        durationSeconds = durationSeconds ?: UNKNOWN,
        viewCount = UNKNOWN,
        textualUploadDate = null,
        uploadDate = null,
        thumbnailUrl = thumbnailUrl,
        isLive = false,
    )

    private companion object {
        /** The extractor's -1-means-unknown ([VideoItem]). */
        const val UNKNOWN = -1L
    }
}

/** How adding a rule went. */
sealed interface RuleEdit {
    data object Added : RuleEdit

    /** The same setter already has this rule; nothing changed. */
    data object AlreadyThere : RuleEdit

    /** Blank, too long, or not a channel or video id the app can use. */
    data object Invalid : RuleEdit

    /** The setter already has [MAX_RULES_PER_KIND] rules of this kind ([MAX_PATTERNS] patterns). */
    data object Full : RuleEdit

    data object Failed : RuleEdit
}

const val MINUTES_PER_DAY = 24 * 60
const val MAX_KEYWORD_LENGTH = 50
const val MAX_PATTERN_LENGTH = 200
const val MAX_PATTERNS = 50
const val MAX_RULES_PER_KIND = 500
