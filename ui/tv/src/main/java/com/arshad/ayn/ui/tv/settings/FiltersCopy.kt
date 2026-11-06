package com.arshad.ayn.ui.tv.settings

// The TV's copy of the Filters page and its sheets (`kp-filters` v2); shared text: feature/settings/FiltersText.kt.

/** Where filters apply (KP-D1), at the foot of every Filters page. */
internal const val FILTERS_REACH =
    "Filters apply to Home, Search, channels, playlists, Up next and your history. A video they hide won’t play, however you reach it."

/** The owner's Filters page adds where restrictions are set. */
internal const val FILTERS_OWNER_TAIL = " To set filters another profile can’t turn off, choose it in Profiles."

/** The heading over the owner's restrictions on someone else's Filters page. */
internal fun setByOwner(ownerName: String): String = "Set by $ownerName, the owner"

/** The line under it. */
internal fun onlyOwnerChanges(ownerName: String): String = "Only $ownerName can change these. They apply on top of yours."

/** Restricted Mode's hint while the owner's Strict is on over the profile's own Off. */
internal fun ownerStrictHint(ownerName: String): String = "Strict applies while $ownerName’s restriction is on"

/** Show Shorts' hint on Filters: [ownerName] when the owner keeps Shorts off for this profile. */
internal fun shortsLinkHint(ownerName: String?): String =
    "Shorts have their own switch, in Interface" + (ownerName?.let { "; $it keeps them off for you" } ?: "")
