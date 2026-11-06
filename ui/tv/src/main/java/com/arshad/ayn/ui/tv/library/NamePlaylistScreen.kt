package com.arshad.ayn.ui.tv.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.store.MAX_PLAYLIST_NAME
import com.arshad.ayn.feature.library.PlaylistNaming
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.GuidedButton
import com.arshad.ayn.ui.tv.common.Picture
import com.arshad.ayn.ui.tv.common.ThumbAspect
import com.arshad.ayn.ui.tv.common.rememberCardFace
import com.arshad.ayn.ui.tv.common.rememberFaceArrival
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.profiles.GuidedTitle
import com.arshad.ayn.ui.tv.profiles.KickerIcon
import com.arshad.ayn.ui.tv.profiles.KickerRow
import com.arshad.ayn.ui.tv.profiles.GuidedField
import com.arshad.ayn.ui.tv.profiles.ProfileGuided
import com.arshad.ayn.ui.tv.profiles.controlRoomLight
import com.arshad.ayn.ui.tv.profiles.rememberInitialFocus
import com.arshad.ayn.ui.tv.theme.KeColors

// `b3-library` newpl, newplsave (px ÷ 2): the field 430 dp wide, 15 dp under the title; its note
// 10 dp below; the video going in first 12 dp below the field, an 80 × 45 dp picture 10 dp from its line.
private val FieldWidth = 430.dp
private val FieldTop = 15.dp
private val NoteTop = 10.dp
private val VideoLineTop = 12.dp
private val VideoLineGap = 10.dp
private val VideoThumbWidth = 80.dp
private val VideoThumbShape = RoundedCornerShape(5.dp)

/**
 * Naming a new playlist (`b3-library` newpl, newplsave): the field starts focused with the system
 * keyboard up; the keyboard's Done key and [confirm] both call [onDone] (nothing happens while the
 * name is blank or a save is under way, [PlaylistNaming.canSave]); DOWN leaves the field for
 * [confirm]. [goesInFirst] names the video the playlist is made for. Cancel calls [onCancel]; BACK
 * is the host's.
 */
@Composable
internal fun NamePlaylistScreen(
    naming: PlaylistNaming,
    confirm: String,
    onNameChange: (String) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    goesInFirst: VideoItem? = null,
) {
    val field = rememberInitialFocus()
    val create = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().controlRoomLight()) {
        ProfileGuided(
            copy = {
                KickerRow("New playlist", Modifier.padding(bottom = 11.dp)) { KickerIcon(R.drawable.ic_plus) }
                GuidedTitle("Name the playlist")
                GuidedField(
                    value = naming.name,
                    onValueChange = onNameChange,
                    onDone = onDone,
                    onDown = { create.requestFocus() },
                    width = FieldWidth,
                    capitalization = KeyboardCapitalization.Sentences,
                    modifier = Modifier.padding(top = FieldTop).focusRequester(field),
                )
                goesInFirst?.let { VideoLine(it) }
                Text(
                    text = "Up to $MAX_PLAYLIST_NAME characters. Names can repeat.",
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.5.sp),
                    color = KeColors.Ink3,
                    modifier = Modifier.padding(top = NoteTop),
                )
            },
            actions = {
                GuidedButton(R.drawable.ic_check, confirm, ControlStyle.Primary, onDone, Modifier.focusRequester(create))
                GuidedButton(R.drawable.ic_back, "Cancel", ControlStyle.Secondary, onCancel)
            },
        )
    }
}

/** "<title> goes in first." beside the video's picture (`.vline`), both its face. */
@Composable
private fun VideoLine(video: VideoItem) {
    val face = rememberCardFace(video)
    val arrival = rememberFaceArrival(face != null)
    Row(
        modifier = Modifier.padding(top = VideoLineTop).width(FieldWidth),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VideoLineGap),
    ) {
        Box(Modifier.size(VideoThumbWidth, VideoThumbWidth / ThumbAspect).clip(VideoThumbShape).background(KeColors.Surface2)) {
            Picture(face?.thumbnailUrl, Modifier.graphicsLayer { alpha = arrival.value })
        }
        Text(
            text = face?.let { "${it.title} goes in first." }.orEmpty(),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.5.sp),
            color = KeColors.Ink2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.graphicsLayer { alpha = arrival.value },
        )
    }
}
