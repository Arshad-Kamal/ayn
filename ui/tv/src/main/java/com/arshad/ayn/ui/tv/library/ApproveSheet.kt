package com.arshad.ayn.ui.tv.library

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.feature.kids.approveNoteText
import com.arshad.ayn.feature.library.ApproveNotice
import com.arshad.ayn.feature.library.BlockNotice
import com.arshad.ayn.feature.library.MenuNotice
import com.arshad.ayn.feature.library.MenuStage
import com.arshad.ayn.feature.library.VideoMenu
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.NoticeLine
import com.arshad.ayn.ui.tv.common.rememberCardFace
import com.arshad.ayn.ui.tv.settings.RowIcon
import com.arshad.ayn.ui.tv.settings.SettingRow
import com.arshad.ayn.ui.tv.settings.SheetFrame
import com.arshad.ayn.ui.tv.settings.SheetHead
import com.arshad.ayn.ui.tv.settings.SheetRowMinHeight
import com.arshad.ayn.ui.tv.settings.SheetRows
import com.arshad.ayn.ui.tv.settings.SheetRowsTop
import com.arshad.ayn.ui.tv.settings.rememberSheetFocus

private const val VIDEO_KEY = "approve-video"
private const val CHANNEL_KEY = "approve-channel"

// `kp-kids` approvesheet (px ÷ 2): the video 15 dp under the title.
private val ApproveHeadTop = 15.dp

/**
 * Approve for a kids profile (`kp-kids` approvesheet, KP-D10): just this video, which then shows
 * in the kid's Home and Search, or its whole channel, which joins the kid's channels. It opens on
 * the video; a video naming no channel offers the video alone. BACK returns to the menu.
 */
@Composable
internal fun ApproveSheet(menu: VideoMenu, stage: MenuStage.Approve, viewModel: VideoMenuViewModel) {
    val kid = stage.kid.name
    val video = menu.video
    val channel = video.uploaderName?.takeIf { video.uploaderUrl != null }
    // The notice names the video as the head shows it.
    val shownTitle = rememberCardFace(video)?.title
    val focus = rememberSheetFocus(VIDEO_KEY)
    SheetFrame(note = approveNoteText(kid)) {
        SheetHead(kicker = "Approve for $kid", title = { Text("Just this video, or the channel?", style = MaterialTheme.typography.headlineSmall) })
        VideoHead(video, short = false, modifier = Modifier.padding(top = ApproveHeadTop))
        SheetRows(top = SheetRowsTop) {
            SettingRow(
                label = "Approve this video",
                onClick = { viewModel.onApproveVideo(shownTitle) },
                hint = "Only this one. It shows in $kid’s Home and Search.",
                minHeight = SheetRowMinHeight,
                modifier = focus.on(VIDEO_KEY),
                lead = { RowIcon(R.drawable.ic_play, it) },
            )
            if (channel != null) {
                SettingRow(
                    label = "Approve the whole channel",
                    onClick = viewModel::onApproveChannel,
                    hint = "All of $channel’s videos, new ones too. $channel joins $kid’s channels.",
                    minHeight = SheetRowMinHeight,
                    modifier = focus.on(CHANNEL_KEY),
                    lead = { RowIcon(R.drawable.ic_nav_subscriptions, it) },
                )
            }
        }
    }
}

/** What the card menu's notice says: a block's ([blockNoticeLine]) or an approval's. */
internal fun menuNoticeLine(notice: MenuNotice): NoticeLine = when (notice) {
    is BlockNotice -> blockNoticeLine(notice)
    is ApproveNotice -> approveNoticeLine(notice)
}

/** `kp-kids` approved: "Approved for Zainab · Extraordinary Wildlife…", or a warning that it
 *  couldn't be. */
internal fun approveNoticeLine(notice: ApproveNotice): NoticeLine =
    if (notice.done) {
        NoticeLine("Approved for ${notice.kid}", notice.what)
    } else {
        NoticeLine("Couldn’t approve for ${notice.kid}", "Try again", undo = false)
    }
