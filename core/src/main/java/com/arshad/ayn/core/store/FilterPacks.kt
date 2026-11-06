package com.arshad.ayn.core.store

/**
 * One setter's filter pack choices (FPK-D1, D2, D6, D8, D12): the profile's own in
 * [ProfileSettings], the owner's in [ProfileRestrictions]; a video either setter's packs hide
 * stays hidden (KP-D12). An option counts only while its pack is on. Every default is off.
 */
data class FilterPacks(
    val harmfulForKids: Boolean = false,
    val overstimulating: Boolean = false,
    val challengesAndVlogs: Boolean = false,
    val halalOnly: Boolean = false,
    val stageMagic: Boolean = false,
    val festivals: Boolean = false,
    val lootBoxes: Boolean = false,
    val islamicOnly: Boolean = false,
    /** The Islamic content only channel switches this setter moved off their defaults, by
     *  channel id: a channel a later release adds starts at its default. */
    val islamicChannels: Map<String, Boolean> = emptyMap(),
)
