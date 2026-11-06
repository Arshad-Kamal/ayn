package com.arshad.ayn.core.filter

import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.ListingItem
import com.arshad.ayn.core.access.PlaylistItem
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.VideoMetadata
import com.arshad.ayn.core.filter.packs.IslamicChannel
import com.arshad.ayn.core.store.ApprovedVideo
import com.arshad.ayn.core.store.BlockedChannel
import com.arshad.ayn.core.store.BlockedVideo
import com.arshad.ayn.core.store.Keyword
import com.arshad.ayn.core.store.Pattern
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.RestrictedMode
import com.arshad.ayn.core.store.canonicalChannelUrl

/**
 * One profile's content filter (KP-D1, D12, D20): its own toggles OR the owner's restrictions,
 * and every list rule either one set. A kids profile's is the locked preset (KP-D1, D3, D11): an
 * allow-list of its approved channels and videos on top, Shorts hidden, Restricted Mode Strict.
 * Either setter's filter packs ([PackFilter], FPK-D5) run after the profile's own rules; a video
 * approved one by one passes them (FPK-D14). Built once per rules snapshot; every check is pure.
 * Two filters are equal when they filter alike, so a cache of filtered answers can key on one
 * (KP-D15).
 */
class ContentFilter private constructor(private val spec: Spec) {
    private data class Spec(
        /** Canonical channel URLs ([canonicalChannelUrl]). */
        val blockedChannels: Set<String>,
        /** Video ids as stored. */
        val blockedVideos: Set<String>,
        val keywords: Set<String>,
        val patterns: Set<String>,
        val hideLive: Boolean,
        val hideMembersOnly: Boolean,
        val minSeconds: Long?,
        val maxSeconds: Long?,
        val hidesShorts: Boolean,
        val restrictedMode: RestrictedMode,
        /** A kids profile's approvals; `null` for a profile without an allow-list. */
        val allowList: AllowList?,
        /** `null` when no pack is on. */
        val packs: PackFilter?,
        val refusesAll: Boolean,
    )

    private data class AllowList(
        /** Canonical channel URLs: the approved channels and Islamic content only's (FPK-D13). */
        val channels: Set<String>,
        val videos: Set<String>,
    )

    private val titles: TitleMatcher? = TitleMatcher(spec.keywords, spec.patterns).takeUnless { it.isEmpty }

    /** Neither a kids profile's allow-list nor Islamic content only's: what names no channel passes. */
    private val blockListOnly: Boolean = spec.allowList == null && spec.packs?.isAllowList != true

    /** Shorts are hidden whatever the profile's Show Shorts says (the owner's Hide Shorts, or a
     *  kids profile). */
    val hidesShorts: Boolean = spec.hidesShorts

    /** The level the profile's InnerTube requests carry: the stricter of its own and the owner's. */
    val restrictedMode: RestrictedMode = spec.restrictedMode

    /** Remote search suggestions are off under Restricted Mode: their host is outside the header's
     *  (KP-D15); a kids profile's are off through its forced Strict (KP-D6). */
    val allowsSuggestions: Boolean = !spec.refusesAll && restrictedMode == RestrictedMode.Off

    /** Comments are off under Restricted Mode, as on YouTube: the `next` answer still carries them
     *  with the header (KP derisk ledger), so they are hidden here. */
    val allowsComments: Boolean = !spec.refusesAll && restrictedMode == RestrictedMode.Off

    /** YouTube's search and browse lists: off for kids, whose Search is a local match over what
     *  the owner approved (KP-D10). */
    val allowsDiscovery: Boolean = !spec.refusesAll && spec.allowList == null

    /** A kids profile plays on within the channel: its Related row and Up next keep only the
     *  playing video's channel (KP-D11). */
    val keepsRelatedToChannel: Boolean = spec.allowList != null

    /** The channels a kids profile's packs approve beside its own approved ones (Islamic content
     *  only's, FPK-D13), in the pack's list order: its Home and Search list their new videos too.
     *  Empty for any other profile. */
    val packApprovedChannels: List<IslamicChannel> = if (spec.allowList == null) emptyList() else spec.packs?.keptIslamicChannels.orEmpty()

    override fun equals(other: Any?): Boolean = other is ContentFilter && other.spec == spec

    override fun hashCode(): Int = spec.hashCode()

