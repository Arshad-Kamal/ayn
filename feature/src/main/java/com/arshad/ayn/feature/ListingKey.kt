package com.arshad.ayn.feature

import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.ListingItem
import com.arshad.ayn.core.access.PlaylistItem
import com.arshad.ayn.core.access.VideoItem

/** Stable lazy-list key across the listing kinds (CMP-LAZY-01). */
val ListingItem.listingKey: String
    get() = when (this) {
        is VideoItem -> id.value
        is ChannelItem -> url
        is PlaylistItem -> url
    }
