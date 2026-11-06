package com.arshad.ayn.ui.mobile.menu

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.feature.format.timecode
import com.arshad.ayn.feature.foryou.ForYouReason
import com.arshad.ayn.feature.foryou.SeedKind
import com.arshad.ayn.feature.home.ForYouHidden
import com.arshad.ayn.feature.home.ForYouHide
import com.arshad.ayn.feature.library.ApproveNotice
import com.arshad.ayn.feature.library.BlockNotice
import com.arshad.ayn.feature.library.BlockProfiles
import com.arshad.ayn.feature.library.BlockTarget
import com.arshad.ayn.feature.library.MenuNotice
import com.arshad.ayn.feature.library.VideoSource
import com.arshad.ayn.feature.settings.spokenList
import com.arshad.ayn.ui.mobile.common.NoticeLine
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.weightSpan

// The card menu's words on the phone (`m6-home-and-menus` states 9–19; M6-D12: copy stays per UI).

/** The first row for a video with a resume point: "Resume from 34:51". */
internal fun resumeText(positionMs: Long): String = "Resume from ${timecode(positionMs)}"

/** The remove row (M5-D17): it takes the video out of where it was listed; none elsewhere. */
internal fun removeText(source: VideoSource): String? = when (source) {
    VideoSource.History -> "Remove from history"
    VideoSource.WatchLater -> "Remove from Watch later"
    is VideoSource.Playlist -> "Remove from this playlist"
    VideoSource.Elsewhere, is VideoSource.ForYou -> null
}

/** Why For you picked the video (FY-D8), its subject in ink. */
internal fun reasonText(reason: ForYouReason): AnnotatedString = buildAnnotatedString {
    append(
        when (reason.kind) {
            SeedKind.Watched -> "Because you watched "
            SeedKind.Saved -> "Because you saved "
            SeedKind.Followed -> "Because you follow "
        },
    )
    withStyle(weightSpan(620, AynColors.Ink)) { append(reason.subject) }
}

/** What Approve for offers: the video, or all of its channel when it names one. */
internal fun approveRowHint(video: VideoItem): String =
    approvableChannel(video)?.let { "This video, or all of $it" } ?: "This video"

/** The channel an approval can take in whole: one with a name and a link. */
internal fun approvableChannel(video: VideoItem): String? = video.uploaderName?.takeIf { video.uploaderUrl != null }

/** What a Block row and its sheet call the target: "Block fern", "Block this video". */
internal fun blockKicker(target: BlockTarget): String = when (target) {
    is BlockTarget.Channel -> "Block ${target.shownName}"
    is BlockTarget.Video -> "Block this video"
}

internal fun blockSub(target: BlockTarget): String = when (target) {
    is BlockTarget.Channel -> "Nothing from ${target.shownName} shows in the profiles you choose, and its videos won’t play there."
    is BlockTarget.Video -> "It won’t show in the profiles you choose, or play there."
}

/** Every profile's name; for anyone but the owner, followed by the owner's lock it asks for. */
internal fun allProfilesHint(blockers: BlockProfiles): String {
    val names = spokenList(blockers.all.map { it.name })
    val owner = blockers.owner ?: return names
    val lock = owner.lock?.takeIf { !blockers.self.isOwner } ?: return names
    return "$names. Asks for ${owner.name}’s ${lockWord(lock)} first"
}

/** The sheet's foot: where the owner's block shows in the other profiles, or how anyone else
 *  blocks without the owner's lock; nothing when the owner has no lock to ask for. */
internal fun blockNote(blockers: BlockProfiles, choosing: Boolean): String? {
    val owner = blockers.owner ?: return null
    return when {
        blockers.self.isOwner && choosing -> "A restriction is one the profile can’t turn off; only you lift it."
        blockers.self.isOwner -> "In the other profiles it shows under Set by ${owner.name}, the owner. Lift it in Settings › Profiles › Restrictions."
        owner.lock != null -> "Blocking only in your profile needs no lock: choose profiles and leave ${blockers.self.name} ticked."
        else -> null
    }
}

internal fun blockInLabel(count: Int): String = if (count == 1) "Block in 1 profile" else "Block in $count profiles"

/** The owner's lock before a block: a sequence ends with OK, a PIN opens on its last digit. */
internal fun gateLine(ownerName: String, kind: LockKind): String = when (kind) {
    LockKind.Sequence -> "Enter $ownerName’s sequence, then tap OK."
    LockKind.Pin -> "Enter $ownerName’s PIN."
}

/** What the card menu's snackbar says (states 16, 18): a block's or an approval's, with Undo (Q20,
 *  as on TV); a failure's, a warning to try again. */
internal fun menuNoticeLine(notice: MenuNotice): NoticeLine = when (notice) {
    is BlockNotice -> blockNoticeLine(notice)
    is ApproveNotice -> if (notice.done) {
        NoticeLine("Approved for ${notice.kid}", notice.what)
    } else {
        NoticeLine("Couldn’t approve for ${notice.kid}", "Try again", action = null, warn = true)
    }
}

/** "fern blocked · in Arshad and Mira", or that nothing could be blocked. */
internal fun blockNoticeLine(notice: BlockNotice): NoticeLine {
    val target = notice.target
    if (notice.blockedIn.isEmpty()) {
        val what = if (target is BlockTarget.Channel) target.shownName else "this video"
        return NoticeLine("Couldn’t block $what", "Try again", action = null, warn = true)
    }
    val what = when (target) {
        is BlockTarget.Channel -> target.name ?: "Channel"
        is BlockTarget.Video -> "Video"
    }
    val where = if (notice.everywhere) "in all profiles" else "in ${spokenList(notice.blockedIn)}"
    return NoticeLine("$what blocked", where)
}

/** What the copy calls a channel inside a sentence. */
private val BlockTarget.Channel.shownName: String get() = name ?: "this channel"

/** The Hidden snackbar (state 12): what the hide does. */
internal fun hiddenLine(hidden: ForYouHidden): NoticeLine = NoticeLine(
    "Hidden",
    when (hidden.hide) {
        ForYouHide.Video -> "You won’t see this video in For you"
        ForYouHide.Channel -> "${hidden.video.uploaderName ?: "This channel"} won’t be recommended in For you"
    },
)

private fun lockWord(kind: LockKind): String = if (kind == LockKind.Pin) "PIN" else "sequence"
