package com.arshad.ayn.feature.library

import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.lock.ProfileLocks
import com.arshad.ayn.core.store.BlockedChannel
import com.arshad.ayn.core.store.BlockedVideo
import com.arshad.ayn.core.store.FilterStore
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileStore
import com.arshad.ayn.core.store.SelfRule
import kotlin.time.TimeSource

/** What the card menu's Block needs besides the menu's own stores (KP-D19). [timeSource] runs
 *  lock waits and must count deep sleep. */
class MenuBlocking(
    val filters: FilterStore,
    val profiles: ProfileStore,
    val locks: ProfileLocks,
    val timeSource: TimeSource,
)

/** What a Block row blocks (`kp-filters` cardmenu). */
sealed interface BlockTarget {
    val rule: SelfRule

    /** The video's channel, by its link; [name] is what the copy calls it. */
    data class Channel(val channelUrl: String, val name: String?) : BlockTarget {
        override val rule: SelfRule get() = BlockedChannel(channelUrl, name)
    }

    /** [shownTitle] is the title the card showed (DeArrow's when it had one), kept as the block's
     *  name; YouTube's when it is `null`. */
    data class Video(val video: VideoItem, val shownTitle: String? = null) : BlockTarget {
        override val rule: SelfRule get() = BlockedVideo(video.id.value, shownTitle ?: video.title, video.uploaderUrl)
    }
}

/** The profiles a Block can reach: the open profile [self] and every profile here, in the
 *  store's order. A kids profile has none (KP-D11). */
data class BlockProfiles(val self: Profile, val all: List<Profile>) {
    val owner: Profile? get() = all.firstOrNull { it.isOwner }

    /** The kids profiles the owner's open profile approves for and blocks for (KP-D10); none in
     *  anyone else's. */
    val kids: List<Profile> get() = if (self.isOwner) all.filter { it.isKids } else emptyList()
}

/** What the card menu leaves on screen as it closes: a notice whose Undo takes it back. */
sealed interface MenuNotice {
    /** The write went through: the notice has an Undo and takes focus. */
    val done: Boolean
}

/**
 * A block just made (`kp-filters` blocked): [blockedIn] names the profiles it now holds in, in the
 * sheet's order, [everywhere] when that is every profile here; empty when nothing could be
 * written. While it shows, Undo takes back what this block added.
 */
data class BlockNotice(val target: BlockTarget, val blockedIn: List<String>, val everywhere: Boolean) : MenuNotice {
    override val done: Boolean get() = blockedIn.isNotEmpty()
}

/** An approval just made for kids profile [kid] (`kp-kids` approved): [what] is the video's title
 *  or the channel's name; not [done] when it couldn't be written. */
data class ApproveNotice(val kid: String, val what: String, override val done: Boolean) : MenuNotice