    fun allows(video: VideoItem): Boolean = allows(
        videoId = video.id,
        title = video.title,
        channelUrl = video.uploaderUrl,
        channelName = video.uploaderName,
        durationSeconds = video.durationSeconds,
        live = video.isLive,
        membersOnly = video.membersOnly,
    )

    /** A video page (the resolve gate): members-only pages already fail to play, so no badge. A
     *  live page hidden only by Restricted Mode passes on to its own refusal (kids, LIVE-D13; or
     *  YouTube's, PAF-D3), which says why. */
    fun allows(video: VideoMetadata): Boolean = allows(
        videoId = video.id,
        title = video.title,
        channelUrl = video.uploaderUrl,
        channelName = video.uploaderName,
        durationSeconds = video.durationSeconds,
        live = video.isLive,
        listing = false,
    )

    /**
     * Whether a video passes; what isn't known passes what needs it: no title skips the title
     * rules, no channel the channel blocks, and a length of 0 or less (unknown) the length bounds
     * (a block-list keeps what it can't judge, KP-D15). A live stream has no length. An allow-list
     * keeps only an approved video or one from an approved channel, so a video with no channel
     * passes it only by its id (KP-D15); a block still wins over an approval. [channelName] is
     * read by the packs alone (FPK-D11). In a [listing], Restricted Mode hides live streams too:
     * YouTube refuses them under it (PAF-D9).
     */
    fun allows(
        videoId: VideoId,
        title: String?,
        channelUrl: String?,
        durationSeconds: Long?,
        live: Boolean = false,
        membersOnly: Boolean = false,
        channelName: String? = null,
        listing: Boolean = true,
    ): Boolean {
        if (spec.refusesAll || videoId.value in spec.blockedVideos) return false
        // Parsed only when a channel rule reads it: these run over whole history lists.
        val canonical by lazy(LazyThreadSafetyMode.NONE) { channelUrl?.let(::canonicalChannelUrl) }
        return when {
            spec.blockedChannels.isNotEmpty() && canonical?.let { it in spec.blockedChannels } == true -> false
            !approved(videoId) { canonical } -> false
            live && (spec.hideLive || (listing && restrictedMode != RestrictedMode.Off)) -> false
            membersOnly && spec.hideMembersOnly -> false
            !live && durationSeconds != null && durationSeconds > 0 && !withinLength(durationSeconds) -> false
            title != null && titles?.matches(title) == true -> false
            !passesPacks(videoId, title, channelName) { canonical } -> false
            else -> true
        }
    }

    /** Whether [title] passes the keywords, patterns and pack title rules alone: a title that
     *  isn't the video's own (DeArrow's) is checked where it shows, the video having passed by
     *  YouTube's (KP-D20). */
    fun allowsTitle(title: String): Boolean =
        !spec.refusesAll && titles?.matches(title) != true && spec.packs?.hidesTitle(title) != true

    /**
     * Whether the channel's own lists show: `false` for a blocked channel (by a rule or a pack),
     * and under an allow-list (a kids profile's, Islamic content only) for any channel it doesn't
     * keep. A missing URL passes a block-list (a Shorts shelf's items carry none) and fails an
     * allow-list. [channelName] is read by the packs alone (FPK-D11); without one their name rules
     * are skipped.
     */
    fun allowsChannel(channelUrl: String?, channelName: String? = null): Boolean {
        if (spec.refusesAll) return false
        if (channelUrl == null) return blockListOnly
        val allowList = spec.allowList
        val canonical = canonicalChannelUrl(channelUrl)
        return canonical !in spec.blockedChannels &&
            (allowList == null || canonical in allowList.channels) &&
            spec.packs?.hidesChannel(canonical, channelName) != true
    }

    /** A playlist card in a listing: it names no channel URL, so an allow-list hides it (its page,
     *  if reached, still filters each video). */
    val allowsPlaylistCards: Boolean = !spec.refusesAll && blockListOnly

    /** A listing's card: a video by its own rules, a channel card by [allowsChannel], a playlist
     *  card by [allowsPlaylistCards]. */
    fun allows(item: ListingItem): Boolean = when (item) {
        is VideoItem -> allows(item)
        is ChannelItem -> allowsChannel(item.url, item.name)
        is PlaylistItem -> allowsPlaylistCards
    }

