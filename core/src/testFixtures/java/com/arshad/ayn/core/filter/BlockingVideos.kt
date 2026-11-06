package com.arshad.ayn.core.filter

import com.arshad.ayn.core.store.BlockedVideo
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.StoredRule

/** A filter hiding the videos [ids] and nothing else: what a block from a card's menu leaves. */
fun blockingVideos(vararg ids: String): ContentFilter = ContentFilter.of(
    ProfileSettings(),
    ProfileRules(rules = ids.map { StoredRule(BlockedVideo(it, title = null, channelUrl = null), byOwner = false, addedAtMillis = 0) }),
)
