package com.arshad.ayn.core.filter

import com.arshad.ayn.core.filter.packs.BlockSet
import com.arshad.ayn.core.filter.packs.HalalOnly
import com.arshad.ayn.core.filter.packs.HarmfulForKids
import com.arshad.ayn.core.filter.packs.IslamicChannel
import com.arshad.ayn.core.filter.packs.IslamicOnly
import com.arshad.ayn.core.filter.packs.PackText
import com.arshad.ayn.core.store.FilterPacks
import com.arshad.ayn.core.store.channelIdOfCanonical

/**
 * The filter packs a profile's two setters turned on (FPK-D2, D5, KP-D12), resolved once per rules
 * snapshot: a video either setter's packs hide stays hidden. Pack rules sit outside the setters'
 * rule limits. Channels are canonical channel URLs ([com.arshad.ayn.core.store.canonicalChannelUrl]),
 * read by their channel id; titles and channel names are read as [PackText], each normalized once
 * per check. Equal when they filter alike: the block sets are the packs' own instances.
 */
internal data class PackFilter private constructor(
    private val harmful: Set<BlockSet>,
    private val halal: Set<BlockSet>,
    /** The Islamic content only channel ids every setter that turned it on keeps (KP-D12); `null`
     *  when no setter did. */
    private val islamicChannels: Set<String>?,
    /** A kids profile's approved channels: Islamic content only keeps them too (FPK-D2). */
    private val approvedChannels: Set<String>,
) {
    /** Islamic content only is on: an allow-list, so what names no channel fails it. */
    val isAllowList: Boolean get() = islamicChannels != null

    /** The Islamic content only channels the profile keeps, in the pack's list order: a kids
     *  profile's allow-list takes them in beside its approved channels (FPK-D13). */
    val keptIslamicChannels: List<IslamicChannel> = islamicChannels?.let { ids -> IslamicOnly.channels.filter { it.id in ids } }.orEmpty()

    /** [keptIslamicChannels]' canonical URLs. */
    val islamicChannelUrls: Set<String> = keptIslamicChannels.mapTo(HashSet()) { it.url }

    /**
     * Whether a video hides. Islamic content only's allow-list is read first (it costs no regex);
     * then FPK-D5's order after the explicit blocks: a pack's exempt channel passes that pack, an
     * exempt title its set, then the pack's block rules. A listed channel's Islamic guard holds
     * even over an approved channel (FPK-D14).
     */
    fun hides(channel: String?, title: String?, channelName: String?): Boolean {
        val channelId = channel?.let(::channelIdOfCanonical)
        if (isAllowList && !keepsUnderIslamic(channel, channelId)) return true
        val titleText = title?.let(PackText::of)
        if (isAllowList && !passesIslamicGuard(channelId, titleText)) return true
        return blocks(channelId, titleText, channelName?.let(PackText::of))
    }

    /** Whether a channel's own lists hide: its id or [channelName] blocked by a pack (no name
     *  skips the name rules), or outside Islamic content only. A guarded channel stays: its
     *  page's videos still pass one by one. */
    fun hidesChannel(channel: String, channelName: String?): Boolean {
        val channelId = channelIdOfCanonical(channel)
        if (isAllowList && !keepsUnderIslamic(channel, channelId)) return true
        return blocks(channelId, null, channelName?.let(PackText::of))
    }

    /** Whether a title alone hides by the packs' title rules; no channel is known, so no channel
     *  exemption applies. */
    fun hidesTitle(title: String): Boolean = blocks(null, PackText.of(title), null)

    private fun blocks(channelId: String?, title: PackText?, channelName: PackText?): Boolean =
        (harmful.isNotEmpty() && !HarmfulForKids.exempts(channelId) && harmful.any { it.hides(channelId, title, channelName) }) ||
            (halal.isNotEmpty() && !HalalOnly.exempts(channelId, title) && halal.any { it.hides(channelId, title, channelName) }) ||
            (isAllowList && IslamicOnly.blocks.hides(channelId, title, channelName))

    private fun keepsUnderIslamic(channel: String?, channelId: String?): Boolean =
        channel in approvedChannels || (channelId != null && channelId in islamicChannels.orEmpty())

    /** A listed channel that posts both versions keeps only its marked titles; any other passes. */
    private fun passesIslamicGuard(channelId: String?, title: PackText?): Boolean =
        channelId?.let(IslamicOnly::channel)?.allows(title) ?: true

    companion object {
        /** The packs [choices] turn on, or `null` when none does. A kids profile's Islamic
         *  channels start at their kids defaults (FPK-D12). */
        fun of(choices: List<FilterPacks>, kids: Boolean, approvedChannels: Set<String>): PackFilter? {
            val harmful = LinkedHashSet<BlockSet>()
            val halal = LinkedHashSet<BlockSet>()
            var islamic: Set<String>? = null
            for (packs in choices) {
                if (packs.harmfulForKids) {
                    harmful += HarmfulForKids.core
                    if (packs.overstimulating) harmful += HarmfulForKids.overstimulating
                    if (packs.challengesAndVlogs) harmful += HarmfulForKids.challengesAndVlogs
                }
                if (packs.halalOnly) {
                    halal += HalalOnly.music
                    halal += HalalOnly.haram
                    if (packs.stageMagic) halal += HalalOnly.stageMagic
                    if (packs.festivals) halal += HalalOnly.festivals
                    if (packs.lootBoxes) halal += HalalOnly.lootBoxes
                }
                if (packs.islamicOnly) {
                    val enabled = IslamicOnly.enabledChannels(packs.islamicChannels, kids)
                    islamic = islamic?.intersect(enabled) ?: enabled
                }
            }
            if (harmful.isEmpty() && halal.isEmpty() && islamic == null) return null
            return PackFilter(harmful, halal, islamic, approvedChannels)
        }
    }
}