    private fun approved(videoId: VideoId, canonicalChannel: () -> String?): Boolean {
        val allowList = spec.allowList ?: return true
        return videoId.value in allowList.videos || canonicalChannel()?.let { it in allowList.channels } == true
    }

    /** The packs, which a video approved one by one passes (FPK-D14). */
    private fun passesPacks(videoId: VideoId, title: String?, channelName: String?, canonicalChannel: () -> String?): Boolean {
        val packs = spec.packs ?: return true
        if (spec.allowList?.videos?.contains(videoId.value) == true) return true
        return !packs.hides(canonicalChannel(), title, channelName)
    }

    private fun withinLength(seconds: Long): Boolean {
        val min = spec.minSeconds
        val max = spec.maxSeconds
        return (min == null || seconds >= min) && (max == null || seconds <= max)
    }

    companion object {
        /** No profile is selected: nothing passes (fail closed, KP-D1). */
        val REFUSE_ALL: ContentFilter = ContentFilter(
            Spec(
                blockedChannels = emptySet(),
                blockedVideos = emptySet(),
                keywords = emptySet(),
                patterns = emptySet(),
                hideLive = false,
                hideMembersOnly = false,
                minSeconds = null,
                maxSeconds = null,
                hidesShorts = true,
                restrictedMode = RestrictedMode.Strict,
                allowList = null,
                packs = null,
                refusesAll = true,
            ),
        )

        /** [settings] are the profile's own toggles and packs, [rules] the owner's restrictions
         *  and both setters' list rules (KP-D12: a video either one hides stays hidden). A kids
         *  profile ([ProfileRules.kids]) adds the allow-list of its approved channels and videos,
         *  hides Shorts and is Strict whatever its rows say (KP-D6, D11). */
        fun of(settings: ProfileSettings, rules: ProfileRules): ContentFilter {
            val restrictions = rules.restrictions
            val listed = rules.rules.map { it.rule }
            val approvedChannels = rules.approvedChannels.mapTo(HashSet(), ::canonicalChannelUrl)
            val packs = PackFilter.of(listOf(settings.filterPacks, restrictions.filterPacks), rules.kids, approvedChannels)
            val mins = listOfNotNull(settings.lengthLimits.minMinutes, restrictions.lengthLimits.minMinutes)
            val maxes = listOfNotNull(settings.lengthLimits.maxMinutes, restrictions.lengthLimits.maxMinutes)
            val restricted = if (rules.kids) RestrictedMode.Strict else maxOf(settings.restrictedMode, restrictions.restrictedMode)
            return ContentFilter(
                Spec(
                    blockedChannels = listed.filterIsInstance<BlockedChannel>().mapTo(HashSet()) { canonicalChannelUrl(it.channelUrl) },
                    blockedVideos = listed.filterIsInstance<BlockedVideo>().mapTo(HashSet()) { it.videoId },
                    keywords = listed.filterIsInstance<Keyword>().mapTo(HashSet()) { it.word },
                    patterns = listed.filterIsInstance<Pattern>().mapTo(HashSet()) { it.pattern },
                    hideLive = settings.hideLive || restrictions.hideLive,
                    hideMembersOnly = settings.hideMembersOnly || restrictions.hideMembersOnly,
                    // The strictest bound wins: the larger minimum, the smaller maximum.
                    minSeconds = mins.maxOrNull()?.let { it * SECONDS_PER_MINUTE },
                    maxSeconds = maxes.minOrNull()?.let { it * SECONDS_PER_MINUTE },
                    hidesShorts = rules.kids || restrictions.hideShorts,
                    restrictedMode = restricted,
                    allowList = if (rules.kids) {
                        AllowList(
                            channels = approvedChannels + packs?.islamicChannelUrls.orEmpty(),
                            videos = listed.filterIsInstance<ApprovedVideo>().mapTo(HashSet()) { it.videoId },
                        )
                    } else {
                        null
                    },
                    packs = packs,
                    refusesAll = false,
                ),
            )
        }

        private const val SECONDS_PER_MINUTE = 60L
    }
}

/** Whether [item] shows under this filter; a `null` filter (not read yet) hides nothing. */
fun ContentFilter?.shows(item: ListingItem): Boolean = this?.allows(item) != false
